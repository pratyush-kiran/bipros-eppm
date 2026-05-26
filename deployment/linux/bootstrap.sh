#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Fresh Ubuntu / Debian EC2 prerequisites for the Bipros deployment.
#
# Run ONCE on a brand-new instance (or any time `which docker` / `python3` /
# `pip3` is missing). Safe to re-run — every step is idempotent.
#
# Usage:
#   sudo ./bootstrap.sh           # apt installs need sudo
#   ./bootstrap.sh --no-sudo      # if you're already root
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ─── Colors ─────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_CYAN=$'\033[36m'; C_BOLD=$'\033[1m'
else
  C_RESET= C_GREEN= C_YELLOW= C_RED= C_CYAN= C_BOLD=
fi
ok()   { printf '%s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
info() { printf '%s•%s %s\n' "$C_CYAN" "$C_RESET" "$*"; }
warn() { printf '%s⚠%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
err()  { printf '%s✗%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }

NEED_SUDO=1
for arg in "$@"; do
  [ "$arg" = "--no-sudo" ] && NEED_SUDO=0
done
SUDO=""
if [ $NEED_SUDO -eq 1 ] && [ "$(id -u)" -ne 0 ]; then
  if command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
  else
    err "Need root (or sudo) for apt installs. Re-run as root or install sudo."
    exit 1
  fi
fi

# ─── Detect distro ──────────────────────────────────────────────────────────
if [ -f /etc/os-release ]; then
  . /etc/os-release
  info "Distro: $PRETTY_NAME"
  case "$ID" in
    ubuntu|debian) ;;
    *) warn "Unrecognised distro ($ID). Script is tuned for Ubuntu/Debian — proceeding anyway." ;;
  esac
else
  warn "Could not detect distro (no /etc/os-release). Proceeding anyway."
fi

# ─── Detect arch ────────────────────────────────────────────────────────────
ARCH=$(uname -m)
info "Architecture: $ARCH"
if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
  warn "ARM/Graviton instance detected. clickhouse-alpine + docling-serve-cpu images"
  warn "are linux/amd64 ONLY — pulls will fail. Use an x86_64 EC2 (t3.large or m5.large)"
  warn "or accept that clickhouse/docling won't start (backend will still come up)."
  read -r -p "Continue anyway? [y/N] " ans
  [ "${ans:-N}" = "y" ] || [ "${ans:-N}" = "Y" ] || { info "Aborted."; exit 0; }
fi

# ─── apt-get update once ────────────────────────────────────────────────────
info "Refreshing apt index…"
DEBIAN_FRONTEND=noninteractive $SUDO apt-get update -qq

# ─── Docker engine + compose plugin ─────────────────────────────────────────
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  ok "docker + compose plugin already installed ($(docker --version | head -1))"
else
  info "Installing docker.io + docker-compose-v2…"
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq \
    docker.io docker-compose-v2 || {
      err "apt-get install failed. If you need Docker CE (newer Ubuntu), follow:"
      err "  https://docs.docker.com/engine/install/ubuntu/"
      exit 1
    }
  ok "docker installed: $(docker --version)"
fi

# Add invoking user to docker group (only if SUDO_USER is set — i.e., ran via sudo)
TARGET_USER="${SUDO_USER:-$(whoami)}"
if [ "$TARGET_USER" != "root" ]; then
  if id -nG "$TARGET_USER" | grep -qw docker; then
    ok "$TARGET_USER already in docker group"
  else
    info "Adding $TARGET_USER to docker group…"
    $SUDO usermod -aG docker "$TARGET_USER"
    warn "$TARGET_USER added to docker group. Log out + back in (or run 'newgrp docker') before deploying."
  fi
fi

# ─── Start + enable docker daemon ───────────────────────────────────────────
if systemctl is-active --quiet docker; then
  ok "docker daemon running"
else
  info "Starting docker daemon…"
  $SUDO systemctl enable --now docker || {
    err "Could not start docker via systemctl. Try: sudo service docker start"
    exit 1
  }
  ok "docker daemon started + enabled at boot"
fi

# ─── Python 3 + pip + openpyxl ──────────────────────────────────────────────
if command -v python3 >/dev/null 2>&1; then
  ok "python3 installed: $(python3 --version)"
else
  info "Installing python3 + python3-pip…"
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq python3 python3-pip
  ok "python3 installed"
fi

if ! python3 -m pip --version >/dev/null 2>&1; then
  info "Installing python3-pip…"
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq python3-pip
fi

for mod_spec in "openpyxl:python3-openpyxl" "dateutil:python3-dateutil"; do
  pymod="${mod_spec%%:*}"
  aptpkg="${mod_spec##*:}"
  if python3 -c "import $pymod" 2>/dev/null; then
    ok "$pymod already installed"
  else
    info "Installing $aptpkg via apt (preferred) then pip (fallback)…"
    if DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq "$aptpkg" 2>/dev/null; then
      ok "$aptpkg installed via apt"
    else
      # Fallback: pip install --break-system-packages (Ubuntu 22.04+ PEP 668)
      python3 -m pip install --user --break-system-packages "$pymod" 2>/dev/null \
        || python3 -m pip install --user "$pymod"
      ok "$pymod installed via pip"
    fi
  fi
done

# ─── Misc tools ─────────────────────────────────────────────────────────────
for pkg in curl jq lsof tmux git; do
  if command -v "$pkg" >/dev/null 2>&1; then
    ok "$pkg present"
  else
    info "Installing $pkg…"
    DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq "$pkg" || warn "Could not install $pkg (non-fatal)"
  fi
done

# ─── Sanity check ───────────────────────────────────────────────────────────
echo
printf '%s%sBootstrap complete.%s\n' "$C_BOLD" "$C_GREEN" "$C_RESET"
printf '  Docker:   %s\n' "$(docker --version 2>/dev/null || echo MISSING)"
printf '  Compose:  %s\n' "$(docker compose version --short 2>/dev/null || echo MISSING)"
printf '  Python:   %s\n' "$(python3 --version 2>/dev/null || echo MISSING)"
printf '  openpyxl: %s\n' "$(python3 -c 'import openpyxl; print(openpyxl.__version__)' 2>/dev/null || echo MISSING)"
echo
if [ "$TARGET_USER" != "root" ] && ! id -nG "$TARGET_USER" 2>/dev/null | grep -qw docker; then
  warn "Run 'newgrp docker' or log out/in before deploying."
fi
printf 'Next:  %scd ../  &&  ./deploy.sh%s\n' "$C_BOLD" "$C_RESET"
