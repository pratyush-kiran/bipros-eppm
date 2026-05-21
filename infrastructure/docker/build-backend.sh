#!/usr/bin/env bash
# infrastructure/docker/build-backend.sh
# Build the backend Docker image (Maven compile happens inside Docker)
# and push it to Docker Hub. No host Maven installation required.
#
# Usage:
#   ./build-backend.sh [TAG]
#
# Arguments:
#   TAG   Optional. Custom image tag (e.g. "v1.2.3" or "hotfix-auth").
#         Defaults to: main-DD-mon-HHam/pm  (e.g. "main-07-may-9am")
#
# Examples:
#   ./build-backend.sh                   # auto-tag from current datetime
#   ./build-backend.sh v2.0.0            # explicit tag
#   ./build-backend.sh staging-latest    # arbitrary label

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DOCKERFILE="${SCRIPT_DIR}/services/backend/Dockerfile.build"

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
LOCAL_IMAGE="bipros-eppm/backend:latest"
TARGET_REPO="${REGISTRY}/bipros-eppm-backend"

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

# ── Docker build (multi-stage: Maven compile + JRE runtime) ───────────────
do_build() {
  echo ""
  info "Building backend image (Maven compiles inside Docker)…"
  info "Build context: ${REPO_ROOT}"
  info "Dockerfile:   ${DOCKERFILE}"
  echo ""

  docker build \
    --no-cache \
    -f "${DOCKERFILE}" \
    -t "${LOCAL_IMAGE}" \
    "${REPO_ROOT}"

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
  echo -e "${GREEN}${BOLD}Backend image pushed successfully!${NC}"
  echo ""
  echo -e "${BOLD}Tag:${NC}   ${TAG}"
  echo -e "${BOLD}Image:${NC} ${TARGET_REPO}:${TAG}"
  echo ""
}

# ── Main ───────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo -e "${CYAN}${BOLD}========================================"
  echo -e " Bipros EPPM — Backend Build & Push"
  echo -e "========================================${NC}"
  echo ""

  check_prerequisites
  do_build
  do_tag_and_push
  print_summary
}

main "$@"
