# Bipros EPPM — Deployment Bundle

Single-host, fully-Dockerised deployment of the **Bipros EPPM** backend
pre-loaded with the **Khasab Road Project 2026** demo dataset. One command
on a fresh Linux / macOS / EC2 machine (or Windows with Docker Desktop)
brings the whole stack up and imports the data.

```
deployment/
├── deploy.sh           # one-click deploy (Linux/macOS/EC2)
├── deploy.ps1          # one-click deploy (Windows)
├── docker-compose.yml  # the stack (Postgres, backend, Redis, ClickHouse, MinIO, Docling, pgAdmin)
├── docker/             # Dockerfiles for backend + Postgres image
├── data/
│   ├── khasab-excel/   # Source workbooks bundled with the deploy
│   └── sql/            # Init schemas + master catalogue + post-deploy fixes
├── imports/            # Khasab import python scripts (env-var-driven)
├── configs/            # .env.example + .env (your overrides)
├── scripts/            # restart, reset, reimport, logs, status, psql-wrapper
├── logs/               # deploy-YYYYMMDD-HHMMSS.log timestamped per run
└── README.md           # you are here
```

---

## 1. Quick start

```bash
# Linux / macOS / EC2
cd deployment
./deploy.sh

# Windows
cd deployment
.\deploy.ps1
```

That's it. About **15 minutes** end-to-end on a warm box (first build), or
~10 minutes on a re-run. When it finishes you'll see:

```
═══════════════════════════════════════════════════════════════
  Deployment complete
═══════════════════════════════════════════════════════════════

URLs:
  Backend health:  http://localhost:8080/actuator/health
  Swagger UI:      http://localhost:8080/swagger-ui.html
  pgAdmin:         http://localhost:5050    (admin@bipros.io / admin)
  MinIO console:   http://localhost:9001    (minio / minio123)

Admin login: admin / admin123     ← change immediately for prod
```

The frontend is **not** included in this stack — start it separately:
```bash
cd ../frontend
pnpm install && pnpm dev          # → http://localhost:3000
```

---

## 2. What the script does (12 stages)

```
[ 1/12] Docker preflight                  ← installed? running? compose?
[ 2/12] Checking host ports + disk space  ← 8080/5433/6379/8123/9000/9001/5001/5050
[ 3/12] Tear-down (--force)               ← only if --force passed
[ 4/12] Build backend image               ← multi-stage Maven + JRE 23
[ 5/12] Start Postgres + Redis            ← wait for healthy
[ 6/12] Start ClickHouse + MinIO + Docling + pgAdmin
[ 7/12] Start bipros-api                  ← profile prod,init-prod
[ 8/12] Bootstrap resource catalogue      ← idempotent
[ 9/12] Authenticate as admin             ← save token under work dir
[10/12] Khasab import — pre-DPR           ← project + WBS + activities + assignments
[11/12] Khasab import — DPR upload        ← 3,431 rows, ~5-15 min
[12/12] Khasab import — post-DPR          ← cost + EVM + BOQ + dashboard + risks
```

Every stage logs to `deployment/logs/deploy-<timestamp>.log` AND to stdout
with colour-coded `[INFO]` / `[OK]` / `[WARN]` / `[ERROR]` lines. The
log symlink `deployment/logs/deploy-latest.log` always points at the most
recent run.

---

## 3. Prerequisites

| Requirement | Linux / EC2 | macOS | Windows |
|---|---|---|---|
| Docker | Docker Engine 24+ | Docker Desktop 4.x | Docker Desktop 4.x |
| Docker Compose v2 | bundled with engine | bundled | bundled |
| Disk free | 20 GB recommended | same | same |
| RAM | 8 GB min (Docling alone wants 12 GB) | same | same |
| Python | 3.11+ (for the import scripts) | system python3 | `python` on PATH |
| Network | Internet for first build (Maven + base images) | same | same |

`deploy.sh` auto-starts Docker on Linux (`systemctl start docker`) and macOS
(`open -a Docker`). `deploy.ps1` does the same on Windows by launching Docker
Desktop. If auto-start fails, the script exits with a clear message — you
start Docker manually and re-run.

