# Khord production deployment

This directory contains everything needed to run a public Khord backend —
one key server + one relay server + Caddy reverse proxy with automatic
Let's Encrypt TLS — on a single Linux host.

The dev `docker-compose.yml` at the repo root is for local development
against `localhost`. **Use the files here for anything internet-facing.**

## Prerequisites

- A Linux server with at least **1 vCPU + 1 GB RAM** (Khord is small;
  this is the comfortable floor, not the minimum).
- **Docker Engine 24+** with the `compose` plugin (`docker compose
  version` should print v2.x). Install instructions:
  <https://docs.docker.com/engine/install/>.
- **Two domains (or subdomains)** you control, both pointing at this
  host's public IP via A or AAAA records. Examples:
  - `keys.example.com`  → key server
  - `relay.example.com` → relay server
- **Ports 80 and 443 reachable from the public internet.** Port 80 is
  used once per cert renewal for Let's Encrypt's HTTP-01 challenge;
  port 443 carries all real traffic. No other ports need to be open.
- `openssl` available locally (used by the setup script to mint random
  secrets). Standard on any Linux distro.

## First-run, in five steps

```bash
# 1. Clone the repo and move into deploy/.
git clone https://github.com/your-org/khord.git
cd khord/deploy

# 2. Configure your environment.
#    Either run the interactive wizard:
./scripts/setup.sh
#    …or do it by hand:
#       cp .env.example .env
#       $EDITOR .env

# 3. Bring the stack up (skip if you ran setup.sh — it does this for you).
docker compose -f docker-compose.prod.yml up -d --build

# 4. Verify both health endpoints over real TLS:
curl https://${DOMAIN_KEYSERVER}/v1/health
curl https://${DOMAIN_RELAY}/v1/health
# Both should return: {"status":"ok"}

# 5. Share the two URLs with your users. They go straight into the
#    Khord Android app's "Use custom servers" screen at first launch:
#       Key server:   https://${DOMAIN_KEYSERVER}
#       Relay server: https://${DOMAIN_RELAY}
```

The first `up -d --build` takes 1–3 minutes: Docker builds the two
server images, Postgres initialises both databases, then Caddy obtains
TLS certificates from Let's Encrypt. Subsequent restarts are seconds.

If `curl` step 4 errors with a TLS handshake failure, give Caddy
another minute — the ACME challenge round-trips can take a moment on
first issuance — and try again. Check progress with `docker compose -f
docker-compose.prod.yml logs caddy`.

## Architecture

```
                    Internet
                       │
                  :80 / :443
                       │
                  ┌────▼────┐
                  │  Caddy  │  ← only container with host port bindings
                  └────┬────┘     ← terminates TLS, proxies HTTP upstream
       ┌───────────────┼───────────────┐
       │ keyserver_net │ relayserver_net│
       │               │                │
   ┌───▼────┐     ┌────▼────┐     ┌─────▼─────┐
   │ keyserver │   │ relayserver │ │  (no     │
   │   :8000   │   │   :8000     │ │   bridge │
   └───┬───────┘   └───┬─────────┘ │  between)│
       │               │           └──────────┘
   ┌───▼─────────┐ ┌───▼─────────┐
   │keyserver-db │ │relayserver-db│   ← Postgres 17, no host port
   └─────────────┘ └─────────────┘     no cross-network reachability
```

Caddy is the only public surface. Both server processes and both
databases bind no host ports; they're reachable only on their
respective Docker networks. The two networks share zero state — this
is the same split-trust model the dev compose enforces (ADR 002).

## Maintenance

### Backups

Run the helper script to snapshot both databases:

```bash
deploy/scripts/backup.sh
# → deploy/backups/khord_<utc-timestamp>.tar.gz
```

Each archive contains two gzipped `pg_dump --clean --if-exists` SQL
files — one per database — produced via `docker compose exec`. The
archive is written under `deploy/backups/` (gitignored). Schedule it
from cron:

```cron
# Daily at 03:15 UTC, keep the last 30 archives.
15 3 * * * cd /srv/khord && deploy/scripts/backup.sh \
    && find deploy/backups -name 'khord_*.tar.gz' -mtime +30 -delete
```

Restoration is intentionally manual — see comments at the top of
`scripts/backup.sh` for the `psql` invocation.

