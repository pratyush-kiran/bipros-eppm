#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Bipros EPPM — one-click deployment for Linux / macOS / EC2.
#
# Brings up the full Dockerised stack from a fresh machine and loads the
# Khasab Road Project 2026 demo dataset. Idempotent: safe to re-run.
#
# Usage:
#   ./deploy.sh                          # full deploy (skip if Khasab already loaded)
#   ./deploy.sh --force                  # tear down volumes + redeploy from scratch
#   ./deploy.sh --skip-import            # bring up stack only, no data import
#   ./deploy.sh --skip-build             # skip docker compose build (use existing image)
#
# Env overrides (optional — see configs/.env.example for defaults):
#   ENV_FILE=/path/to/.env  ./deploy.sh
# ─────────────────────────────────────────────────────────────────────────────
set -eo pipefail

# ─── Configuration ──────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load env file if present
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/configs/.env}"
if [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
elif [ -f "$SCRIPT_DIR/configs/.env.example" ] && [ ! -f "$SCRIPT_DIR/configs/.env" ]; then
  cp "$SCRIPT_DIR/configs/.env.example" "$SCRIPT_DIR/configs/.env"
  set -a; . "$SCRIPT_DIR/configs/.env"; set +a
fi

LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"
DEPLOY_LOG="$LOG_DIR/deploy-$(date +%Y%m%d-%H%M%S).log"
ln -sf "$DEPLOY_LOG" "$LOG_DIR/deploy-latest.log"

API_HOST_PORT="${API_HOST_PORT:-8080}"
PG_HOST_PORT="${PG_HOST_PORT:-5433}"
REQUIRED_PORTS=("$API_HOST_PORT" "$PG_HOST_PORT" "${REDIS_HOST_PORT:-6379}" \
                "${CLICKHOUSE_HTTP_PORT:-8123}" "${MINIO_API_PORT:-9000}" \
                "${MINIO_CONSOLE_PORT:-9001}" "${DOCLING_PORT:-5001}" \
                "${PGADMIN_PORT:-5050}")

# Stages — keep TOTAL_STEPS in sync
TOTAL_STEPS=12

FORCE=0
SKIP_IMPORT=0
SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --force)        FORCE=1 ;;
    --skip-import)  SKIP_IMPORT=1 ;;
    --skip-build)   SKIP_BUILD=1 ;;
    -h|--help)      sed -n '2,16p' "$0"; exit 0 ;;
    *)              echo "Unknown arg: $arg"; exit 2 ;;
  esac
done