The import scripts use `python3` and the `openpyxl` Excel reader. The
script installs `openpyxl` via `pip install --user` if missing — no other
pip dependencies are required.

---

## 4. Environment variables

Copy `configs/.env.example` to `configs/.env` and edit. The script auto-copies
on first run if `.env` doesn't exist.

| Variable | Default | What |
|---|---|---|
| `POSTGRES_USER` | `bipros` | DB user — must match what import scripts expect |
| `POSTGRES_PASSWORD` | `bipros_dev` | DB password |
| `POSTGRES_DB` | `bipros` | DB name |
| `PG_HOST_PORT` | `5433` | Host port for Postgres (5432 reserved for native Postgres on dev machines) |
| `API_HOST_PORT` | `8080` | Host port for the backend |
| `BIPROS_PROFILES` | `prod,init-prod` | Spring profile for first boot. Switch to `prod` after. |
| `JWT_SECRET` | `change-me-…` | **Rotate before any prod use** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Set to your real frontend origin |
| `BIPROS_AI_KEK` | dev KEK | **Rotate before any prod use** |
| `ANTHROPIC_API_KEY` | _(empty)_ | Optional, enables `/v1/ai/chat` |
| `REDIS_HOST_PORT` | `6379` | |
| `CLICKHOUSE_HTTP_PORT` | `8123` | |
| `MINIO_API_PORT` / `MINIO_CONSOLE_PORT` | `9000` / `9001` | |
| `DOCLING_PORT` | `5001` | |
| `PGADMIN_PORT` | `5050` | |
| `JAVA_OPTS` | `-Xmx2g -XX:MaxRAMPercentage=75` | |
| `DB_POOL_MAX` | `60` | HikariCP max — keep ≥ 60 for DPR bulk import |
| `BIPROS_WORK_DIR` | `/tmp/khasab` | Where the import scripts stash intermediate JSON / tokens |

---

## 5. Common operations

### Restart (preserve data)
```bash
./scripts/restart.sh        # Linux/macOS
.\scripts\restart.ps1       # Windows
```

### Re-run import (data refresh, containers stay)
```bash
./scripts/reimport.sh       # re-parse + seed catalog + WIPE & reload DPRs (by-date parallel)
.\scripts\reimport.ps1
```
Wipes the project's existing DPRs and rebuilds them from source (a plain import would only
hit `dup`). Seeds any missing rate-book variants first so every resource resolves.

### Reset everything (destructive — drops volumes)
```bash
./scripts/reset.sh           # asks for "yes" confirmation
./scripts/reset.sh --yes     # skip confirmation
.\scripts\reset.ps1 -Yes
# Then re-run ./deploy.sh
```

### Tail backend logs
```bash
./scripts/logs.sh                # bipros-api (default)
./scripts/logs.sh postgresql     # any service name
.\scripts\logs.ps1
```

### Status snapshot
```bash
./scripts/status.sh
.\scripts\status.ps1
```

### Switch to steady-state `prod` profile after first boot
```bash
BIPROS_PROFILES=prod docker compose up -d bipros-api
```

---

## 6. EC2 deployment notes

1. **Instance type**: `t3.large` (2 vCPU, 8 GB) works for the demo but is
   tight. `t3.xlarge` (4 vCPU, 16 GB) is comfortable — Docling alone can
   consume 4–6 GB during PDF parsing.

2. **Storage**: 30 GB gp3 minimum. Docker images + Postgres volume + DPR
   import logs use ~10 GB.

3. **Security group**: at minimum open `:8080` (backend) and `:3000` (frontend
   if you also run it on the box). For an internal demo, also `:5050`
   (pgAdmin) and `:9001` (MinIO console). For pure backend access, only
   `:8080` is needed.

4. **First-time setup on a fresh EC2 (Ubuntu 22.04/24.04)**:
   ```bash
   sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 python3-pip
   sudo usermod -aG docker $USER
   newgrp docker            # or log out/in so the group takes effect

   git clone <repo-url>
   cd bipros-eppm/deployment
   ./deploy.sh              # ~15 min
   ```

5. **Backgrounding for SSH disconnects**:
   ```bash
   tmux new -s deploy
   ./deploy.sh
   # Ctrl-b d to detach; reattach with `tmux attach -t deploy`
   ```
   Or:
   ```bash
   nohup ./deploy.sh > logs/deploy-nohup.log 2>&1 &
   tail -f logs/deploy-latest.log
   ```

