#!/usr/bin/env bash
# Staging firewall (design §8.2): SSH, HTTPS and the two device ports only. Idempotent.
# Oracle Cloud also needs the same ports in the VCN security list (see the runbook).
set -euo pipefail
ufw --force reset > /dev/null
ufw default deny incoming
ufw default allow outgoing
for port in 22 80 443 5023 5013; do ufw allow "$port/tcp"; done
ufw --force enable
ufw status verbose
