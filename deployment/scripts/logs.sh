#!/usr/bin/env bash
# Tail the bipros-api logs. Default: follow last 100 lines.
# Usage: ./scripts/logs.sh [service]   service defaults to bipros-api
set -eo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
SVC="${1:-bipros-api}"
exec docker compose logs -f --tail=100 "$SVC"
