#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Bipros EPPM — Linux/EC2-hardened deployment.
#
# Differences vs the cross-platform deployment/deploy.sh:
#   • Every docker compose command's exit code is checked — no silent failures
#   • Image-pull errors surfaced verbatim (the cross-platform script filtered
#     them through `grep` which can hide real pull failures)
#   • Detects ARM/Graviton and warns (clickhouse-alpine + docling have no ARM images)
#   • Auto-detects missing python deps and self-installs via pip --break-system-packages
#   • Stage 6 waits up to 240s for clickhouse healthy (vs 90s); skips docling/minio
#     wait so a slow-pulling docling image doesn't block the backend
#   • Tail commands so `docker compose pull` progress prints LIVE not buffered
#
# Usage:
#   ./deploy.sh                          # full deploy
#   ./deploy.sh --force                  # tear down volumes + redeploy
#   ./deploy.sh --skip-import            # bring up stack only
#   ./deploy.sh --skip-build             # use cached image (much faster)
#   ./deploy.sh --no-docling             # skip docling (saves ~12 GB RAM, no PDF AI)
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail   # NOTE: no -e — we check exit codes explicitly per command

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"   # the deployment/ dir (parent)
cd "$DEPLOY_ROOT"

# ─── Env file ───────────────────────────────────────────────────────────────
ENV_FILE="${ENV_FILE:-$DEPLOY_ROOT/configs/.env}"
if [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
elif [ -f "$DEPLOY_ROOT/configs/.env.example" ]; then
  cp "$DEPLOY_ROOT/configs/.env.example" "$ENV_FILE"
  set -a; . "$ENV_FILE"; set +a
fi

LOG_DIR="$DEPLOY_ROOT/logs"; mkdir -p "$LOG_DIR"
DEPLOY_LOG="$LOG_DIR/deploy-linux-$(date +%Y%m%d-%H%M%S).log"
ln -sf "$DEPLOY_LOG" "$LOG_DIR/deploy-latest.log"

API_HOST_PORT="${API_HOST_PORT:-8080}"
PG_HOST_PORT="${PG_HOST_PORT:-5433}"
PG_USER="${POSTGRES_USER:-bipros}"
PG_DB="${POSTGRES_DB:-bipros}"

REQUIRED_PORTS=("$API_HOST_PORT" "$PG_HOST_PORT" "${REDIS_HOST_PORT:-6379}" \
                "${CLICKHOUSE_HTTP_PORT:-8123}" "${MINIO_API_PORT:-9000}" \
                "${MINIO_CONSOLE_PORT:-9001}" "${PGADMIN_PORT:-5050}")

TOTAL_STEPS=12; CURRENT_STEP=0
FORCE=0; SKIP_IMPORT=0; SKIP_BUILD=0; NO_DOCLING=0
for arg in "$@"; do
  case "$arg" in
    --force)       FORCE=1 ;;
    --skip-import) SKIP_IMPORT=1 ;;
    --skip-build)  SKIP_BUILD=1 ;;
    --no-docling)  NO_DOCLING=1 ;;
    -h|--help)     sed -n '2,22p' "$0"; exit 0 ;;
    *)             echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ─── Colors ─────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  R=$'\033[0m'; B=$'\033[1m'; D=$'\033[2m'
  RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYN=$'\033[36m'
else
  R= B= D= RED= GRN= YEL= CYN=
fi

log()   { local m="$*"; printf '%s %s\n' "$(date '+%H:%M:%S')" "$m" >> "$DEPLOY_LOG"; }
info()  { printf '%s[INFO]%s %s\n'  "$CYN" "$R" "$*"; log "[INFO] $*"; }
ok()    { printf '%s[OK]%s   %s\n'  "$GRN" "$R" "$*"; log "[OK]   $*"; }
warn()  { printf '%s[WARN]%s %s\n'  "$YEL" "$R" "$*"; log "[WARN] $*"; }
err()   { printf '%s[ERR]%s  %s\n'  "$RED" "$R" "$*" >&2; log "[ERR]  $*"; }
fatal() { err "$@"; err "See full log: $DEPLOY_LOG"; exit 1; }

