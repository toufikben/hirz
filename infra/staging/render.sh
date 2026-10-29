#!/usr/bin/env bash
# Renders /opt/hirz/traccar.xml from the template with the secrets in /opt/hirz/core.env, then checks it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
env_file="${1:-/opt/hirz/core.env}"
out="${2:-/opt/hirz/traccar.xml}"
set -a; . "$env_file"; set +a
: "${HIRZ_TRACCAR_DB_PASSWORD:?missing in $env_file}" "${HIRZ_TRACCAR_SECRET:?missing in $env_file}"
umask 077
sed -e "s|\${HIRZ_TRACCAR_DB_PASSWORD}|${HIRZ_TRACCAR_DB_PASSWORD}|" \
    -e "s|\${HIRZ_TRACCAR_SECRET}|${HIRZ_TRACCAR_SECRET}|g" \
    -e "s|<entry key='web.address'>127.0.0.1</entry>|<entry key='web.address'>0.0.0.0</entry>|" \
    "$here/../traccar/hirz.template.xml" > "$out"
# Inside Docker Traccar binds 0.0.0.0 so Core can reach it; compose never publishes 8082.
docker run --rm -v "$here/../traccar/tools:/tools:ro" -v "$out:/cfg.xml:ro" node:22-alpine node /tools/check-config.mjs --container /cfg.xml
echo "rendered $out"
