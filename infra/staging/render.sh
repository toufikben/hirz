#!/usr/bin/env bash
# Renders /opt/hirz/traccar.xml from the template with the secrets in /opt/hirz/core.env, then checks it. Run as root.
# Secrets never appear in a command line: awk reads them from its environment (review: no `ps` exposure).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
env_file="${1:-/opt/hirz/core.env}"
out="${2:-/opt/hirz/traccar.xml}"
value() { grep -E "^$1=" "$env_file" | tail -1 | cut -d= -f2-; }
HIRZ_TRACCAR_DB_PASSWORD="$(value HIRZ_TRACCAR_DB_PASSWORD)" HIRZ_TRACCAR_SECRET="$(value HIRZ_TRACCAR_SECRET)"
export HIRZ_TRACCAR_DB_PASSWORD HIRZ_TRACCAR_SECRET
: "${HIRZ_TRACCAR_DB_PASSWORD:?missing in $env_file}" "${HIRZ_TRACCAR_SECRET:?missing in $env_file}"
case "$HIRZ_TRACCAR_DB_PASSWORD$HIRZ_TRACCAR_SECRET" in
  *[\&\<\>\\\"\']*) echo "secrets must not contain & < > \\ \" or ' (generate them with openssl as in the runbook)" >&2; exit 1 ;;
esac
umask 077
awk '{
  gsub(/\$\{HIRZ_TRACCAR_DB_PASSWORD\}/, ENVIRON["HIRZ_TRACCAR_DB_PASSWORD"])
  gsub(/\$\{HIRZ_TRACCAR_SECRET\}/, ENVIRON["HIRZ_TRACCAR_SECRET"])
  sub(/<entry key=.web\.address.>127\.0\.0\.1<\/entry>/, "<entry key='"'"'web.address'"'"'>0.0.0.0</entry>")
  print
}' "$here/../traccar/hirz.template.xml" > "$out"
# Inside Docker Traccar binds 0.0.0.0 so Core can reach it; compose never publishes 8082.
docker run --rm -v "$here/../traccar/tools:/tools:ro" -v "$out:/cfg.xml:ro" node:22-alpine node /tools/check-config.mjs --container /cfg.xml
echo "rendered $out"
