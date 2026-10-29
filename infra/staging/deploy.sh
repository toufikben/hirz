#!/usr/bin/env bash
# Deploys a Core image (the CI commit sha, or "main") and waits until https://$HIRZ_HOST/health answers.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
export HIRZ_CORE_TAG="${1:?usage: deploy.sh <image-tag>}"
: "${HIRZ_HOST:?export HIRZ_HOST, e.g. staging.example.dz}"
cd "$here"
docker compose pull
docker compose up -d
for i in $(seq 1 60); do
  if curl -fsS "https://$HIRZ_HOST/health" > /dev/null; then echo "healthy after $((i * 5)) s: core $HIRZ_CORE_TAG"; exit 0; fi
  sleep 5
done
echo "Core did not become healthy" >&2
docker compose logs --tail 80 core >&2
exit 1