### Updates

```bash
cd khord
git pull
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

Compose detects which images changed and only rebuilds those. Postgres
volumes persist across restarts so user data is preserved.

### Logs

```bash
# Live tail across all services.
docker compose -f deploy/docker-compose.prod.yml logs -f

# Single service, last 200 lines.
docker compose -f deploy/docker-compose.prod.yml logs --tail 200 keyserver

# Caddy access + cert-issuance events.
docker compose -f deploy/docker-compose.prod.yml logs -f caddy
```

JSON-file rotation is configured (10 MB × 5 files per service), so the
log volume can't run away.

### Certificates

Caddy stores its ACME account + issued certificates in the `caddy_data`
Docker volume; renewal is fully automatic and starts ~30 days before
expiry. To force a renewal cycle (rarely needed):

```bash
docker compose -f deploy/docker-compose.prod.yml restart caddy
```

If you change one of the domains in `.env`, restart Caddy — it will
issue a fresh cert for the new name on next start. The old cert stays
in the volume but isn't used.

To migrate the certificate cache to a new host, copy the `caddy_data`
volume:

```bash
# On the old host:
docker run --rm -v khord_caddy_data:/from -v /tmp:/to alpine \
    sh -c 'cd /from && tar -czf /to/caddy_data.tar.gz .'
# Move /tmp/caddy_data.tar.gz to the new host, then:
docker run --rm -v khord_caddy_data:/to -v /tmp:/from alpine \
    sh -c 'cd /to && tar -xzf /from/caddy_data.tar.gz'
```

## What this directory does NOT do

- **Monitoring / alerting.** No Prometheus exporter, no uptime probe.
  Wire your own. The `/v1/health` endpoint on each service is the
  obvious target.
- **Horizontal scaling.** This is a single-host deployment. For
  multi-host, replace the local Postgres containers with a managed
  database and the Caddy block with your platform's TLS termination.
- **Outbound mail / push notifications.** Khord's reference client uses
  polling; UnifiedPush is deferred (see ADR 013).
- **Server-side log retention beyond 50 MB per service.** If you need
  forensic-grade archives, ship logs to an external sink.

## Coolify Deployment

[Coolify](https://coolify.io) provides Traefik with automatic Let's
Encrypt TLS in front of every resource it deploys, so the Coolify
compose files in this directory omit the Caddy service that the
standalone stack bundles. This section is both a UI-driven happy-path
guide and a list of the specific pitfalls we hit during the first real
deployment — each with the symptom you'd observe and how to fix it.

There are **two ways** to deploy Khord on Coolify:

  - **Split deployment (recommended).** Two separate Coolify
    applications — one for the Key Server, one for the Relay Server
    — each with its own single-service compose file. This is the
    decision in ADR 024. Eliminates the shared-bridge race condition
    that left one service stranded with a stale network attachment
    after every redeploy on the combined stack (see gotcha #6 in the
    deploy-pitfalls section below). Use this for fresh deployments.

  - **Combined deployment (legacy).** A single Coolify application
    running both servers from `docker-compose.coolify.yml`. Kept for
    backward compatibility with existing deployments. Suffers from
    the gotcha-#6 stop+start dance after every rebuild. Not
    recommended for new deployments. The migration playbook from
    combined to split is in ADR 024.

If you want the short version of either path: **use `https://` URLs
in the per-service domain config** and **don't change the magic
env-var declaration in the compose file** — those two are by far the
most common ways this goes wrong.

### Coolify: Split Deployment (recommended)

Two separate Coolify applications, each pointing at the same Git
repository but with a different compose file path. Each app has its
own env vars, its own domain, its own volume — no shared state
between them at the Coolify level. This mirrors the split-trust
architecture at the deploy layer: the Key Server and Relay Server are
operated independently in production, just as they're trusted
independently at the protocol level.

**Why this is the recommended path:**

  - **No shared-bridge race.** Each app's containers are recreated
    independently. A redeploy of one server can't disturb the other's
    network attachment.
  - **Cleaner blast radius.** A bad config or stuck container on the
    Relay Server doesn't take the Key Server down with it during the
    "Removing old containers" phase of a Coolify deploy.
  - **Different operators possible.** Future-proofs the deployment
    for the "key server run by org X, relay server run by org Y"
    pattern that ADR 002 envisions.
  - **Simpler troubleshooting.** Logs, metrics, and the Coolify
    dashboard each show one server at a time.

