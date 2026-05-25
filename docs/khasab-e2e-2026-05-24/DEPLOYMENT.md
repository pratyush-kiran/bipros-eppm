# Deployment guide — dev + prod

Does restarting the backend wipe the Khasab data? **No.** This doc explains why, and how to deploy the demo dataset to a fresh dev or prod environment.

---

## 1. Restart behavior (TL;DR)

| What you do | What happens to data |
|---|---|
| `pkill -f bipros-api` then restart backend | **Nothing wiped.** Schemas stay, all rows stay. Idempotent seeders check-and-skip. |
| Restart with `ddl-auto: update` (dev default) | Hibernate ADDS new columns/tables for any new `@Entity`. **Never drops.** Existing data intact. |
| Restart with `ddl-auto: validate` (prod default) | Just validates schema matches entities. No data change. Boot fails if mismatch — fix via Liquibase. |
| Restart with `DDL_AUTO=create-drop` | **DESTRUCTIVE.** Drops + recreates all schemas. Don't use unless you want to start over. |
| Restart with `DATABASE_URL` pointing at empty DB | DDL is built from entities (dev) or migration runs (prod), then admin/roles seeded. No Khasab data. |
| Run any script in `scripts/` (e.g. `wipe-transactional.sql`, `rebuild_demo.py`) | **Destructive — but only when you explicitly run them.** |

---

## 2. What runs automatically at startup

These are idempotent — they check before they write — so restart is always safe:

| Seeder | What it does | Idempotency |
|---|---|---|
| `DataSeeder` (`@Profile dev,seed`) | Roles (22), admin user, calendar, currency, EVM settings | Skip-if-exists per row |
| `ResourceTypeSeeder` | MANPOWER, EQUIPMENT, MATERIAL types | Skip-if-exists |
| `*RateMasterBackfillSeeder` | Links existing resources to rate masters | No-op if already linked |
| `ManpowerMasterSeeder` | Categories, employment types, skills, nationalities | Skip-if-exists |
| `SubContractorMasterSeeder` | Sub-contractor master table | Skip-if-exists |
| `DefaultFolderStartupBackfill` | Default document folder per project | Idempotent scan |
| `WbsTemplateService` | Default WBS templates | Skip-if-exists |
| `IntegrationConfigSeeder` | PFMS / GeM / CPPP / GSTN / PARIVESH configs | Skip-if-exists |
| `ChecklistTemplateSeeder` | PRE_CONCRETE / EXCAVATION / SHUTTERING templates | Skip-if-exists |
| `FormulaMasterSeeder` | 80+ formula definitions | Skip-if-exists |
| `OmanDemoManpowerRateMasterSeeder` | Oman demo enrichment | Skip-if-exists (only fires when seed profile active) |
| **My flags in `application.yml`** | `bipros.dbs.backfill.enabled: false`, `bipros.seeder.project-team.enabled: false`, `bipros.backfill.legacy-daily-output.enabled: false` | Explicit OFF; pre-empts those backfills |

**Bottom line:** restart is safe. Your Khasab project, 3,431 DPRs, 39 activities, 8 risks, 60 weather rows — all persist.

---

## 3. Deploying to a fresh DEV environment

### Option A — pg_restore (fastest, 60 seconds)
```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin

# 1. Create empty DB
PGPASSWORD=bipros_dev $PG/psql -h <host> -U bipros -d postgres -c \
  "CREATE DATABASE bipros OWNER bipros;"

# 2. Restore
PGPASSWORD=bipros_dev $PG/pg_restore -h <host> -U bipros -d bipros \
  --clean --if-exists \
  bipros-FINAL-backup.dump
```
After step 2: schemas, masters, KHASAB-2026 project, all 3,431 DPRs, calendar, weather, issues, risks — everything restored.

Now start the backend:
```bash
cd /path/to/bipros-eppm
export BIPROS_AI_KEK="<your-base64-KEK>"
export BIPROS_AI_ENABLED=true
export DB_POOL_MAX=60       # for heavy load
mvn -f backend/bipros-api/pom.xml -am -Dmaven.test.skip=true spring-boot:run
```

That's it. Demo-ready in 60 seconds.

### Option B — run all scripts from scratch (1.5–2 hours)
Follow [`RUNBOOK.md`](RUNBOOK.md) Path D top-to-bottom. Use this if you want the full audit trail or to adapt the data for a different project.

### Option C — keep your existing dev data, just add Khasab
Don't use `--clean` on pg_restore. Restore into a separate database:
```bash
# Create separate DB
PGPASSWORD=bipros_dev psql -h <host> -U bipros -d postgres -c \
  "CREATE DATABASE bipros_khasab_demo OWNER bipros;"

PGPASSWORD=bipros_dev pg_restore -h <host> -U bipros -d bipros_khasab_demo \
  bipros-FINAL-backup.dump
```
Then point a separate backend instance at it with `DATABASE_URL=jdbc:postgresql://<host>:5432/bipros_khasab_demo`.

---

## 4. Deploying to PRODUCTION

Production has different rules. The dev `application.yml` flags I added are **not appropriate for prod** because prod doesn't use the in-app seeders at all — it uses Liquibase migrations.

### What changes in prod mode

Activated by `SPRING_PROFILES_ACTIVE=prod`. From `application.yml`:

| Setting | Dev | Prod |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `update` | `validate` |
| `spring.liquibase.enabled` | `false` | `true` |
| `logging.level.com.bipros` | `DEBUG` | `INFO` |
| `bipros.errors.include-detail` | `true` (leaks stack to clients) | `false` |
| Required env vars | DATABASE_URL, JWT_SECRET (optional), CORS_ALLOWED_ORIGINS | All three **required** + ANTHROPIC_API_KEY, BIPROS_AI_KEK |

