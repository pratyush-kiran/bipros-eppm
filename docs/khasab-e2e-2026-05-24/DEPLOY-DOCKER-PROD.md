# Deploy to Docker — prod profile, clean data

End-to-end guide to bring up the **Khasab Road Project 2026** demo in a Dockerized stack — backend (Spring Boot, Java 23) + Postgres + Redis + ClickHouse + MinIO + Docling — starting from an empty database and loading the prepared dataset.

This complements `SETUP.md` (host-only) and `DEPLOYMENT.md` (self-managed prod). Use **this** doc when you want everything in Docker on a single machine.

> **Honest framing:** This is **"prod profile in Docker for demo purposes"**, not regulated-prod. We disable Liquibase because the committed YAML changelog has latent bugs (it's been off in dev forever, so it's never been exercised); Hibernate `ddl-auto: update` builds the schema from `@Entity` classes instead. Everything else (validate vs update aside, error masking, prod logging, JWT secret) is real prod profile. To run this in true prod, fix the Liquibase changelog first — see §13 Known Issues.

---

## 0. What you'll get

| Container | Image | Host port | Purpose |
|---|---|---|---|
| `bipros-api` | `bipros-api:prod` (built locally) | `8080` | Spring Boot, profile `prod,init-prod` on first boot |
| `bipros-postgres` | `bipros-postgis-pgvector:17-3.5` (built locally) | `5433` | Postgres 17 + PostGIS + pgvector — credentials `bipros / bipros_dev` |
| `bipros-pgadmin` | `dpage/pgadmin4:latest` | `5050` | DB browser (optional) |
| `bipros-redis` | `redis:7-alpine` | `6379` | Token / cache |
| `bipros-clickhouse` | `clickhouse/clickhouse-server:24.12-alpine` | `8123`, `9002` | Analytics warehouse |
| `bipros-minio` | `minio/minio:latest` | `9000`, `9001` | S3-compatible store |
| `bipros-docling` | `quay.io/docling-project/docling-serve-cpu:latest` | `5001` | Document AI |

Frontend is **not** in this stack — run `pnpm dev` on the host (or build a separate image later).

The dev "native Postgres.app on :5432" is **untouched** — Docker Postgres binds host port `:5433`.

---

## 1. Prerequisites

- Docker Desktop ≥ 24 (Compose v2 built-in)
- ≥ 8 GB free RAM, ≥ 20 GB free disk
- Internet on first build (Maven dependency resolution + base image pulls)
- Python 3.11+ on the host (for the Khasab import scripts; not inside containers)
- The host's native Postgres.app DB has the Khasab demo masters populated — Docker DB inherits 4 master tables from it via `pg_dump` (see §6.1). If you don't have a populated dev DB, that step needs adapting.

---

## 2. First-boot profile: `prod,init-prod`

`DataSeeder` is gated on `@Profile({"dev","seed","init-prod"})`. Liquibase has no admin-user changeset. A pure `prod` boot against an empty DB produces a schema but **no admin** — login impossible.

We can't use `seed` either: it activates four bundled Khasab seeders (`KhasabConcretePourSeeder`, `KhasabDailyDataSeeder`, `KhasabProductivityNormSeeder`, `KhasabSupervisorUserSeeder`) that would pre-fill the DB with their own copy of Khasab data and collide with our import scripts.

The narrower `init-prod` profile activates only `DataSeeder` (admin user + 22 roles + 2 demo users) and nothing else.

Two-phase pattern:
1. **First boot** — `SPRING_PROFILES_ACTIVE=prod,init-prod`. Liquibase OFF (see §13). Hibernate builds schema from entities. `DataSeeder` creates `admin/admin123` + roles. Always-on backfill seeders (formula catalogue, resource types, risk categories) also fire.
2. **Subsequent boots** — `SPRING_PROFILES_ACTIVE=prod`. DataSeeder no-op; everything else stays.

