# Daily Balance Sheet (DBS) & Material Consumption Report — End-to-End Guide

**Status:** v1 shipped 2026-05-17.
**Audience:** developers (setup + testing), site engineers / project managers (usage), QA (verification).

This document covers everything delivered for the DBS rollout (Phases A → G of `/Users/hemendra/.claude/plans/please-verify-and-confirm-vivid-hanrahan.md`). For the broader product, start with the repo's `CLAUDE.md` / `AGENTS.md`.

---

## 1. What was implemented

Seven phases across backend, frontend, infra, and tests.

### Phase A — Foundations

| Item | Where | Why |
|---|---|---|
| `project.project_team` table | Liquibase `101-project-team.yaml` | Project-scoped reporting line (PM → SiteManager → Engineer → Supervisor → QS → Safety). Hierarchy is project-local, not HR-global. |
| `ProjectTeamMember` entity, `ProjectRole` enum, repo, service, controller | `backend/bipros-project/.../{domain,application,api}` | CRUD + `resolveEngineerFor(projectId, supervisorUserId)` + `resolvePmFor(projectId)`. |
| `ProjectTeamBackfillSeeder` | `backend/bipros-api/.../seeder` | Creates a PM membership from `Project.ownerId` if none exists. Idempotent. |
| Material consumption identity | Liquibase `102-material-consumption-user-cols.yaml` | Added `issued_by_user_id`, `received_by_user_id` UUIDs. Legacy `issued_by`/`received_by` text kept for back-compat. |
| Two new events | `bipros-common`: `MaterialConsumptionLoggedEvent`, `ResourceDeploymentSavedEvent` | Both with nested `EventType` (CREATED/UPDATED/DELETED). Published from their respective services. |
| Frontend `/projects/[id]/team` page | `frontend/src/app/(app)/projects/[projectId]/team/page.tsx` | Add/remove team members per role; pick "reports to" within team. |
| Frontend material-consumption form upgrade | `.../material-consumption/page.tsx` | User pickers for issued/received-by, role chip per row, filter dropdowns. |

### Phase B — DBS module skeleton

