# ADR 024: Split Coolify Deployment to Eliminate Shared-Bridge Race Condition

**Status:** Accepted
**Date:** 2026-05-19
**Supersedes:** the implicit single-stack assumption in the original
ADR 019 (deployment).

## Context

Khord's two server roles (Key Server and Relay Server) have, since
the first production deploy on 2026-05-08, been packaged as a single
Coolify "Docker Compose" application using
`deploy/docker-compose.coolify.yml`. Both servers + their two
Postgres databases live in one compose stack. Coolify's Traefik
fronts both via the shared `r13ssqyvk64fz6gx0dt6h2w1` container
network.

This layout has reliably produced one specific failure mode on
**every redeploy** — call it gotcha #6 (also tracked in the Coolify
gotchas memory):

  - After Coolify rebuilds and brings up the new containers, one of
    the two servers (never both, never predictable which) ends up
    with a Docker network attachment that Traefik / Caddy cannot
    reach. The container is healthy internally (uvicorn started,
    `/v1/health` works on `localhost:8000`), but the public URL
    times out at the proxy layer.
  - The Coolify "Restart" button does NOT fix this (it re-execs the
    process inside the same container, preserving the broken
    attachment).
  - **Stop + Start** does fix it — it fully recreates the container
    with a fresh attachment.

Confirmed instances (chronological):

| Date | Affected service | Trigger |
|---|---|---|
| 2026-05-08 | relayserver | initial bring-up |
| 2026-05-17 | relayserver | docs-only redeploy (`d5d5b65`) |
| 2026-05-17 | keyserver | CORS commit (`b5b24f2`) |
| 2026-05-18 | relayserver | group-messaging commit (`7d4c233`) |
| 2026-05-18 | **both** | failed `docker_compose_custom_start_command` experiment + restore |
| 2026-05-19 | relayserver | spontaneous, no recent deploy |

The pattern — exactly one service per incident, never the same one
twice in a row — is the signature of a race condition between the
two containers competing for the shared bridge endpoint during
recreation. We do not have visibility into Docker's bridge-management
code from outside Coolify; the symptom is reproducible enough that
we treat the diagnosis as established without code-level proof.

Three candidate fixes were on the table:

1. **Drop the per-service `keyserver_net` / `relayserver_net`
   bridges** so each container sits on only one network (the shared
   one). Investigated; the per-service bridges are how
   server↔database traffic is isolated, and Coolify's compose
   rewriter assigns each container to BOTH networks regardless. The
   race appears to be on the shared bridge specifically. Removing
   the per-service bridges does not address it.
2. **Coolify post-deploy hook running `docker compose up -d
   --force-recreate`.** Attempted on 2026-05-18 via the
   `docker_compose_custom_start_command` field. Failed — the field
   wants the FULL `docker compose ...` invocation, not just args.
   Coolify's deploy flow had already removed the old containers
   before the malformed start command ran, taking production fully
   offline for the duration of the recovery. Lesson: don't
   experiment with deploy-strategy settings on the live application.
3. **Split the combined application into two Coolify applications**,
   each with a single-service compose file pointing at the same Git
   repo but at a different `deploy/docker-compose.coolify.*.yml`.

## Decision

**Adopt option 3 — split deployment.** Two Coolify applications,
one per server, each with its own:

  - single-service compose file
  - environment variables (no overlap, no shared secrets)
  - Postgres volume
  - public hostname

The combined `docker-compose.coolify.yml` is kept in the repo for
backward compatibility with already-deployed instances. The
deployment README marks the combined path as "legacy" and points
new deployments at the split path.

### Architectural alignment

This decision also aligns the production deployment with the
split-trust architecture (ADR 002). The Key Server and Relay Server
are independently-trusted components; production was packaging them
into a single operational unit purely as a PoC convenience. That
convenience came at the cost of the gotcha-#6 dance and contradicted
the future state where the two servers may be run by different
operators in different jurisdictions.

### Compose files added

  - `deploy/docker-compose.coolify.keyserver.yml` — keyserver + its
    Postgres. Uses DB user/name/volume `khord_ks` / `khord_keyserver`
    / `keyserver_data`.
  - `deploy/docker-compose.coolify.relayserver.yml` — relayserver +
    its Postgres. Uses `khord_rs` / `khord_relayserver` /
    `relayserver_data`.

The combined stack uses `keyserver` / `keyserver` / `keyserver_db_data`
(and similarly for the relay). The distinct volume names let the
two stacks coexist briefly during the cutover (see migration playbook
below).

### Env templates added

  - `deploy/.env.coolify.keyserver.example` — `KEY_SERVER_TOKEN_SECRET`
    + `KEYSERVER_DB_PASSWORD`.
  - `deploy/.env.coolify.relayserver.example` — `RELAYSERVER_DB_PASSWORD`,
    `RELAY_PROOF_OF_WORK_DIFFICULTY_BITS`, `RELAY_MESSAGE_TTL_SECONDS`.

No overlap. The Key Server app's environment contains zero relay
secrets and vice versa.

## Migration playbook (combined → split)