Compose defaults to `prod,init-prod` via `BIPROS_PROFILES`. Override with:
```bash
export BIPROS_PROFILES=prod        # for restart after first boot
docker compose up -d bipros-api
```

---

## 3. Build the backend image

From the repo root:
```bash
docker compose build bipros-api
```

The Dockerfile (`backend/Dockerfile`) is multi-stage:
- **Stage 1** — `maven:3.9-eclipse-temurin-23`, `mvn -pl bipros-api -am package -DskipTests`. BuildKit `--mount=type=cache,target=/root/.m2` so rebuilds don't re-download.
- **Stage 2** — `eclipse-temurin:23-jre`, copies fat jar, runs as non-root `bipros`, exposes 8080, `/app/storage` chowned to `bipros` for runtime artefacts (DPR photos, attachments).

Cold build: ~8–12 min. Warm rebuild (code-only): ~2 min.

---

## 4. Clean wipe + bring up infra

```bash
cd /path/to/bipros-eppm

# 1. Tear down + drop all volumes (irreversible — wipes DB, MinIO, ClickHouse)
docker compose down -v

# 2. Bring infra up first (Postgres needs to be healthy before backend boots,
#    ClickHouse needs to be healthy or backend boot fails)
docker compose up -d postgresql redis clickhouse minio docling pgadmin

# 3. Wait for Postgres + ClickHouse healthy
until docker compose ps postgresql --format json | grep -q '"Health":"healthy"'; do sleep 3; done
until docker compose ps clickhouse  --format json | grep -q '"Health":"healthy"'; do sleep 3; done

# 4. Bring backend up
docker compose up -d bipros-api

# 5. Wait for boot (~60–90s)
until curl -fsS http://localhost:8080/actuator/health | grep -q UP; do sleep 5; done
echo "Backend UP"
```

If boot hangs, `docker logs bipros-api --tail 50` and check §13 for known issues.

---

## 5. Smoke-test the empty-but-seeded stack

```bash
curl http://localhost:8080/actuator/health     # → {"status":"UP"}

TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
echo "$TOKEN" > /tmp/admin-token.txt

# Confirm zero projects (clean DB) + admin + roles seeded
docker exec bipros-postgres psql -U bipros -d bipros -c \
  "SELECT COUNT(*) AS projects FROM project.projects;
   SELECT COUNT(*) AS users    FROM public.users;
   SELECT COUNT(*) AS roles    FROM public.roles;"
# Expected: projects=0, users=3 (admin + pmanager + scheduler), roles=22
```

---

## 6. Load the Khasab dataset

The scripts assume a populated **resource catalogue** (manpower role rates, equipment role variants, etc.). The always-on `*MasterBackfillSeeder` beans only fill a fraction of what's needed — the rest is populated in dev by manual steps or one-off scripts. For Docker, the easiest bootstrap is to copy those master tables from the dev DB.

### 6.1 Seed master catalogue from dev (one-time)

```bash
cd /path/to/bipros-eppm

# Pull resource catalogue from native dev Postgres
PGPASSWORD=bipros_dev /Applications/Postgres.app/Contents/Versions/latest/bin/pg_dump \
  -h 127.0.0.1 -U bipros -d bipros \
  --data-only --column-inserts \
  -t resource.resource_types \
  -t resource.resource_roles \
  -t resource.manpower_role_rates \
  -t resource.material_role_variants \
  -t resource.equipment_role_variants \
  -t resource.equipment_rate_masters \
  > /tmp/bipros-masters.sql

# Docker DB had auto-seeded resource_types with different UUIDs — wipe + reload
docker exec bipros-postgres psql -U bipros -d bipros -c "
  DELETE FROM resource.resource_roles;
  DELETE FROM resource.resource_types;"
docker exec -i bipros-postgres psql -U bipros -d bipros < /tmp/bipros-masters.sql

# Verify
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT 'resource_types', COUNT(*) FROM resource.resource_types
UNION ALL SELECT 'resource_roles', COUNT(*) FROM resource.resource_roles
UNION ALL SELECT 'equipment_role_variants', COUNT(*) FROM resource.equipment_role_variants
UNION ALL SELECT 'manpower_role_rates', COUNT(*) FROM resource.manpower_role_rates
UNION ALL SELECT 'equipment_rate_masters', COUNT(*) FROM resource.equipment_rate_masters;"
# Expected: 3 / 219 / 22 / 24 / 57
```