**The trade-off:** two applications to configure in the Coolify UI
instead of one, and a one-time first-deploy initialises empty
databases (no data carries over from a previous combined deployment
unless you do the migration playbook in ADR 024).

#### Key Server application

1. **Create a Docker Compose application** in Coolify pointing at
   this repo (or a fork). Branch: `main`. Name it `khord-keyserver`
   or similar.
2. **Compose file path**: `deploy/docker-compose.coolify.keyserver.yml`.
3. **Environment Variables** — paste from
   `.env.coolify.keyserver.example`:

   | Variable | Generate | Notes |
   |---|---|---|
   | `KEY_SERVER_TOKEN_SECRET` | `openssl rand -hex 32` | HMAC-SHA256 secret for session tokens |
   | `KEYSERVER_DB_PASSWORD` | `openssl rand -hex 24` | Postgres pw — pick before first deploy (volume bakes it in) |

4. **Domains** — for the `keyserver` service:

   | Domain (must include scheme) | Port |
   |---|---|
   | `https://keys.khord.org` | `8000` |

5. **Deploy.** Builds the keyserver image, brings up its Postgres,
   runs `alembic upgrade head`, routes via Traefik. ~60 s.
6. **Verify**: `curl https://keys.khord.org/v1/health` → `{"status":"ok"}`

#### Relay Server application

Repeat the process in a second Coolify application:

1. **Create a Docker Compose application** in Coolify pointing at the
   same repo. Name it `khord-relayserver` or similar.
2. **Compose file path**: `deploy/docker-compose.coolify.relayserver.yml`.
3. **Environment Variables** — paste from
   `.env.coolify.relayserver.example`:

   | Variable | Generate | Notes |
   |---|---|---|
   | `RELAYSERVER_DB_PASSWORD` | `openssl rand -hex 24` | Postgres pw |
   | `RELAY_PROOF_OF_WORK_DIFFICULTY_BITS` | `16` | ADR 012 tunable |
   | `RELAY_MESSAGE_TTL_SECONDS` | `604800` | 7 days |

4. **Domains** — for the `relayserver` service:

   | Domain (must include scheme) | Port |
   |---|---|
   | `https://relay.khord.org` | `8000` |

5. **Deploy.**
6. **Verify**: `curl https://relay.khord.org/v1/health` → `{"status":"ok"}`

#### Shared considerations

  - **No env-var overlap.** The Key Server app has no
    `RELAYSERVER_DB_PASSWORD`; the Relay Server app has no
    `KEY_SERVER_TOKEN_SECRET`. Pasting a var into the wrong app is
    harmless (unused) but a leak of `KEY_SERVER_TOKEN_SECRET` into
    the relay app's environment widens its blast radius unnecessarily.
  - **No coordinated downtime.** Deploying or restarting the Key
    Server has zero effect on the Relay Server, and vice versa.
  - **Webhook delivery** is per-application — both apps point at the
    same repo, so the same `git push` will trigger both to redeploy.
    For a server-side change that only touches one server, **disable
    auto-deploy on the other** to avoid the unnecessary rebuild +
    momentary downtime window.
  - **Migrating from the combined stack to split** — see ADR 024 for
    the cutover playbook (backup volumes → deploy new apps → verify
    → delete old combined app). The compose files in this directory
    use distinct volume names (`keyserver_data`, `relayserver_data`)
    versus the combined stack's `keyserver_db_data` /
    `relayserver_db_data`, so the two can coexist briefly during the
    cutover.

### Coolify: Combined Deployment (legacy)

A single Coolify application running both servers from
`docker-compose.coolify.yml`. Kept for backward compatibility with
deployments already on this layout. New deployments should use the
split path above.

The known failure mode of this layout is gotcha #6 below — after
every redeploy of the combined stack, one of the two services
silently ends up with a stale Docker network attachment that Traefik
can't reach. The workaround is to manually Stop+Start the
application in the Coolify UI (Restart alone doesn't fix it — full
container recreate is needed). Annoying but reliable; takes ~30 s
per deploy.