stage() {
  CURRENT_STEP=$((CURRENT_STEP + 1))
  local pct=$(( CURRENT_STEP * 100 / TOTAL_STEPS ))
  local len=24
  local filled=$(( CURRENT_STEP * len / TOTAL_STEPS ))
  local bar=""
  for ((i = 0; i < filled; i++)); do bar+="█"; done
  for ((i = filled; i < len; i++)); do bar+="░"; done
  echo
  echo "${D}═══════════════════════════════════════════════════════════════${R}"
  echo "${B}[${CURRENT_STEP}/${TOTAL_STEPS}]${R} ${B}${CYN}$*${R}  ${D}[${bar}]${R} ${pct}%"
  log "[STAGE ${CURRENT_STEP}/${TOTAL_STEPS}] $*"
}

# ─── Wrapper: run a command, log full output, check exit code ───────────────
run() {
  local desc="$1"; shift
  log "[CMD] $*"
  if ! "$@" >>"$DEPLOY_LOG" 2>&1; then
    err "$desc failed. Last 20 log lines:"
    tail -20 "$DEPLOY_LOG" | sed "s/^/  ${RED}|${R} /" >&2
    return 1
  fi
}

run_live() {
  # Same as run() but mirrors stdout/stderr to terminal — for slow operations
  local desc="$1"; shift
  log "[CMD] $*"
  "$@" 2>&1 | tee -a "$DEPLOY_LOG"
  local rc=${PIPESTATUS[0]}
  if [ $rc -ne 0 ]; then
    err "$desc failed (exit $rc)"
    return 1
  fi
}

banner() {
  echo "${B}${CYN}"
  echo "═══════════════════════════════════════════════════════════════"
  echo "  Bipros EPPM — Khasab Road Project 2026"
  echo "  Linux / EC2 hardened deployment"
  echo "═══════════════════════════════════════════════════════════════${R}"
  echo "  Arch:           $(uname -m)"
  echo "  Distro:         $( . /etc/os-release 2>/dev/null && echo "$PRETTY_NAME" || echo "unknown")"
  echo "  Deploy log:     $DEPLOY_LOG"
  echo "  Env file:       $ENV_FILE"
  echo "  Force redeploy: $([ $FORCE -eq 1 ] && echo YES || echo no)"
  echo "  Skip import:    $([ $SKIP_IMPORT -eq 1 ] && echo YES || echo no)"
  echo "  Docling:        $([ $NO_DOCLING -eq 1 ] && echo SKIPPED || echo enabled)"
  echo
}

# ─── Stage 1: Docker preflight ──────────────────────────────────────────────
preflight_docker() {
  stage "Docker preflight"
  # CRITICAL: the bipros-api image needs the Java sources at ../backend.
  # If you only copied the deployment/ folder, the build will fail with
  # "unable to prepare context: path ... /backend not found".
  if [ ! -d "$DEPLOY_ROOT/../backend" ]; then
    err "Missing $DEPLOY_ROOT/../backend (the Spring Boot source tree)."
    err "You appear to have copied only the deployment/ folder."
    err ""
    err "Fix — clone the full repo:"
    err "  cd $(dirname "$DEPLOY_ROOT")"
    err "  rm -rf $(basename "$(dirname "$DEPLOY_ROOT")")"
    err "  git clone https://github.com/Hemendra1990/bipros-eppm.git $(basename "$(dirname "$DEPLOY_ROOT")")"
    err "  cd $(basename "$(dirname "$DEPLOY_ROOT")")"
    err "  git checkout khasab-demo-ready-2026-05-24"
    err "  cd deployment/linux && ./deploy.sh"
    fatal "Cannot continue without backend/ sources"
  fi

  command -v docker >/dev/null 2>&1 || fatal "Docker missing. Run ./bootstrap.sh first."
  ok "docker:        $(docker --version)"

  if ! docker info >/dev/null 2>&1; then
    warn "Docker daemon not running — trying systemctl…"
    sudo systemctl start docker 2>>"$DEPLOY_LOG" \
      || fatal "Could not start docker. Try: sudo systemctl status docker"
    sleep 2
    docker info >/dev/null 2>&1 || fatal "Docker still not responding after start"
  fi
  ok "daemon:        running"

  docker compose version >/dev/null 2>&1 || fatal "docker compose plugin missing. Run ./bootstrap.sh first."
  ok "compose:       $(docker compose version --short 2>/dev/null || docker compose version | head -1)"

  # ARM warning
  local arch
  arch=$(uname -m)
  if [ "$arch" = "aarch64" ] || [ "$arch" = "arm64" ]; then
    warn "ARM/Graviton: clickhouse-alpine + docling-serve-cpu have no ARM image."
    warn "Use --no-docling to skip docling, and accept that clickhouse will fail to pull."
  fi
}

