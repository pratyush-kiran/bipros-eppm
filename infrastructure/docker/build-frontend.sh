#!/usr/bin/env bash
# infrastructure/docker/build-frontend.sh
# Build the frontend Docker image (pnpm install + Next.js build happens inside Docker)
# and push it to Docker Hub. No host Node/pnpm installation required.
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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DOCKERFILE="${SCRIPT_DIR}/services/frontend/Dockerfile.build"
FRONTEND_DIR="${REPO_ROOT}/frontend"

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
LOCAL_IMAGE="bipros-eppm/frontend:latest"
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

  info "Docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo 'unknown')"
}

# ── Resolve NEXT_PUBLIC_API_URL from .env ──────────────────────────────────
# NEXT_PUBLIC_* vars are baked into the JS bundle at build time.
# Production builds must use an empty string so axios uses relative URLs
# (proxied by nginx). Localhost values are automatically cleared.
resolve_api_url() {
  local env_file="${SCRIPT_DIR}/.env"
  local env_example="${SCRIPT_DIR}/.env.example"

  if [[ ! -f "$env_file" ]]; then
    if [[ -f "$env_example" ]]; then
      warn ".env not found — copying from .env.example"
      cp "$env_example" "$env_file"
    else
      warn ".env not found — NEXT_PUBLIC_API_URL will be empty (relative URLs)."
      echo ""
      return
    fi
  fi

  local api_url
  api_url="$(grep -E '^NEXT_PUBLIC_API_URL=' "${env_file}" | cut -d= -f2- | tr -d '"' || true)"

  if [[ "$api_url" == *"localhost"* || "$api_url" == *"127.0.0.1"* ]]; then
    warn "NEXT_PUBLIC_API_URL contains a localhost address ('${api_url}') — clearing it for production build."
    api_url=""
  fi

  echo "${api_url:-}"
}

# ── Docker build (multi-stage: pnpm install + Next.js build + Node runtime) ─
do_build() {
  local api_url="$1"

  echo ""
  info "Building frontend image (pnpm + Next.js compile inside Docker)…"
  info "Build context: ${FRONTEND_DIR}"
  info "Dockerfile:   ${DOCKERFILE}"
  [[ -n "$api_url" ]] && info "NEXT_PUBLIC_API_URL: ${api_url}" || info "NEXT_PUBLIC_API_URL: (empty — relative URLs via nginx)"
  echo ""

  docker build \
    --no-cache \
    --build-arg "NEXT_PUBLIC_API_URL=${api_url}" \
    -f "${DOCKERFILE}" \
    -t "${LOCAL_IMAGE}" \
    "${FRONTEND_DIR}"

  success "Docker image built: ${LOCAL_IMAGE}"
}

# ── Tag & push ─────────────────────────────────────────────────────────────
do_tag_and_push() {
  local full_target="${TARGET_REPO}:${TAG}"
  echo ""
  info "Tagging: ${LOCAL_IMAGE}  →  ${full_target}"
  docker tag "${LOCAL_IMAGE}" "${full_target}"
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
  local api_url
  api_url="$(resolve_api_url)"
  do_build "${api_url}"
  do_tag_and_push
  print_summary
}

main "$@"
