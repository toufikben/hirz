#!/usr/bin/env bash
# Nightly export of the Supabase database to /opt/hirz/backups, keeping 7 days. Root crontab:
#   30 2 * * * /opt/hirz/repo/infra/staging/backup.sh >> /opt/hirz/backup.log 2>&1
# Use a pg_dump at least as new as the Supabase server (check `select version()`; set PG_IMAGE if it is newer than 16).
set -euo pipefail
env_file=/opt/hirz/core.env
value() { grep -E "^$1=" "$env_file" | tail -1 | cut -d= -f2-; }
url="$(value HIRZ_DATABASE_URL)"; user="$(value HIRZ_DATABASE_USER)"; PGPASSWORD="$(value HIRZ_DATABASE_PASSWORD)"
export PGPASSWORD
dir=/opt/hirz/backups
mkdir -p "$dir"; chmod 700 "$dir"
# HIRZ_DATABASE_URL is a JDBC URL: jdbc:postgresql://host:port/db?params
host_port_db="${url#jdbc:postgresql://}"; host_port_db="${host_port_db%%\?*}"
docker run --rm -e PGPASSWORD -v "$dir:/out" "${PG_IMAGE:-postgres:16-alpine}" \
  pg_dump "postgresql://${user}@${host_port_db}?sslmode=require" -Fc -f "/out/hirz-$(date -u +%Y%m%d).dump"
find "$dir" -name 'hirz-*.dump' -mtime +7 -delete
ls -l "$dir"
