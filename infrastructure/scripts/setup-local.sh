#!/usr/bin/env bash
# infrastructure/scripts/setup-local.sh
# One-time local development environment setup for Bipros EPPM.
#
# Run this once after cloning the repo. It will:
#   1. Validate system requirements
#   2. Copy .env.example files to .env (without overwriting existing files)
#   3. Start the infrastructure stack (postgres, redis, minio, pgadmin)
#   4. Create the MinIO bucket needed for satellite tile storage
#   5. Print a summary of local access URLs
#
# Usage:
#   cd infrastructure
#   ./scripts/setup-local.sh [--full-stack]
#
# Options:
#   --full-stack   Also build and start backend + frontend containers
#                  (omit to run app services from your IDE)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DOCKER_DIR="$INFRA_DIR/docker"

# ── Colour helpers ─────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
die()     { error "$*"; exit 1; }
step()    { echo -e "\n${BOLD}── $* ──${NC}"; }

# ── Parse arguments ────────────────────────────────────────────────────────
FULL_STACK=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --full-stack) FULL_STACK=true ;;
    -h|--help)
      sed -n '/^# Usage:/,/^[^#]/{ /^#/{ s/^# \?//; p }; /^[^#]/q }' "$0"
      exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
  shift
done

# ── Prerequisite checks ────────────────────────────────────────────────────
check_prerequisites() {
  step "Checking prerequisites"

  local missing=()

  command -v docker &>/dev/null || missing+=("docker")
  command -v git    &>/dev/null || missing+=("git")

  if docker compose version &>/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
  elif command -v docker-compose &>/dev/null; then
    COMPOSE_CMD="docker-compose"
  else
    missing+=("docker-compose")
  fi

  if [[ ${#missing[@]} -gt 0 ]]; then
    die "Missing required tools: ${missing[*]}"
  fi

  success "All prerequisites found."
  info "Docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo 'unknown')"
}

# ── Environment files ──────────────────────────────────────────────────────
setup_env_files() {
  step "Setting up environment files"

  local infra_env="$INFRA_DIR/.env"
  if [[ ! -f "$infra_env" ]] && [[ -f "$INFRA_DIR/.env.example" ]]; then
    cp "$INFRA_DIR/.env.example" "$infra_env"
    success "Created $infra_env from .env.example"
  else
    info "$infra_env already exists — not overwriting"
  fi

  local docker_env="$DOCKER_DIR/.env"
  if [[ ! -f "$docker_env" ]] && [[ -f "$DOCKER_DIR/.env.example" ]]; then
    cp "$DOCKER_DIR/.env.example" "$docker_env"
    success "Created $docker_env from .env.example"
  else
    info "$docker_env already exists — not overwriting"
  fi

  if grep -q 'CHANGE_ME' "$docker_env" 2>/dev/null; then
    warn "JWT_SECRET is still the placeholder. Update $docker_env before exposing"
    warn "this service to any network beyond localhost."
  fi
}

# ── Start infrastructure ───────────────────────────────────────────────────
start_infrastructure() {
  step "Starting infrastructure services"

  if [[ "$FULL_STACK" == "true" ]]; then
    info "Full stack mode — building and starting all services"
    $COMPOSE_CMD \
      -f "$DOCKER_DIR/docker-compose.yml" \
      -f "$DOCKER_DIR/docker-compose.override.yml" \
      --env-file "$DOCKER_DIR/.env" \
      -p bipros-eppm \
      up -d --build --remove-orphans
  else
    info "Infrastructure-only mode — start backend/frontend from your IDE"
    $COMPOSE_CMD \
      -f "$DOCKER_DIR/docker-compose.infra.yml" \
      --env-file "$DOCKER_DIR/.env" \
      -p bipros-eppm \
      up -d --remove-orphans
  fi

  success "Services started."
}

# ── Wait for services ──────────────────────────────────────────────────────
wait_for_postgres() {
  step "Waiting for PostgreSQL to be ready"
  local retries=30 n=0
  until docker exec bipros-postgres pg_isready -U postgres -d bipros &>/dev/null; do
    n=$((n + 1))
    [[ $n -ge $retries ]] && die "PostgreSQL did not become ready after ${retries} attempts."
    info "Waiting... ($n/$retries)"
    sleep 3
  done
  success "PostgreSQL is ready."
}

wait_for_minio() {
  local retries=20 n=0
  until curl -sf http://localhost:9000/minio/health/live &>/dev/null; do
    n=$((n + 1))
    [[ $n -ge $retries ]] && return 1
    info "Waiting for MinIO... ($n/$retries)"
    sleep 3
  done
  return 0
}

# ── Create MinIO bucket ────────────────────────────────────────────────────
setup_minio_bucket() {
  step "Creating MinIO bucket"

  # shellcheck disable=SC1090
  source <(grep -v '^#' "$DOCKER_DIR/.env" 2>/dev/null | grep -v '^\s*$' || true)
  local bucket="${MINIO_BUCKET:-sentinel-tiles}"
  local minio_user="${MINIO_ROOT_USER:-minio}"
  local minio_pass="${MINIO_ROOT_PASSWORD:-minio123}"

  if wait_for_minio; then
    docker run --rm \
      --network bipros-eppm_bipros-network \
      --entrypoint sh \
      minio/mc:latest -c "
        mc alias set local http://minio:9000 '${minio_user}' '${minio_pass}' &&
        mc mb --ignore-existing local/${bucket} &&
        echo 'Bucket ${bucket} ready.'
      " && success "MinIO bucket '${bucket}' ready." \
      || warn "Could not create MinIO bucket — create it manually at http://localhost:9001"
  else
    warn "MinIO not ready — create bucket '${bucket}' manually at http://localhost:9001"
  fi
}

# ── Summary ────────────────────────────────────────────────────────────────
print_summary() {
  # shellcheck disable=SC1090
  source <(grep -v '^#' "$DOCKER_DIR/.env" 2>/dev/null | grep -v '^\s*$' || true)
  local nginx_port="${NGINX_PORT:-65000}"
  local pgadmin_port="${PGADMIN_PORT:-5050}"
  local minio_console="${MINIO_CONSOLE_PORT:-9001}"
  local postgres_port="${POSTGRES_PORT:-5432}"
  local redis_port="${REDIS_PORT:-6379}"
  local minio_api_port="${MINIO_API_PORT:-9000}"

  echo ""
  echo -e "${BOLD}${GREEN}====================================="
  echo -e " Bipros EPPM — Local Setup Complete"
  echo -e "=====================================${NC}"
  echo ""

  if [[ "$FULL_STACK" == "true" ]]; then
    echo -e "  Application:    ${GREEN}http://localhost:${nginx_port}${NC}"
    echo -e "  API (Swagger):  ${GREEN}http://localhost:${nginx_port}/swagger-ui.html${NC}"
  else
    echo -e "  ${YELLOW}App services are NOT running.${NC}"
    echo -e "  Backend (IDE):   ${CYAN}http://localhost:8080${NC}"
    echo -e "  Frontend (IDE):  ${CYAN}http://localhost:3000${NC}"
    echo ""
    echo -e "  Backend env vars for your IDE run config:"
    echo "    SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:${postgres_port}/bipros"
    echo "    SPRING_DATASOURCE_USERNAME=bipros"
    echo "    SPRING_DATASOURCE_PASSWORD=bipros_dev"
    echo "    SPRING_DATA_REDIS_HOST=localhost"
    echo "    BIPROS_STORAGE_S3_ENDPOINT=http://localhost:${minio_api_port}"
    echo "    BIPROS_STORAGE_S3_ACCESS_KEY=minio"
    echo "    BIPROS_STORAGE_S3_SECRET_KEY=minio123"
  fi

  echo ""
  echo -e "  Infrastructure:"
  echo -e "    PostgreSQL:     localhost:${postgres_port}  (db=bipros, user=bipros, pw=bipros_dev)"
  echo -e "    Redis:          localhost:${redis_port}"
  echo -e "    MinIO API:      http://localhost:${minio_api_port}"
  echo -e "    MinIO Console:  ${GREEN}http://localhost:${minio_console}${NC}  (minio / minio123)"
  echo -e "    pgAdmin:        ${GREEN}http://localhost:${pgadmin_port}${NC}  (admin@bipros.local / admin)"
  echo ""
  echo -e "  To stop:  ${CYAN}cd infrastructure/docker && ./deploy.sh --clean${NC}"
  echo -e "  To logs:  ${CYAN}cd infrastructure/docker && ./deploy.sh --logs${NC}"
  echo ""
}

# ── Main ───────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo -e "${BOLD}${CYAN}======================================="
  echo -e " Bipros EPPM — Local Environment Setup"
  echo -e "=======================================${NC}"
  echo ""

  check_prerequisites
  setup_env_files
  start_infrastructure
  wait_for_postgres
  setup_minio_bucket
  print_summary
}

main "$@"