### 6.2 Patch the EPS node UUID (per-deploy)

`rebuild_demo.py` hardcodes an `epsNodeId` UUID that exists in dev but not in Docker. Create a fresh EPS node in the Docker DB, then sed the script:

```bash
TOKEN=$(cat /tmp/admin-token.txt)
EPS_ID=$(curl -sS -X POST http://localhost:8080/v1/eps \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"BIPROS","name":"Bipros Construction"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
echo "EPS_ID=$EPS_ID"

# Replace the hardcoded UUID
perl -i -pe "s|e38edde8-b6cb-4d2c-8e16-72a8336e7c0a|$EPS_ID|g" \
  docs/khasab-e2e-2026-05-24/scripts/rebuild_demo.py
```

### 6.3 Patch the EQUIP_TYPE_ID (per-deploy)

`fix_role_assignments.py` hardcodes a `resource_types` UUID for EQUIPMENT. After §6.1 it matches dev's UUID, but if you skipped §6.1 the UUID has to be re-pointed:

```bash
EQUIP_TYPE=$(docker exec bipros-postgres psql -U bipros -d bipros -At -c \
  "SELECT id FROM resource.resource_types WHERE code='EQUIPMENT'")
perl -i -pe "s|EQUIP_TYPE_ID = \"[a-f0-9-]+\"|EQUIP_TYPE_ID = \"$EQUIP_TYPE\"|" \
  docs/khasab-e2e-2026-05-24/scripts/fix_role_assignments.py
```

### 6.4 Run the import chain

Scripts run on the **host** and hit the dockerized backend at `http://localhost:8080`. Set `BIPROS_PG_PORT=5433` so the SQL-direct scripts target the Docker Postgres (host port 5433) instead of native dev Postgres on :5432.

```bash
cd /path/to/bipros-eppm/docs/khasab-e2e-2026-05-24
export BIPROS_PG_PORT=5433
mkdir -p /tmp/khasab

# Required: token already in /tmp/admin-token.txt from §5

# Parse Excel + plan resources (no DB writes)
python3 scripts/parse_khasab.py
python3 scripts/parse_master_sheet.py
python3 scripts/analyze_resource_demand.py

# Project + team + WBS + 33 activities with REAL names
python3 scripts/rebuild_demo.py
PROJECT_ID=$(cat /tmp/khasab/project-id.txt)
echo "PROJECT_ID=$PROJECT_ID"

# 229 role-assignments (manpower + equipment + material)
python3 scripts/fix_role_assignments.py

# Re-lock activities (fix_role_assignments unlocks them and doesn't re-lock,
# but DPR import requires LOCKED edit_status)
TOKEN=$(cat /tmp/admin-token.txt)
for aid in $(python3 -c "import json; print(' '.join(json.load(open('/tmp/khasab/activity-ids.json')).values()))"); do
  curl -sS -o /dev/null -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/activities/$aid/lock" \
    -H "Authorization: Bearer $TOKEN"
done

# 3,431 DPRs — runs ~60–90 min. Use tmux/nohup.
nohup python3 scripts/import_khasab_dprs.py all > /tmp/dpr-import.log 2>&1 &
tail -f /tmp/dpr-import.log

# Once that finishes:
python3 scripts/fix_demo_v2.py              # cost + EVM + BOQ + MCL + DBS recompute
python3 scripts/create_norms_only.py        # 66 productivity norms
docker exec -i bipros-postgres psql -U bipros -d bipros \
  < scripts/tune_productivity_norms.sql     # calibrate per family
python3 scripts/populate_dashboard.py       # calendar + weather + milestones + issues
python3 scripts/add_weather_risks.py        # risk register

# Safety net for the denormalized DPR activity_name snapshot
docker exec -i bipros-postgres psql -U bipros -d bipros \
  < scripts/fix_dpr_activity_name_drift.sql
```