The current `apps1.mikro.events` instance has the combined
application live at `keys.khord.org` / `relay.khord.org`. The
migration is tracked in `docs/DEFERRED.md` as **D-032 (P1)**.

1. **Snapshot the production databases.** Before doing anything in
   the Coolify UI, take a backup of both Postgres volumes from the
   running combined stack:
   ```bash
   docker exec keyserver-db-r13ssqyvk64fz6gx0dt6h2w1-XXX \
       pg_dump -U keyserver keyserver > keyserver.sql
   docker exec relayserver-db-r13ssqyvk64fz6gx0dt6h2w1-XXX \
       pg_dump -U relayserver relayserver > relayserver.sql
   ```
   Container IDs vary per redeploy; check via Coolify's UI or
   `docker ps --filter name=r13ssqyvk64fz6gx0dt6h2w1`.

2. **Create the Key Server application** in Coolify per the split-
   deployment Quick Start in `deploy/README.md`. Use a different
   subdomain initially (e.g. `keys-new.khord.org`) so it doesn't
   collide with the live route. Verify health, then keep it idle.

3. **Restore the keyserver data** into the new app's empty database.
   The new DB user is `khord_ks` and the DB name is
   `khord_keyserver` (vs `keyserver` / `keyserver` on the old
   stack). The restore needs a small rewrite:
   ```bash
   sed 's/^GRANT .* TO keyserver;$/GRANT ... TO khord_ks;/' keyserver.sql \
       | sed 's/^ALTER .* OWNER TO keyserver;$/ALTER ... OWNER TO khord_ks;/' \
       | docker exec -i keyserver-db-new pg_restore -U khord_ks -d khord_keyserver
   ```
   Or simpler: edit the new app's compose to use the old DB user/
   name/volume before first deploy, and accept the cosmetic
   inconsistency. The split-vs-combined fix doesn't require the
   rename — the rename was chosen to make the two stacks visually
   distinct.

4. **Repeat for the Relay Server application** at e.g.
   `relay-new.khord.org`.

5. **Cutover.** When both new apps verify healthy:
   - PATCH each new app's `docker_compose_domains` to the production
     hostname (`keys.khord.org` / `relay.khord.org`).
   - In the OLD combined app, remove the domain bindings (so Traefik
     drops the routes) OR delete the app entirely.
   - Verify the production hostnames now resolve to the new apps.

6. **Decommission the combined app.** Once the new apps have been
   serving the production hostnames for a reasonable buffer (one
   week is conservative, one day is fine given there are no real
   users yet), delete the old combined application via the Coolify
   API (with `delete_volumes=true` to reclaim the old database
   volumes).

The "early PoC, no real users" status means a simpler variant of
this playbook is acceptable: backup volumes for audit, deploy the
new apps with empty databases, point the production hostnames at
the new apps, accept that any in-flight messages are lost (TTL is
7 days; the worst-case window is one week of missed messages).

## Privacy property

Unchanged. Both deployment shapes preserve the split-trust property:

  - Key Server has no visibility into the relay (separate process,
    separate network, separate Postgres).
  - Relay Server has no visibility into the key server (same).

The split deployment makes the operational separation visible at
the Coolify-application layer in addition to the protocol layer.

## Consequences

  - **Two Coolify apps to configure** instead of one. Each has its
    own env-vars tab, its own domain config, its own webhook.
  - **Webhook delivery is per-app.** A push that only touches one
    server (e.g. a relay-only change) will rebuild both apps unless
    auto-deploy is disabled on the unaffected one. The Coolify UI
    supports per-app auto-deploy toggles.
  - **Different DB user/name in the new compose** vs. the combined
    stack (`khord_ks` / `khord_keyserver` vs. `keyserver` /
    `keyserver`). Tradeoff: distinct names let the two stacks
    coexist during the cutover; the cost is a non-zero migration if
    you want to preserve existing data.
  - **Zero impact on Khord clients.** The split is purely a
    deployment layout change; both servers still expose the same
    public hostnames and the same HTTP / WebSocket API.
  - **Future operator-split is now natural.** Moving the Key Server
    to a different operator (e.g. a non-profit running it as a
    public good) becomes a "give them this compose file + this env
    template" handoff rather than "carve out half of this combined
    stack and good luck."

## What's NOT in this ADR

  - The actual production cutover. Tracked as D-032 (P1) in
    `docs/DEFERRED.md`. Will be executed on the live
    `apps1.mikro.events` Coolify instance during a planned window
    with volume backups in hand.
  - Removing the combined `docker-compose.coolify.yml` from the
    repo. Kept for now so any existing combined deployment elsewhere
    keeps working. Removal can happen after the production cutover
    if no other consumers are known.
  - Auto-deploy throttling rules to avoid double-builds on shared-
    repo pushes. The Coolify UI's per-app auto-deploy toggle is the
    manual workaround; a smarter
    "this commit touches `servers/keyserver/**` so only rebuild the
    keyserver app" filter would be a CI/CD pipeline concern (see
    D-020 in DEFERRED).