# ─── Colors (only if TTY) ───────────────────────────────────────────────────
if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'; C_CYAN=$'\033[36m'
  TTY=1
else
  C_RESET= C_BOLD= C_DIM= C_RED= C_GREEN= C_YELLOW= C_BLUE= C_CYAN=
  TTY=0
fi

CURRENT_STEP=0
log()      { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$DEPLOY_LOG" >&2; }
log_info() { printf '%s%s%s %s\n' "$C_CYAN"   "[INFO]"    "$C_RESET" "$*" | tee -a "$DEPLOY_LOG" >&2; }
log_ok()   { printf '%s%s%s %s\n' "$C_GREEN"  "[OK]"      "$C_RESET" "$*" | tee -a "$DEPLOY_LOG" >&2; }
log_warn() { printf '%s%s%s %s\n' "$C_YELLOW" "[WARN]"    "$C_RESET" "$*" | tee -a "$DEPLOY_LOG" >&2; }
log_err()  { printf '%s%s%s %s\n' "$C_RED"    "[ERROR]"   "$C_RESET" "$*" | tee -a "$DEPLOY_LOG" >&2; }

stage() {
  CURRENT_STEP=$((CURRENT_STEP + 1))
  local pct=$(( CURRENT_STEP * 100 / TOTAL_STEPS ))
  local bar_len=24
  local filled=$(( CURRENT_STEP * bar_len / TOTAL_STEPS ))
  local bar=""
  for ((i = 0; i < filled; i++)); do bar+="█"; done
  for ((i = filled; i < bar_len; i++)); do bar+="░"; done
  printf '\n%s═══════════════════════════════════════════════════════════════%s\n' "$C_DIM" "$C_RESET" | tee -a "$DEPLOY_LOG" >&2
  printf '%s[%2d/%2d]%s %s%s%s  %s[%s]%s %d%%\n' \
    "$C_BOLD" "$CURRENT_STEP" "$TOTAL_STEPS" "$C_RESET" \
    "$C_BOLD$C_CYAN" "$*" "$C_RESET" "$C_DIM" "$bar" "$C_RESET" "$pct" | tee -a "$DEPLOY_LOG" >&2
}

banner() {
  cat <<EOF | tee -a "$DEPLOY_LOG" >&2
${C_BOLD}${C_CYAN}
═══════════════════════════════════════════════════════════════
  Bipros EPPM — Khasab Road Project 2026
  Single-host deployment (Docker)
═══════════════════════════════════════════════════════════════${C_RESET}
  Deploy log:   $DEPLOY_LOG
  Env file:     $ENV_FILE
  Force redeploy: $([ $FORCE -eq 1 ] && echo YES || echo no)
  Skip import:    $([ $SKIP_IMPORT -eq 1 ] && echo YES || echo no)
EOF
}

fatal() { log_err "$*"; exit 1; }

# ─── Step 1: Docker preflight ───────────────────────────────────────────────
preflight_docker() {
  stage "Docker preflight"
  # CRITICAL: the bipros-api image needs the Java sources at ../backend.
  # If you only copied the deployment/ folder, the build will fail with
  # "unable to prepare context: path ... /backend not found".
  if [ ! -d "$SCRIPT_DIR/../backend" ]; then
    log_err "Missing $SCRIPT_DIR/../backend (the Spring Boot source tree)."
    log_err "You appear to have copied only the deployment/ folder."
    log_err ""
    log_err "Fix — clone the full repo:"
    log_err "  cd $(dirname "$SCRIPT_DIR")"
    log_err "  git clone https://github.com/Hemendra1990/bipros-eppm.git $(basename "$(dirname "$SCRIPT_DIR")")"
    log_err "  cd $(basename "$(dirname "$SCRIPT_DIR")") && git checkout khasab-demo-ready-2026-05-24"
    log_err "  cd deployment && ./deploy.sh"
    fatal "Cannot continue without backend/ sources"
  fi

  if ! command -v docker >/dev/null 2>&1; then
    fatal "Docker is not installed. Install Docker Engine or Docker Desktop and re-run."
  fi
  log_ok "docker CLI:    $(docker --version)"

  if ! docker info >/dev/null 2>&1; then
    log_warn "Docker daemon is not running — attempting to start it"
    case "$(uname -s)" in
      Darwin)
        open -a Docker || fatal "Could not auto-start Docker Desktop on macOS"
        log_info "Waiting up to 90s for Docker Desktop to come up…"
        local t=0
        until docker info >/dev/null 2>&1; do
          sleep 3; t=$((t+3))
          [ $t -ge 90 ] && fatal "Docker did not start within 90s"
        done
        ;;
      Linux)
        if command -v systemctl >/dev/null 2>&1; then
          sudo systemctl start docker || fatal "systemctl start docker failed — check 'systemctl status docker'"
        elif command -v service >/dev/null 2>&1; then
          sudo service docker start || fatal "service docker start failed"
        else
          fatal "Docker daemon not running and no systemctl/service available — start it manually"
        fi
        sleep 2
        docker info >/dev/null 2>&1 || fatal "Docker still not responding after start attempt"
        ;;
      *)
        fatal "Unknown platform $(uname -s) — start Docker manually and re-run"
        ;;
    esac
  fi
  log_ok "docker daemon: running"

  if ! docker compose version >/dev/null 2>&1; then
    fatal "docker compose plugin missing — install Docker Compose v2"
  fi
  log_ok "compose:       $(docker compose version --short 2>/dev/null || docker compose version | head -1)"
}