#### Quick start (Coolify UI, combined stack)

1. **Create a Docker Compose application** in Coolify pointing at this
   repo (or a fork). Branch: `main` (or whichever you deploy from).
2. Set the **Compose file path** to `deploy/docker-compose.coolify.yml`.
3. **Environment Variables** tab — paste the five from
   `.env.coolify.example`:

   | Variable | Generate | Notes |
   |---|---|---|
   | `KEY_SERVER_TOKEN_SECRET` | `openssl rand -hex 32` | HMAC-SHA256 secret for session tokens |
   | `KEYSERVER_DB_PASSWORD` | `openssl rand -hex 24` | Postgres pw — pick before first deploy (volume bakes it in) |
   | `RELAYSERVER_DB_PASSWORD` | `openssl rand -hex 24` | Same as above |
   | `RELAY_PROOF_OF_WORK_DIFFICULTY_BITS` | `16` | ADR 012 tunable |
   | `RELAY_MESSAGE_TTL_SECONDS` | `604800` | 7 days |

   `DATABASE_URL` for both servers is built inside the compose file
   from the password vars — don't add it manually.

4. **Domains** tab — for each service:

   | Service | Domain (must include scheme) | Port |
   |---|---|---|
   | `keyserver` | `https://keys.khord.org` | `8000` |
   | `relayserver` | `https://relay.khord.org` | `8000` |

   The `https://` prefix is **required**, not optional — see "Pitfall:
   bare hostnames" below for what happens if you forget it.

5. **Deploy.** Coolify builds both server images, brings up both
   Postgres databases, runs `alembic upgrade head` on each, and routes
   traffic via Traefik. First deploy takes ~60-90 seconds. ACME
   issuance follows automatically once Traefik registers the route.

