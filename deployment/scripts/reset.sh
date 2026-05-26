#!/usr/bin/env bash
# DESTRUCTIVE — stops all containers AND drops every volume.
# Use this to start completely fresh. The next ./deploy.sh will rebuild + re-import.
set -eo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ "${1:-}" != "--yes" ]; then
  read -r -p "This will DESTROY all bipros containers and volumes. Type 'yes' to continue: " confirm
  [ "$confirm" = "yes" ] || { echo "Aborted."; exit 1; }
fi

docker compose down -v --remove-orphans
rm -rf "${BIPROS_WORK_DIR:-/tmp/khasab}"
echo "Reset complete. Run ./deploy.sh to redeploy."