# ─── Step 2: Port + disk preflight ──────────────────────────────────────────
preflight_ports() {
  stage "Checking host ports + disk space"
  local conflicts=()
  for p in "${REQUIRED_PORTS[@]}"; do
    if (lsof -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | grep -q LISTEN) \
       || (ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":${p}$"); then
      # Identify the owner (container name if a container holds it; PID otherwise).
      local owner
      owner=$(docker ps --filter "publish=$p" --format '{{.Names}}' 2>/dev/null | head -1)
      if [ -z "$owner" ]; then
        owner=$(lsof -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1}')
        owner="${owner:-(unknown)}"
      fi
      # Allow ports owned by our own bipros-* containers (idempotent re-runs)
      if [[ "$owner" != bipros-* ]]; then
        conflicts+=("$p → $owner")
      fi
    fi
  done
  if [ ${#conflicts[@]} -gt 0 ]; then
    log_err "Ports already in use by other processes:"
    for c in "${conflicts[@]}"; do log_err "  $c"; done
    log_err ""
    log_err "Fix — pick one of:"
    log_err "  (a) Stop the conflicting container/process, then re-run."
    log_err "      e.g.  docker stop <name>"
    log_err "  (b) Remap our port via $SCRIPT_DIR/configs/.env, then re-run. Example:"
    log_err "      echo 'REDIS_HOST_PORT=6380' >> $SCRIPT_DIR/configs/.env"
    log_err "      echo 'API_HOST_PORT=8081'   >> $SCRIPT_DIR/configs/.env"
    log_err "      (every host port is overridable — see configs/.env.example)"
    fatal "Port conflict"
  fi
  log_ok "All required ports free or owned by bipros containers"

  local free_kb
  if command -v df >/dev/null 2>&1; then
    free_kb=$(df -k "$SCRIPT_DIR" | awk 'NR==2 {print $4}')
    local free_gb=$(( free_kb / 1024 / 1024 ))
    if [ "$free_gb" -lt 10 ]; then
      log_warn "Only ${free_gb} GB free on $SCRIPT_DIR — recommend ≥ 20 GB"
    else
      log_ok "Disk free:     ${free_gb} GB"
    fi
  fi
}

# ─── Step 3: Optional force-wipe ────────────────────────────────────────────
force_wipe() {
  stage "Tear-down (--force)"
  if [ $FORCE -ne 1 ]; then
    log_info "Skipped (no --force flag)"
    return
  fi
  log_warn "Destroying existing containers + volumes…"
  docker compose down -v --remove-orphans 2>&1 | tee -a "$DEPLOY_LOG" >&2 || true
  log_ok "Tear-down complete"

  # After a wipe the DB is empty again — DataSeeder must fire to recreate the
  # admin user. Override BIPROS_PROFILES for this run if the caller (or .env)
  # has trimmed it down to a profile that doesn't activate DataSeeder.
  if [[ ! ",${BIPROS_PROFILES:-prod,init-prod}," =~ ,(dev|seed|init-prod), ]]; then
    log_warn "BIPROS_PROFILES=${BIPROS_PROFILES} would skip DataSeeder on empty DB — appending init-prod for this run"
    export BIPROS_PROFILES="${BIPROS_PROFILES},init-prod"
  fi
}

# ─── Step 4: Build backend image ────────────────────────────────────────────
build_image() {
  stage "Build backend image"
  if [ $SKIP_BUILD -eq 1 ]; then
    log_info "Skipped (--skip-build)"
    return
  fi
  log_info "Building bipros-api:prod (multi-stage, ~5-12 min first time)…"
  # IMPORTANT: stream the build output and check the actual exit code
  # (the previous version masked failures with `|| true`).
  if ! DOCKER_BUILDKIT=1 docker compose build bipros-api 2>&1 | tee -a "$DEPLOY_LOG" >&2; then
    log_err "Image build failed. Common causes:"
    log_err "  • Missing ../backend/ sources (clone the full repo, not just deployment/)"
    log_err "  • Out of disk space"
    log_err "  • Network timeout to Maven Central / Docker Hub"
    fatal "Build failure"
  fi
  if [ "${PIPESTATUS[0]:-0}" -ne 0 ]; then
    fatal "Image build failed — see log: $DEPLOY_LOG"
  fi
  log_ok "Image built"
}

# ─── Step 5: Start Postgres + Redis ─────────────────────────────────────────
start_infra_db() {
  stage "Start Postgres + Redis"
  docker compose up -d postgresql redis 2>&1 | tee -a "$DEPLOY_LOG" >&2
  log_info "Waiting for Postgres healthcheck…"
  local t=0
  until docker compose ps postgresql --format json 2>/dev/null | grep -q '"Health":"healthy"'; do
    sleep 3; t=$((t+3))
    [ $t -ge 120 ] && fatal "Postgres did not become healthy within 120s"
  done
  log_ok "Postgres healthy"
}

# ─── Step 6: Start ClickHouse + MinIO + Docling + pgAdmin ───────────────────
start_infra_misc() {
  stage "Start ClickHouse + MinIO + Docling + pgAdmin"
  docker compose up -d clickhouse minio docling pgadmin 2>&1 | tee -a "$DEPLOY_LOG" >&2
  log_info "Waiting for ClickHouse healthcheck…"
  local t=0
  until docker compose ps clickhouse --format json 2>/dev/null | grep -q '"Health":"healthy"'; do
    sleep 3; t=$((t+3))
    [ $t -ge 90 ] && { log_warn "ClickHouse slow to start — continuing anyway"; break; }
  done
  log_ok "Infra stack up"
}

# ─── Step 7: Start backend (first-boot profile) ─────────────────────────────
start_backend() {
  stage "Start bipros-api (profile: ${BIPROS_PROFILES:-prod,init-prod})"
  docker compose up -d bipros-api 2>&1 | tee -a "$DEPLOY_LOG" >&2
  log_info "Waiting for backend health (up to 3 min)…"
  local t=0
  until curl -fsS "http://localhost:${API_HOST_PORT}/actuator/health" 2>/dev/null | grep -q UP; do
    sleep 5; t=$((t+5))
    if docker ps -a --filter "name=bipros-api" --format "{{.Status}}" | grep -qiE "exit"; then
      log_err "bipros-api exited during boot — last 15 log lines:"
      docker logs bipros-api --tail 15 2>&1 | tee -a "$DEPLOY_LOG" >&2
      fatal "Backend boot failure"
    fi
    [ $t -ge 180 ] && fatal "Backend did not become healthy within 180s"
    [ $((t % 30)) -eq 0 ] && log_info "  …still waiting (${t}s)"
  done
  log_ok "Backend healthy: http://localhost:${API_HOST_PORT}/actuator/health"
}

# ─── Step 8: Bootstrap masters from data/sql/ ──────────────────────────────
bootstrap_masters() {
  stage "Bootstrap resource catalogue (masters)"
  # Only seed if catalogue is empty (idempotent)
  local n
  n=$(docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -At \
        -c "SELECT COUNT(*) FROM resource.equipment_role_variants" 2>/dev/null || echo 0)
  if [ "$n" -gt 0 ]; then
    log_info "Catalogue already populated ($n equipment role variants) — skipping"
    return
  fi
  log_info "Loading $SCRIPT_DIR/data/sql/01-bipros-masters.sql…"
  # Wipe Hibernate-auto-seeded resource_types first so dev UUIDs land cleanly
  docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -q \
    -c "DELETE FROM resource.resource_roles; DELETE FROM resource.resource_types;" >> "$DEPLOY_LOG" 2>&1 || true
  docker exec -i bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" \
    < "$SCRIPT_DIR/data/sql/01-bipros-masters.sql" 2>&1 \
    | grep -E --line-buffered "^(INSERT|ERROR)" | head -50 >> "$DEPLOY_LOG" 2>&1 || true
  n=$(docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -At \
        -c "SELECT COUNT(*) FROM resource.equipment_role_variants")
  log_ok "Catalogue loaded: $n equipment role variants, $(docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -At -c "SELECT COUNT(*) FROM resource.resource_roles") resource roles"

  # Apply post-deploy schema patches the backend assumes (tsv column for HDS
  # search, default UI theme rows). Idempotent — guarded with IF NOT EXISTS /
  # ON CONFLICT DO NOTHING.
  if [ -f "$SCRIPT_DIR/data/sql/02-post-deploy-patches.sql" ]; then
    log_info "Applying post-deploy schema patches…"
    docker exec -i bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" \
      < "$SCRIPT_DIR/data/sql/02-post-deploy-patches.sql" >> "$DEPLOY_LOG" 2>&1
    log_ok "post-deploy patches applied (hds.hds_chunk.tsv + ui.* defaults)"
  fi
}

# ─── Step 9: Verify admin + grab token ──────────────────────────────────────
grab_admin_token() {
  stage "Authenticate as admin"
  local WORK_DIR="${BIPROS_WORK_DIR:-/tmp/khasab}"
  mkdir -p "$WORK_DIR"

  # Backend health goes UP as soon as Tomcat starts, but DataSeeder
  # (@Order(100) CommandLineRunner) runs ~15s later. Poll for the admin
  # row in the DB so we don't hit /v1/auth/login during that window.
  log_info "Waiting for DataSeeder to create the admin row (up to 90s)…"
  local t=0
  until [ "$(docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -At \
              -c "SELECT 1 FROM public.users WHERE username='admin' LIMIT 1" 2>/dev/null)" = "1" ]; do
    sleep 3; t=$((t+3))
    if [ $t -ge 90 ]; then
      log_err "admin row never appeared in public.users after 90s."
      log_err "Check that DataSeeder ran:"
      log_err "  docker logs bipros-api | grep -i DataSeeder"
      log_err "Common cause: BIPROS_PROFILES doesn't include init-prod (or dev/seed)."
      log_err "Current BIPROS_PROFILES = ${BIPROS_PROFILES:-prod,init-prod}"
      fatal "admin user not seeded"
    fi
  done

  local TOKEN
  TOKEN=$(curl -sS -X POST "http://localhost:${API_HOST_PORT}/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}' \
    | python3 -c 'import sys,json; r=json.load(sys.stdin); print(r["data"]["accessToken"])' 2>/dev/null) || true
  if [ -z "$TOKEN" ]; then
    fatal "admin row exists but login still returned 401 — password mismatch or auth filter not ready"
  fi
  echo "$TOKEN" > "$WORK_DIR/admin-token.txt"
  log_ok "Admin token saved → $WORK_DIR/admin-token.txt"
}

# ─── Step 10: Run Khasab import (parse, project, activities, role-assignments) ──
run_import_pre_dpr() {
  stage "Khasab import — parse + project + activities + role-assignments"
  if [ $SKIP_IMPORT -eq 1 ]; then
    log_info "Skipped (--skip-import)"
    return
  fi

  # Idempotency check
  local PROJECT_COUNT
  PROJECT_COUNT=$(docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -At \
    -c "SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026'" 2>/dev/null || echo 0)
  if [ "$PROJECT_COUNT" -gt 0 ] && [ $FORCE -ne 1 ]; then
    log_info "KHASAB-2026 project already exists — skipping import. Use --force to redo."
    return
  fi

  # Common env for all python scripts
  export BIPROS_API_BASE="http://localhost:${API_HOST_PORT}"
  export BIPROS_PG_HOST=127.0.0.1
  export BIPROS_PG_PORT="${PG_HOST_PORT}"
  export BIPROS_PG_USER="${POSTGRES_USER:-bipros}"
  export BIPROS_PG_PASS="${POSTGRES_PASSWORD:-bipros_dev}"
  export BIPROS_PG_DB="${POSTGRES_DB:-bipros}"
  export BIPROS_WORK_DIR="${BIPROS_WORK_DIR:-/tmp/khasab}"
  export BIPROS_TOKEN_FILE="$BIPROS_WORK_DIR/admin-token.txt"
  export BIPROS_EXCEL_DIR="$SCRIPT_DIR/data/khasab-excel"
  export BIPROS_PSQL="${SCRIPT_DIR}/scripts/psql-wrapper.sh"

  # Quick sanity: required python modules. Install strategy adapts to the
  # environment — venvs need plain `pip install`, system Python on Ubuntu 24.04+
  # needs apt (or `pip --break-system-packages` for PEP 668), older systems
  # tolerate `pip --user`. Errors are written to the deploy log instead of
  # silently swallowed.
  local _missing=()
  python3 -c 'import openpyxl'               2>/dev/null || _missing+=(openpyxl)
  python3 -c 'import dateutil.relativedelta' 2>/dev/null || _missing+=(python-dateutil)
  if [ ${#_missing[@]} -gt 0 ]; then
    log_warn "python deps missing: ${_missing[*]} — installing…"
    local _apt=()
    for m in "${_missing[@]}"; do
      case "$m" in
        openpyxl)         _apt+=(python3-openpyxl) ;;
        python-dateutil)  _apt+=(python3-dateutil) ;;
      esac
    done

    _install_ok=0
    if [ -n "${VIRTUAL_ENV:-}" ]; then
      # Inside a venv — pip install goes into venv/site-packages, which the
      # venv's python WILL find. apt + --user both miss the venv's sys.path.
      log_info "  detected venv $VIRTUAL_ENV — using plain pip install"
      python3 -m pip install --quiet "${_missing[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    elif command -v apt-get >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
      log_info "  trying apt (no venv detected)…"
      sudo apt-get install -y -qq "${_apt[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    fi
    if [ $_install_ok -ne 1 ]; then
      log_info "  trying pip --break-system-packages…"
      python3 -m pip install --user --break-system-packages --quiet "${_missing[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    fi
    if [ $_install_ok -ne 1 ]; then
      log_info "  trying pip --user…"
      python3 -m pip install --user --quiet "${_missing[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    fi

    if ! python3 -c 'import openpyxl, dateutil.relativedelta' 2>/dev/null; then
      log_err "Could not install python deps. Manual fixes:"
      log_err "  If inside venv:   pip install ${_missing[*]}"
      log_err "  If system python: sudo apt-get install -y ${_apt[*]}"
      log_err "  Then re-run with --skip-build"
      fatal "python dependency setup failed (full output in $DEPLOY_LOG)"
    fi
  fi

  local imp="$SCRIPT_DIR/imports"
  log_info "  parse_khasab.py"
  python3 "$imp/parse_khasab.py" 2>&1 | tail -3 | tee -a "$DEPLOY_LOG" >&2
  log_info "  parse_master_sheet.py"
  python3 "$imp/parse_master_sheet.py" 2>&1 | tail -3 | tee -a "$DEPLOY_LOG" >&2
  log_info "  analyze_resource_demand.py"
  python3 "$imp/analyze_resource_demand.py" 2>&1 | tail -3 | tee -a "$DEPLOY_LOG" >&2
  log_info "  rebuild_demo.py (project + users + team + WBS + 33 activities)"
  python3 "$imp/rebuild_demo.py" 2>&1 | tail -15 | tee -a "$DEPLOY_LOG" >&2
  log_info "  fix_role_assignments.py (229 role-assignments)"
  python3 "$imp/fix_role_assignments.py" 2>&1 | grep -E "Total|created" | tee -a "$DEPLOY_LOG" >&2 || true

  # Guarantee every DPR resource name has a rate-book variant so the imported
  # manpower/equipment rows resolve to a role+variant and pre-select in the DPR
  # dropdown on any activity (idempotent — skips names already covered).
  log_info "  seed_resource_catalog.py (rate-book variants for every DPR resource)"
  python3 "$imp/seed_resource_catalog.py" 2>&1 | grep -E "Seeding|Seed summary|Coverage|STILL" | tee -a "$DEPLOY_LOG" >&2 || true

  # Assign each activity the supervisor(s) who actually file DPRs for it, so the DPR form
  # filters activities by supervisor (instead of "No activities assigned … showing all").
  log_info "  assign_activity_supervisors.py (activity ↔ supervisor mapping)"
  python3 "$imp/assign_activity_supervisors.py" 2>&1 | tail -3 | tee -a "$DEPLOY_LOG" >&2 || true

  # fix_role_assignments unlocks activities; re-lock so DPRs can post
  log_info "  Re-locking activities for DPR ingest"
  local TOKEN PID
  TOKEN=$(cat "$BIPROS_WORK_DIR/admin-token.txt")
  PID=$(cat "$BIPROS_WORK_DIR/project-id.txt")
  local locked=0
  for aid in $(python3 -c "import json; print(' '.join(json.load(open('$BIPROS_WORK_DIR/activity-ids.json')).values()))"); do
    sc=$(curl -sS -o /dev/null -w "%{http_code}" -X POST \
      "http://localhost:${API_HOST_PORT}/v1/projects/$PID/activities/$aid/lock" \
      -H "Authorization: Bearer $TOKEN")
    [ "$sc" = "200" ] && locked=$((locked+1))
  done
  log_ok "Locked $locked activities"
}

# ─── Step 11: DPR import (long: ~5-15 min in container) ─────────────────────
run_import_dprs() {
  stage "Khasab import — DPR upload (3,431 rows, ~5-15 min)"
  if [ $SKIP_IMPORT -eq 1 ]; then
    log_info "Skipped (--skip-import)"
    return
  fi

  local PROJECT_COUNT
  PROJECT_COUNT=$(docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -At \
    -c "SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')" 2>/dev/null || echo 0)
  if [ "$PROJECT_COUNT" -ge 3400 ] && [ $FORCE -ne 1 ]; then
    log_info "DPRs already imported ($PROJECT_COUNT rows) — skipping"
    return
  fi

  python3 "$SCRIPT_DIR/imports/import_khasab_dprs.py" all 2>&1 \
    | tee -a "$LOG_DIR/dpr-import.log" \
    | grep -E --line-buffered "DONE|ok=|fail=|Sample errors" >&2 || true

  local n
  n=$(docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -At \
        -c "SELECT COUNT(*) FROM project.daily_progress_reports")
  log_ok "DPRs in DB: $n"
}

# ─── Step 12: Post-import (cost/EVM/BOQ/norms/dashboard/risks) ──────────────
run_import_post_dpr() {
  stage "Khasab import — cost + EVM + BOQ + dashboard + risks"
  if [ $SKIP_IMPORT -eq 1 ]; then
    log_info "Skipped (--skip-import)"
    return
  fi

  local imp="$SCRIPT_DIR/imports"
  log_info "  fix_demo_v2.py (cost + EVM + BOQ + MCL + DBS recompute)"
  python3 "$imp/fix_demo_v2.py" 2>&1 | tail -10 | tee -a "$DEPLOY_LOG" >&2
  # Every DPR must reference a BOQ item. This links each DPR to the BOQ whose item_no
  # matches its activity code, or — if none matches — a stable pseudo-random existing BOQ
  # item (race-free SQL; uses only the existing register). Must run AFTER fix_demo_v2,
  # which (re)creates the BOQ items.
  log_info "  link_dprs_to_boq.py (BOQ item on every DPR)"
  python3 "$imp/link_dprs_to_boq.py" 2>&1 | tail -6 | tee -a "$DEPLOY_LOG" >&2 || true
  # Make WorkActivity.default_unit, the Activity, and the DPR all carry one canonical unit
  # per activity code (kills the "Master Work Activity unit is X but this DPR uses Y" warning).
  log_info "  align_activity_units.py (consistent unit across WorkActivity/Activity/DPR)"
  python3 "$imp/align_activity_units.py" 2>&1 | tail -3 | tee -a "$DEPLOY_LOG" >&2 || true
  log_info "  create_norms_only.py (idempotent)"
  python3 "$imp/create_norms_only.py" 2>&1 | tail -5 | tee -a "$DEPLOY_LOG" >&2 || true
  log_info "  tune_productivity_norms.sql"
  docker exec -i bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" \
    < "$imp/tune_productivity_norms.sql" 2>&1 | tail -5 | tee -a "$DEPLOY_LOG" >&2 || true
  log_info "  populate_dashboard.py (calendar + weather + milestones + 6 issues)"
  python3 "$imp/populate_dashboard.py" 2>&1 | tail -10 | tee -a "$DEPLOY_LOG" >&2
  log_info "  add_weather_risks.py (weather + 8 risks)"
  python3 "$imp/add_weather_risks.py" 2>&1 | tail -10 | tee -a "$DEPLOY_LOG" >&2
  log_info "  fix_dpr_activity_name_drift.sql (denormalized snapshot fix)"
  docker exec -i bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" \
    < "$SCRIPT_DIR/data/sql/99-fix-dpr-drift.sql" 2>&1 | tail -5 | tee -a "$DEPLOY_LOG" >&2 || true
}

# ─── Summary ────────────────────────────────────────────────────────────────
print_summary() {
  printf '\n%s═══════════════════════════════════════════════════════════════%s\n' "$C_BOLD$C_GREEN" "$C_RESET"
  printf '%s  Deployment complete%s\n' "$C_BOLD$C_GREEN" "$C_RESET"
  printf '%s═══════════════════════════════════════════════════════════════%s\n\n' "$C_BOLD$C_GREEN" "$C_RESET"

  # Final state from DB
  if docker ps --filter "name=bipros-postgres" --filter "status=running" -q | grep -q .; then
    printf '%sFinal state in DB:%s\n' "$C_BOLD" "$C_RESET"
    docker exec bipros-postgres psql -U "${POSTGRES_USER:-bipros}" -d "${POSTGRES_DB:-bipros}" -c "
SELECT
  (SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026') AS project,
  (SELECT COUNT(*) FROM project.daily_progress_reports) AS dprs,
  (SELECT COUNT(*) FROM activity.activities WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS activities,
  (SELECT COUNT(*) FROM project.wbs_nodes WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS wbs,
  (SELECT COUNT(*) FROM resource.resource_assignments) AS role_assigns,
  (SELECT COUNT(*) FROM project.boq_items WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS boq,
  (SELECT COUNT(*) FROM risk.risks WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS risks,
  (SELECT COUNT(*) FROM project.dpr_issues WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS dpr_issues,
  (SELECT COUNT(*) FROM project.daily_weather WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS weather;
" 2>/dev/null || true
  fi

  cat <<EOF

${C_BOLD}URLs:${C_RESET}
  Backend health:  http://localhost:${API_HOST_PORT}/actuator/health
  Swagger UI:      http://localhost:${API_HOST_PORT}/swagger-ui.html
  pgAdmin:         http://localhost:${PGADMIN_PORT:-5050}    (${PGADMIN_EMAIL:-admin@bipros.io} / ${PGADMIN_PASSWORD:-admin})
  MinIO console:   http://localhost:${MINIO_CONSOLE_PORT:-9001}    (${MINIO_ROOT_USER:-minio} / ${MINIO_ROOT_PASSWORD:-minio123})

${C_BOLD}Admin login:${C_RESET}
  Username: admin
  Password: admin123    ${C_YELLOW}(change immediately for prod)${C_RESET}

${C_BOLD}Frontend:${C_RESET} not in this stack — run \`pnpm dev\` in ../frontend/

${C_BOLD}Next steps:${C_RESET}
  - Switch to steady-state prod profile:
      ${C_DIM}BIPROS_PROFILES=prod docker compose up -d bipros-api${C_RESET}
  - Re-import data:
      ${C_DIM}./scripts/reimport.sh${C_RESET}
  - Tear down + redeploy:
      ${C_DIM}./deploy.sh --force${C_RESET}

Deploy log: $DEPLOY_LOG
EOF
}

# ─── Main ───────────────────────────────────────────────────────────────────
banner
preflight_docker
preflight_ports
force_wipe
build_image
start_infra_db
start_infra_misc
start_backend
bootstrap_masters
grab_admin_token
run_import_pre_dpr
run_import_dprs
run_import_post_dpr
print_summary
