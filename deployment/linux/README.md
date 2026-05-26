# Bipros EPPM — Linux / EC2 deployment

This folder contains the **Linux-hardened** variant of the deployment scripts.
Use these if you're on Ubuntu / Debian / Amazon Linux (especially EC2).

The cross-platform `../deploy.sh` works on Linux too, but this variant:
- Surfaces image-pull errors (the cross-platform script filters output and can hide them)
- Verifies every `docker compose` exit code (no silent failures)
- Pre-pulls images so any pull problem stops the script in stage 5, not deep in stage 8
- Detects ARM/Graviton and warns
- Has `--no-docling` flag to skip the heaviest container
- Auto-installs `openpyxl` with `--break-system-packages` (Ubuntu 22.04+ PEP 668 friendly)
- Includes `bootstrap.sh` for a fresh EC2 (installs Docker, Python, pip, lsof, tmux)

---

## Quick start on a fresh EC2 Ubuntu

```bash
# 1. SSH into the instance
ssh ubuntu@your-ec2-host

# 2. Clone the repo
git clone https://github.com/Hemendra1990/bipros-eppm.git
cd bipros-eppm
git checkout khasab-demo-ready-2026-05-24

# 3. Bootstrap prerequisites (Docker, Python, pip, openpyxl, lsof, tmux)
cd deployment/linux
chmod +x bootstrap.sh deploy.sh
sudo ./bootstrap.sh

# 4. Log out + back in so the docker group takes effect
exit
ssh ubuntu@your-ec2-host
cd bipros-eppm/deployment/linux

# 5. Deploy (≈ 15-25 min on a t3.large)
./deploy.sh

# OR, to avoid SSH disconnect issues:
tmux new -s deploy
./deploy.sh
# Ctrl-b d to detach; reattach with: tmux attach -t deploy
```

---

## Instance sizing

| Instance | RAM | What happens |
|---|---|---|
| `t3.medium` (4 GB) | ✗ | OOM during Docling startup |
| `t3.large` (8 GB) | ⚠ | Works if you pass `--no-docling`. With docling it's tight |
| `t3.xlarge` (16 GB) | ✓ | Comfortable headroom |
| `m5.large` (8 GB) | ⚠ | Same as t3.large |
| `c6g.large` (Graviton) | ✗ | ARM — clickhouse-alpine + docling have no ARM images |

**Recommended: `t3.xlarge` or `m5.xlarge`** with 30 GB gp3 storage.

---

## Flags

```bash
./deploy.sh                    # Full deploy
./deploy.sh --force            # Wipe volumes + redeploy from scratch
./deploy.sh --skip-build       # Use cached image (saves ~10 min)
./deploy.sh --skip-import      # Bring up stack, don't import Khasab
./deploy.sh --no-docling       # Skip docling container (saves ~12 GB RAM, no PDF AI)
```

---

## What changed vs the cross-platform deploy.sh

| Issue you might've hit | Cross-platform script | Linux variant |
|---|---|---|
| `docker compose up -d` exited non-zero | Output filtered through `grep` → exit code hidden by `\|\| true` → script continues | `run_live` wrapper checks `$PIPESTATUS[0]` → fatals on non-zero |
| `docker compose pull` rate-limited | Implicit pull during `up -d` → quiet failure | Explicit `pull_images` stage 5 surfaces the error immediately |
| ARM/Graviton instance | No detection → confusing image-not-found later | `preflight_docker` checks `uname -m` and warns |
| ClickHouse slow on a small box | Hard-coded 90s timeout | 240s timeout; continues with warning if still slow |
| Docling 12 GB RAM kills small instances | Always pulled + started | `--no-docling` skips both pull and start |
| `lsof` not pre-installed on EC2 | `lsof || ss` fallback | `ss` only (always present on systemd hosts) |
| Ubuntu 22.04 PEP 668 blocks `pip install` | Plain pip install fails | `pip install --break-system-packages` first |
| pgAdmin restart loop on `.local` email | n/a (fixed in main script too) | `@bipros.io` default |

---

## Bootstrap script details

`sudo ./bootstrap.sh` installs / verifies on Ubuntu/Debian:
- `docker.io` + `docker-compose-v2` (apt — fine for demos; for prod use Docker CE per Docker's official docs)
- Adds you to the `docker` group
- Starts and enables the docker daemon at boot
- `python3` + `python3-pip` + `python3-openpyxl`
- `curl jq lsof tmux git`
- Warns on ARM architecture

Re-running is safe — every step is idempotent.

---

## Troubleshooting

### `containers didn't start, only postgres and redis started`
This is what you hit. Usually one of:

1. **Image pull failure** for clickhouse / minio / docling. The new `pull_images`
   stage 5 in this folder's `deploy.sh` surfaces the error explicitly. Most likely
   cause: dockerhub rate limit (anonymous pulls = 100/6h per IP). Fix: `docker login`
   with any free dockerhub account.

2. **ARM instance**. `uname -m` returns `aarch64` → clickhouse-alpine and
   docling-serve-cpu fail to pull (linux/amd64 only). Use x86_64 EC2 (`t3.*`, `m5.*`).

3. **Disk full mid-pull**. `df -h /var/lib/docker` to check. Need ~10 GB free for
   all images.

### `bipros-api container exits during boot`
Look at the actual error:
```bash
docker logs bipros-api --tail 50
```
Most common: ClickHouse isn't up yet → backend can't open the JDBC pool. The
deploy script in this folder waits up to 240s for ClickHouse before starting
the backend.

### `BIPROS_AI_KEK is not set`
Compose has a default but if you wiped `configs/.env` and it didn't regenerate,
set it manually:
```bash
echo 'BIPROS_AI_KEK=Vd/RdHKwlLA1vFuDVUr/ou0CMHAsha99Cfi8UXzXUlA=' >> ../configs/.env
```

### `pip install openpyxl` errored with `externally-managed-environment`
Ubuntu 22.04+. The script auto-handles this with `--break-system-packages`,
but if you hit it standalone:
```bash
sudo apt-get install -y python3-openpyxl
# OR:
pip install --user --break-system-packages openpyxl
```

### `permission denied` running `./deploy.sh`
```bash
chmod +x bootstrap.sh deploy.sh
```
(git clone usually preserves the bit, but `chmod +x` if it didn't.)

### EC2 security group blocks the URLs
Open inbound TCP `:8080` (backend) at minimum. For full demo access also
`:3000` (if you'll run pnpm dev on the box), `:5050` (pgAdmin), `:9001`
(MinIO console).

### Log files
`logs/deploy-latest.log` is a symlink to the most-recent run.
`logs/dpr-import.log` has the long DPR import detail.

---

## After deploy

The deploy script auto-detects EC2 public IP from instance metadata and prints
the URLs with the real IP, e.g.:
```
URLs:
  Backend health:  http://13.234.56.78:8080/actuator/health
  Swagger UI:      http://13.234.56.78:8080/swagger-ui.html
  pgAdmin:         http://13.234.56.78:5050    (admin@bipros.io / admin)

Admin login:  admin / admin123  (change for prod)
```

To run the frontend on the same instance:
```bash
cd ../../frontend
sudo apt-get install -y nodejs npm
sudo npm install -g pnpm
pnpm install && pnpm dev    # http://localhost:3000
```

To browse from your laptop, either:
- Open `:3000` in the EC2 security group, or
- Use SSH tunnel: `ssh -L 3000:localhost:3000 -L 8080:localhost:8080 ubuntu@ec2-host`