---

## 7. Switch to steady-state `prod` profile

```bash
export BIPROS_PROFILES=prod
docker compose up -d bipros-api     # recreates the container with new env

# Verify admin still works
curl -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | head -c 100

# Confirm Khasab project survives
docker exec bipros-postgres psql -U bipros -d bipros -c \
  "SELECT code, name FROM project.projects WHERE code='KHASAB-2026';"
```

---

## 8. Verify in the browser

Start the frontend on the host (not in compose):
```bash
cd /path/to/bipros-eppm/frontend
pnpm install
pnpm dev          # opens on http://localhost:3000
```

Login `admin / admin123`. Verify:
- Projects list → **Khasab Road Project 2026** (`KHASAB-2026`)
- Overview → Overall Progress ~95%, Budget ₹5 Cr, populated Site Conditions
- Activities tab → real names (Camp work, Mechanical Excavation, Blasting, …)
- DPR tab → 3,431 records grouped by real activity names (not "Khasab 1.1")
- Capacity Util. → set range 2026-01-24 → 2026-03-29, Total Eff ≈ 88.6%
- Risks → 8 entries

---

## 9. Post-deploy hardening (mandatory for real prod)

| Step | Why | How |
|---|---|---|
| Change admin password | `admin/admin123` is dev default | UI → user menu, or `psql`-update BCrypt hash |
| Rotate `JWT_SECRET` | Compose default is a placeholder | `openssl rand -base64 48`, export, `docker compose up -d bipros-api` |
| Rotate `BIPROS_AI_KEK` | Compose default is the shared dev KEK — anyone with the repo can decrypt persisted API keys | Generate via your KEK provisioning, export, restart |
| Set `ANTHROPIC_API_KEY` | `/v1/ai/chat` falls back to canned answers without it | Export, restart |
| Tighten `CORS_ALLOWED_ORIGINS` | Default `http://localhost:3000` is dev-only | Set to your real frontend origin(s) |
| Use real DB credentials | Compose defaults to `bipros/bipros_dev` for script compatibility | Change `POSTGRES_USER`/`POSTGRES_PASSWORD`, recreate volume, update backend env |
| Drop the seed profile | Avoid surprise re-seeds | `export BIPROS_PROFILES=prod` + restart |
| Schedule pg_dump backups | First backup ≠ DR strategy | `docker exec bipros-postgres pg_dump -U bipros -F c bipros > /backups/$(date +%F).dump` in cron |
| Pin `latest` tags | Reproducible deploys | Edit `docker-compose.yml` to use exact tags |
| Fix Liquibase before going to true prod | See §13 | (See §13) |

---

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Container exits with `schema "project" does not exist` | Postgres volume already populated, init script didn't run | `docker compose down -v` to drop the volume |
| Admin login returns 401 after fresh boot | First boot didn't include `init-prod` profile | `BIPROS_PROFILES=prod,init-prod docker compose up -d bipros-api` |
| Backend exit with `BIPROS_AI_KEK is not set` | Non-dev profile requires KEK | Compose has a default; export `BIPROS_AI_KEK` to override |
| `Connection refused 127.0.0.1:8123` | Backend tried to reach ClickHouse on its own loopback | Use `CLICKHOUSE_URL=jdbc:clickhouse://clickhouse:8123/bipros_analytics` (already in compose) |
| `Unable to initialise DPR photo storage at ./storage/dpr-photos` | `/app/storage` not writable by non-root user | Dockerfile chowns `/app` to `bipros` — rebuild if you see this |
| `Liquibase ChangeLogParseException: Unexpected node: 2)` | YAML inline-flow types like `DECIMAL(15, 2)` not quoted | We disable Liquibase via `SPRING_LIQUIBASE_ENABLED=false`. To re-enable, fix the YAML — see §13 |
| `Unknown change type 'addCheckConstraint'` | OSS Liquibase 4.x dropped this; needs `<sql>` rewrite | See §13 |
| `fix_role_assignments.py` → `eq_no_match` for everything | Resource catalogue empty | Run §6.1 to import from dev |
| `Cannot submit DPR — activity is still in Draft` | DPR import ran before re-locking activities | Re-run the lock loop in §6.4, then re-run import |
| DPR import < 0.5 DPR/s | HikariCP exhausted | `DB_POOL_MAX=60` is already set; check `docker logs bipros-api | grep HikariPool` for warnings |
| Port 8080 in use | Host backend still running | `pkill -f spring-boot.*bipros` then `docker compose up -d bipros-api` |
| Browser "Network Error" on every API call | CORS rejecting frontend origin | Set `CORS_ALLOWED_ORIGINS` correctly |

