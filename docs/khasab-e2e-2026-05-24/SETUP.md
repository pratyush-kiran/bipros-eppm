# Khasab Demo — Single Source of Truth

**One file. Everything you need to deploy the Khasab Road Project 2026 demo to any environment.**

If you only read one document, read this one. The other files (`RUNBOOK.md`, `DEPLOYMENT.md`, `CALENDAR-AND-DASHBOARD.md`, `INDEX.md`) are deeper references; this consolidates the essentials.

---

## Table of contents

1. [What you get](#1-what-you-get)
2. [Prerequisites](#2-prerequisites)
3. [FASTEST path — restore from backup (60 seconds)](#3-fastest-path--restore-from-backup-60-seconds)
4. [FULL REBUILD path — from source data (~2 hours)](#4-full-rebuild-path--from-source-data-2-hours)
5. [Calendar setup — required for "Run Schedule" to work](#5-calendar-setup--required-for-run-schedule-to-work)
6. [Dashboard population — Site Conditions, Timeline, Issues, Alerts](#6-dashboard-population--site-conditions-timeline-issues-alerts)
7. [Restart safety — what survives, what doesn't](#7-restart-safety--what-survives-what-doesnt)
8. [Production differences](#8-production-differences)
9. [Smoke test (5 commands)](#9-smoke-test-5-commands)
10. [Backup + restore](#10-backup--restore)
11. [Troubleshooting](#11-troubleshooting)
12. [Final state — what should be there](#12-final-state--what-should-be-there)

---

## 1. What you get

A **Khasab Road Project 2026** demo with:

| Item | Count |
|---|---:|
| Project | 1 (KHASAB-2026) |
| Users (PM, CM, 2 SE, 12 Supervisors) | 16 |
| Project team members with reports-to chain | 16 |
| WBS nodes | 22 |
| Activities (real master sheet names: "Camp work", "Blasting", "Mechanical Excavation"…) | 33 + 6 milestones = **39** |
| DPRs across Jan–Mar 2026 | **3,431** |
| Role-assignments (planned manpower/equipment/material) | 229 |
| BOQ items (real descriptions) | 30 |
| Material Consumption Logs | 76 |
| Productivity Norms (tuned) | 66 |
| Risk register entries | 8 |
| Weather rows (Khasab climate) | 60 |
| DPR Issues (Open + Resolved) | 6 |
| Calendar | Oman 5-day Construction Calendar (Sun–Thu) linked everywhere |
| EVM Budget at Completion | ₹5 Crore |
| Total project actual cost | ~₹1.26 Crore |
| Avg activity % complete | ~95% |

---

## 2. Prerequisites

### Software (all environments)

| Tool | Version | Notes |
|---|---|---|
| **PostgreSQL** | 14+ (16+ recommended) | Native (Postgres.app on Mac) or managed (RDS, Cloud SQL, Azure DB) |
| **Java** | 23 (GraalVM CE works) | For backend |
| **Maven** | 3.9+ | For backend build |
| **Node.js** | 22+ + **pnpm 9+** | For frontend |
| **Python** | 3.11+ with `openpyxl` + `pandas` + `python-dateutil` | For import scripts |
| **`psql`** + **`pg_dump`** + **`pg_restore`** | Same version as your Postgres | For backups/restores |

### Python venv (one-time setup)
```bash
python3 -m venv /tmp/xlsx_venv
/tmp/xlsx_venv/bin/pip install --quiet openpyxl pandas python-dateutil
```

### Environment variables (set BEFORE starting backend)

| Var | Required | Value | Purpose |
|---|---|---|---|
| `DATABASE_URL` | yes | `jdbc:postgresql://<host>:5432/bipros` | DB JDBC URL |
| `DB_USERNAME` | yes | `bipros` (dev) / your prod user | DB user |
| `DB_PASSWORD` | yes | `bipros_dev` (dev) / secret | DB password |
| `BIPROS_AI_KEK` | yes if you want AI | base64 string | Decrypts the stored LLM API key. Without it, `/v1/ai/chat` returns empty text. |
| `BIPROS_AI_ENABLED` | yes for AI | `true` | Master AI switch |
| `JWT_SECRET` | prod only | 32+ random bytes | JWT signing |
| `CORS_ALLOWED_ORIGINS` | prod | `https://app.bipros.io,…` | Frontend origins |
| `DB_POOL_MAX` | recommended | `60` (during heavy import) / `20` (steady) | HikariCP pool size |
| `SPRING_PROFILES_ACTIVE` | prod | `prod` | Switches to validate + Liquibase + INFO logging |
| `ANTHROPIC_API_KEY` | prod with AI | your key | Direct vision/insight calls |

### Files in this directory

```
docs/khasab-e2e-2026-05-24/
├── SETUP.md                              ← THIS FILE (your one-stop guide)
├── bipros-FINAL-backup.dump              ← 1.6 MB pg_dump snapshot of demo-ready state
├── source-data/                          ← Original Excel files
│   ├── 1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx
│   └── 3. Supervisor-Engineer-CM-PM DBS (2).xlsx
├── scripts/                              ← 20 Python + SQL scripts (rebuild from source)
├── state/                                ← Runtime ID registry (project, users, WBS, activities)
├── screenshots/                          ← 20+ proof-of-state browser captures
├── wipe-transactional.sql                ← Destructive cleanup (used by Path B)
├── RUNBOOK.md                            ← Detailed rebuild paths (A/B/C/D)
├── DEPLOYMENT.md                         ← Dev/staging/prod deep-dive
├── CALENDAR-AND-DASHBOARD.md             ← Calendar wiring + dashboard mechanics
├── INDEX.md                              ← File-by-file annotated index
├── 2026-05-24-…design.md / …import.md    ← Brainstorm spec + implementation plan
├── dpr-dbs-e2e-execution-log-…html       ← Consolidated visual run report (49 KB)
├── dpr-dbs-e2e-test-execution-log-…md    ← Markdown narrative log
├── khasab-dpr-2026-05-24.csv             ← 3,431-row flat DPR export
├── khasab-dpr-2026-05-24.xlsx            ← 4-sheet pivoted DPR export
├── khasab-dpr-parsed.json                ← Normalized DPR JSON (intermediate)
├── khasab-dpr-validation_report.md       ← Parser validation report
└── ai-ground-truth.json                  ← 50 SQL-precomputed AI test answers
```

---

## 3. FASTEST path — restore from backup (60 seconds)

Use this for **demos, presentations, or any time you just want the data**.

### 3.1 Create the DB (if it doesn't exist)
```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin   # adjust for your install
PGPASSWORD=<root-pw> $PG/psql -h <host> -U postgres -c "
  CREATE USER bipros WITH PASSWORD 'bipros_dev';
  CREATE DATABASE bipros OWNER bipros;
"
```

### 3.2 Restore the backup
```bash
PGPASSWORD=bipros_dev $PG/pg_restore -h <host> -U bipros -d bipros \
  --clean --if-exists \
  bipros-FINAL-backup.dump
```
This wipes any existing `bipros` content and replaces it with the demo-ready state. Takes ~30 seconds.

### 3.3 Start backend + frontend
```bash
# Backend
cd /path/to/bipros-eppm
export BIPROS_AI_KEK="Vd/RdHKwlLA1vFuDVUr/ou0CMHAsha99Cfi8UXzXUlA="
export BIPROS_AI_ENABLED=true
export DB_POOL_MAX=60
mvn -f backend/bipros-api/pom.xml -am -Dmaven.test.skip=true spring-boot:run &

# Wait for health
until curl -sf http://localhost:8080/actuator/health > /dev/null; do sleep 3; done

# Frontend (separate terminal)
(cd frontend && pnpm dev) &
until curl -sf http://localhost:3000 > /dev/null; do sleep 3; done
```

### 3.4 Verify
Open `http://localhost:3000`, login as `admin / admin123`, click **Projects** → **Khasab Road Project 2026**.

Expected: Overall Progress 95%, Budget Utilised ₹5 Cr, 3 of 33 Tasks Completed, Project Timeline populated with 6 future milestones, Site Conditions tile showing temp/wind/rain.

**Done.** Run the [smoke test](#9-smoke-test-5-commands) if you want belt-and-suspenders.

---

## 4. FULL REBUILD path — from source data (~2 hours)

Use this when you want the **whole audit trail**, or you need to **adapt the data for a different project** (different supervisors / activities / dates).

### 4.1 Wipe + restore variant tables
```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin

# Backup current state first (safety net)
PGPASSWORD=bipros_dev $PG/pg_dump -h 127.0.0.1 -U bipros -F c \
  -f /tmp/bipros-pre-rebuild-$(date +%Y%m%d-%H%M%S).dump bipros

# Kill backend BEFORE wiping (avoids in-flight writes during TRUNCATE)
pkill -f bipros-api || true
sleep 3

# Wipe transactional data
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros \
  -v ON_ERROR_STOP=1 -f wipe-transactional.sql

# Restore variant tables (cascade-deleted by the wipe — needed for role-assignments)
for tbl in profiles profile_permissions manpower_role_rates \
           equipment_role_variants material_role_variants; do
  PGPASSWORD=bipros_dev $PG/pg_restore -h 127.0.0.1 -U bipros -d bipros \
    --data-only --table=$tbl bipros-FINAL-backup.dump
done

# Re-attach admin to ADMIN role
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -c "
INSERT INTO public.user_roles (id, created_at, updated_at, role_id, user_id, version)
SELECT gen_random_uuid(), now(), now(), r.id, u.id, 0
FROM public.users u, public.roles r
WHERE u.username = 'admin' AND r.name = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;
"
```

### 4.2 Set application.yml flags (one-time edit, in `backend/bipros-api/src/main/resources/application.yml`)

Inside the existing `bipros:` block, add:
```yaml
bipros:
  dbs:
    backfill:
      enabled: false
  seeder:
    project-team:
      enabled: false
  backfill:
    legacy-daily-output:
      enabled: false
```
These prevent backfill seeders from running into wiped data during boot.

### 4.3 Start backend + frontend
Same commands as Path 3.3 above. Wait for `{"status":"UP"}` before proceeding.

### 4.4 Run the scripts in order
```bash
# Get admin token
TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
echo "$TOKEN" > /tmp/admin-token.txt
mkdir -p /tmp/khasab

# 1. Parse source data
/tmp/xlsx_venv/bin/python scripts/parse_khasab.py            # Daily Excel → JSON
/tmp/xlsx_venv/bin/python scripts/parse_master_sheet.py      # DBS workbook DPR sheet
/tmp/xlsx_venv/bin/python scripts/analyze_resource_demand.py # Per-activity resource demand

# 2. Create skeleton
python3 scripts/create_khasab_users.py                       # 16 users
python3 scripts/create_project_team.py                       # Reports-to chain
python3 scripts/create_wbs_and_activities.py                 # 22 WBS nodes
python3 scripts/rebuild_demo.py                              # Project + 33 activities with real master names
python3 scripts/fix_role_assignments.py                      # 229 planned-resource rows

# 3. Import DPRs (~30 min with pool=60, 4 workers)
nohup python3 scripts/import_khasab_dprs.py all > /tmp/dpr-import.log 2>&1 &
tail -f /tmp/dpr-import.log    # watch progress; Ctrl-C the tail (not the import)

# Wait for it to finish, then:

# 4. Cost + EVM + BOQ + MCLs + Productivity Norms + DBS recompute
python3 scripts/fix_demo_v2.py

# 5. Tune productivity norms so Capacity Utilization shows demo-realistic %
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros \
  -f scripts/tune_productivity_norms.sql

# 6. Rename activities with the REAL master names (from Code sheet)
/tmp/xlsx_venv/bin/python scripts/rename_activities_add_risks_weather.py

# 7. Add weather + risks
python3 scripts/add_weather_risks.py
python3 scripts/create_risks.py

# 8. Calendar + milestones + DPR issues (the dashboard population)
python3 scripts/populate_dashboard.py

# 9. Verify
python3 scripts/check_capacity.py
```

### 4.5 Verify state
See [section 12 — Final state](#12-final-state--what-should-be-there).

---

## 5. Calendar setup — required for "Run Schedule" to work

If "Run Schedule" on the Activities tab says **"Calendar not set"**:

### Quick fix (any environment)
```sql
-- 1. Find an existing calendar
SELECT id, name, calendar_type FROM scheduling.calendars;
-- Typical result: "Standard" + "Oman 5-day Construction Calendar (Sun–Thu)"

-- 2. Link to project + all activities (adjust the UUID + project code)
UPDATE project.projects
SET calendar_id = (SELECT id FROM scheduling.calendars WHERE name LIKE 'Oman%' LIMIT 1)
WHERE code = 'KHASAB-2026';

UPDATE activity.activities
SET calendar_id = (SELECT id FROM scheduling.calendars WHERE name LIKE 'Oman%' LIMIT 1)
WHERE project_id = (SELECT id FROM project.projects WHERE code = 'KHASAB-2026');
```

### Create your own calendar
```sql
INSERT INTO scheduling.calendars
  (id, created_at, updated_at, calendar_type, code, name, is_default,
   standard_work_days_per_week, standard_work_hours_per_day)
VALUES
  (gen_random_uuid(), now(), now(), 'PROJECT', 'MY-CAL',
   'My Project Calendar', false, 5, 8)
RETURNING id;

-- Then insert work-week pattern + holidays into:
--   scheduling.calendar_work_weeks    (per day-of-week working hours)
--   scheduling.calendar_exceptions    (holidays / non-working days)
```

### Where the calendar is referenced
- **Schema:** `project.projects.calendar_id`, `activity.activities.calendar_id`
- **Master:** `scheduling.calendars`, `scheduling.calendar_work_weeks`, `scheduling.calendar_exceptions`
- **API:** `POST /v1/projects/{pid}/schedule` (the Run Schedule button)
- **Code:** `ScheduleController` (bipros-scheduling) → `CalendarService` / `WorkingDayResolver` (bipros-calendar)
- **UI:** Admin → Master Data → Calendars (`/admin/calendars`); Activity detail drawer → Calendar field

---

## 6. Dashboard population — Site Conditions, Timeline, Issues, Alerts

If Project Overview tiles are blank, populate via SQL:

### 6.1 Project Timeline Preview ("No scheduled phases")
**Cause:** No activities of type `START_MILESTONE` / `FINISH_MILESTONE` with `planned_finish_date >= CURRENT_DATE`.

**Fix:** Insert future-dated milestone activities (see `scripts/populate_dashboard.py` step 3).

### 6.2 Site Conditions (TEMP / WIND / RAIN all blank)
**Cause:** No rows in `project.daily_weather` for the project.

**Fix:**
```sql
INSERT INTO project.daily_weather (id, created_at, updated_at, project_id, log_date,
  weather_condition, temp_min_c, temp_max_c, wind_kmh, rainfall_mm, working_hours)
VALUES (gen_random_uuid(), now(), now(), '<PROJECT_ID>', CURRENT_DATE,
  'CLEAR', 22, 32, 12, 0, 8);
```
The UI reads the LATEST row by `log_date DESC LIMIT 1`. Add a row for today so the tile populates.

### 6.3 Open Issues + Active Alerts (0)
**Cause:** No rows in `project.dpr_issues`.

**Fix:**
```sql
INSERT INTO project.dpr_issues
  (id, created_at, updated_at, version, project_id, dpr_id, activity_id, activity_name,
   report_date, opened_at, title, description, category, severity, status,
   supervisor_user_id, supervisor_name)
SELECT gen_random_uuid(), now(), now(), 0, '<PROJECT_ID>', d.id, d.activity_id,
  a.name, d.report_date, d.report_date::timestamptz,
  'Equipment breakdown — example', 'Example issue text',
  'EQUIPMENT_BREAKDOWN', 'HIGH', 'OPEN',
  d.supervisor_user_id, u.username
FROM project.daily_progress_reports d
  JOIN activity.activities a ON a.id = d.activity_id
  LEFT JOIN public.users u ON u.id = d.supervisor_user_id
WHERE d.project_id = '<PROJECT_ID>' LIMIT 1;
```

### Valid enums (gotchas)
| Column | Valid values |
|---|---|
| `dpr_issues.category` | `SAFETY`, `QUALITY`, `MATERIAL_SHORTAGE`, `EQUIPMENT_BREAKDOWN`, `MANPOWER_SHORTAGE`, `WEATHER`, `DESIGN_CHANGE`, `LAND_ACCESS`, `UTILITY_CLASH`, `PERMIT_DELAY`, `SUBCONTRACTOR`, `ENVIRONMENTAL`, `OTHER` |
| `dpr_issues.severity` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `dpr_issues.status` | `OPEN`, `IN_PROGRESS`, `BLOCKED`, `RESOLVED`, `CLOSED`, `CANCELLED` |
| `risks.status` | `IDENTIFIED`, `ANALYZING`, `MITIGATING`, `RESOLVED`, `CLOSED`, `ACCEPTED`, `REJECTED`, `REALISED` |
| `risks.category` | Code from `risk.risk_category_master` (e.g. `MW-GENERIC`, `HSE-GENERIC`, `CG-PROCUREMENT-LEAD-TIME`) — NOT free text |
| `activity.activity_type` | `TASK_DEPENDENT`, `RESOURCE_DEPENDENT`, `LEVEL_OF_EFFORT`, `START_MILESTONE`, `FINISH_MILESTONE`, `WBS_SUMMARY` |
| `activity.duration_type` | `FIXED_DURATION_AND_UNITS`, `FIXED_DURATION_AND_UNITS_PER_TIME`, `FIXED_UNITS`, `FIXED_UNITS_PER_TIME` |
| `activity.percent_complete_type` | `PHYSICAL`, `DURATION`, `UNITS` |
| `activity.status` | `NOT_STARTED`, `IN_PROGRESS`, `COMPLETED` |

---

## 7. Restart safety — what survives, what doesn't

| Action | Data fate |
|---|---|
| `pkill -f bipros-api && mvn spring-boot:run` | **All data persists.** Idempotent seeders skip-if-exists. |
| Boot with `ddl-auto: update` (dev default) | Hibernate adds new columns/tables. **Never drops.** |
| Boot with `ddl-auto: validate` (prod default) | No DDL. Just validates schema matches entities. Boot fails if mismatch. |
| Boot with `DDL_AUTO=create-drop` | **DESTRUCTIVE.** Drops + recreates everything. |
| Boot against empty DB | Schema built (dev) or Liquibase runs (prod). Admin seeded. No Khasab data. |
| Run `wipe-transactional.sql` | **DESTRUCTIVE.** Only fires if explicitly invoked. |
| Run any `scripts/rebuild_*.py` | **DESTRUCTIVE.** Only when explicitly invoked. |

**TL;DR:** Restart never wipes. Only explicit destructive commands do.

---

## 8. Production differences

`SPRING_PROFILES_ACTIVE=prod` changes:

| Setting | Dev | Prod |
|---|---|---|
| `ddl-auto` | `update` | `validate` |
| `liquibase.enabled` | `false` | `true` |
| Logging level | `DEBUG` | `INFO` |
| Error detail leakage | `true` (stack visible) | `false` |
| Default admin password | `admin / admin123` | **MUST change** |
| `JWT_SECRET` | dev default | **MUST set** (32+ random bytes) |
| `BIPROS_AI_KEK` | reused | Rotate via secrets manager |
| Disable flags in `application.yml` | Demo-tuned (off) | Review per use case |

### Prod deployment sequence
1. Provision empty Postgres DB (managed: RDS / Cloud SQL / Azure DB).
2. Set all required env vars (see [Prerequisites](#2-prerequisites)).
3. Boot backend — Liquibase migrations run from `backend/bipros-api/src/main/resources/db/changelog/`.
4. Login as admin, **change password immediately**.
5. Seed demo data either by:
   - Running the scripts against the prod URL (don't restore the dev `.dump` to prod — it embeds dev IDs)
   - Or curated production seed data scripts

---

## 9. Smoke test (5 commands)

Copy-paste after any restart or fresh deploy:

```bash
# 1. Health
curl -s http://localhost:8080/actuator/health | jq .
# Expected: {"status":"UP"}

# 2. Admin login
TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r .data.accessToken)
echo "Token: ${#TOKEN} chars"

# 3. Project + DPR count
PG=/Applications/Postgres.app/Contents/Versions/latest/bin
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT 'project' AS m, code FROM project.projects WHERE code='KHASAB-2026'
UNION ALL SELECT 'dprs', COUNT(*)::text FROM project.daily_progress_reports
UNION ALL SELECT 'activities', COUNT(*)::text FROM activity.activities
UNION ALL SELECT 'risks', COUNT(*)::text FROM risk.risks
UNION ALL SELECT 'role_assignments', COUNT(*)::text FROM resource.resource_assignments;
"
# Expected: project=KHASAB-2026, dprs≥3431, activities=39, risks=8, role_assignments=229

# 4. Calendar wired
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT calendar_id IS NOT NULL AS project_cal_set FROM project.projects WHERE code='KHASAB-2026';
"
# Expected: t (true)

# 5. AI returns text
PROJECT_ID=$(PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -At -c \
  "SELECT id FROM project.projects WHERE code='KHASAB-2026'")
curl -sS -X POST http://localhost:8080/v1/ai/chat \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"message\":\"How many DPRs does this project have?\",\"projectId\":\"$PROJECT_ID\"}" \
  | jq -r .data.text | head -c 200
# Expected: non-empty text mentioning ~3431 DPRs (not empty string)
```

If all 5 pass, you're demo-ready.

---

## 10. Backup + restore

### Take a backup
```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin
PGPASSWORD=bipros_dev $PG/pg_dump -h 127.0.0.1 -U bipros -F c \
  -f /tmp/bipros-$(date +%Y%m%d-%H%M%S).dump bipros
ls -lh /tmp/bipros-*.dump
```

### Restore (DESTRUCTIVE — overwrites current DB)
```bash
PGPASSWORD=bipros_dev $PG/pg_restore -h 127.0.0.1 -U bipros -d bipros \
  --clean --if-exists \
  bipros-FINAL-backup.dump
```

### Restore into a NEW DB (non-destructive)
```bash
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d postgres -c \
  "CREATE DATABASE bipros_test OWNER bipros;"
PGPASSWORD=bipros_dev $PG/pg_restore -h 127.0.0.1 -U bipros -d bipros_test \
  bipros-FINAL-backup.dump
# Then point a separate backend at it via DATABASE_URL=jdbc:postgresql://...bipros_test
```

---

## 11. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Backend starts but `/v1/ai/chat` returns empty text | `BIPROS_AI_KEK` not set | Set env var, restart |
| "Run Schedule" → "Calendar not set" | `project.calendar_id` or `activity.calendar_id` NULL | Run [Calendar setup](#5-calendar-setup--required-for-run-schedule-to-work) |
| Project Overview shows 0% everything | Activities have no `percent_complete` or `planned_start_date` | Run `scripts/fix_demo_v2.py` |
| Capacity Utilization shows "no norm grey" | Activities not linked to `work_activity_id` OR norms missing | Run `scripts/fix_role_assignments.py` + `scripts/create_norms_only.py` |
| Capacity Utilization % is 1000%+ | Norms too conservative | Run `scripts/tune_productivity_norms.sql` |
| Admin can't login after wipe | `user_roles` truncated; `profile_permissions` cascade-deleted | Restore `profiles` + `profile_permissions` from backup; re-attach admin → ADMIN role |
| DPR import is very slow (<0.5 DPR/s) | HikariCP pool exhausted | Set `DB_POOL_MAX=60`, restart backend |
| `psql` not found | Postgres.app not on PATH | `export PATH=/Applications/Postgres.app/Contents/Versions/latest/bin:$PATH` |
| Activity detail drawer shows empty "Manpower Requirements" | No role-assignments | Run `scripts/fix_role_assignments.py` |
| Site Conditions tile shows "Latest reading unavailable" | No `project.daily_weather` rows for today | Run `scripts/populate_dashboard.py` |
| "Equipment governs this activity" yellow notes everywhere | SERIES norm combination (informational, not an error) | Leave SERIES; the notes are explanations. PARALLEL gave worse numbers. |
| `pg_restore: violates check constraint` | Restoring into incompatible schema | Use a fresh `bipros` DB or `--clean --if-exists` |
| Activities visible but DPRs rejected with `ACTIVITY_DRAFT_DPR_REJECTED` | Activities are in DRAFT, not LOCKED | `POST /v1/projects/{pid}/activities/{aid}/lock` for each |
| DPR list groups show old names ("Khasab 1", "Khasab 1.1") even though Activities tab shows the real names | `daily_progress_reports.activity_name` is a denormalized snapshot. It drifts whenever an activity is renamed by a path that bypasses `ActivityRenameDprSyncListener` — raw SQL on `activity.activities`, a Liquibase migration, or an older `rename_activities_add_risks_weather.py`. | `psql -f scripts/fix_dpr_activity_name_drift.sql` — idempotent, safe to run any time. API renames (PUT /v1/projects/{pid}/activities/{aid}) auto-sync via the listener, no fix-up needed. |

---

## 12. Final state — what should be there

Run this in `psql` to verify. All numbers should match:

```sql
SELECT
  (SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026')           AS project,            -- 1
  (SELECT COUNT(*) FROM project.daily_progress_reports
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS dprs,           -- 3431
  (SELECT COUNT(*) FROM activity.activities
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS activities,     -- 39 (33 tasks + 6 milestones)
  (SELECT COUNT(*) FROM activity.activities
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')
     AND activity_type IN ('START_MILESTONE','FINISH_MILESTONE'))             AS milestones,       -- 6
  (SELECT COUNT(*) FROM project.wbs_nodes
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS wbs_nodes,      -- 22
  (SELECT COUNT(*) FROM resource.resource_assignments
   WHERE activity_id IN (SELECT id FROM activity.activities
                         WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')))
                                                                              AS role_assignments,  -- 229
  (SELECT COUNT(*) FROM project.boq_items
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS boq_items,      -- 30
  (SELECT COUNT(*) FROM resource.material_consumption_logs
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS mcls,           -- 76
  (SELECT COUNT(*) FROM resource.productivity_norms pn
   JOIN resource.work_activities wa ON wa.id=pn.work_activity_id
   WHERE wa.id IN (SELECT work_activity_id FROM activity.activities
                   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')))
                                                                              AS norms,             -- 66
  (SELECT COUNT(*) FROM risk.risks
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS risks,          -- 8
  (SELECT COUNT(*) FROM project.daily_weather
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS weather,        -- 60+
  (SELECT COUNT(*) FROM project.dpr_issues
   WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026'))  AS dpr_issues,     -- 6
  (SELECT calendar_id IS NOT NULL FROM project.projects
   WHERE code='KHASAB-2026')                                                  AS calendar_linked,   -- true
  (SELECT COUNT(*) FROM public.users)                                         AS users;             -- 19 (admin + 2 demo + 16 khasab)
```

UI verification:
- `/projects/<id>` → Overall Progress ~95%, Budget ₹5 Cr, Project Timeline with milestones, Site Conditions with temp/wind/rain
- `/projects/<id>/activities` → real names like "Camp work", "Blasting", "Mechanical Excavation"
- Click activity → drawer shows populated **Manpower Requirements** + **Equipment Requirements**
- `/projects/<id>/capacity-utilization` → set date to 2026-01-24 → 2026-03-29, Total Eff ≈ 88.6%
- `/projects/<id>/risks` → 8 entries
- `/projects/<id>/dpr` → 3,431 records, grouped by real activity names (e.g. "Mechanical Excavation", "Blasting"), NOT placeholder codes

Also verify no denormalized-name drift:
```sql
SELECT COUNT(*) AS stale_dpr_name_rows
FROM project.daily_progress_reports d
JOIN activity.activities a ON a.id = d.activity_id
WHERE d.activity_name IS DISTINCT FROM a.name;
-- expected: 0
```
If non-zero, run `scripts/fix_dpr_activity_name_drift.sql`.

---

## Branch + repo

Latest pushed to: **`khasab-demo-ready-2026-05-24`**
PR template: https://github.com/Hemendra1990/bipros-eppm/pull/new/khasab-demo-ready-2026-05-24

## Questions? Read the deeper docs

- `RUNBOOK.md` — full 4-path reproduction guide
- `DEPLOYMENT.md` — dev/staging/prod deep-dive
- `CALENDAR-AND-DASHBOARD.md` — every code path the calendar flows through
- `INDEX.md` — file-by-file annotated index
- `dpr-dbs-e2e-execution-log-2026-05-24.html` — visual consolidated run report