6. **Verify**:

   ```bash
   curl https://keys.khord.org/v1/health   # → {"status":"ok"}
   curl https://relay.khord.org/v1/health  # → {"status":"ok"}
   ```

   If one of the two times out (gotcha #6), Stop+Start the application
   in the Coolify UI — see "Pitfall: gotcha #6" below.

### Configuration reference

#### Build context

Both services in `docker-compose.coolify.yml` use **repo-root-relative**
build contexts (`./servers/keyserver`, `./servers/relayserver`) — NOT
`../servers/keyserver` like the dev / Caddy stacks do. Coolify invokes
compose with `--project-directory <repo-root>`, which resolves relative
paths against the repo root, not against the compose file's own
directory. The dev / Caddy stacks expect you to `cd deploy/` first, so
`../servers/X` is correct there. Two different invocation contexts,
two different relative paths.

#### Port numbers

Both server processes listen on **port 8000** inside their containers
(see `EXPOSE 8000` and `--port 8000` in each Dockerfile `CMD`). The
dev compose's `8001` / `8002` are HOST port mappings — those numbers
don't exist inside the containers. Coolify's Traefik routes
container-to-container, so the routable port for both is `8000`. Don't
get confused by the dev-stack labels.

#### The "magic env var" line

Each app service has this in its `environment:`:

```yaml
keyserver:
  environment:
    - SERVICE_FQDN_KEYSERVER_8000     # ← bare name, list form, no value
```

This line is **load-bearing**. The presence of `SERVICE_FQDN_<SERVICE>_<PORT>`
declared in **list form** (no `=`, no `:`) tells Coolify to (a) substitute
the configured FQDN for that service into this env var at deploy time,
AND (b) emit Traefik labels routing the configured domain to this
container's port (the suffix on the var name). Without this line, the
domain config in Coolify's UI is dead weight — Traefik never learns
about the route.

Compose YAML doesn't allow mixing list and map forms within one
`environment:` block, which is why both services' env blocks are entirely
list form (`- KEY=value`) rather than map form (`KEY: value`).

### Common pitfalls

These are the four ways this deploy can silently misroute or hang. All
four were hit during the first attempt at production deployment; the
fixes below distill the diagnoses.

#### 1. Bare hostnames in `Domains` config (NO `https://` prefix)

**Symptom**: the deploy succeeds, the resource is `running:healthy`,
but `https://your.domain/v1/health` serves `CN=TRAEFIK DEFAULT CERT`
self-signed — no real Let's Encrypt cert. Inspecting the Coolify-
rewritten compose shows the Traefik rule comes out as
`Host(\`\`) && PathPrefix(\`your.domain\`)` — empty Host, hostname
ended up in PathPrefix.

**Cause**: Coolify's URL parser expects a full URL with scheme. If you
enter just `keys.khord.org` (bare), it gets parsed as a path. The
COOLIFY_URL env var also comes out malformed — `'keys.khord.org://keys.khord.org'`.

**Fix**: edit each service's domain in Coolify's UI to include the
scheme: `https://keys.khord.org`, `https://relay.khord.org`. Redeploy.
Real cert issues within ~30-60 seconds.

#### 2. Missing or mis-shaped magic env var declaration

**Symptom**: deploy succeeds, but Coolify never adds `traefik.*` labels
to the running containers (visible in the resource's rewritten compose
in the API response). Domain registers in `docker_compose_domains` but
nothing ever routes to the service.

**Cause**: the magic env var must be declared in **list form, bare name,
no value** (`- SERVICE_FQDN_KEYSERVER_8000`). Map form with empty value
(`SERVICE_FQDN_KEYSERVER_8000: ""`) is silently ignored by Coolify's
scanner. Map form with port suffix doesn't work either; only the list
form triggers label generation.

**Fix**: edit the compose to use list-form environment. Both services'
env blocks must be entirely list form (mixing list and map forms within
one block is a YAML error).

#### 3. Build context path

**Symptom**: deploy fails very early with
`Error: unable to prepare context: path "/artifacts/servers/keyserver" not found`.

**Cause**: Coolify resolves `build.context` relative to its
`--project-directory` (the cloned repo root), not relative to the
compose file's directory. Using `../servers/keyserver` results in
`<repo-root>/../servers/keyserver` which is outside the cloned repo.

**Fix**: use `./servers/keyserver` and `./servers/relayserver` (already
applied in this repo). If you're authoring a new compose file for
Coolify, anchor every relative path at the repo root, not at the
compose file's directory.

#### 4. Domain config added AFTER the first deploy

**Symptom**: domains were added in Coolify UI after a successful
deploy. The resource is healthy but Traefik labels still point at
nothing. A simple redeploy doesn't fix it.

**Cause**: Coolify's Traefik label generation runs at deploy time, not
at config-update time. Adding a domain to a running resource doesn't
re-emit labels.

**Fix**: trigger a redeploy after any domain config change. If the
state still looks wrong (env vars stuck, labels stale), the
"Recovery" section below is faster than debugging.

### API-based setup

If you'd rather automate, the same configuration can be done entirely
via Coolify's REST API. The token needs `Authorization: Bearer ...` and
your IP must be on the API allowlist (Settings → API → Allowed IPs).

```bash
# 1. Discover prerequisite UUIDs
curl -H "Authorization: Bearer $TOKEN" $BASE/projects             # → khord project uuid
curl -H "Authorization: Bearer $TOKEN" $BASE/projects/$PROJ_UUID  # → environments[].uuid (production)
curl -H "Authorization: Bearer $TOKEN" $BASE/servers              # → server uuid
curl -H "Authorization: Bearer $TOKEN" $BASE/github-apps          # → github app uuid (for private repos)

# 2. Create the application
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  $BASE/applications/private-github-app -d '{
    "project_uuid": "...",
    "environment_name": "production",
    "server_uuid": "...",
    "github_app_uuid": "...",
    "git_repository": "owner/khord",
    "git_branch": "main",
    "build_pack": "dockercompose",
    "docker_compose_location": "/deploy/docker-compose.coolify.yml",
    "base_directory": "/",
    "ports_exposes": "8000"
  }'
# → { "uuid": "<APP_UUID>", "domains": "..." }

# 3. Set the per-service domains (use https:// prefix; array shape on PATCH)
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  $BASE/applications/$APP_UUID -d '{
    "docker_compose_domains": [
      {"name":"keyserver",  "domain":"https://keys.khord.org"},
      {"name":"relayserver","domain":"https://relay.khord.org"}
    ]
  }'

# 4. Set environment variables — note: PATCH, NOT POST.
#    When Coolify auto-discovers vars from the compose file (via ${VAR}
#    references), it pre-creates the rows. POST returns "already exists,
#    use PATCH". So PATCH is the universal verb here:
for kv in \
  'KEYSERVER_DB_PASSWORD:<32-hex>' \
  'RELAYSERVER_DB_PASSWORD:<32-hex>' \
  'KEY_SERVER_TOKEN_SECRET:<64-hex>' \
  'RELAY_PROOF_OF_WORK_DIFFICULTY_BITS:16' \
  'RELAY_MESSAGE_TTL_SECONDS:604800'; do
  k="${kv%%:*}"; v="${kv#*:}"
  curl -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    $BASE/applications/$APP_UUID/envs \
    -d "{\"key\":\"$k\",\"value\":\"$v\",\"is_preview\":false}"
done

# 5. Deploy
curl -X POST -H "Authorization: Bearer $TOKEN" \
  $BASE/deploy?uuid=$APP_UUID
```

Asymmetries to remember:

- **`docker_compose_domains` GET vs PATCH shape.** GET returns a JSON
  string `'{"keyserver":{"domain":"..."}}'`. PATCH accepts an array
  `[{"name":"keyserver","domain":"..."}]`. Don't try to PATCH the
  string-of-map form back; the validator rejects it.
- **Env var endpoint verb.** `POST /applications/{uuid}/envs` returns
  *Environment variable already exists. Use PATCH request to update it.*
  whenever Coolify has pre-discovered a var from the compose. PATCH is
  the safe default.
- **Field naming.** Internal field is `is_buildtime` (single word), not
  `is_build_time`. Same for `is_runtime`, `is_coolify`. POST/PATCH
  accept any subset; you don't need to send all flags.

### Recovery: clean slate

If the deploy gets into a state where successive redeploys don't
converge — stale containers, half-applied label changes, env vars
stuck empty — **deleting and recreating the application is faster than
debugging**. We did this once during initial bring-up and it took
under five minutes end-to-end.

```bash
# 1. Delete the application (also wipes its volumes and per-resource networks)
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "$BASE/applications/$OLD_APP_UUID?delete_volumes=true&delete_connected_networks=true&docker_cleanup=true"
```

Or, in the UI: **Resource → Settings → Danger zone → Delete**, with the
"Delete volumes" and "Delete connected networks" checkboxes ticked.

Then run the API-based setup above (or repeat the UI quick-start). The
project + environment + GitHub App source are independent of any
specific application, so you don't need to recreate those.

Postgres data IS wiped by this — but at the early-deployment stage
that's exactly what you want. If you've been running long enough to
have real data, take a backup first (`scripts/backup.sh` works against
the Coolify-hosted containers — run it from a shell on the Coolify host
or adapt the `docker compose exec` calls to Coolify's container names).

### Coolify-vs-standalone trade-offs

| | Standalone (`docker-compose.prod.yml`) | Coolify (`docker-compose.coolify.yml`) |
|---|---|---|
| Reverse proxy | Caddy bundled in this directory | Traefik provided by Coolify |
| TLS | Caddy → Let's Encrypt | Coolify → Let's Encrypt |
| Configuration | `.env` on the host | Coolify UI environment vars |
| Logs | `docker compose logs` | Coolify UI logs panel |
| Backups | `scripts/backup.sh` | Same script + Coolify-managed volume backups |
| Maintenance | Manual `git pull` + `docker compose up -d` | Coolify auto-redeploys on git push if configured |

Choose Coolify if you already run it for other services or want a
managed UX; choose the standalone stack if you want a zero-dependency
deployment that's plain Docker + Caddy.

## Files in this directory

```
deploy/
├── docker-compose.prod.yml      six services + four volumes + two networks (Caddy stack)
├── docker-compose.coolify.yml   four services + two volumes + two networks (Coolify stack)
├── Caddyfile                    reverse proxy config for the standalone stack
├── .env.example                 template for the Caddy stack; copy to .env (gitignored)
├── .env.coolify.example         checklist of vars to paste into Coolify's UI
├── .gitignore                   excludes .env, backups/
├── README.md                    you are here
└── scripts/
    ├── setup.sh                 interactive first-run bootstrap (Caddy stack only)
    └── backup.sh                pg_dump both databases, timestamped tarball
```