---

## 11. Tear down

```bash
# Stop only (data preserved)
docker compose down

# Stop + wipe all volumes (back to §4)
docker compose down -v

# Also remove built images
docker compose down -v --rmi local
```

---

## 12. Single-command rebuild from scratch

For the impatient — assumes Docker running, dev Postgres has masters populated, Khasab Excel files in `docs/ActualData/`:

```bash
cd /path/to/bipros-eppm

# Build + bring up empty stack
docker compose down -v
docker compose build bipros-api
docker compose up -d postgresql redis clickhouse minio docling pgadmin
until docker compose ps postgresql --format json | grep -q '"Health":"healthy"'; do sleep 3; done
until docker compose ps clickhouse  --format json | grep -q '"Health":"healthy"'; do sleep 3; done
docker compose up -d bipros-api
until curl -fsS http://localhost:8080/actuator/health | grep -q UP; do sleep 5; done

# Bootstrap masters from native dev Postgres
PGPASSWORD=bipros_dev /Applications/Postgres.app/Contents/Versions/latest/bin/pg_dump \
  -h 127.0.0.1 -U bipros -d bipros --data-only --column-inserts \
  -t resource.resource_types -t resource.resource_roles \
  -t resource.manpower_role_rates -t resource.material_role_variants \
  -t resource.equipment_role_variants -t resource.equipment_rate_masters \
  > /tmp/bipros-masters.sql
docker exec bipros-postgres psql -U bipros -d bipros -c \
  "DELETE FROM resource.resource_roles; DELETE FROM resource.resource_types;"
docker exec -i bipros-postgres psql -U bipros -d bipros < /tmp/bipros-masters.sql

# Admin token + EPS node
TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
echo "$TOKEN" > /tmp/admin-token.txt
mkdir -p /tmp/khasab

EPS_ID=$(curl -sS -X POST http://localhost:8080/v1/eps \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"code":"BIPROS","name":"Bipros Construction"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
perl -i -pe "s|e38edde8-b6cb-4d2c-8e16-72a8336e7c0a|$EPS_ID|g" \
  docs/khasab-e2e-2026-05-24/scripts/rebuild_demo.py

EQUIP_TYPE=$(docker exec bipros-postgres psql -U bipros -d bipros -At -c \
  "SELECT id FROM resource.resource_types WHERE code='EQUIPMENT'")
perl -i -pe "s|EQUIP_TYPE_ID = \"[a-f0-9-]+\"|EQUIP_TYPE_ID = \"$EQUIP_TYPE\"|" \
  docs/khasab-e2e-2026-05-24/scripts/fix_role_assignments.py

# Run the chain (sequential — ~90 min, DPR import is the long pole)
cd docs/khasab-e2e-2026-05-24
export BIPROS_PG_PORT=5433
python3 scripts/parse_khasab.py
python3 scripts/parse_master_sheet.py
python3 scripts/analyze_resource_demand.py
python3 scripts/rebuild_demo.py
PROJECT_ID=$(cat /tmp/khasab/project-id.txt)
python3 scripts/fix_role_assignments.py
for aid in $(python3 -c "import json; print(' '.join(json.load(open('/tmp/khasab/activity-ids.json')).values()))"); do
  curl -sS -o /dev/null -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/activities/$aid/lock" \
    -H "Authorization: Bearer $TOKEN"
done
python3 scripts/import_khasab_dprs.py all
python3 scripts/fix_demo_v2.py
python3 scripts/create_norms_only.py
docker exec -i bipros-postgres psql -U bipros -d bipros < scripts/tune_productivity_norms.sql
python3 scripts/populate_dashboard.py
python3 scripts/add_weather_risks.py
docker exec -i bipros-postgres psql -U bipros -d bipros < scripts/fix_dpr_activity_name_drift.sql

# Switch to steady-state
cd /path/to/bipros-eppm
BIPROS_PROFILES=prod docker compose up -d bipros-api
```

