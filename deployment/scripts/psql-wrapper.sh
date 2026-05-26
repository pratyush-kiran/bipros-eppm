#!/usr/bin/env bash
# Thin wrapper so the python import scripts can call "psql" without needing
# psql installed on the host. Forwards through `docker exec bipros-postgres
# psql …`. Drops the host/port args (the in-container psql uses the local
# socket as the bipros user, no password needed).
#
# Set this via BIPROS_PSQL=…/psql-wrapper.sh
set -eo pipefail

CONTAINER="${BIPROS_PG_CONTAINER:-bipros-postgres}"

# Strip -h <val> and -p <val> pairs; pass the rest through.
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|-p) shift 2 ;;
    *)     ARGS+=("$1"); shift ;;
  esac
done

# Pipe stdin through so `psql … <<< "SELECT …"` and -f - both work.
if [ -t 0 ]; then
  exec docker exec -i -e PGPASSWORD="${PGPASSWORD:-}" "$CONTAINER" psql "${ARGS[@]}"
else
  exec docker exec -i -e PGPASSWORD="${PGPASSWORD:-}" "$CONTAINER" psql "${ARGS[@]}"
fi