6. **Persistent storage**: Postgres data lives in the Docker volume
   `bipros-eppm_postgres_data` on the instance. Survives `restart.sh`
   but is wiped by `reset.sh`. For real production, mount this volume
   from an EBS volume or RDS.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Docker is not installed` | First time on fresh box | `apt-get install -y docker.io docker-compose-v2` then re-run |
| `Docker daemon not running` (Linux) | systemd unit not started | `sudo systemctl start docker` and re-run; deploy.sh tries this automatically |
| `Port in use: 5433` | Native Postgres on host | Edit `configs/.env` → `PG_HOST_PORT=5434`, re-run |
| Backend exits with `BIPROS_AI_KEK is not set` | Non-dev profile requires KEK | Default in compose works; if you cleared it, set in `configs/.env` |
| Backend exits with `Connection refused 127.0.0.1:8123` | ClickHouse hostname not in-container | Already fixed via `CLICKHOUSE_URL=jdbc:clickhouse://clickhouse:8123/…` in compose — don't override |
| Admin login returns 401 after first boot | First boot didn't include `init-prod` | Force again: `BIPROS_PROFILES=prod,init-prod docker compose up -d bipros-api` |
| `fix_role_assignments.py` → all `eq_no_match` | Catalogue empty | `deploy.sh` step 8 should load it. If it skipped, run `docker exec -i bipros-postgres psql -U bipros -d bipros < data/sql/01-bipros-masters.sql` |
| DPR list shows old "Khasab 1.1" labels | Denormalized snapshot drift | `docker exec -i bipros-postgres psql -U bipros -d bipros < data/sql/99-fix-dpr-drift.sql` |
| `Cannot submit DPR — activity is still in Draft` | Re-lock step skipped | `deploy.sh` re-locks after `fix_role_assignments.py`; re-run import with `./scripts/reimport.sh` |
| Frontend "Network Error" on every API call | CORS rejecting frontend origin | Set `CORS_ALLOWED_ORIGINS=http://your-host:3000` in `configs/.env`, then `BIPROS_PROFILES=prod docker compose up -d bipros-api` |
| `pip install openpyxl` failure on EC2 | No `pip3` | `sudo apt-get install -y python3-pip` |

For deeper context (Liquibase issues, resource catalogue gaps, hardcoded
UUID handling, etc.), see [`../docs/khasab-e2e-2026-05-24/DEPLOY-DOCKER-PROD.md`](../docs/khasab-e2e-2026-05-24/DEPLOY-DOCKER-PROD.md).

---

## 8. Production hardening checklist

Before exposing this stack to the outside world:

- [ ] Change admin password from `admin/admin123` via the UI
- [ ] Generate a real `JWT_SECRET` (`openssl rand -base64 48`), set in `configs/.env`, restart `bipros-api`
- [ ] Rotate `BIPROS_AI_KEK` to a fresh value (regenerate per env)
- [ ] Tighten `CORS_ALLOWED_ORIGINS` to your real frontend host
- [ ] Set a strong `POSTGRES_PASSWORD` and `MINIO_ROOT_PASSWORD`
- [ ] Drop `init-prod` profile after first boot: `BIPROS_PROFILES=prod docker compose up -d bipros-api`
- [ ] Schedule daily `pg_dump`: `docker exec bipros-postgres pg_dump -U bipros -F c bipros > /backups/$(date +%F).dump`
- [ ] Pin all `latest`/`alpine` image tags in `docker-compose.yml` for reproducibility
- [ ] Move Postgres data to an EBS or RDS volume (not the ephemeral Docker volume)
- [ ] Set up TLS termination in front of the backend (nginx, ALB, Caddy)

For full background on what each of these means — and the latent Liquibase
issues to address before flipping back to `validate` — see
[`../docs/khasab-e2e-2026-05-24/DEPLOY-DOCKER-PROD.md §13`](../docs/khasab-e2e-2026-05-24/DEPLOY-DOCKER-PROD.md).

---

## 9. Import process — what runs in stages 10/11/12