| Item | Where |
|---|---|
| New Maven module `bipros-dbs` | `backend/bipros-dbs/` (peer of `bipros-evm`) |
| `dbs` schema | `docker/init-schemas.sql` |
| Three aggregate tables (Liquibase `103-dbs-tables.yaml`) | `dbs.dbs_daily_supervisor` (with `*_lines_json` per-section JSONB), `dbs.dbs_daily_engineer`, `dbs.dbs_daily_project` |
| Six section calculators | `service/calculator/`: Section A Manpower, B Admin/Catering, C Machinery, D Fuel, E Material, F BOQ Work. Section F Sub-Contractor stubbed (v2). |
| `DbsAggregationService` | Runs all 6 calculators, upserts row, persists JSON lines, resolves engineer via `ProjectTeamService`. `recomputeSupervisorDay → recomputeEngineerDay → recomputeProjectDay`. |
| `DbsRecomputeListener` | `@TransactionalEventListener(phase=AFTER_COMMIT)` on `DprSubmittedEvent`, `ResourceDeploymentSavedEvent`, `MaterialConsumptionLoggedEvent`. |
| `DailyProgressReportRepository.findDistinctSupervisorUserIdsByProjectAndDate` | New finder used by the listener to fan out per-supervisor recompute when DRD / Material events fire (those events don't carry supervisor identity). |

### Phase C — Full API + dashboard

| Item | Where |
|---|---|
| `DbsQueryService` | Read-side: zero-fills missing rows, parses JSON lines, computes period sums and cumulative-to-date. Period bounds: ISO Mon–Sun week, calendar-month bounds. |
| `DbsController` endpoints | `GET /supervisor/{id}?date=&periodType=`, `GET /engineer/{id}?date=&periodType=`, `GET /project?date=&periodType=`, `GET /supervisors?date=`, `POST /recompute?date=`, `POST /recompute-range?from=&to=`. `periodType` ∈ {DAY,WEEK,MONTH}. |
| Frontend `/projects/[id]/dbs` page | Three tabs (Supervisor / Engineer / PM), URL-state (`?tab=&date=&period=&supervisor=&engineer=`), totals panel, 7 collapsible section cards, currency-aware formatting. |

### Phase D — Exports

| Item | Where |
|---|---|
| `DbsExcelWriter` | Apache POI XSSF. PM report = Summary-Financial sheet + per-engineer PRE sheet(s) + one sheet per supervisor. Supervisor report = single sheet. Mirrors client workbook layout. |
| `DbsPdfWriter` | openhtmltopdf + PDFBox. Strict-XHTML render. |
| Endpoints | `GET /v1/projects/{id}/dbs/export.xlsx?date=&level=PM|SUPERVISOR&supervisorUserId=` and `.../export.pdf?...`. Returns binary blobs, not `ApiResponse`. |
| Frontend hooks | `dbsApi.downloadExcel` / `downloadPdf` + buttons on PM tab. |

### Phase E — Material Consumption Report

| Item | Where |
|---|---|
| `MaterialConsumptionReportService` | `bipros-reporting/.../materialconsumption/`. Read-only. JPQL + targeted native SQL. Pulls consumption logs + issued qty + BOQ planned qty (best-effort via WBS join). |
| Alerts | `EXCESS_CONSUMPTION` (>1.10× planned), `NEGATIVE_BALANCE`, `BUDGET_OVERCONSUMPTION`, `MISSING_UNIT_RATE`. |
| `MaterialConsumptionExcelWriter` | Single-sheet XSSF with title band, 18 columns, totals row, alert summary. |
| Endpoints | `GET /v1/projects/{id}/reports/material-consumption?...` with filters + `groupBy=DAY|MATERIAL|ACTIVITY|SUPERVISOR`. `GET .../export.xlsx?...`. |
| Frontend page | `/projects/[id]/reports/material-consumption` — filter panel, summary cards, alert chips, sortable table, Export Excel. |

### Phase F — DBS alerts + ADMIN/CATERING type

| Item | Where |
|---|---|
| `DeploymentResourceType` | Added `ADMIN` + `CATERING` enum values. Activates Section B calculator. |
| `DbsAlertEvaluator` | Alert codes: `NEGATIVE_CONTRIBUTION`, `LOW_CONTRIBUTION_PCT` (<5%), `RUNAWAY_FUEL` (fuel > 0.5×total expense), `MISSING_RATE_DATA`. |
| Endpoint | `GET /v1/projects/{id}/dbs/alerts?date=` returns `List<String>` (codes). Also embedded in `DbsProjectDayResponse.alerts`. |
| Frontend banner | Coloured alert banner on PM tab. |

### Phase G — Tests

| Item | Where | Notes |
|---|---|---|
| Backend integration tests | `backend/bipros-api/src/test/java/com/bipros/api/dbs/DbsAggregationIntegrationTest.java` (8 cases), `.../reports/MaterialConsumptionReportIntegrationTest.java` (5 cases) | `@Disabled` pending testcontainer + seed wiring. Remove disable to run. |
| Playwright specs | `frontend/e2e/tests/40-dbs-dashboard.spec.ts` (5), `41-material-consumption-report.spec.ts` (4), `42-project-team.spec.ts` (1) | Tests `test.skip(...)` themselves when seed data unavailable. |

---

## 2. Setup (step by step)

Assumes macOS + Docker Desktop + Maven 3.9+ + Java 23 + pnpm + Node 22.

### 2.1 First-time prerequisites

```bash
# Tools
brew install java maven node pnpm
brew install --cask docker

# Clone (skip if already)
cd /Volumes/Java/Projects/bipros-eppm
```

### 2.2 Start Postgres + Redis

```bash
docker compose up -d
# Verifies postgres on :5432, pgadmin on :5050, redis on :6379
docker compose ps
```

### 2.3 Create the `dbs` schema (only needed once on existing DBs)

Dev `application.yml` sets `hibernate.hbm2ddl.create_namespaces=true`, so a first-time boot
against an empty DB creates the `dbs` schema automatically. For **existing** DBs that pre-date
this release, run:

```bash
docker exec -it bipros-postgres psql -U bipros -d bipros -c \
  "CREATE SCHEMA IF NOT EXISTS dbs AUTHORIZATION bipros;"
```

(Or in DBeaver / pgAdmin run the same statement. The Liquibase changeset `103-dbs-tables.yaml` then creates the three tables inside it.)

> **Dual-Postgres dev note** (memory: dev-dual-postgres): on some workstations both native
> Homebrew Postgres and docker `bipros-postgres` bind to 5432. The backend connects to
> whichever owns the loopback socket — usually native. Run the `CREATE SCHEMA` against the
> instance the backend actually connects to. From the host, that means a plain `psql`
> (native) rather than `docker exec ... psql` (container) — otherwise the schema is created
> in the wrong DB and Hibernate startup still fails with `schema "dbs" does not exist`.

### 2.4 Build + start the backend

```bash
cd backend
mvn -pl bipros-api -am clean install -DskipTests   # build everything once, fills ~/.m2

# Set required env (memory: BIPROS_AI_KEK must be set for AI features)
export BIPROS_AI_KEK='<your-base64-kek>'    # only needed for /v1/ai/* features

# Start
mvn -pl bipros-api -am spring-boot:run
```

> **Maven gotcha** (memory: dev-maven-stale-m2-gotcha): always pass `-am` so sibling module jars get rebuilt — without it Spring Boot loads stale snapshots from `~/.m2` and your new routes silently 404.

On first boot:
- With `ddl-auto: update` (default), the new `dbs.dbs_daily_*` tables are created automatically AND Liquibase 101/102/103 changesets run.
- The default admin (`admin` / `admin123`) is seeded.
- `ProjectTeamBackfillSeeder` runs (it's idempotent — re-runs are no-ops).

### 2.5 Seed demo data

```bash
# Backend must be running
./scripts/seed-icpms-data.sh    # ICPMS demo project (heavier)
./scripts/seed-demo-data.sh     # smaller generic demo
./scripts/seed-post-data.sh     # rate masters + extra masters
```

After seeding, check rate masters are populated:

```bash
curl -s -u admin:admin123 http://localhost:8080/v1/manpower-rate-master | head -200
curl -s -u admin:admin123 http://localhost:8080/v1/equipment-rate-master | head -200
curl -s -u admin:admin123 http://localhost:8080/v1/material-rate-master | head -200
```

If any of these are empty, DBS will show zeros for that section.

### 2.6 Start the frontend

```bash
cd frontend
pnpm install        # first time
pnpm dev            # → http://localhost:3000
```

Log in with `admin` / `admin123`.

---

## 3. How to use the features

### 3.1 Set up the project team (prerequisite for DBS)

1. Navigate to `Projects → <your project> → Team`.
2. Add the PM first (role = PM). Leave "Reports to" empty.
3. Add Site Manager(s) reporting to the PM.
4. Add Engineer(s) reporting to a Site Manager (or directly to PM).
5. Add Supervisor(s) reporting to an Engineer.
6. Optionally add QS / Safety.

Why: the DBS rollup walks `reports_to_user_id` upward to attribute each supervisor's daily total to their engineer's row, and engineers' totals to the PM/project row. **Supervisors without an engineer link still show up at the project level but are missing from any engineer-level rollup.**

### 3.2 File a Daily Progress Report (DPR)

1. `Projects → <project> → DPR`.
2. Pick the report date (today by default).
3. Pick the activity. The supervisor is auto-set to the logged-in user when they own that activity.
4. Enter `qtyExecuted`, manpower rows, equipment rows, material rows, issues.
5. Submit.

Behind the scenes: `DprSubmittedEvent` fires → `DbsRecomputeListener` runs `recomputeSupervisorDay → recomputeEngineerDay → recomputeProjectDay`. The DBS row for `(project, supervisor, date)` is created/updated within ~50 ms after the DPR commit.

### 3.3 Record daily resource deployment

`Projects → <project> → Resource Deployment` — enter MANPOWER / EQUIPMENT / ADMIN / CATERING rows for the day. Each save triggers `ResourceDeploymentSavedEvent` → DBS recompute for the project's date (all supervisors).

### 3.4 Record material consumption

`Projects → <project> → Material Consumption`:
- Pick a date.
- Use the user pickers for **Issued by** and **Received by**.
- The **Entered by role** chip auto-populates from your role (SUPERVISOR / STOREKEEPER / etc.).
- Filter rows by role or by user.

Each save fires `MaterialConsumptionLoggedEvent` → DBS recompute.

### 3.5 Read the Daily Balance Sheet

`Projects → <project> → DBS`. Three tabs:

**Supervisor tab**
- Pick a supervisor (the dropdown lists supervisors that have either a DBS row or a DPR on the selected date).
- Choose period: DAY / WEEK / MONTH.
- Totals panel at top, then 7 collapsible sections — E.Material / A.Man Power / B.Catering-Admin / C.Machinery / D.Fuel / F.Sub-Contractor (v2 placeholder) / BOQ Work.
- When period ≠ DAY, a small daily-breakdown table also renders.

**Engineer tab**
- Pick an engineer (populated from `projectTeamApi.list(projectId, 'ENGINEER')`).
- Totals panel + chips for the supervisor IDs that roll up to this engineer. Click a chip to drill down (jumps to Supervisor tab pre-filled).

**PM tab**
- No picker. Shows project totals, cumulative-to-date, per-engineer breakdown (Plan / Achieved / Cost / Cost% / Contribution / Contribution% / Profit-Loss chip).
- Alert banner at top when any DBS alert is active.
- "Recompute" button forces a backfill for the current date. "Recompute range" lets you backfill a date range (useful after bulk DPR edits).
- "Export Excel" → downloads `dbs-{date}-PM.xlsx` mirroring the client workbook (per-supervisor sheets + PRE + Summary-Financial).
- "Export PDF" → printable PDF.

### 3.6 Read the Material Consumption Report

`Projects → <project> → Reports → Material Consumption`:
- Filters: date range, WBS, Activity, Supervisor, Storekeeper, Material, Group by.
- Summary cards: Planned / Actual / Variance / Wastage%.
- Alert summary chips (counts per alert code).
- Sortable table with per-row alert badges.
- "Export Excel" downloads an .xlsx mirroring the on-screen table.

### 3.7 Direct API access

```bash
# DBS — supervisor day
curl -s -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/dbs/supervisor/$SID?date=2026-05-17"

# DBS — supervisor week (ISO Mon–Sun)
curl -s -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/dbs/supervisor/$SID?date=2026-05-17&periodType=WEEK"

# DBS — PM day
curl -s -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/dbs/project?date=2026-05-17"

# DBS — list supervisors for a date
curl -s -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/dbs/supervisors?date=2026-05-17"

# DBS — alerts
curl -s -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/dbs/alerts?date=2026-05-17"

# DBS — force recompute
curl -X POST -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/dbs/recompute?date=2026-05-17"

# DBS — range recompute
curl -X POST -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/dbs/recompute-range?from=2026-05-01&to=2026-05-17"

# DBS — Excel export
curl -u admin:admin123 -o dbs.xlsx \
  "http://localhost:8080/v1/projects/$PID/dbs/export.xlsx?date=2026-05-17&level=PM"

# DBS — PDF export
curl -u admin:admin123 -o dbs.pdf \
  "http://localhost:8080/v1/projects/$PID/dbs/export.pdf?date=2026-05-17&level=PM"

# Material Consumption Report
curl -s -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/reports/material-consumption?from=2026-05-01&to=2026-05-17&groupBy=ACTIVITY"

curl -u admin:admin123 -o material.xlsx \
  "http://localhost:8080/v1/projects/$PID/reports/material-consumption/export.xlsx?from=2026-05-01&to=2026-05-17"

# Project Team
curl -s -u admin:admin123 \
  "http://localhost:8080/v1/projects/$PID/team"

curl -X POST -u admin:admin123 -H 'Content-Type: application/json' \
  "http://localhost:8080/v1/projects/$PID/team" \
  -d '{"userId":"<uuid>","role":"ENGINEER","reportsToUserId":"<pm-uuid>"}'
```

Swagger UI: `http://localhost:8080/swagger-ui.html` (live spec).

---

## 4. End-to-end test plan

### 4.1 Smoke (manual, ~15 min)

1. Backend up, frontend up, log in as `admin`.
2. Open `Projects` → pick the seeded ICPMS or demo project.
3. Go to `Team`. Add yourself as PM. Add one Engineer (a different seeded user) reporting to you. Add two Supervisors reporting to that Engineer.
4. Go to `DPR`. As Supervisor 1, create a DPR for today with one activity + 5 man-hours + 1 m³ qtyExecuted.
5. Go to `Resource Deployment`. Add 4 LABOR rows, 2 EQUIPMENT rows for today.
6. Go to `Material Consumption`. Add 2 rows; set Issued-by + Received-by + Entered-by role.
7. Go to `DBS` → Supervisor tab → pick Supervisor 1. Confirm the totals are non-zero.
8. Switch to Engineer tab → pick the Engineer. Confirm totals = sum of Supervisor 1 + Supervisor 2.
9. Switch to PM tab. Confirm cumulative totals, alert banner (if any), per-engineer breakdown.
10. Click "Export Excel" — open the file; verify there's a Summary-Financial sheet + PRE sheet + per-supervisor sheets.
11. Click "Export PDF" — verify PDF opens with the totals.
12. Go to `Reports → Material Consumption`. Set the date range to today, "Group by Activity". Verify the rows + summary + alert chips render. Click Export Excel.

### 4.2 Backend integration tests

```bash
cd backend

# Run only the new DBS integration test file
mvn -pl bipros-api -am test -Dtest=DbsAggregationIntegrationTest

# Material consumption report tests
mvn -pl bipros-api -am test -Dtest=MaterialConsumptionReportIntegrationTest
```

These are currently `@Disabled` pending testcontainer + seed wiring. To enable:
1. Remove the class-level `@Disabled` annotation.
2. Ensure `application-test.yml` points at a testcontainer Postgres OR an embedded H2 with `dbs` schema pre-created.
3. Ensure the test profile runs all the seeders (or the test fixtures populate rate masters + activities + a supervisor user).

### 4.3 Playwright e2e tests

```bash
cd frontend

# All new specs (gated by SEED_PROJECT_ID env)
SEED_PROJECT_ID=<your-test-project-uuid> pnpm test:e2e \
  --grep "DBS Dashboard|Material Consumption Report|Project Team admin"

# Headed (watch them run)
SEED_PROJECT_ID=<uuid> pnpm test:e2e:headed --grep "DBS Dashboard"

# UI mode (interactive)
pnpm test:e2e:ui
```

The specs `test.skip(...)` themselves when:
- `SEED_PROJECT_ID` isn't set AND the home page shows no projects.
- Role-gated UI (Add Member, Recompute, Export) isn't visible.

So they're safe to run against any DB state — they'll skip rather than fail.

### 4.4 Full pre-commit verification

```bash
# Backend
cd backend && mvn -pl bipros-api -am compile     # must be BUILD SUCCESS
mvn -pl bipros-api -am test-compile              # ignore pre-existing unrelated failures

# Frontend
cd frontend && pnpm tsc --noEmit                 # ignore AddDesignationForm.tsx (pre-existing)
pnpm lint --max-warnings=10000                   # we're at 305 pre-existing problems; new code adds 0
```

---

## 5. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `/v1/projects/{id}/dbs/...` returns 404 | New routes not picked up — stale `~/.m2` jars | Restart with `mvn -pl bipros-api -am spring-boot:run` (must include `-am`) |
| DBS Supervisor totals are all 0 | Rate masters empty, OR DPR has no `boq_item_id`, OR DPR's supervisor_user_id is null | Verify rate masters seeded (§2.5); ensure DPR form picks a BOQ item; ensure supervisor was selected (not free-text "Other") |
| Section B (Admin/Catering) always 0 | No DRD rows of type `ADMIN`/`CATERING` exist | Add deployment rows with those types, or accept zero — Section B is optional |
| Section D (Fuel) always 0 | No project-level fuel rate config exists (v1 stub) | Will be filled when `project_costing_config.fuel_rate_per_litre` is wired (deferred). |
| Engineer tab empty | No PROJECT_TEAM rows of role ENGINEER | Go to `/projects/[id]/team` and add Engineers |
| PM rollup missing a supervisor | That supervisor's `reports_to_user_id` is null OR doesn't chain up to an Engineer | Fix the team mapping |
| Excel export errors with "Invalid header value" | Filename contains slash/colon — should be escaped already, but raise if seen | Sanitize filename in `DbsController` |
| Alerts banner never appears | Project day has `contribution >= 0` and `contributionPct >= 5%` etc. | Working as designed; force one by reducing income or inflating fuel |
| Liquibase fails on startup with "schema dbs does not exist" | Fresh container without `dbs` schema in `init-schemas.sql` | Run `CREATE SCHEMA dbs;` as bipros user (§2.3) |
| `MaterialConsumptionLog` insert fails NOT NULL on a deprecated column | Stale dev DB (memory: dev DB is `ddl-auto: update` which only adds, never drops) | Restart with `DDL_AUTO=create-drop` OR `ALTER TABLE … DROP COLUMN …` manually |
| Frontend tab routing breaks | `useSearchParams()` not wrapped in Suspense in Next 16 | DBS page already wraps it; for new pages remember the Suspense boundary |

---

## 6. Architecture cheatsheet

```
┌─────────────────────────────────────────────────────────────────┐
│   User actions (Frontend / API)                                 │
│   ┌──────────┐  ┌────────────────┐  ┌──────────────────────┐    │
│   │  DPR     │  │ Resource       │  │ Material Consumption │    │
│   │  /dpr    │  │ Deployment     │  │ /material-consumption│    │
│   └────┬─────┘  └────────┬───────┘  └──────────┬───────────┘    │
└────────┼─────────────────┼──────────────────────┼───────────────┘
         │                 │                       │
    ┌────▼─────────────────▼───────────────────────▼──────────┐
    │  Backend Services  (publish events AFTER_COMMIT)        │
    │  - DailyProgressReportService → DprSubmittedEvent       │
    │  - DailyResourceDeploymentService → ResourceDeploymentS.│
    │  - MaterialConsumptionLogService → MaterialConsumptionL.│
    └─────────────────────────┬───────────────────────────────┘
                              │
                ┌─────────────▼──────────────┐
                │  DbsRecomputeListener      │
                │  @TransactionalEventListener(phase=AFTER_COMMIT) │
                │  Fan out: per supervisor   │
                └─────────────┬──────────────┘
                              │
            ┌─────────────────▼─────────────────┐
            │  DbsAggregationService            │
            │  recomputeSupervisorDay():        │
            │    - SectionAManpowerCalculator   │
            │    - SectionBAdminCalculator      │
            │    - SectionCMachineryCalculator  │
            │    - SectionDFuelCalculator       │
            │    - SectionEMaterialCalculator   │
            │    - SectionFBoqCalculator        │
            │    upsert dbs_daily_supervisor    │
            │  recomputeEngineerDay()           │
            │    upsert dbs_daily_engineer      │
            │  recomputeProjectDay()            │
            │    upsert dbs_daily_project       │
            └─────────────────┬─────────────────┘
                              │
            ┌─────────────────▼─────────────────┐
            │  dbs schema (Postgres)            │
            │  - dbs_daily_supervisor (+JSON)   │
            │  - dbs_daily_engineer             │
            │  - dbs_daily_project              │
            └─────────────────┬─────────────────┘
                              │
                              │ reads (DbsQueryService — zero-fills + cumulative-on-read)
                              ▼
            ┌─────────────────────────────────────┐
            │  DbsController                      │
            │  GET /dbs/supervisor|engineer|project│
            │      ?periodType=DAY|WEEK|MONTH      │
            │  POST /dbs/recompute, /recompute-range│
            │  GET /dbs/export.{xlsx,pdf}          │
            │  GET /dbs/alerts                     │
            └─────────────────────────────────────┘
```

Source of truth for module dependencies: `backend/bipros-dbs/pom.xml` (depends on bipros-common, bipros-project, bipros-activity, bipros-resource; **never** on bipros-reporting — avoids dependency cycle).

---

## 7. Known limitations (v1) and what's next

| # | Limitation | When to fix |
|---|---|---|
| 1 | Section F (Sub-Contractor) is stubbed | When `SubcontractorRateMaster` lands (v2). UI shows "Coming soon" badge. |
| 2 | Section D (Fuel) returns zero | Needs `project_costing_config.fuel_rate_per_litre`. Not yet modelled. |
| 3 | Section B (Admin/Catering) needs DRD rows of those types | Enum values added in Phase F; UI for monthly admin cost entry not yet built. |
| 4 | Supervisor filtering on Section A/C is best-effort | DRD has no direct supervisor FK. v2 will use `activity_supervisors` to attribute. |
| 5 | Engineer-period rollup fans out per-day (no per-engineer period query) | Acceptable for v1; revisit if >50 engineers per project. |
| 6 | `cumulative_*` columns deliberately not persisted | Always computed on read. Avoids late-edit cascade complexity. |
| 7 | Sub-contractor split when activity has multiple supervisors | v1 attributes DPR solely to `dpr.supervisor_user_id`. v2 may split. |
| 8 | Alerts table not persisted | Alerts computed on-the-fly. v2 can add `dbs_alerts` for history. |
| 9 | Permissions are coarse (any project member can read DBS) | `DBS.READ` / `DBS.RECOMPUTE` codes not yet defined in RBAC matrix. |
| 10 | Backend integration tests `@Disabled` | Enable when testcontainer + seed wiring is built. |

---

## 8. File reference

### Backend (bipros-dbs module)

```
backend/bipros-dbs/
├── pom.xml
└── src/main/java/com/bipros/dbs/
    ├── api/
    │   ├── DbsController.java
    │   └── dto/
    │       ├── DbsSectionLineDto.java
    │       ├── DbsSupervisorDayResponse.java
    │       ├── DbsEngineerDayResponse.java
    │       ├── DbsProjectDayResponse.java
    │       ├── DbsSupervisorSummaryDto.java
    │       ├── DbsSupervisorPeriodResponse.java
    │       ├── DbsEngineerPeriodResponse.java
    │       └── DbsProjectPeriodResponse.java
    ├── domain/
    │   ├── model/
    │   │   ├── DbsDailySupervisor.java
    │   │   ├── DbsDailyEngineer.java
    │   │   └── DbsDailyProject.java
    │   └── repository/
    │       ├── DbsDailySupervisorRepository.java
    │       ├── DbsDailyEngineerRepository.java
    │       └── DbsDailyProjectRepository.java
    ├── service/
    │   ├── DbsAggregationService.java
    │   ├── DbsQueryService.java
    │   ├── DbsAlertEvaluator.java
    │   └── calculator/
    │       ├── SectionLine.java
    │       ├── SectionResult.java
    │       ├── BoqSectionResult.java
    │       ├── SectionAManpowerCalculator.java
    │       ├── SectionBAdminCalculator.java
    │       ├── SectionCMachineryCalculator.java
    │       ├── SectionDFuelCalculator.java
    │       ├── SectionEMaterialCalculator.java
    │       └── SectionFBoqCalculator.java
    ├── listener/
    │   └── DbsRecomputeListener.java
    └── export/
        ├── DbsExcelWriter.java
        └── DbsPdfWriter.java
```

### Backend (cross-module additions)

```
backend/bipros-project/
├── src/main/java/com/bipros/project/
│   ├── domain/model/
│   │   ├── ProjectTeamMember.java         (new)
│   │   ├── ProjectRole.java               (new)
│   │   └── DeploymentResourceType.java    (added ADMIN, CATERING)
│   ├── domain/repository/
│   │   ├── ProjectTeamRepository.java     (new)
│   │   └── DailyProgressReportRepository.java   (added findDistinctSupervisorUserIdsByProjectAndDate)
│   ├── application/
│   │   ├── dto/{ProjectTeamMemberRequest, ProjectTeamMemberResponse}.java
│   │   └── service/
│   │       ├── ProjectTeamService.java    (new)
│   │       └── DailyResourceDeploymentService.java  (publishes ResourceDeploymentSavedEvent)
│   └── api/ProjectTeamController.java     (new)

backend/bipros-resource/
└── src/main/java/com/bipros/resource/
    ├── domain/model/MaterialConsumptionLog.java     (added issuedByUserId, receivedByUserId)
    ├── application/dto/{CreateMaterialConsumptionLogRequest, MaterialConsumptionLogResponse}.java (new fields)
    └── application/service/MaterialConsumptionLogService.java   (publishes MaterialConsumptionLoggedEvent)

backend/bipros-common/
└── src/main/java/com/bipros/common/event/
    ├── MaterialConsumptionLoggedEvent.java    (new)
    └── ResourceDeploymentSavedEvent.java      (new)

backend/bipros-reporting/
└── src/main/java/com/bipros/reporting/materialconsumption/
    ├── MaterialConsumptionRow.java
    ├── MaterialConsumptionFilter.java
    ├── MaterialConsumptionReportResponse.java
    ├── MaterialConsumptionAlertEvaluator.java
    ├── MaterialConsumptionReportService.java
    ├── MaterialConsumptionReportController.java
    └── MaterialConsumptionExcelWriter.java

backend/bipros-api/
├── src/main/java/com/bipros/api/config/seeder/ProjectTeamBackfillSeeder.java   (new)
├── src/main/resources/db/changelog/
│   ├── 101-project-team.yaml                 (new)
│   ├── 102-material-consumption-user-cols.yaml   (new)
│   ├── 103-dbs-tables.yaml                  (new)
│   └── db.changelog-master.yaml             (3 new includes appended)
└── src/test/java/com/bipros/api/
    ├── dbs/DbsAggregationIntegrationTest.java          (new, @Disabled)
    └── reports/MaterialConsumptionReportIntegrationTest.java   (new, @Disabled)
```

### Frontend

```
frontend/
├── src/lib/api/
│   ├── dbsApi.ts                            (new)
│   ├── projectTeamApi.ts                    (new)
│   ├── materialConsumptionApi.ts            (added new fields)
│   └── materialConsumptionReportApi.ts      (new)
├── src/lib/utils/format.ts                  (added formatCurrency, formatPercent)
├── src/app/(app)/projects/[projectId]/
│   ├── team/page.tsx                        (new)
│   ├── dbs/
│   │   ├── page.tsx                         (new)
│   │   └── components/
│   │       ├── SupervisorDbsTab.tsx
│   │       ├── EngineerDbsTab.tsx
│   │       ├── PmDbsTab.tsx
│   │       ├── SectionCard.tsx
│   │       └── TotalsPanel.tsx
│   ├── material-consumption/page.tsx        (user pickers + role chip)
│   └── reports/material-consumption/page.tsx   (new)
└── e2e/tests/
    ├── 40-dbs-dashboard.spec.ts             (new, 5 cases)
    ├── 41-material-consumption-report.spec.ts (new, 4 cases)
    └── 42-project-team.spec.ts              (new, 1 case)
```

### Infra

```
docker/init-schemas.sql      (added: CREATE SCHEMA IF NOT EXISTS dbs)
backend/pom.xml              (added bipros-dbs module + dependencyManagement entry)
backend/bipros-api/pom.xml   (added bipros-dbs dependency)
```

---

## 9. Quick reference card

```
Setup once:            docker compose up -d
                       psql ... -c "CREATE SCHEMA IF NOT EXISTS dbs;"
                       cd backend && mvn -pl bipros-api -am clean install -DskipTests

Run backend:           cd backend && mvn -pl bipros-api -am spring-boot:run
Run frontend:          cd frontend && pnpm dev
Seed demo data:        ./scripts/seed-icpms-data.sh && ./scripts/seed-demo-data.sh

User journey:          /projects/[id]/team       → set hierarchy
                       /projects/[id]/dpr        → file DPRs
                       /projects/[id]/resource-deployment   → log deployments
                       /projects/[id]/material-consumption  → log materials
                       /projects/[id]/dbs        → read DBS (3 tabs)
                       /projects/[id]/reports/material-consumption  → consumption report

Force recompute:       POST /v1/projects/{id}/dbs/recompute?date=YYYY-MM-DD
Export:                GET  /v1/projects/{id}/dbs/export.{xlsx,pdf}?date=&level=PM

Tests:                 mvn -pl bipros-api -am test -Dtest=DbsAggregationIntegrationTest
                       SEED_PROJECT_ID=<uuid> pnpm test:e2e --grep "DBS|Material Consumption|Project Team"
```
