#!/usr/bin/env bash
#
# Khord production backup — pg_dump both databases through the running
# Postgres containers, gzip each dump, drop both into a single
# timestamped tarball under deploy/backups/.
#
# Usage:
#   deploy/scripts/backup.sh          # writes deploy/backups/<timestamp>.tar.gz
#
# Restore (manual, kept off-by-default to prevent accidents):
#   tar -xzf deploy/backups/<timestamp>.tar.gz -C /tmp
#   cat /tmp/keyserver_<timestamp>.sql.gz | gunzip \
#     | docker compose -f deploy/docker-compose.prod.yml exec -T keyserver-db \
#         psql -U keyserver -d keyserver
#   # …same for relayserver…

set -euo pipefail

cd "$(dirname "$0")/.."
DEPLOY_DIR="$(pwd)"
COMPOSE_FILE="${DEPLOY_DIR}/docker-compose.prod.yml"
BACKUP_DIR="${DEPLOY_DIR}/backups"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$BACKUP_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok()  { printf "\033[32m✓ %s\033[0m\n" "$*"; }
err() { printf "\033[31m✗ %s\033[0m\n" "$*" >&2; }

dump_one() {
    local svc="$1" user="$2" db="$3" out="$4"
    if ! docker compose -f "$COMPOSE_FILE" exec -T "$svc" \
            pg_dump -U "$user" -d "$db" --clean --if-exists \
            | gzip > "$out"; then
        err "pg_dump of $db failed (is the $svc container running?)"
        return 1
    fi
    ok "$db -> $(basename "$out") ($(du -h "$out" | cut -f1))"
}

dump_one keyserver-db   keyserver   keyserver   "$TMP/keyserver_${TIMESTAMP}.sql.gz"
dump_one relayserver-db relayserver relayserver "$TMP/relayserver_${TIMESTAMP}.sql.gz"

ARCHIVE="${BACKUP_DIR}/khord_${TIMESTAMP}.tar.gz"
tar -czf "$ARCHIVE" -C "$TMP" \
    "keyserver_${TIMESTAMP}.sql.gz" \
    "relayserver_${TIMESTAMP}.sql.gz"

ok "Wrote $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