### Prod deployment steps

#### Phase 1 — schema only (initial deploy)
```bash
# 1. Create empty bipros DB on the prod Postgres (managed RDS / Azure DB / etc.)
# 2. Set env vars:
export DATABASE_URL=jdbc:postgresql://prod-db:5432/bipros
export DB_USERNAME=bipros_prod
export DB_PASSWORD=<from-secrets-manager>
export JWT_SECRET=<32-byte-random>
export CORS_ALLOWED_ORIGINS=https://app.bipros.io
export BIPROS_AI_KEK=<base64-KEK>
export BIPROS_AI_ENABLED=true
export ANTHROPIC_API_KEY=<anthropic-key>
export SPRING_PROFILES_ACTIVE=prod

# 3. Boot — Liquibase runs migrations from backend/bipros-api/src/main/resources/db/changelog/
java -jar bipros-api-0.1.0-SNAPSHOT.jar
```
At this point: schema exists, admin user seeded, no project data.

#### Phase 2 — seed demo data (one-off)
**DO NOT** copy the dev `bipros-FINAL-backup.dump` directly to prod — it embeds dev-only IDs, demo users, demo settings.

Instead, use the scripts to recreate from source data:
```bash
# Same admin token flow as dev, but against prod URL
TOKEN=$(curl -sS -X POST https://api.bipros.io/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<prod-admin-pw>"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')

# Override BASE in the scripts
export BASE=https://api.bipros.io
# Then run the rebuild chain (Path D from RUNBOOK)
```

#### Phase 3 — production hardening
| Check | Action |
|---|---|
| Default admin password | **Change immediately.** `admin / admin123` is dev-only. |
| `application.yml` disable flags | Review. The 3 flags I added (`dbs.backfill`, `seeder.project-team`, `backfill.legacy-daily-output`) are tuned for the E2E test, not prod. Re-enable as appropriate. |
| `BIPROS_AI_KEK` rotation | Use a managed-secret rotation policy. Without it, AI returns empty. |
| `JWT_SECRET` | 32+ random bytes; never reuse the dev default. |
| Liquibase changelog | Verify it's complete — any entity added in dev with `ddl-auto: update` may not have a corresponding migration. |
| Backups | Schedule daily pg_dump or RDS automated backups; verify restore quarterly. |
| Read replicas | Configure HikariCP with `maximum-pool-size` per replica + connection-test settings. |

---

## 5. Per-environment quick reference

### Dev (your laptop right now)
- DB: native Postgres.app at `127.0.0.1:5432`
- Profile: `dev` (default)
- DDL: `update`
- AI KEK: `Vd/RdHKwlLA1vFuDVUr/ou0CMHAsha99Cfi8UXzXUlA=` (already set)
- Restart command: `pkill -f bipros-api && (cd backend && mvn -f bipros-api/pom.xml -am -Dmaven.test.skip=true spring-boot:run) &`
- After restart: Khasab data persists.

### Staging (recommended)
- DB: separate Postgres instance (managed)
- Profile: `prod` BUT with `bipros.errors.include-detail: true` for debugging
- DDL: `validate`
- AI: enabled with separate KEK
- Restore source: `bipros-FINAL-backup.dump` (dev-equivalent OK in staging)

### Production
- DB: managed Postgres (RDS / Cloud SQL / Azure) with read replicas
- Profile: `prod` strict
- DDL: `validate` (Liquibase is source of truth)
- AI: enabled with rotation-tracked KEK
- Restore source: Either Liquibase + script-driven seed, OR a STAGING backup (NOT a dev backup)

---

## 6. What to test after restart (smoke checklist)

```bash
# 1. Health
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}

# 2. Admin login
curl -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq .data.accessToken

# 3. Project still there
PGPASSWORD=bipros_dev psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT code, name, calendar_id FROM project.projects WHERE code='KHASAB-2026';"

# 4. DPR count unchanged
PGPASSWORD=bipros_dev psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT COUNT(*) AS dprs FROM project.daily_progress_reports;"
# Expected: 3431

# 5. AI returns text (not empty)
TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' \
  | jq -r .data.accessToken)
PROJECT_ID=$(PGPASSWORD=bipros_dev psql -h 127.0.0.1 -U bipros -d bipros -At -c \
  "SELECT id FROM project.projects WHERE code='KHASAB-2026'")
curl -X POST http://localhost:8080/v1/ai/chat \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"message\":\"How many DPRs?\",\"projectId\":\"$PROJECT_ID\"}" | jq .data.text
```

If all 5 pass, the environment is demo-ready.

---

## 7. Backup + restore playbook

### Take a backup (any time)
```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin
TS=$(date +%Y%m%d-%H%M%S)
PGPASSWORD=bipros_dev $PG/pg_dump -h 127.0.0.1 -U bipros -F c \
  -f /tmp/bipros-$TS.dump bipros
ls -lh /tmp/bipros-$TS.dump
```

### Restore a backup
```bash
# DESTRUCTIVE — wipes current bipros DB
PGPASSWORD=bipros_dev $PG/pg_restore -h 127.0.0.1 -U bipros -d bipros \
  --clean --if-exists \
  /tmp/bipros-<TS>.dump
```

### Restore to a NEW DB (non-destructive)
```bash
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d postgres -c \
  "CREATE DATABASE bipros_test OWNER bipros;"
PGPASSWORD=bipros_dev $PG/pg_restore -h 127.0.0.1 -U bipros -d bipros_test \
  /tmp/bipros-<TS>.dump
```
