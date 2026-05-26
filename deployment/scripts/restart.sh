#!/usr/bin/env bash
# Recreate all containers without touching volumes (data preserved).
set -eo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
echo "Restarting bipros stack (volumes preserved)…"
docker compose restart
echo "Waiting for backend health…"
until curl -fsS http://localhost:${API_HOST_PORT:-8080}/actuator/health 2>/dev/null | grep -q UP; do sleep 3; done
echo "OK — http://localhost:${API_HOST_PORT:-8080}/actuator/health"
