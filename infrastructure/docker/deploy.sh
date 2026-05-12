#!/usr/bin/env bash
# infrastructure/docker/deploy.sh
# Deployment helper for the Bipros EPPM Docker stack.
#
# Usage:
#   ./deploy.sh [OPTIONS]
#
# Options:
#   --build        Force rebuild of all images before starting
#   --infra-only   Start infrastructure services only (no app containers)
#   --clean        Stop and remove containers, networks, and volumes, then exit
#   --logs         Tail logs of the running stack
#   --status       Show status of all containers
#   -h, --help     Show this help message

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Colour helpers ─────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
die()     { error "$*"; exit 1; }

# ── Parse arguments ────────────────────────────────────────────────────────
BUILD=false
INFRA_ONLY=false
CLEAN=false
LOGS=false
STATUS=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)       BUILD=true ;;
    --infra-only)  INFRA_ONLY=true ;;
    --clean)       CLEAN=true ;;
    --logs)        LOGS=true ;;
    --status)      STATUS=true ;;
    -h|--help)
      sed -n '/^# Usage:/,/^[^#]/{ /^#/{ s/^# \?//; p }; /^[^#]/q }' "$0"
      exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
  shift
done

# ── Prerequisite checks ────────────────────────────────────────────────────
check_prerequisites() {
  if ! command -v docker &>/dev/null; then
    die "Docker is not installed or not in PATH."
  fi

  if docker compose version &>/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
  elif command -v docker-compose &>/dev/null; then
    COMPOSE_CMD="docker-compose"
  else
    die "Docker Compose is not installed."
  fi

  info "Docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo 'unknown')"
}

# ── Environment file setup ─────────────────────────────────────────────────
setup_env() {
  local env_file="$SCRIPT_DIR/.env"
  local env_example="$SCRIPT_DIR/.env.example"

  if [[ ! -f "$env_file" ]]; then
    if [[ -f "$env_example" ]]; then
      warn ".env not found — copying from .env.example"
      cp "$env_example" "$env_file"
      warn "Review and update $env_file before running in production!"
    else
      die ".env.example not found at $env_example"
    fi
  fi

  if grep -q 'CHANGE_ME' "$env_file" 2>/dev/null; then
    warn "JWT_SECRET in .env still uses the placeholder value."
    warn "Update it before exposing the service to a network."
  fi
}

# ── Compose file selection ─────────────────────────────────────────────────
get_compose_args() {
  local args=()

  if [[ "$INFRA_ONLY" == "true" ]]; then
    args+=(-f "$SCRIPT_DIR/docker-compose.infra.yml")
    info "Mode: infrastructure only"
  else
    args+=(-f "$SCRIPT_DIR/docker-compose.yml")
    if [[ -f "$SCRIPT_DIR/docker-compose.override.yml" ]]; then
      args+=(-f "$SCRIPT_DIR/docker-compose.override.yml")
      info "Applying resource overrides from docker-compose.override.yml"
    fi
    info "Mode: full stack"
  fi

  args+=(--env-file "$SCRIPT_DIR/.env")
  args+=(-p bipros-eppm)

  echo "${args[@]}"
}

# ── Actions ────────────────────────────────────────────────────────────────
do_clean() {
  info "Stopping and removing all containers, networks, and volumes..."
  local compose_args
  # shellcheck disable=SC2207
  compose_args=($(get_compose_args))
  $COMPOSE_CMD "${compose_args[@]}" down --volumes --remove-orphans
  success "Clean complete."
}

do_status() {
  local compose_args
  # shellcheck disable=SC2207
  compose_args=($(get_compose_args))
  $COMPOSE_CMD "${compose_args[@]}" ps
}

do_logs() {
  local compose_args
  # shellcheck disable=SC2207
  compose_args=($(get_compose_args))
  $COMPOSE_CMD "${compose_args[@]}" logs -f --tail=100
}

do_up() {
  local compose_args
  # shellcheck disable=SC2207
  compose_args=($(get_compose_args))

  local up_args=(up -d --remove-orphans)
  if [[ "$BUILD" == "true" ]]; then
    up_args+=(--build)
    info "Building images before starting..."
  fi

  info "Starting services..."
  $COMPOSE_CMD "${compose_args[@]}" "${up_args[@]}"

  echo ""
  success "Stack is up. Service summary:"
  $COMPOSE_CMD "${compose_args[@]}" ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"

  echo ""
  info "Access points:"
  # shellcheck disable=SC1090
  source <(grep -v '^#' "$SCRIPT_DIR/.env" | grep -v '^\s*$' | grep -v '^$')
  local nginx_port="${NGINX_PORT:-65000}"
  local pgadmin_port="${PGADMIN_PORT:-5050}"
  local minio_console="${MINIO_CONSOLE_PORT:-9001}"
  echo -e "  Application:    ${GREEN}http://localhost:${nginx_port}${NC}"
  echo -e "  API docs:       ${GREEN}http://localhost:${nginx_port}/swagger-ui.html${NC}"
  echo -e "  pgAdmin:        ${GREEN}http://localhost:${pgadmin_port}${NC}"
  echo -e "  MinIO Console:  ${GREEN}http://localhost:${minio_console}${NC}"
}

# ── Main ───────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo -e "${CYAN}==============================="
  echo -e " Bipros EPPM — Docker Deploy"
  echo -e "===============================${NC}"
  echo ""

  check_prerequisites
  setup_env

  if [[ "$CLEAN" == "true" ]]; then do_clean; exit 0; fi
  if [[ "$STATUS" == "true" ]]; then do_status; exit 0; fi
  if [[ "$LOGS" == "true" ]]; then do_logs; exit 0; fi

  do_up
}

main "$@"
