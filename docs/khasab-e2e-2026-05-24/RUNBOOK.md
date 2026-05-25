# Khasab Real-Data E2E — Reproduction Runbook

End-to-end steps to recreate the demo-ready KHASAB-2026 project in a fresh Bipros environment from these artifacts. Tested 2026-05-24 against backend commit `ce3dd88e`.

---

## 0. Prerequisites

### Software
- **PostgreSQL** 14+ on `localhost:5432` with a `bipros` user able to create databases.
  - Native install (e.g. Postgres.app on macOS) at `/Applications/Postgres.app/Contents/Versions/latest/bin/` works fine; if `psql` isn't on PATH, prefix the absolute path.
- **Java 23 + Maven 3.9+** for the backend (`backend/bipros-api`).
- **Node 22 + pnpm 9+** for the frontend (`frontend/`).
- **Python 3.11+** with `openpyxl` and `pandas` for the import scripts.
  - One-shot venv setup: `python3 -m venv /tmp/xlsx_venv && /tmp/xlsx_venv/bin/pip install openpyxl pandas python-dateutil`

### Environment variables (set BEFORE starting the backend)
| Var | Value | Purpose |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/bipros` (default) | DB connection |
| `DB_USERNAME` | `bipros` | DB user |
| `DB_PASSWORD` | `bipros_dev` | DB password (dev) |
| `BIPROS_AI_KEK` | _(your base64 KEK)_ | Required for `/v1/ai/chat` to return non-empty text |
| `BIPROS_AI_ENABLED` | `true` | Enables AI orchestrator |

### Files in this directory
| File / Folder | What |
|---|---|
| `source-data/1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx` | **Source DPR Excel** (3 monthly sheets, ~26K rows; double-space in filename is intentional) |
| `source-data/3. Supervisor-Engineer-CM-PM DBS (2).xlsx` | DBS master / activity reference |
| `bipros-backup-2026-05-24.dump` | **Postgres custom-format backup** taken BEFORE the wipe — use to roll back if needed |
| `wipe-transactional.sql` | TRUNCATE script used to wipe demo data while keeping system masters |
| `scripts/*.py` | Import scripts (run in the order in §3 below) |
| `state/*.json` | UUID registry from the original run (project, users, WBS, activities) |
| `khasab-dpr-parsed.json` | Pre-parsed DPR JSON if you want to skip re-parsing |
| `khasab-dpr-2026-05-24.csv` / `.xlsx` | Final flat DPR export |
| `ai-ground-truth.json` | 50 SQL-precomputed AI test answers |
| `dpr-dbs-e2e-execution-log-2026-05-24.html` | Single consolidated HTML report from the original run |
| `2026-05-24-fresh-env-and-khasab-import-design.md` | Brainstorming spec |
| `2026-05-24-fresh-env-and-khasab-import.md` | 20-task implementation plan |
| `screenshots/*.png` | Visual proof (pre-fix + post-fix browser captures) |

---

## 1. Choose your reproduction path

### Path A — Restore the backup verbatim (fastest, 1 minute)
Skip everything else. Just restore the pg_dump:

```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin
PGPASSWORD=bipros_dev $PG/pg_restore \
  -h 127.0.0.1 -U bipros -d bipros --clean --if-exists \
  bipros-backup-2026-05-24.dump
```

Then start backend + frontend. The state you'll see is the **pre-run** state (3 demo projects, 21,667 old DPRs). This is **NOT** the demo-ready state — it's the rollback target. Use Path B/C for the actual KHASAB-2026 demo.

### Path B — Full clean rebuild (1.5–2 hours)
Run all scripts top-to-bottom against your live backend. This recreates everything from source.

### Path C — Apply just the demo fix (15 minutes, assumes you have DPRs imported)
Run only `scripts/fix_demo_v2.py` + `scripts/create_norms_only.py` + the tune SQL if you already have a project + DPRs and need to make it demo-ready.

### Path D — REDO (master names + planned resources, ~2 hours)
The most-correct path. Uses the **master sheet's real activity descriptions** and creates **planned manpower/equipment/material per activity** (229 role-assignments) so the activity drawer + Capacity Utilization actually compute against plan-vs-actual:

```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin
export DB_POOL_MAX=60   # bigger HikariCP pool — DPR import is heavy
# 1. Parse master sheet for real names
/tmp/xlsx_venv/bin/python scripts/parse_master_sheet.py
# 2. Analyze DPR data to derive per-activity resource demand
/tmp/xlsx_venv/bin/python scripts/analyze_resource_demand.py
# 3. Wipe + restore variant tables + create users/project/WBS/activities with REAL names + work_activity link
python3 scripts/rebuild_demo.py
# 4. POST 229 role-assignments (planned manpower + equipment + material per activity)
python3 scripts/fix_role_assignments.py
# 5. Import 3,431 DPRs (~30 min with pool=60, 4 workers)
nohup python3 scripts/import_khasab_dprs.py all > /tmp/dpr-import.log 2>&1 &
# 6. After import: DPR rates SQL + EVM + BOQ from master + MCLs + norms + DBS recompute
python3 scripts/fix_demo_v2.py
# 7. Tune productivity norms so Capacity Util % lands at demo-realistic 60-200%
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -f scripts/tune_productivity_norms.sql
# 8. Verify
python3 scripts/check_capacity.py
```

Expected final state (see Markdown log for full table):
- 3,431 DPRs, 229 role-assignments, 30 BOQ items, 76 MCLs, 66 norms
- Total project cost ~₹1.26 Cr
- Avg activity % complete: 95.5%
- Capacity Utilization Total Eff: 88.6% (yellow, with realistic per-role spread)

The rest of this runbook is **Path B**.

---

## 2. Path B — Wipe + Restart

### 2.1 Backup your current DB (safety)
```bash
PG=/Applications/Postgres.app/Contents/Versions/latest/bin
mkdir -p /tmp
PGPASSWORD=bipros_dev $PG/pg_dump -h 127.0.0.1 -U bipros -F c \
  -f /tmp/bipros-pre-rerun-$(date +%Y%m%d-%H%M%S).dump bipros
```

### 2.2 Kill backend, edit YAML, wipe, restart

```bash
# Stop backend
pkill -f "bipros-api" || true

# In backend/bipros-api/src/main/resources/application.yml, ensure these are inside the bipros: block
# (they default true otherwise and the backfill seeders run into wiped data):
#
#   bipros:
#     dbs:
#       backfill:
#         enabled: false
#     seeder:
#       project-team:
#         enabled: false
#     backfill:
#       legacy-daily-output:
#         enabled: false

# Wipe transactional data, keep system masters (188 TRUNCATE statements, FK-safe order)
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -v ON_ERROR_STOP=1 \
  -f wipe-transactional.sql

# IMPORTANT: profile_permissions cascade-deletes via profiles wipe. Restore from backup:
PGPASSWORD=bipros_dev $PG/pg_restore -h 127.0.0.1 -U bipros -d bipros \
  --data-only --table=profiles \
  bipros-backup-2026-05-24.dump

PGPASSWORD=bipros_dev $PG/pg_restore -h 127.0.0.1 -U bipros -d bipros \
  --data-only --table=profile_permissions \
  bipros-backup-2026-05-24.dump

# Re-attach admin to ADMIN role
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -c "
INSERT INTO public.user_roles (id, created_at, updated_at, role_id, user_id, version)
SELECT gen_random_uuid(), now(), now(), r.id, u.id, 0
FROM public.users u, public.roles r
WHERE u.username = 'admin' AND r.name = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;"

# Start backend (in background) with -am to pull fresh sibling jars (avoids stale-M2 gotcha)
cd /Volumes/Java/Projects/bipros-eppm
(mvn -f backend/bipros-api/pom.xml -am -Dmaven.test.skip=true spring-boot:run > /tmp/bipros-backend.log 2>&1) &

# Wait for health
until curl -sf http://localhost:8080/actuator/health > /dev/null; do sleep 5; done
echo "backend up"

# Start frontend (if not running)
(cd frontend && pnpm dev > /tmp/bipros-frontend.log 2>&1) &
until curl -sf http://localhost:3000 > /dev/null; do sleep 3; done
echo "frontend up"
```

### 2.3 Verify clean state
```bash
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT 'dpr' AS metric, COUNT(*) FROM project.daily_progress_reports
UNION ALL SELECT 'projects', COUNT(*) FROM project.projects
UNION ALL SELECT 'users', COUNT(*) FROM public.users
UNION ALL SELECT 'roles', COUNT(*) FROM public.roles
UNION ALL SELECT 'manpower_rates', COUNT(*) FROM resource.manpower_rate_masters
UNION ALL SELECT 'profile_permissions', COUNT(*) FROM public.profile_permissions
ORDER BY metric;"
```

Expected: dpr=0, projects=0, users=1-3 (admin + DataSeeder demo users), roles=22, manpower_rates≥16, profile_permissions=552.

---

## 3. Path B — Sequential import (run from this directory)

### 3.1 Setup
```bash
# Get admin token
TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
echo "$TOKEN" > /tmp/admin-token.txt

# Workspace for runtime ID registry
mkdir -p /tmp/khasab

# Python venv (skip if already set up)
python3 -m venv /tmp/xlsx_venv 2>/dev/null
/tmp/xlsx_venv/bin/pip install --quiet openpyxl pandas python-dateutil
```

### 3.2 Parse the Excel
```bash
# Edit scripts/parse_khasab.py to point XLSX at the actual file path if you moved this folder.
# Default: docs/ActualData/1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx
/tmp/xlsx_venv/bin/python scripts/parse_khasab.py
# → /tmp/khasab-dpr-parsed.json (3431 DPRs)
# → /tmp/khasab-dpr-validation_report.md
```

### 3.3 Create 16 users
```bash
python3 scripts/create_khasab_users.py
# → /tmp/khasab/user-ids.json
```

### 3.4 Create project KHASAB-2026
```bash
RAVI=$(python3 -c 'import json; print(json.load(open("/tmp/khasab/user-ids.json"))["ravi"])')

PROJECT_RESP=$(curl -sS -X POST http://localhost:8080/v1/projects \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"code\":\"KHASAB-2026\",\"name\":\"Khasab Road Project 2026\",\"epsNodeId\":\"e38edde8-b6cb-4d2c-8e16-72a8336e7c0a\",\"currencyCode\":\"INR\",\"ownerId\":\"$RAVI\"}")

PROJECT_ID=$(echo "$PROJECT_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
echo "$PROJECT_ID" > /tmp/khasab/project-id.txt

# Set planned dates + ACTIVE status via PUT
curl -sS -X PUT "http://localhost:8080/v1/projects/$PROJECT_ID" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"code\":\"KHASAB-2026\",\"name\":\"Khasab Road Project 2026\",\"epsNodeId\":\"e38edde8-b6cb-4d2c-8e16-72a8336e7c0a\",\"plannedStartDate\":\"2026-01-01\",\"plannedFinishDate\":\"2026-12-31\",\"dataDate\":\"2026-01-01\",\"status\":\"ACTIVE\",\"currencyCode\":\"INR\",\"ownerId\":\"$RAVI\"}" > /dev/null

# Set budget directly via SQL (PUT doesn't expose this field)
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -c "
UPDATE project.projects SET original_budget = 50000000, current_budget = 50000000
WHERE id = '$PROJECT_ID';"

# Note: the EPS UUID above is MIG-CIV from the original env. If your env doesn't have it,
# query an existing one: SELECT id, code FROM project.eps_nodes LIMIT 5;
```

### 3.5 Create project team (reports-to chain)
```bash
python3 scripts/create_project_team.py
```

### 3.6 Build WBS (22 nodes)
```bash
python3 scripts/create_wbs_and_activities.py
# → /tmp/khasab/wbs-ids.json
```

### 3.7 Create 33 activities
```bash
python3 scripts/create_activities.py
# → /tmp/khasab/activity-ids.json
```

### 3.8 Lock all activities (gotcha — DPRs reject against unlocked activities)
```bash
for aid in $(python3 -c 'import json; print(*json.load(open("/tmp/khasab/activity-ids.json")).values())'); do
  curl -sS -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/activities/$aid/lock" \
    -H "Authorization: Bearer $TOKEN" -o /dev/null
done
```

### 3.9 Import DPRs (slowest step, ~80 min for 3,431 rows)
```bash
python3 scripts/import_khasab_dprs.py all
# Imports Jan (591), Feb (1151), Mar (1689) sequentially. Throughput ~0.5–1 DPR/s.
# Run individual months if you want to checkpoint: python3 scripts/import_khasab_dprs.py 2026-01
```

### 3.10 Apply demo-readiness fixes (Phase 6+ of original spec)
```bash
python3 scripts/fix_demo.py
```
This script does:
1. Unlocks all 33 activities
2. Creates `KHASAB_*` records in `resource.work_activities`
3. SQL-updates each activity: links work_activity_id, sets planned_start/finish, original_duration, supervisor_user_id, percent_complete, status (3 marked COMPLETED)
4. SQL-updates DPR rates to realistic INR daily rates (Helper ₹500/day, Excavator ₹5000/day, etc.) — total project cost goes from ₹23K to ₹1.26 Cr
5. Inserts EVM row with `budget_at_completion = 50,000,000`
6. Creates 20 BOQ items
7. Creates 40 Material Consumption Logs (synthetic but realistic)
8. Re-locks all activities
9. Triggers DBS recompute per-day (range API has bug — Finding 18)

### 3.11 Create productivity norms (66 = 33 manpower + 33 equipment)
```bash
python3 scripts/create_norms_only.py
```

### 3.12 Verify
```bash
PGPASSWORD=bipros_dev $PG/psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT 'dprs' m, COUNT(*) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'
UNION ALL SELECT 'mp_cost', ROUND(SUM(line_cost)::numeric,2) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID'
UNION ALL SELECT 'eq_cost', ROUND(SUM(line_cost)::numeric,2) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID'
UNION ALL SELECT 'boq', COUNT(*) FROM project.boq_items WHERE project_id='$PROJECT_ID'
UNION ALL SELECT 'mcl', COUNT(*) FROM resource.material_consumption_logs WHERE project_id='$PROJECT_ID'
UNION ALL SELECT 'norms', COUNT(*) FROM resource.productivity_norms WHERE work_activity_id IN (SELECT id FROM resource.work_activities WHERE code LIKE 'KHASAB_%')
UNION ALL SELECT 'avg_pct', ROUND(AVG(percent_complete)::numeric,1) FROM activity.activities WHERE project_id='$PROJECT_ID'
ORDER BY m;"
```

Expected (matches the original run):

| metric | value |
|---|---:|
| dprs | 3431 |
| mp_cost | 1,923,300 |
| eq_cost | 10,639,900 |
| boq | 20 |
| mcl | 40 |
| norms | 66 |
| avg_pct | 67.7 |

### 3.13 Browser verification
Open `http://localhost:3000/projects/<PROJECT_ID>` (or click via the projects list).

Expected hero card values:
- Overall Progress: ~50%
- Budget Utilised: ₹5.0 Cr
- Tasks Completed: 3 of 33
- Project Timeline: 6-month preview populated
- Project Health doughnut: rendered

### 3.14 AI validation (optional, 10-20 min)
```bash
python3 scripts/ai_ground_truth.py   # → /tmp/ai-ground-truth.json
python3 scripts/ai_grade.py          # → /tmp/ai-results.json
```
Stops early if 3 consecutive AI responses are identical (per `feedback_ai_test_stop_on_repeat` memory).

### 3.15 Exports + HTML report
```bash
python3 scripts/export_dpr_csv_xlsx.py
# → docs/ActualData/exports/khasab-dpr-<today>.{csv,xlsx}

python3 scripts/build_html_report.py
# → docs/dpr-dbs-e2e-execution-log-<today>.html
```

---

## 4. Known gotchas (encountered during the original run)

Numbered to match the markdown log:

| # | Gotcha | Fix |
|---|---|---|
| 5 | `BoqActualRateRecalcListener` doesn't fire on `MaterialConsumptionLoggedEvent` | After MCL submission, re-PUT parent DPR to refresh BOQ actualRate |
| 7 | `% Achieved` tile missing from Supervisor DBS UI | UI gap, file issue |
| 8 | CM-tier `contributionPct` scaled as percentage not fraction | API inconsistency, flag but don't auto-fix |
| 9 | CM-tier missing `totalExpense` + `contribution` fields | API inconsistency |
| 10 | 4 Khasab supervisors missing from user spec (Sohail, Manzar, V.P. Gupta, A.K. Mishra) | Added as additional SUPERVISOR users (scripts include them) |
| 11 | `TRUNCATE public.profiles CASCADE` wipes `profile_permissions` (552 rows) | Restore both tables from backup after wipe; runbook step 2.2 does this |
| 12 | Project DTO field names: `epsParentId`→`epsNodeId`, `contractValue`→`originalBudget` (SQL-only), `startDate`→`plannedStartDate` | Use names from runbook step 3.4 |
| 13 | `POST /v1/projects/{id}/activities` requires `projectId` in body even though it's in URL | Scripts include it |
| 14 | `ProjectRole` enum is `{PM, CONSTRUCTION_MANAGER, SITE_MANAGER, ENGINEER, SUPERVISOR, QS, SAFETY}` — NOT `PROJECT_MANAGER`/`SITE_ENGINEER` | Scripts use correct enum |
| 15 | DPR validator rejects `qtyExecuted=0` (real data has 470 idle-only days) | Scripts substitute 0.01 + remarks marker |
| 16 | Khasab activity codes don't match `work_activities` catalogue | `fix_demo.py` creates `KHASAB_*` work_activities then links |
| 17 | Phase 7 (subcontractors) is no-op for Khasab — 0 sub rows in source | Skip; document |
| 18 | `POST /dbs/recompute-range` returns 500 (`IncorrectResultSizeDataAccessException`) | Use per-day `recompute?date=X` in a loop |
| 19 | `ProductivityNorm.normType` enum is `{MANPOWER, EQUIPMENT}` — NOT `MANPOWER_UTILIZATION`/`EQUIPMENT_UTILIZATION` | Scripts use correct enum |
| 20 | `activity.duration_type` valid values: `FIXED_DURATION_AND_UNITS`, `FIXED_DURATION_AND_UNITS_PER_TIME`, `FIXED_UNITS`, `FIXED_UNITS_PER_TIME` — NOT `FIXED_DURATION` | Use `FIXED_DURATION_AND_UNITS` |
| 21 | AI chat response field is `data.text`, not `data.responseText` | `ai_grade.py` updated |
| 22 | `POST /v1/projects/{pid}/dpr` requires `activityName` + `supervisorName` text fields | Scripts include them |
| 23 | DPR list groups by `daily_progress_reports.activity_name` (denormalized snapshot). Renaming an activity via raw SQL drifts the snapshot and the UI keeps showing the old label. | Backend now publishes `ActivityUpdatedEvent` → `ActivityRenameDprSyncListener` keeps the snapshot in sync for API renames (PUT /v1/projects/{pid}/activities/{aid}). For SQL renames or one-shot repair, run `scripts/fix_dpr_activity_name_drift.sql`. The current copy of `rename_activities_add_risks_weather.py` updates the snapshot inline. |

---

## 5. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Backend starts but `/v1/ai/chat` returns empty `text` | `BIPROS_AI_KEK` not set or wrong | Set env var, restart |
| All 1,000+ DPRs fail "Request validation failed: qtyExecuted must be > 0" | Idle-day records | Script substitutes 0.01 — make sure you ran the latest `import_khasab_dprs.py` |
| Project Overview shows 0% everything | Activities have no `planned_start_date` / `percent_complete` | Run `fix_demo.py` |
| Capacity Utilization shows "no norm grey" | Activities not linked to `work_activity_id` OR norms missing | `fix_demo.py` + `create_norms_only.py` |
| Admin can't login after wipe | `user_roles` was truncated, profile_permissions cascade-deleted | Runbook step 2.2 restores them; verify with `SELECT * FROM public.user_roles WHERE user_id = (SELECT id FROM public.users WHERE username='admin');` |
| DPR import is very slow (<0.5 DPR/s) | Sequential 1-by-1 POSTs through validation | Acceptable; ~80 min for 3,431 — run in background |
| `psql` not found | Postgres.app not on PATH | `export PATH=/Applications/Postgres.app/Contents/Versions/latest/bin:$PATH` |
| DPR list shows old "Khasab X.Y" group headers after rename | Denormalized `daily_progress_reports.activity_name` drift (see gotcha #23) | `psql -f scripts/fix_dpr_activity_name_drift.sql` |

---

## 6. Final state checklist

After completing all steps, you should have:

- [ ] Project `KHASAB-2026` visible in UI with non-zero hero KPIs
- [ ] 16 users + 16 project team members with reports-to chain
- [ ] 22 WBS nodes + 33 activities (all linked to work_activity)
- [ ] 3,431 DPRs across Jan/Feb/Mar 2026
- [ ] Total project cost ~₹1.26 Crore
- [ ] 20 BOQ items + 40 Material Consumption Logs + 66 Productivity Norms
- [ ] EVM BAC = ₹5 Crore
- [ ] DBS aggregates for 562 supervisor + 80 project rows
- [ ] CSV/XLSX exports in `docs/ActualData/exports/`
- [ ] HTML report in `docs/dpr-dbs-e2e-execution-log-2026-05-24.html`

---

## Path D — Step 9: Calendar + Dashboard population (REQUIRED for demo)

After Path D step 8 (`tune_productivity_norms.sql`), run:

```bash
python3 scripts/populate_dashboard.py
# Then create DPR issues for Open Issues / Active Alerts:
python3 scripts/create_risks.py    # 8 risks (idempotent — deletes + re-inserts for this project)
# And separately, DPR issues via SQL (script above does inline)
```

This:
1. Links **Oman 5-day Construction Calendar** to project + all activities → "Run Schedule" succeeds
2. Inserts 60 rows in `project.daily_weather` → Site Conditions tile populated
3. Creates 6 future-dated milestones → Project Timeline Preview shows the schedule
4. Creates 6 DPR issues → Open Issues / Active Alerts populated

See [`CALENDAR-AND-DASHBOARD.md`](CALENDAR-AND-DASHBOARD.md) for full detail, including:
- Every code path the calendar flows through
- All valid enum values for `dpr_issues`, `risks`, `activities.activity_type`
- Verification queries

## Final DB backup

`bipros-FINAL-backup.dump` (1.6 MB, custom format) — taken after all REDO v2 + dashboard population. Restore with:

```bash
PGPASSWORD=bipros_dev /Applications/Postgres.app/Contents/Versions/latest/bin/pg_restore \
  -h 127.0.0.1 -U bipros -d bipros --clean --if-exists \
  bipros-FINAL-backup.dump
```

This is the FASTEST way to get a demo-ready environment — single command, no script chain needed.
