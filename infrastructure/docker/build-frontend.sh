#!/usr/bin/env bash
# infrastructure/docker/build-frontend.sh
# Build the Next.js bundle on the host and package it into a Docker image.
#
# Usage:
#   ./build-frontend.sh [TAG]
#
# Arguments:
#   TAG   Optional. Custom image tag (e.g. "v1.2.3" or "hotfix-ui").
#         Defaults to: main-DD-mon-HHam/pm  (e.g. "main-07-may-9am")
#
# Examples:
#   ./build-frontend.sh                   # auto-tag from current datetime
#   ./build-frontend.sh v2.0.0            # explicit tag
#   ./build-frontend.sh staging-latest    # arbitrary label
#
# Note on NEXT_PUBLIC_API_URL:
#   NEXT_PUBLIC_* vars are baked into the JS bundle at build time — Docker
#   runtime env cannot override them. Production builds must use an empty
#   string so axios uses relative URLs (proxied by nginx). Localhost values
#   are automatically cleared with a warning.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

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

# ── Registry / image config ────────────────────────────────────────────────
REGISTRY="supportbipros"
SOURCE_IMAGE="bipros-eppm/frontend:latest"
TARGET_REPO="${REGISTRY}/bipros-eppm-frontend"

# ── Dynamic default tag ────────────────────────────────────────────────────
generate_tag() {
  local day month hour ampm
  day=$(date +%d)
  month=$(date +%b | tr '[:upper:]' '[:lower:]')
  hour=$(date +%I)
  hour="${hour#0}"
  ampm=$(date +%p | tr '[:upper:]' '[:lower:]')
  echo "main-${day}-${month}-${hour}${ampm}"
}

if [[ $# -ge 1 && -n "$1" ]]; then
  TAG="$1"
  info "Using provided tag: ${BOLD}${TAG}${NC}"
else
  TAG="$(generate_tag)"
  info "No tag supplied — using auto-generated tag: ${BOLD}${TAG}${NC}"
fi

# ── Prerequisite checks ────────────────────────────────────────────────────
check_prerequisites() {
  command -v docker &>/dev/null || die "Docker is not installed or not in PATH."
  command -v node   &>/dev/null || die "Node.js is not installed or not in PATH."

  if docker compose version &>/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
  elif command -v docker-compose &>/dev/null; then
    COMPOSE_CMD="docker-compose"
  else
    die "Docker Compose is not installed."
  fi

  if command -v pnpm &>/dev/null; then
    PKG_CMD="pnpm"
  elif command -v npm &>/dev/null; then
    PKG_CMD="npm"
  else
    die "Neither pnpm nor npm is installed."
  fi

  info "Docker:  $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo 'unknown')"
  info "Compose: $(${COMPOSE_CMD} version --short 2>/dev/null || echo 'unknown')"
  info "Node:    $(node --version)"
  info "Pkg mgr: ${PKG_CMD} $(${PKG_CMD} --version)"
}

# ── Ensure .env exists ─────────────────────────────────────────────────────
setup_env() {
  local env_file="${SCRIPT_DIR}/.env"
  local env_example="${SCRIPT_DIR}/.env.example"

  if [[ ! -f "$env_file" ]]; then
    if [[ -f "$env_example" ]]; then
      warn ".env not found — copying from .env.example"
      cp "$env_example" "$env_file"
      warn "Review ${env_file} before building for production!"
    else
      die ".env not found at ${env_file} and no .env.example to fall back to."
    fi
  fi
}

# ── Pre-build: compile Next.js bundle on host ──────────────────────────────
do_prebuild() {
  echo ""
  info "Building Next.js frontend on host…"

  local api_url
  api_url="$(grep -E '^NEXT_PUBLIC_API_URL=' "${SCRIPT_DIR}/.env" | cut -d= -f2- | tr -d '"' || true)"

  if [[ "$api_url" == *"localhost"* || "$api_url" == *"127.0.0.1"* ]]; then
    warn "NEXT_PUBLIC_API_URL contains a localhost address ('${api_url}') — clearing it for production build."
    api_url=""
  fi

  (cd "${REPO_ROOT}/frontend" && \
    NEXT_PUBLIC_API_URL="${api_url:-}" \
    NEXT_TELEMETRY_DISABLED=1 \
    ${PKG_CMD} install --frozen-lockfile && \
    NEXT_PUBLIC_API_URL="${api_url:-}" \
    NEXT_TELEMETRY_DISABLED=1 \
    ${PKG_CMD} run build) || die "Frontend build failed."

  success "Frontend build ready."
}

# ── Docker build ───────────────────────────────────────────────────────────
do_build() {
  echo ""
  info "Packaging frontend Docker image (no cache)…"
  ${COMPOSE_CMD} \
    -f "${SCRIPT_DIR}/docker-compose.yml" \
    -f "${SCRIPT_DIR}/docker-compose.override.yml" \
    --env-file "${SCRIPT_DIR}/.env" \
    -p bipros-eppm \
    build --no-cache frontend
  success "Docker image built: ${SOURCE_IMAGE}"
}

# ── Tag & push ─────────────────────────────────────────────────────────────
do_tag_and_push() {
  local full_target="${TARGET_REPO}:${TAG}"
  echo ""
  info "Tagging: ${SOURCE_IMAGE}  →  ${full_target}"
  docker tag "${SOURCE_IMAGE}" "${full_target}"
  docker push "${full_target}"
  success "Pushed ${full_target}"
}

# ── Summary ────────────────────────────────────────────────────────────────
print_summary() {
  echo ""
  echo -e "${GREEN}${BOLD}Frontend image pushed successfully!${NC}"
  echo ""
  echo -e "${BOLD}Tag:${NC}   ${TAG}"
  echo -e "${BOLD}Image:${NC} ${TARGET_REPO}:${TAG}"
  echo ""
}

# ── Main ───────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo -e "${CYAN}${BOLD}========================================"
  echo -e " Bipros EPPM — Frontend Build & Push"
  echo -e "========================================${NC}"
  echo ""

  check_prerequisites
  setup_env
  do_prebuild
  do_build
  do_tag_and_push
  print_summary
}

main "$@"
