#!/usr/bin/env bash
# Refresh the Khasab DPRs against the ALREADY-running stack (does NOT touch containers).
# Use when DPR data is stale/corrupted and you want to rebuild it from source without a
# full reset+deploy.
#
# Unlike a fresh deploy, the DB already has DPRs — so a plain import would just hit
# "dup". This wipes the project's DPRs and re-imports them (by-date parallel), seeding
# any missing rate-book variants first so every resource resolves and pre-selects in the
# DPR dropdown.
#
# Assumes bipros-api is up and admin/admin123 works. For a full rebuild use ./deploy.sh.
set -eo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Load deployment env (API port, work dir, etc.) if present.
[ -f configs/.env ] && set -a && . configs/.env && set +a

export BIPROS_API_BASE="${BIPROS_API_BASE:-http://localhost:${API_HOST_PORT:-8080}}"
export BIPROS_WORK_DIR="${BIPROS_WORK_DIR:-/tmp/khasab}"
export BIPROS_TOKEN_FILE="${BIPROS_TOKEN_FILE:-$BIPROS_WORK_DIR/admin-token.txt}"
export BIPROS_EXCEL_DIR="${BIPROS_EXCEL_DIR:-$PWD/data/khasab-excel}"
# psql wrapper + DB coords for the BOQ-link SQL step (forwards through docker exec).
export BIPROS_PSQL="${BIPROS_PSQL:-$PWD/scripts/psql-wrapper.sh}"
export BIPROS_PG_USER="${BIPROS_PG_USER:-${POSTGRES_USER:-bipros}}"
export BIPROS_PG_DB="${BIPROS_PG_DB:-${POSTGRES_DB:-bipros}}"

imp="imports"

echo "==> Re-parsing source workbook"
python3 "$imp/parse_khasab.py" 2>&1 | tail -3

echo "==> Seeding rate-book variants for every DPR resource (idempotent)"
python3 "$imp/seed_resource_catalog.py" 2>&1 | grep -E "Seeding|Seed summary|Coverage|STILL" || true

echo "==> Wipe + reload DPRs (by-date parallel)"
python3 "$imp/reload_dprs.py"

echo "==> Linking every DPR to a BOQ item (match by code, else random)"
python3 "$imp/link_dprs_to_boq.py"

echo "==> Assigning supervisors to activities (from DPR data)"
python3 "$imp/assign_activity_supervisors.py"

echo "==> Aligning units across WorkActivity / Activity / DPR"
python3 "$imp/align_activity_units.py"