---

## 13. Known issues (must address before true prod)

### A. Liquibase changelog has parse errors

Liquibase is **disabled** in this Docker stack (`SPRING_LIQUIBASE_ENABLED=false` in compose) and schema is built by Hibernate (`ddl-auto: update`). Dev does the same and the latent issues in `backend/bipros-api/src/main/resources/db/changelog/*.yaml` have never been caught.

Two classes of issue we hit:
1. **Unquoted inline-flow `DECIMAL(x, y)` / `NUMERIC(x, y)`** — YAML parses the comma as a key separator. We mass-fixed 91 occurrences in 16 files (see commit message). Verify with:
   ```bash
   grep -rE 'type:\s*(decimal|numeric)\(\d+,\s*\d+\)' \
     backend/bipros-api/src/main/resources/db/changelog/ | grep -v '"'
   # expected: 0 lines
   ```
2. **`addCheckConstraint` no longer in OSS Liquibase** — `050-resource-assignment-role-staffing.yaml` uses it. Replace with raw `<sql>ALTER TABLE … ADD CONSTRAINT … CHECK (…)</sql>` blocks.

There are very likely more failures hidden behind these — the changelog has never run end-to-end on a fresh DB. A full audit (run Liquibase against an empty DB, fix each failure, repeat) is the right path before flipping `validate` + Liquibase back on for true prod.

### B. Resource catalogue not seeded by always-on beans

The four `*Master*` seeders that fire in every profile only populate a fraction of `resource.*`. The Khasab demo expects:
- `manpower_role_rates` (24 rows)
- `equipment_role_variants` (22 rows)
- `equipment_rate_masters` (57 rows)
- `material_role_variants` (10 rows)
- `resource_roles` (219 rows)

In dev these were populated by manual steps. For Docker we copy them from dev (§6.1). For true prod, either fold the bootstrap SQL into a Liquibase changeset or write a new always-on seeder (`ProductionResourceCatalogueSeeder`) that reads them from a CSV bundled in the jar.

### C. Hardcoded UUIDs in import scripts

`rebuild_demo.py` and `fix_role_assignments.py` carry dev-specific UUIDs for EPS node and EQUIPMENT resource_type. We sed them per-deploy (§6.2, §6.3). A cleaner fix is to look these up via API/SQL at script start.

### D. `fix_role_assignments.py` unlocks but doesn't re-lock

The script unlocks every activity to attach role-assignments and exits without re-locking. DPRs reject against DRAFT activities. We add a manual lock loop in §6.4. A cleaner fix is to put the re-lock at the end of the script.

### E. Frontend not containerized

`pnpm dev` on the host is fine for demo; production would want a Next.js standalone build + nginx + a `frontend` service in compose.

---

## 14. Branch + repo

All artefacts (Dockerfile, docker-compose update, this doc, Liquibase YAML quoting fixes, script patches) live on **`khasab-demo-ready-2026-05-24`**.
PR template: https://github.com/Hemendra1990/bipros-eppm/pull/new/khasab-demo-ready-2026-05-24
