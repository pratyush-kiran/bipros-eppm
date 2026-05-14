#!/usr/bin/env bash
# Wipes activity-level resource assignments + DPRs across all projects, then
# seeds default role-rate variants for every existing ResourceRole. Run AFTER
# the backend has booted at least once (so Liquibase has created the schemas).
#
# Usage:
#   ./scripts/reset-and-seed-role-rates.sh
#
# Override DB connection via env vars:
#   PGHOST (default localhost) PGPORT (5432) PGDATABASE (bipros)
#   PGUSER (postgres) PGPASSWORD (postgres_password)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="$SCRIPT_DIR/reset-and-seed-role-rates.sql"

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-bipros}"
export PGUSER="${PGUSER:-postgres}"
export PGPASSWORD="${PGPASSWORD:-postgres_password}"

echo "Resetting role-rate data on $PGHOST:$PGPORT/$PGDATABASE as $PGUSER..."
psql -f "$SQL_FILE"
echo "Done."