# ─── Stage 2: Ports + disk + python ─────────────────────────────────────────
preflight_env() {
  stage "Host preflight (ports + disk + python)"
  local conflicts=()
  for p in "${REQUIRED_PORTS[@]}"; do
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":${p}\$"; then
      # Identify what holds the port. Prefer container name (clearer than "some docker process").
      local owner
      owner=$(docker ps --filter "publish=$p" --format '{{.Names}}' 2>/dev/null | head -1)
      if [ -z "$owner" ]; then
        # Not a docker container — must be a host process
        owner=$(ss -ltnp 2>/dev/null | awk -v port=":${p}" '$4 ~ port {print $6}' | head -1)
        owner="${owner:-(unknown host process)}"
      fi
      if [[ "$owner" != bipros-* ]]; then
        conflicts+=("$p → $owner")
      fi
    fi
  done
  if [ ${#conflicts[@]} -gt 0 ]; then
    err "Ports already in use by other processes:"
    for c in "${conflicts[@]}"; do err "  $c"; done
    err ""
    err "Fix — pick one of:"
    err "  (a) Stop the conflicting container/process, then re-run."
    err "      e.g. for a container:  docker stop <name>"
    err "  (b) Remap our port in $DEPLOY_ROOT/configs/.env, then re-run. Example:"
    err "      echo 'REDIS_HOST_PORT=6380' >> $DEPLOY_ROOT/configs/.env"
    err "      echo 'API_HOST_PORT=8081'   >> $DEPLOY_ROOT/configs/.env"
    err "      (variables: API_HOST_PORT PG_HOST_PORT REDIS_HOST_PORT"
    err "       CLICKHOUSE_HTTP_PORT MINIO_API_PORT MINIO_CONSOLE_PORT"
    err "       PGADMIN_PORT — see configs/.env.example)"
    fatal "Port conflict"
  fi
  ok "ports:         all required free"

  local free_gb
  free_gb=$(df -BG "$DEPLOY_ROOT" 2>/dev/null | awk 'NR==2 {gsub("G",""); print $4}')
  if [ -n "${free_gb:-}" ]; then
    if [ "$free_gb" -lt 10 ]; then
      warn "Only ${free_gb} GB free on $DEPLOY_ROOT — recommend ≥ 20 GB"
    else
      ok "disk free:     ${free_gb} GB"
    fi
  fi

  python3 --version >/dev/null 2>&1 || fatal "python3 missing. Run ./bootstrap.sh first."
  local _missing=()
  python3 -c 'import openpyxl'               2>/dev/null || _missing+=(openpyxl)
  python3 -c 'import dateutil.relativedelta' 2>/dev/null || _missing+=(python-dateutil)
  if [ ${#_missing[@]} -gt 0 ]; then
    warn "python deps missing: ${_missing[*]} — installing…"
    local _apt=()
    for m in "${_missing[@]}"; do
      case "$m" in
        openpyxl)        _apt+=(python3-openpyxl) ;;
        python-dateutil) _apt+=(python3-dateutil) ;;
      esac
    done

    _install_ok=0
    if [ -n "${VIRTUAL_ENV:-}" ]; then
      # Inside a venv — plain pip install lands in venv/site-packages.
      # apt + --user both miss the venv's sys.path.
      info "  detected venv $VIRTUAL_ENV — using plain pip install"
      python3 -m pip install --quiet "${_missing[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    elif command -v apt-get >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
      info "  trying apt…"
      sudo apt-get install -y -qq "${_apt[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    fi
    if [ $_install_ok -ne 1 ]; then
      info "  trying pip --break-system-packages…"
      python3 -m pip install --user --break-system-packages --quiet "${_missing[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    fi
    if [ $_install_ok -ne 1 ]; then
      info "  trying pip --user…"
      python3 -m pip install --user --quiet "${_missing[@]}" >>"$DEPLOY_LOG" 2>&1 && _install_ok=1
    fi

    if ! python3 -c 'import openpyxl, dateutil.relativedelta' 2>/dev/null; then
      err "Could not install python deps. Manual fixes:"
      err "  If inside venv ((venv) in your prompt):  pip install ${_missing[*]}"
      err "  Outside venv on Ubuntu/Debian:           sudo apt-get install -y ${_apt[*]}"
      err "  Then re-run with --skip-build"
      fatal "python dependency setup failed (full output in $DEPLOY_LOG)"
    fi
  fi
  ok "python:        $(python3 --version) + openpyxl + dateutil$( [ -n "${VIRTUAL_ENV:-}" ] && echo " (venv)")"
}

# ─── Stage 3: Force tear-down ───────────────────────────────────────────────
force_wipe() {
  stage "Tear-down (--force)"
  if [ $FORCE -ne 1 ]; then info "Skipped (no --force)"; return; fi
  warn "Destroying containers + volumes…"
  docker compose down -v --remove-orphans 2>&1 | tee -a "$DEPLOY_LOG"
  ok "tear-down complete"

  # After a wipe the DB is empty — DataSeeder must fire to recreate admin.
  # If BIPROS_PROFILES has been trimmed to something that skips DataSeeder
  # (e.g. just "prod"), append init-prod for this run.
  if [[ ! ",${BIPROS_PROFILES:-prod,init-prod}," =~ ,(dev|seed|init-prod), ]]; then
    warn "BIPROS_PROFILES=${BIPROS_PROFILES} would skip DataSeeder on empty DB — appending init-prod for this run"
    export BIPROS_PROFILES="${BIPROS_PROFILES},init-prod"
  fi
}

# ─── Stage 4: Build backend ─────────────────────────────────────────────────
build_image() {
  stage "Build backend image"
  if [ $SKIP_BUILD -eq 1 ]; then info "Skipped (--skip-build)"; return; fi
  info "Building bipros-api:prod (multi-stage Maven → JRE; first build ~10 min)…"
  if ! DOCKER_BUILDKIT=1 run_live "image build" docker compose build bipros-api; then
    fatal "Build failed. Common causes: out of disk, network timeout to Maven Central"
  fi
  ok "image built"
}

# ─── Stage 5: Pre-pull all images (so we see pull errors NOW) ───────────────
pull_images() {
  stage "Pre-pulling images (so any pull error surfaces here)"
  local services=(postgresql redis clickhouse minio pgadmin)
  [ $NO_DOCLING -eq 0 ] && services+=(docling)
  for svc in "${services[@]}"; do
    info "Pulling $svc…"
    if ! run_live "pull $svc" docker compose pull --quiet "$svc"; then
      if [ "$svc" = "docling" ] || [ "$svc" = "clickhouse" ]; then
        warn "Could not pull $svc. Re-run with --no-docling to skip docling, or use an x86_64 instance."
        fatal "image pull failed"
      else
        fatal "image pull failed for $svc — see log"
      fi
    fi
  done
  ok "all images present locally"
}

# ─── Stage 6: Start Postgres + Redis ────────────────────────────────────────
start_db() {
  stage "Start Postgres + Redis"
  if ! run_live "compose up postgresql redis" docker compose up -d postgresql redis; then
    fatal "Could not start Postgres/Redis — see log"
  fi
  info "Waiting for Postgres health (up to 90s)…"
  local t=0
  until docker compose ps postgresql --format json 2>/dev/null | grep -q '"Health":"healthy"'; do
    sleep 3; t=$((t+3))
    if [ $t -ge 90 ]; then
      err "Postgres not healthy after 90s. Logs:"
      docker logs bipros-postgres --tail 25 2>&1 | sed "s/^/  | /" >&2
      fatal "Postgres healthcheck failed"
    fi
  done
  ok "Postgres healthy"
}

# ─── Stage 7: Start ClickHouse + MinIO + (Docling) + pgAdmin ────────────────
start_infra() {
  stage "Start ClickHouse + MinIO + pgAdmin$( [ $NO_DOCLING -eq 0 ] && echo ' + Docling')"
  local services=(clickhouse minio pgadmin)
  [ $NO_DOCLING -eq 0 ] && services+=(docling)
  if ! run_live "compose up infra" docker compose up -d "${services[@]}"; then
    fatal "Could not start infra services — see log. (Try --no-docling if docling is the problem.)"
  fi
  # ClickHouse is the only one we hard-wait for — Docling is slow + optional
  info "Waiting for ClickHouse health (up to 240s)…"
  local t=0
  until docker compose ps clickhouse --format json 2>/dev/null | grep -q '"Health":"healthy"'; do
    sleep 4; t=$((t+4))
    if [ $t -ge 240 ]; then
      warn "ClickHouse slow to become healthy — last 10 lines:"
      docker logs bipros-clickhouse --tail 10 2>&1 | sed "s/^/  | /"
      warn "Continuing anyway (analytics module may fail until ClickHouse is up)"
      break
    fi
  done
  ok "infra started"
}

# ─── Stage 8: Backend ───────────────────────────────────────────────────────
start_backend() {
  stage "Start bipros-api (profile: ${BIPROS_PROFILES:-prod,init-prod})"
  if ! run_live "compose up bipros-api" docker compose up -d bipros-api; then
    fatal "Could not start bipros-api — see log"
  fi
  info "Waiting for backend health (up to 240s)…"
  local t=0
  until curl -fsS "http://localhost:${API_HOST_PORT}/actuator/health" 2>/dev/null | grep -q UP; do
    sleep 5; t=$((t+5))
    if docker ps -a --filter "name=bipros-api" --format "{{.Status}}" | grep -qiE "exit|restart"; then
      err "bipros-api exited / restarting. Last 25 log lines:"
      docker logs bipros-api --tail 25 2>&1 | sed "s/^/  | /" >&2
      fatal "Backend boot failure"
    fi
    [ $t -ge 240 ] && {
      err "Backend not healthy after 240s. Last 25 log lines:"
      docker logs bipros-api --tail 25 2>&1 | sed "s/^/  | /" >&2
      fatal "Backend healthcheck timeout"
    }
    [ $((t % 30)) -eq 0 ] && info "  …still waiting (${t}s)"
  done
  ok "Backend healthy: http://localhost:${API_HOST_PORT}/actuator/health"
}

# ─── Stage 9: Bootstrap masters ─────────────────────────────────────────────
bootstrap_masters() {
  stage "Bootstrap resource catalogue (masters)"
  local n
  n=$(docker exec bipros-postgres psql -U "$PG_USER" -d "$PG_DB" -At \
        -c "SELECT COUNT(*) FROM resource.equipment_role_variants" 2>/dev/null || echo 0)
  if [ "${n:-0}" -gt 0 ]; then
    info "Catalogue already populated ($n equipment role variants) — skipping"
    return
  fi
  info "Loading data/sql/01-bipros-masters.sql…"
  docker exec bipros-postgres psql -U "$PG_USER" -d "$PG_DB" -q \
    -c "DELETE FROM resource.resource_roles; DELETE FROM resource.resource_types;" >>"$DEPLOY_LOG" 2>&1
  docker exec -i bipros-postgres psql -U "$PG_USER" -d "$PG_DB" \
    < "$DEPLOY_ROOT/data/sql/01-bipros-masters.sql" >>"$DEPLOY_LOG" 2>&1 || true
  n=$(docker exec bipros-postgres psql -U "$PG_USER" -d "$PG_DB" -At \
        -c "SELECT COUNT(*) FROM resource.equipment_role_variants")
  ok "catalogue loaded: $n equipment role variants"

  # Post-deploy schema patches (tsv column for HDS search, ui.* defaults).
  # Idempotent. Without this, AI/HDS search throws "column tsv does not exist"
  # on every query.
  if [ -f "$DEPLOY_ROOT/data/sql/02-post-deploy-patches.sql" ]; then
    info "Applying post-deploy schema patches…"
    docker exec -i bipros-postgres psql -U "$PG_USER" -d "$PG_DB" \
      < "$DEPLOY_ROOT/data/sql/02-post-deploy-patches.sql" >>"$DEPLOY_LOG" 2>&1
    ok "post-deploy patches applied"
  fi
}

# ─── Stage 10: Admin token ──────────────────────────────────────────────────
grab_token() {
  stage "Authenticate as admin"
  local WORK_DIR="${BIPROS_WORK_DIR:-/tmp/khasab}"; mkdir -p "$WORK_DIR"

  # Backend health goes UP as soon as Tomcat starts, but DataSeeder
  # (@Order(100) CommandLineRunner) runs ~15s later. Poll for the admin
  # row so we don't hit /v1/auth/login during that window.
  info "Waiting for DataSeeder to create the admin row (up to 90s)…"
  local t=0
  until [ "$(docker exec bipros-postgres psql -U "$PG_USER" -d "$PG_DB" -At \
              -c "SELECT 1 FROM public.users WHERE username='admin' LIMIT 1" 2>/dev/null)" = "1" ]; do
    sleep 3; t=$((t+3))
    if [ $t -ge 90 ]; then
      err "admin row never appeared in public.users after 90s."
      err "Check DataSeeder log:  docker logs bipros-api | grep -i DataSeeder"
      err "Current BIPROS_PROFILES = ${BIPROS_PROFILES:-prod,init-prod} (must include dev/seed/init-prod)"
      fatal "admin user not seeded"
    fi
  done

  local token
  token=$(curl -sS -X POST "http://localhost:${API_HOST_PORT}/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}' \
    | python3 -c 'import sys,json; r=json.load(sys.stdin); print(r["data"]["accessToken"])' 2>/dev/null) || true
  [ -n "$token" ] || fatal "admin row exists but login returned 401 — password mismatch or auth filter not ready"
  echo "$token" > "$WORK_DIR/admin-token.txt"
  ok "admin token saved → $WORK_DIR/admin-token.txt"
}

# ─── Stage 11: Khasab import (pre-DPR + DPR) ────────────────────────────────
run_imports_main() {
  stage "Khasab import — project + activities + role-assignments + 3,431 DPRs"
  if [ $SKIP_IMPORT -eq 1 ]; then info "Skipped (--skip-import)"; return; fi

  local exists
  exists=$(docker exec bipros-postgres psql -U "$PG_USER" -d "$PG_DB" -At \
    -c "SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026'" 2>/dev/null || echo 0)
  if [ "${exists:-0}" -gt 0 ] && [ $FORCE -ne 1 ]; then
    info "KHASAB-2026 already loaded — skipping. Use --force to redo."
    return
  fi

  export BIPROS_API_BASE="http://localhost:${API_HOST_PORT}"
  export BIPROS_PG_HOST=127.0.0.1
  export BIPROS_PG_PORT="${PG_HOST_PORT}"
  export BIPROS_PG_USER="$PG_USER"
  export BIPROS_PG_PASS="${POSTGRES_PASSWORD:-bipros_dev}"
  export BIPROS_PG_DB="$PG_DB"
  export BIPROS_WORK_DIR="${BIPROS_WORK_DIR:-/tmp/khasab}"
  export BIPROS_TOKEN_FILE="$BIPROS_WORK_DIR/admin-token.txt"
  export BIPROS_EXCEL_DIR="$DEPLOY_ROOT/data/khasab-excel"
  export BIPROS_PSQL="$DEPLOY_ROOT/scripts/psql-wrapper.sh"

  local imp="$DEPLOY_ROOT/imports"
  for step in parse_khasab.py parse_master_sheet.py analyze_resource_demand.py rebuild_demo.py fix_role_assignments.py; do
    info "  $step"
    python3 "$imp/$step" 2>&1 | tee -a "$DEPLOY_LOG" | tail -10
    rc=${PIPESTATUS[0]}
    if [ "$rc" -ne 0 ]; then
      err "$step exited $rc — see $DEPLOY_LOG"
      fatal "Import step failed"
    fi
  done

  # fix_role_assignments unlocks activities — re-lock so DPRs can post
  info "  Re-locking 33 activities for DPR ingest"
  local TOKEN PID locked=0
  TOKEN=$(cat "$BIPROS_WORK_DIR/admin-token.txt")
  PID=$(cat "$BIPROS_WORK_DIR/project-id.txt")
  for aid in $(python3 -c "import json; print(' '.join(json.load(open('$BIPROS_WORK_DIR/activity-ids.json')).values()))"); do
    sc=$(curl -sS -o /dev/null -w "%{http_code}" -X POST \
      "http://localhost:${API_HOST_PORT}/v1/projects/$PID/activities/$aid/lock" \
      -H "Authorization: Bearer $TOKEN")
    [ "$sc" = "200" ] && locked=$((locked+1))
  done
  ok "  locked $locked activities"

  # DPR import — long pole. Stream progress lines.
  info "  import_khasab_dprs.py all (~5-15 min on a t3.large)…"
  python3 "$imp/import_khasab_dprs.py" all 2>&1 \
    | tee -a "$LOG_DIR/dpr-import.log" \
    | grep -E --line-buffered "DONE|/[0-9]+ \(.*s\)|fail=[1-9]"
  local n
  n=$(docker exec bipros-postgres psql -U "$PG_USER" -d "$PG_DB" -At \
        -c "SELECT COUNT(*) FROM project.daily_progress_reports")
  ok "DPRs in DB: $n"
}

# ─── Stage 12: Post-DPR polish ──────────────────────────────────────────────
run_imports_post() {
  stage "Khasab import — cost + EVM + BOQ + dashboard + risks"
  if [ $SKIP_IMPORT -eq 1 ]; then info "Skipped"; return; fi
  local imp="$DEPLOY_ROOT/imports"
  for step in fix_demo_v2.py create_norms_only.py populate_dashboard.py add_weather_risks.py; do
    info "  $step"
    python3 "$imp/$step" 2>&1 | tee -a "$DEPLOY_LOG" | tail -5
  done
  info "  tune_productivity_norms.sql"
  docker exec -i bipros-postgres psql -U "$PG_USER" -d "$PG_DB" \
    < "$imp/tune_productivity_norms.sql" >>"$DEPLOY_LOG" 2>&1 || true
  info "  fix_dpr_activity_name_drift.sql"
  docker exec -i bipros-postgres psql -U "$PG_USER" -d "$PG_DB" \
    < "$DEPLOY_ROOT/data/sql/99-fix-dpr-drift.sql" >>"$DEPLOY_LOG" 2>&1 || true
  ok "polish complete"
}

print_summary() {
  echo
  echo "${B}${GRN}═══════════════════════════════════════════════════════════════${R}"
  echo "${B}${GRN}  Deployment complete${R}"
  echo "${B}${GRN}═══════════════════════════════════════════════════════════════${R}"
  echo
  docker exec bipros-postgres psql -U "$PG_USER" -d "$PG_DB" -c "
SELECT
  (SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026') AS project,
  (SELECT COUNT(*) FROM project.daily_progress_reports) AS dprs,
  (SELECT COUNT(*) FROM activity.activities WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS activities,
  (SELECT COUNT(*) FROM resource.resource_assignments) AS role_assigns,
  (SELECT COUNT(*) FROM risk.risks) AS risks,
  (SELECT COUNT(*) FROM project.dpr_issues) AS dpr_issues;
" 2>/dev/null

  local host_ip
  host_ip=$(curl -sS http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || echo localhost)

  echo
  echo "${B}URLs:${R}"
  echo "  Backend health:  http://${host_ip}:${API_HOST_PORT}/actuator/health"
  echo "  Swagger UI:      http://${host_ip}:${API_HOST_PORT}/swagger-ui.html"
  echo "  pgAdmin:         http://${host_ip}:${PGADMIN_PORT:-5050}    (${PGADMIN_EMAIL:-admin@bipros.io} / ${PGADMIN_PASSWORD:-admin})"
  echo
  echo "${B}Admin login:${R}  admin / admin123  ${YEL}(change for prod)${R}"
  echo "${B}Log:${R}          $DEPLOY_LOG"
}

# ─── Main ───────────────────────────────────────────────────────────────────
banner
preflight_docker
preflight_env
force_wipe
build_image
pull_images
start_db
start_infra
start_backend
bootstrap_masters
grab_token
run_imports_main
run_imports_post
print_summary
