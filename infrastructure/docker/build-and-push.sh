#!/usr/bin/env bash
# infrastructure/docker/build-and-push.sh
# Build all Docker images and push them to Docker Hub.
#
# Usage:
#   ./build-and-push.sh [TAG]
#
# Arguments:
#   TAG   Optional. Custom image tag (e.g. "v1.2.3" or "hotfix-auth").
#         Defaults to: main-DD-mon-HHam/pm  (e.g. "main-07-may-9am")
#
# Examples:
#   ./build-and-push.sh                   # auto-tag from current datetime
#   ./build-and-push.sh v2.0.0            # explicit tag
#   ./build-and-push.sh staging-latest    # arbitrary label

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
IMAGES=(
  "bipros-eppm/backend:latest  ${REGISTRY}/bipros-eppm-backend"
  "bipros-eppm/frontend:latest ${REGISTRY}/bipros-eppm-frontend"
  "bipros-eppm/docs:latest     ${REGISTRY}/bipros-eppm-docs"
)

# ── Dynamic default tag: main-DD-mon-HHam/pm  ─────────────────────────────
generate_tag() {
  local day month hour ampm
  day=$(date +%d)
  month=$(date +%b | tr '[:upper:]' '[:lower:]')   # "may", "jun", …
  hour=$(date +%I)                                  # 01-12
  hour="${hour#0}"                                  # strip leading zero → 1-12
  ampm=$(date +%p | tr '[:upper:]' '[:lower:]')    # "am" or "pm"
  echo "main-${day}-${month}-${hour}${ampm}"
}

# ── Resolve tag (arg or auto) ──────────────────────────────────────────────
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
  command -v mvn    &>/dev/null || die "Maven (mvn) is not installed or not in PATH."
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
  info "Maven:   $(mvn -q --version 2>/dev/null | head -1 || echo 'unknown')"
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

# ── Repo root (two levels up from infrastructure/docker/) ─────────────────
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ── Pre-build: compile artifacts on the host where internet works ──────────
do_prebuild() {
  echo ""
  info "Pre-building artifacts on host…"

  # Backend — Maven
  info "  [1/3] Building Spring Boot JAR…"
  (cd "${REPO_ROOT}" && mvn -B -pl backend/bipros-api -am \
    -Dmaven.test.skip=true \
    --no-transfer-progress \
    package) || die "Maven build failed."
  success "  Backend JAR ready."

  # Frontend — Next.js standalone
  info "  [2/3] Building Next.js frontend…"
  local api_url
  api_url="$(grep -E '^NEXT_PUBLIC_API_URL=' "${SCRIPT_DIR}/.env" | cut -d= -f2- | tr -d '"' || true)"
  (cd "${REPO_ROOT}/frontend" && \
    NEXT_PUBLIC_API_URL="${api_url:-}" \
    NEXT_TELEMETRY_DISABLED=1 \
    ${PKG_CMD} install --frozen-lockfile && \
    NEXT_PUBLIC_API_URL="${api_url:-}" \
    NEXT_TELEMETRY_DISABLED=1 \
    ${PKG_CMD} run build) || die "Frontend build failed."
  success "  Frontend build ready."

  # Docs — Docusaurus
  info "  [3/3] Building Docusaurus docs…"
  (cd "${REPO_ROOT}/user-guide" && \
    npm ci && \
    npm run build) || die "Docs build failed."
  success "  Docs build ready."

  echo ""
  success "All artifacts built. Proceeding to Docker packaging…"
  echo ""
}

# ── Build ──────────────────────────────────────────────────────────────────
do_build() {
  info "Packaging Docker images (no cache)…"
  ${COMPOSE_CMD} \
    -f "${SCRIPT_DIR}/docker-compose.yml" \
    -f "${SCRIPT_DIR}/docker-compose.override.yml" \
    --env-file "${SCRIPT_DIR}/.env" \
    -p bipros-eppm \
    build --no-cache
  success "Build complete."
}

# ── Tag & push ─────────────────────────────────────────────────────────────
do_tag_and_push() {
  echo ""
  info "Tagging and pushing with tag: ${BOLD}${TAG}${NC}"
  echo ""

  for entry in "${IMAGES[@]}"; do
    # entry format: "source:tag  target-repo"
    local source target
    source=$(echo "$entry" | awk '{print $1}')
    target=$(echo "$entry" | awk '{print $2}')

    local full_target="${target}:${TAG}"

    info "  ${source}  →  ${full_target}"
    docker tag "${source}" "${full_target}"
    docker push "${full_target}"
    success "  Pushed ${full_target}"
    echo ""
  done
}

# ── Summary ────────────────────────────────────────────────────────────────
print_summary() {
  echo ""
  echo -e "${GREEN}${BOLD}All images pushed successfully!${NC}"
  echo ""
  echo -e "${BOLD}Tag:${NC} ${TAG}"
  echo ""
  echo -e "${BOLD}Images:${NC}"
  for entry in "${IMAGES[@]}"; do
    local target
    target=$(echo "$entry" | awk '{print $2}')
    echo -e "  ${GREEN}✓${NC}  ${target}:${TAG}"
  done
  echo ""
}

# ── Main ───────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo -e "${CYAN}${BOLD}========================================"
  echo -e " Bipros EPPM — Build & Push"
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
