#!/usr/bin/env bash
# Nightly export of the Supabase database to /opt/hirz/backups, keeping 7 days. Cron: 30 2 * * * /opt/hirz/repo/infra/staging/backup.sh
set -euo pipefail
set -a; . /opt/hirz/core.env; set +a
dir=/opt/hirz/backups
mkdir -p "$dir"; chmod 700 "$dir"
# HIRZ_DATABASE_URL is a JDBC URL: jdbc:postgresql://host:port/db?params
url="${HIRZ_DATABASE_URL#jdbc:}"
host_port_db="${url#postgresql://}"; host_port_db="${host_port_db%%\?*}"
PGPASSWORD="$HIRZ_DATABASE_PASSWORD" docker run --rm -e PGPASSWORD -v "$dir:/out" postgres:16-alpine \
  pg_dump "postgresql://${HIRZ_DATABASE_USER}@${host_port_db}?sslmode=require" -Fc -f "/out/hirz-$(date -u +%Y%m%d).dump"
find "$dir" -name 'hirz-*.dump' -mtime +7 -delete
ls -l "$dir"
