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

## Files in this directory

```
deploy/
├── docker-compose.prod.yml   six services + four volumes + two networks
├── Caddyfile                 reverse proxy config, two server blocks
├── .env.example              template; copy to .env (gitignored)
├── .gitignore                excludes .env, backups/
├── README.md                 you are here
└── scripts/
    ├── setup.sh              interactive first-run bootstrap
    └── backup.sh             pg_dump both databases, timestamped tarball
```