| Step | Script | What it does |
|---|---|---|
| Parse | `parse_khasab.py` | Reads `data/khasab-excel/daily-data.xlsx`, normalises into 3,431 DPR records, applies +1y date shift |
| Parse | `parse_master_sheet.py` | Reads `data/khasab-excel/dbs-master.xlsx`, extracts master activity table |
| Plan | `analyze_resource_demand.py` | Derives per-activity resource demand (manpower/equipment counts) |
| Build | `rebuild_demo.py` | Creates EPS node (if absent) → project → 16 users → project team → 23 WBS nodes → 33 activities (with REAL names) |
| Plan | `fix_role_assignments.py` | 229 role-assignments (manpower role rates + equipment variants + material variants); creates missing variants on-the-fly |
| Catalog | `seed_resource_catalog.py` | Ensures **every** DPR manpower/equipment name has an active rate-book variant (creates the role and/or variant if missing). Idempotent. Without this, resources whose role isn't planned on an activity import as free-text and show blank in the DPR resource dropdown. |
| Supervisors | `assign_activity_supervisors.py` | Assigns each activity the supervisor(s) who actually file DPRs for it (`activity.activity_supervisors`). Without it the DPR form shows "No activities assigned to this supervisor — showing all". Idempotent. |
| Lock | inline | Re-locks the 33 activities so DPR submission accepts them |
| Ingest | `import_khasab_dprs.py` | Imports 3,431 DPRs **parallelised by date** (default 12 workers — see note below), resolving each manpower/equipment row to a role+variant (activity-planned first, then the global rate book) so it pre-selects in the DPR dropdown. Workdone Quantity is always a **whole number**: the rounded source qty on working days, or — for the ~74% idle/no-output source days — a realistic whole number sampled from that activity's real daily quantities (stable per DPR). |
| Polish | `fix_demo_v2.py` | Sets BOQ items, EVM, MCLs, productivity norms, DBS recompute |
| Polish | `link_dprs_to_boq.py` | Sets a BOQ item on **every** DPR — matching `boq.item_no == activity.code`, else a stable pseudo-random existing BOQ item. Race-free SQL (the import can't set `boqItemNo` itself — see note). Runs after `fix_demo_v2.py`. |
| Polish | `align_activity_units.py` | Sets one canonical unit per activity code (from `activity-units.json`, written by `parse_khasab.py`) on both `resource.work_activities.default_unit` and `project.daily_progress_reports.unit`, so the WorkActivity, Activity, and DPR all agree — removing the "Master Work Activity unit is X but this DPR uses Y" warning. Race-free SQL, idempotent. |
| Polish | `create_norms_only.py` | Per-activity productivity norms (66 total) |
| Polish | `tune_productivity_norms.sql` | Calibrates norms per family for realistic Capacity Util % |
| Polish | `populate_dashboard.py` | Links calendar, 60 weather rows, 6 milestones, 6 DPR issues |
| Polish | `add_weather_risks.py` | Per-day weather conditions + 8 risk register entries |
| Polish | `fix_dpr_activity_name_drift.sql` | Safety-net for the denormalized `daily_progress_reports.activity_name` snapshot |

If any step fails, the deploy script keeps going (the polish steps are
independently useful) but logs `[WARN]` so you can re-run after fixing.

**Why "parallelised by date":** each DPR write fires an `AFTER_COMMIT` DBS recompute that
is serialised per `(project, date)` by a Postgres advisory lock. Parallelising arbitrarily
makes workers on the same date queue/contend on that lock (~1 row/sec — a full reload would
take ~1–2 h). Giving each worker a whole **date** (its rows posted sequentially) and running
different dates in parallel means the lock is never contended → near-linear speedup (full
3,431-row reload in ~6–7 min). Tune with `BIPROS_DPR_IMPORT_WORKERS=N`. This is safe because
the import sends no `boqItemNo` (BOQ-sync is a no-op) and the activity-progress listeners are
`AFTER_COMMIT` (a race there can't roll back a DPR write).

**Refreshing DPRs on a populated DB:** a plain re-import only POSTs, so existing rows come
back as `dup` and keep stale data. `scripts/reimport.sh` therefore re-parses, seeds the
catalog, runs `imports/reload_dprs.py` which **wipes** the project's DPRs (also by-date
parallel) and re-imports them, then re-links them to BOQ items.

**Why BOQ linking is a separate SQL step (`link_dprs_to_boq.py`):** linking a DPR to a BOQ
item fires the in-transaction `DprBoqSyncListener`, a `@Version` read-modify-write on the
shared `boq_items` row. Under the by-date parallel import, concurrent DPRs for one activity
(different dates) would collide on that row → optimistic-lock → failed POSTs. So the import
sends no `boqItemNo` (keeping by-date parallelism safe) and the link is applied afterwards
with a single race-free SQL `UPDATE` per activity. For the same reason `reload_dprs.py`
NULLs the BOQ link before wiping: deleting a BOQ-linked DPR re-runs `BoqCalculator`, which
can overflow the `numeric(9,6)` `percent_complete`/`cost_variance_percent` columns (409) and
abort the delete — leaving stragglers that come back as `dup`.

---

## 10. Files in this bundle

```
deployment/
├── README.md                       ← you are here
├── deploy.sh                       ← Linux/macOS/EC2 entry
├── deploy.ps1                      ← Windows entry
├── docker-compose.yml              ← 7 services with healthchecks
├── docker/
│   ├── backend.Dockerfile          ← multi-stage Spring Boot build
│   └── postgres.Dockerfile         ← Postgres 17 + PostGIS + pgvector
├── configs/
│   ├── .env.example                ← copy to .env and edit
│   └── .env                        ← (gitignored — your overrides)
├── data/
│   ├── khasab-excel/               ← bundled source workbooks
│   │   ├── daily-data.xlsx
│   │   └── dbs-master.xlsx
│   └── sql/
│       ├── 00-init-schemas.sql     ← auto-loaded into fresh Postgres
│       ├── 01-bipros-masters.sql   ← resource catalogue bootstrap
│       ├── 99-fix-dpr-drift.sql    ← post-import safety net
│       └── clickhouse-init.sql     ← auto-loaded into fresh ClickHouse
├── imports/
│   ├── parse_khasab.py             ← stage 10 scripts
│   ├── rebuild_demo.py             ← project + WBS + activities
│   ├── fix_role_assignments.py     ← 229 role-assignments
│   ├── import_khasab_dprs.py       ← stage 11 (the long one)
│   ├── fix_demo_v2.py              ← stage 12 cost + EVM + BOQ
│   ├── populate_dashboard.py       ← calendar + weather + issues
│   ├── add_weather_risks.py        ← weather + 8 risks
│   └── …                            ← others used by rebuild_demo
├── scripts/
│   ├── psql-wrapper.sh / .cmd      ← lets python scripts call "psql" without psql on host
│   ├── restart.sh / .ps1           ← restart all containers, keep data
│   ├── reset.sh / .ps1             ← destructive wipe (volumes too)
│   ├── reimport.sh / .ps1          ← re-run Khasab imports
│   ├── logs.sh / .ps1              ← tail container logs
│   └── status.sh / .ps1            ← snapshot container + DB state
└── logs/                            ← deploy-YYYYMMDD-HHMMSS.log per run
```

---

## 11. Known gaps (read before going to true prod)

These are documented at length in
[`../docs/khasab-e2e-2026-05-24/DEPLOY-DOCKER-PROD.md §13`](../docs/khasab-e2e-2026-05-24/DEPLOY-DOCKER-PROD.md):

1. **Liquibase changelog has latent parse errors**, so the deployment runs
   with `SPRING_LIQUIBASE_ENABLED=false` and Hibernate `ddl-auto: update`
   (same as dev). Fine for demos; for regulated prod, audit the changelog
   YAML first.
2. **Resource catalogue is bootstrapped from a `pg_dump`-derived SQL file**
   bundled in `data/sql/01-bipros-masters.sql`. For true prod, fold this
   into a Liquibase changeset or a CSV-backed `@Always-on` seeder.
3. **`DataSeeder` is gated on a custom `init-prod` profile** so we get
   admin without activating the bundled Khasab demo seeders. Drop `init-prod`
   after the first boot.

These gaps are intentional trade-offs — the deployment is "demo-ready in
prod profile", not "regulated-prod ready".
