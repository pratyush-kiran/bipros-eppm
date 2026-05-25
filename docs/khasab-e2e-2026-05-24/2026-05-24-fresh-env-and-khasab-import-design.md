# Fresh Environment + Khasab Real-Data E2E — Design

**Date:** 2026-05-24
**Author:** Hemendra (driver) + Claude (implementer)
**Status:** Draft for review
**Related:**
- `docs/test-prompts/dpr-dbs-e2e.md` (synthetic-data runbook the user supplied as primary reference)
- `docs/dpr-dbs-e2e-test-runbook.md` (master runbook)
- `docs/dpr-dbs-e2e-test-execution-log-2026-05-19.md` (most recent successful execution log)
- `docs/ActualData/1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx` (real source data — double-space in filename)
- `docs/ActualData/3. Supervisor-Engineer-CM-PM DBS (2).xlsx` (activity master sheet)

## Goal

Wipe the live Bipros EPPM database back to system-master baseline, then drive a deterministic end-to-end run against **real** Khasab project DPR data (Jan–Mar 2026 sheets, ~26,920 source rows). Produce a single consolidated HTML report covering every phase, every validation, the AI test pass/fail matrix, and the data-generation logic.

This is a much broader task than the synthetic runbook (which exercises 3 DPRs against 3 activities). This run exercises the full DPR → BOQ → DBS → Productivity → Capacity → Dashboard → AI chain against a production-shaped dataset, with eight real supervisors, two engineers, a project-control / CM, and a project manager.

## Non-goals

- **No BOQ construction for Khasab.** The Excel data has no contract-line BOQ. Synthesizing one would fabricate validation numbers and obscure what the dataset actually tests. BOQ validation is explicitly skipped (and called out in the HTML report as deferred).
- **No production deployment** — dev environment only.
- **No re-implementation of features.** Productivity Norms, Capacity Utilisation, Subcontractor mappings, and AI tools already exist and are exercised, not modified.
- **No baselines, no risk register, no contracts** — out of scope for this run.

## Inputs verified during brainstorming exploration

| Aspect | Finding |
|---|---|
| Live DB | `bipros@127.0.0.1:5432` (native Homebrew Postgres, not docker), 21 schemas, 249 tables, **21,667 historical DPRs + 122K log rows across 3 demo projects** (RD-AUDIT-001, 6155, SC-180) — all wipe candidates. |
| Active profile | `dev`. All `@Profile("seed")` and `@Profile("legacy-demo")` seeders already inert. |
| Boot-critical seeder | `DataSeeder` (roles + admin user + calendar + currency). Required. |
| Disableable backfills | `bipros.dbs.backfill.enabled`, `seeders.legacy.enabled`, `analytics.bootstrap.enabled`, `bipros.backfill.legacy-daily-output.enabled`. |
| Always-on seeders | `ResourceTypeSeeder`, `ManpowerMasterSeeder`, `*RateMasterBackfillSeeder` (idempotent / skip-if-exists). Safe. |
| ProjectCreatedEvent listeners | `DefaultFolderSeeder` (document folders) + `GeneralExpenseService` (Section G 20-row plan). Both required for DPR functionality. |
| Excel real path | `docs/ActualData/1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx` (double-space — NOT the `Hemu-` copy). 3 monthly sheets, 26,920 total data rows, 8 supervisors, 50+ activity codes, 10 manpower roles. **Date column inside is 2025-01-24 → 2025-03-29, despite sheet/filename saying 2026.** Decision: shift +1 year at parse time so dates land inside the 2026 project window. |
| DPR bulk endpoint | `POST /v1/projects/{pid}/dpr/bulk` exists. |
| MCL bulk endpoint | `POST /v1/projects/{pid}/material-consumption/bulk` exists. |
| Productivity Norms | Entity + endpoint exist: `POST /v1/productivity-norms/bulk`. |
| Capacity Utilisation | Reports + AI insights endpoint exist: `/v1/reports/capacity-utilization`. |
| AI tool surface | 77 tools across 10+ domains. DBS Financial supports all 4 tiers (PROJECT/ENGINEER/SUPERVISOR/CM). |
| Backend AI prerequisite | `BIPROS_AI_KEK` must be set or `/v1/ai/chat` returns empty text. |

## Decisions locked in with user

1. **DB cleanup**: backup → TRUNCATE transactional, keep system masters (roles, permissions, rate masters, work activities, resource roles, category masters, admin user).
2. **Date handling**: shift +1 year (2025 → 2026) at parse time so dates fall inside the project window.
3. **Cadence**: single long session, pause-for-OK after each of the 14 phases.
4. **Agent team**: 3-agent per phase — implementer, reviewer, devil's advocate. All mutations on main thread for single audit trail.

## Architecture

### Agent team protocol (per phase)

```
                ┌────────────────────────────────────┐
                │  Main thread (this Claude)         │
                │  - synthesizes                     │
                │  - asks user for OK                │
                │  - executes mutations (curl/SQL)   │
                │  - updates HTML log                │
                └────────┬──────────┬──────────┬─────┘
                         │          │          │ dispatches (parallel)
              ┌──────────▼──┐ ┌─────▼──────┐ ┌─▼──────────────┐
              │ Implementer │ │ Reviewer   │ │ Devil's        │
              │ (general)   │ │ (Explore)  │ │ Advocate       │
              │ drafts work │ │ verifies   │ │ (Explore)      │
              │ + commands  │ │ vs spec    │ │ challenges     │
              └─────────────┘ └────────────┘ └────────────────┘
```

### Tooling stack

- **DB**: `psql -h 127.0.0.1 -U bipros -d bipros`
- **Excel parse**: Python venv at `/tmp/xlsx_venv` with openpyxl + pandas
- **API**: `curl` with admin JWT; rate-limit to 5 req/s to avoid overwhelming the backend
- **Browser checks**: Playwright MCP (`mcp__plugin_playwright_playwright__browser_*`) at key gates
- **Task tracking**: TaskCreate per phase, mark in_progress on entry, completed on exit
- **Logging**: append to `docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md` as we go; HTML report generated last from the markdown log

### Deliverables

| File | Format | Purpose |
|---|---|---|
| `docs/dpr-dbs-e2e-execution-log-2026-05-24.html` | Single self-contained HTML | The user's requested consolidated artifact |
| `docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md` | Markdown | Mirrors prior 2026-05-{19,20,21} logs for diff-ability |
| `docs/ActualData/exports/khasab-dpr-2026-05-24.csv` | CSV | Flat DPR export for QA/external testing |
| `docs/ActualData/exports/khasab-dpr-2026-05-24.xlsx` | Excel (4 sheets) | DPR + By-Supervisor + By-Activity + By-Date pivots |
| `docs/superpowers/specs/2026-05-24-fresh-env-and-khasab-import-design.md` | Markdown | This spec |
| `/tmp/bipros-backup-2026-05-24.dump` | pg_dump custom format | Pre-wipe backup for rollback |
| `/tmp/khasab-dpr-parsed.json` | JSON | Normalized intermediate import payload |
| `/tmp/khasab-dpr-validation_report.md` | Markdown | Pre-import validation findings |
| `/tmp/ai-ground-truth.json` | JSON | Precomputed answers for the 50 AI-test questions |

## Phases

### Phase 1 — DB cleanup (backup → wipe transactional → restart)

**1.1 Backup**

```bash
pg_dump -h 127.0.0.1 -U bipros -F c -f /tmp/bipros-backup-2026-05-24.dump bipros
ls -lh /tmp/bipros-backup-2026-05-24.dump
pg_restore --list /tmp/bipros-backup-2026-05-24.dump | wc -l  # smoke test
```

**1.2 TRUNCATE plan** (FK-safe order)

Wipe (TRUNCATE ... CASCADE where safe):
- All tables in schemas: `project`, `activity`, `scheduling`, `cost`, `evm`, `baseline`, `dbs`, `udf`, `portfolio`, `contract`, `document`, `permit`, `safety`, `site_ops`, `ai`, `gis`, `analytics`
- `risk.*` rows EXCEPT `risk_category_master` and `risk_templates`
- `resource.*_assignments`, `resource.daily_*`, `resource.material_consumption_logs`, `resource.productivity_norms`, `resource.role_assignments`, `resource.resource_assignments`
- `public.audit_log`
- `public.users` WHERE `username != 'admin'`

Keep:
- `public.users` (admin only), `public.roles`, `public.permissions`, `public.profile_permissions`, `public.role_permissions`
- `public.calendars`, `public.currencies`, `public.evm_settings`
- `resource.manpower_rate_masters` (16), `resource.equipment_rate_masters` (57), `resource.material_rate_masters` (33)
- `resource.work_activities` (178), `resource.resource_roles` (207)
- All `*_category_master` and `*_master` rows in `resource` and `project`
- `risk.risk_category_master` + `risk.risk_templates`

Implementer drafts the SQL; reviewer verifies FK order; devil's advocate hunts for transactional tables I forgot (e.g. session tokens, refresh tokens, AI conversation cache).

**1.3 Disable backfill seeders** in `application.yml` (or via `-D` flags at boot):

```yaml
bipros:
  dbs:
    backfill:
      enabled: false
  backfill:
    legacy-daily-output:
      enabled: false
seeders:
  legacy:
    enabled: false
  excel-master:
    enabled: false
analytics:
  bootstrap:
    enabled: false
```

`ResourceTypeSeeder` / `ManpowerMasterSeeder` / `*RateMasterBackfillSeeder` stay on (idempotent, skip-if-exists).

**1.4 Restart backend + frontend**

```bash
# kill backend
pkill -f "bipros-api" || true
sleep 2

# boot backend in background; -am pulls fresh sibling jars (memory: dev_maven_stale_m2_gotcha)
(cd backend && mvn -f bipros-api/pom.xml -am -Dmaven.test.skip=true spring-boot:run) &

# poll health
until curl -sf http://localhost:8080/actuator/health > /dev/null; do sleep 5; done

# confirm BIPROS_AI_KEK is set (memory: dev_ai_kek)
test -n "$BIPROS_AI_KEK" || echo "WARN: BIPROS_AI_KEK not set — AI tests will return empty"

# restart frontend if not running
(cd frontend && pnpm dev) &
until curl -sf http://localhost:3000 > /dev/null; do sleep 3; done
```

**1.5 Verification queries**

```sql
SELECT COUNT(*) FROM project.daily_progress_reports;     -- expect 0
SELECT COUNT(*) FROM project.projects;                    -- expect 0
SELECT COUNT(*) FROM public.users;                        -- expect 1
SELECT username FROM public.users;                        -- expect 'admin'
SELECT COUNT(*) FROM public.roles;                        -- expect 22
SELECT COUNT(*) FROM resource.manpower_rate_masters;      -- expect 16
SELECT COUNT(*) FROM resource.equipment_rate_masters;     -- expect 57
SELECT COUNT(*) FROM resource.material_rate_masters;      -- expect 33
SELECT COUNT(*) FROM resource.work_activities;            -- expect 178
```

Backend log scan: no `Section G seeded N default items` lines for non-existent projects; no `DbsBackfillSeeder` activity; admin user reseeded if needed (DataSeeder is `@Profile("dev","seed")` and `dev` is active).

**Gate:** pause for user OK.

### Phase 2 — Frontend smoke + admin login

Use Playwright MCP browser tools:
- Navigate `http://localhost:3000`
- Login as `admin / admin123`
- Confirm dashboard renders zero-state cleanly
- Capture: page screenshot, console messages (any error?), network errors (4xx/5xx?)
- Save screenshot to `frontend/e2e/.artifacts/screenshots/2026-05-24-phase2-empty-dashboard.png`

**Gate:** pause for user OK.

### Phase 3 — Source files resolved + parsed

**3.1 Resolve actual files**

```bash
ls -la "docs/ActualData/1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx"
ls -la "docs/ActualData/3. Supervisor-Engineer-CM-PM DBS (2).xlsx"
```

**3.2 Parse Khasab workbook with Python**

`/tmp/parse_khasab.py`:
- Read 3 sheets (`Jan-2026`, `Feb-2026`, `March-2026`) starting at header row 4
- For each row: extract date, site, activity code, unit, executed qty, supervisor (`Name`), manpower category, count, rate, equipment, material, subcontractor
- **Apply date shift: `date = date + relativedelta(years=1)`**
- Group rows by `(date, activity_code, supervisor_name)` → one DPR per group
- Aggregate manpower lines, equipment lines, material lines under each DPR header
- Map supervisor names to canonical usernames (e.g. `Mohd Ismaila` → `ismaila`)
- Map activity codes to WBS leaf paths (built in Phase 5)
- Map manpower roles to rate-master IDs (resolved after Phase 6)
- Write `/tmp/khasab-dpr-parsed.json` + `/tmp/khasab-dpr-validation_report.md`

**3.3 Validation report contents**

- Total source rows by sheet vs total DPRs produced (aggregation collapse ratio)
- Unknown supervisor names (rows that don't map to one of the 8 canonical supervisors)
- Unknown activity codes (rows that don't fit the WBS hierarchy)
- Date outliers (anything outside the +1y shifted window)
- Rows with missing rates or zero quantities

### Phase 4 — User creation (12 users)

| Username | Display | Role | Reports To |
|---|---|---|---|
| ravi | RAVI | PROJECT_MANAGER | (top) |
| rahul | Rahul | PROJECT_CONTROL / SITE_MANAGER (CM-tier DBS access) | ravi |
| hemendrase | HemendraSE | SITE_ENGINEER | rahul |
| subratse | SubratSE | SITE_ENGINEER | rahul |
| anirban | Anirban Datta | SUPERVISOR | hemendrase |
| illayaraja | Illayaraja | SUPERVISOR | hemendrase |
| kbarman | K. Barman | SUPERVISOR | hemendrase |
| parvaiz | Parvaiz | SUPERVISOR | hemendrase |
| saiffuddin | Md Saiffuddin | SUPERVISOR | subratse |
| ismaila | Mohd Ismaila | SUPERVISOR | subratse |
| sanjar | Sanjar Alam | SUPERVISOR | subratse |
| vijaykumar | VijayKumar | SUPERVISOR | subratse |

All passwords `Password@123`, emails `<username>@bipros.test`.

Reviewer agent checks `dev_rbac_layout` memory and `bipros-security` module for the exact role name that grants CM-tier DBS access (PROJECT_CONTROL vs CONSTRUCTION_MANAGER vs SITE_MANAGER — confirm which enum value the `DbsFinancialTool` checks).

Endpoints:
```
POST /v1/users                    # create user
POST /v1/users/{id}/roles         # assign role(s)
```

**Validation gate:** each user logs in via `POST /v1/auth/login` and JWT contains expected roles + permissions.

### Phase 5 — Project + WBS

**Project payload**:
```json
{
  "code": "KHASAB-2026",
  "name": "Khasab Road Project 2026",
  "epsParentId": "e38edde8-b6cb-4d2c-8e16-72a8336e7c0a",
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "contractValue": 50000000,
  "currencyCode": "INR",
  "ownerId": "<ravi user id>"
}
```

Contract value (₹5 crore placeholder) lets Section G insurance (0.015% CV) and bank-charges (0.01% CV) formula rows produce non-zero values during DBS rollup.

**WBS hierarchy** (created via `POST /v1/projects/{pid}/wbs`):

```
KHASAB-2026 (root)
├── 1.0 Preliminaries
│   └── 1.3 Soil Investigation                ← activity 1.3.5(i)a–z
├── 2.0 Sub-structure
│   ├── 2.3 Bored Cast In-Situ Piling         ← activities 2.3.6(i)*
│   ├── 2.4 Pile Cap                          ← activities 2.4.6(i)*
│   ├── 2.6 Pier                              ← activities 2.6.6(i)*
│   └── 2.7 Abutment                          ← activities 2.7.6(i)*
├── 3.0 Super-structure
│   └── 3.2 Concrete RCC                      ← activities 3.2.6(ii)*
├── 5.0 Bearings                              ← activities 5.1.7(iii)*
├── 9.0 Approach Slab                         ← activities 9.1.6(ii)*
├── 13.0 Drainage                             ← activities 13.1.7(ix)*
└── 18.0 Pavement                             ← activities 18.3.6(i)*
```

**Activity creation rule**: each activity sits on a WBS leaf, carries a `workActivityId` from the existing 178-row catalogue. If the catalogue lacks a code, add to work-activity master first via `POST /v1/work-activities`. Plan dates default to full project window; activity owner = primary supervisor observed in data for that code.

**Validation gate:** WBS tree page renders all leaves; activity list returns N entries; Section G plan items auto-seeded (20 rows with sortOrder 1–20, two formula rows).

### Phase 6 — Master data audit + augmentation

**Strategy**: SIMPLE round-number rates. Audit existing rate-master rows; only ADD what's missing — don't update existing values (avoids drift with other projects in the backup).

**Manpower** (₹/day):

| Role | Rate |
|---|---:|
| Helper | 500 |
| Mason | 800 |
| Carpenter | 800 |
| Steel Fixer | 900 |
| Rigger | 900 |
| Scaffolder | 900 |
| Bankman | 700 |
| Chargehand | 1,000 |
| Foreman | 1,200 |
| Supervisor | 1,500 |

**Equipment** (₹/day):

| Equipment | Rate |
|---|---:|
| Vibrator | 500 |
| Concrete Mixer | 2,000 |
| Wheel Loader | 3,000 |
| Truck | 4,000 |
| Excavator | 5,000 |
| Crane | 8,000 |

**Material**:

| Material | Unit | Rate (₹) |
|---|---|---:|
| Cement OPC 43 | kg | 10 |
| Steel Fe500 | kg | 70 |
| Aggregate 20mm | cum | 20 |
| Sand | cum | 15 |
| Water | litre | 0.50 |

**Units** in active use: `cum`, `sqm`, `m`, `km`, `kg`, `MT`, `litre`, `nos`, `hour`, `day`.

**Validation gate**: `SELECT name, rate FROM resource.manpower_rate_masters` matches table; same for equipment + material.

### Phase 7 — Subcontractor setup

- Create 2 generic subcontractor masters: `Generic-Sub-A`, `Generic-Sub-B` (rate ₹600/day per worker).
- Map to RCC + piling activities via `SubContractorWorkActivityMapping`.
- During DPR import, rows with non-empty `Subcontract Name` column attach to one of these (or new SC created on-the-fly if name doesn't match).

### Phase 8 — Productivity norms (bulk)

Tiered: defaults per (resource-type, activity-family) + per-activity overrides for top 10 codes by row volume.

**Defaults** (`baseUnitsPerDay` in the activity's unit):

| Resource | Excavation (cum) | Concreting (cum) | Formwork (sqm) | Pavement (sqm) |
|---|---:|---:|---:|---:|
| 1 Helper | 2 | — | — | 5 |
| 1 Mason | — | 3 | — | — |
| 1 Carpenter | — | — | 4 | — |
| 1 Steel Fixer | — | — | — | — |
| 1 Excavator | 50 | — | — | — |
| 1 Wheel Loader | 80 | — | — | — |
| 1 Concrete Mixer | — | 10 | — | — |

**Per-activity overrides**: top 10 activity codes by Excel row volume get specific norms derived from observed `(qty_executed / crew_size)` ratios across all DPRs for that code.

Endpoint: `POST /v1/productivity-norms/bulk` with the full set.

**Reviewer check**: per-activity overrides are within ±30% of defaults (else either default is wrong or data has an anomaly worth flagging).

### Phase 9 — DPR import (layered)

**9.1 Pre-import dry run** — Python script produces JSON + validation_report.

**9.2 Lock all activities** (runbook gotcha #5):
```bash
for aid in $(jq -r '.activities[].id' /tmp/khasab-state.json); do
  curl -sS -X POST "localhost:8080/v1/projects/$PID/activities/$aid/lock" \
       -H "Authorization: Bearer $TOKEN" >/dev/null
done
```

**9.3 Import Jan-2026 first** — `POST /v1/projects/{pid}/dpr/bulk` in batches of 25 (devil's advocate flagged 100 as potentially timeout-prone). Every cost row carries `manpowerRoleRateId` / `equipmentRoleVariantId` / `materialRoleVariantId` (runbook gotcha #1). Idempotent: catch `DPR_ALREADY_EXISTS_FOR_ACTIVITY` and skip.

**9.4 Spot-check Jan** — pick 3 random DPRs in DB, compare quantities/costs/date/supervisor against Excel. Pause for user OK.

**9.5 Import Feb + March** — same flow.

**9.6 Material Consumption Logs** — separate pass. After each MCL, re-PUT the parent DPR so `BoqActualRateRecalcListener` fires (runbook gotcha #2 / open Finding 5).

**9.7 Force DBS recompute**:
```bash
curl -sS -X POST "localhost:8080/v1/projects/$PID/dbs/recompute-range?from=2026-01-24&to=2026-03-29" \
     -H "Authorization: Bearer $TOKEN"
```

**Expected dataset after Phase 9**:
- ~1,000–1,500 unique DPRs (depending on aggregation collapse ratio)
- 8 supervisors active, 50+ activity codes used, ~54 working days

### Phase 10 — Resource planning validation

- `GET /v1/projects/{pid}/role-assignments?activityId=X` per activity → confirm planned crew
- 5-activity spot check: hand-calculate planned vs actual, compare to API
- Verify all activities still locked

### Phase 11 — Application validation (per-module screen sweep)

Per-screen Playwright navigation + screenshot + SQL ground-truth comparison.

| Module | URL | Ground-truth SQL | What to verify |
|---|---|---|---|
| Executive dashboard | `/dashboards/executive` | `count distinct projects, sum(actualCost)` | KPI tiles match |
| Project overview | `/projects/{pid}/overview` | `sum dpr.actualCost` | Hero KPIs |
| WBS tree | `/projects/{pid}/wbs` | activity counts per leaf | Tree structure + rolled metrics |
| BOQ | `/projects/{pid}/boq` | (skipped — see Non-goals) | n/a |
| DPR list | `/projects/{pid}/dpr` | `count(*) per supervisor per month` | Filtered list totals |
| Productivity | `/productivity-norms` | `count from resource.productivity_norms` | Bulk-created norms visible |
| Capacity util | `/reports/capacity-utilization` | resource-hour totals per role per week | Pivot rendering |
| DBS Supervisor tab | per-user | recompute output | Income/expense/contribution per supervisor |
| DBS Engineer | per-engineer | rollup of his supervisors | Aggregated row |
| DBS CM | rahul | rollup of both engineers | Aggregated row; check Finding 8 (% scale) and Finding 9 (missing totalExpense/contribution) |
| DBS PM | ravi | project-wide total | Including Section G fold-in |
| Field summary | `/projects/{pid}/dashboards/field/summary` | crew-counts per day | Today shows zero (no DPR today) |

BOQ skipped (rationale in Non-goals).

### Phase 12 — AI validation (50 questions)

**Pass A — ground-truth precompute**: SQL per question → `/tmp/ai-ground-truth.json`.

**Pass B — execute + grade**: `/v1/ai/chat` per question; capture response + tool-invocation trace.

Grades: PASS (within 1%), PARTIAL (correct but missing context / wrong tool), FAIL (wrong number / hallucination / refusal).

**Stop-condition** (memory: `feedback_ai_test_stop_on_repeat`): abort after 3 identical canned answers.

**Question family distribution** (50 total):

| Family | Tools exercised | # |
|---|---|---:|
| DPR summary (count, total cost, by month, by supervisor) | QueryDpr, GetDprDetails | 8 |
| Resource utilization (per role per day, peak day, idle) | DeploymentUtilization, LabourReturns | 6 |
| Productivity (norm vs actual, factor analysis) | QueryProductivityNorm, CompareActualVsNorm, AnalyzeProductivityFactor | 6 |
| Capacity utilization (weekly pivot, top role, gaps) | CapacityUtilizationInsights | 4 |
| Cost analysis (per activity, per supervisor, per month) | AnalyzeCost, CostBreakdown, ProjectCostSummary | 6 |
| Activity/WBS (status snapshot, % complete, schedule) | GetActivityFullContext, QueryWbs, ListActivities | 5 |
| Materials (consumption, top consumer, by activity) | MaterialsTool, EquipmentLog | 4 |
| DBS financial (per tier — Supervisor, Engineer, CM, PM) | DbsFinancial at each level | 6 |
| Cross-domain (e.g. "which supervisor exceeded labour norm in Feb?") | multi-tool | 5 |

**Backend prerequisite check**: confirm `BIPROS_AI_KEK` is set or `/v1/ai/chat` returns empty text (memory: `dev_ai_kek`).

### Phase 13 — Error handling protocol

For each anomaly: capture → triage → decide (fix-now / work-around / accept-as-finding) → document → re-validate. Finding numbering continues from prior log (current open: 5, 7, 8, 9 — new findings start at 10).

### Phase 14 — Export

CSV + 4-sheet XLSX. SQL-generated, not API-generated.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Bulk DPR endpoint times out on 100-row batch | Drop batch size to 25, parallelize across activities |
| Date-shift collides with existing DPRs from a prior run | TRUNCATE in Phase 1 already wipes them |
| Productivity norms cause DPR validation to reject | Norms are advisory in this codebase (verified during exploration); won't reject |
| AI conversation cache poisoned by old project data | TRUNCATE `ai.conversations` + `ai.messages` in Phase 1 |
| Disk space for backup + HTML report + Excel | `/tmp` has 100+GB on dev machine, no concern |
| Frontend session expires during multi-hour run | Re-fetch JWT per phase, store in `$TOKEN` env |
| Playwright tests block on dev-server reload | Restart dev server only in Phase 1; never again |
| AI grader is generous to a fault | Devil's advocate agent reviews PARTIAL grades skeptically |
| Backend log fills disk over 4–8h run | Tail-rotate not needed; warn at 1GB |
| Spec → execution drift mid-run | Update the markdown log live; HTML report generated last from log |
| `DataSeeder` re-creates demo users on restart | DataSeeder is idempotent (skip-if-exists); demo users present from prior runs are wiped in 1.2 before restart |
| Section G insurance/bank-charges formulas reference zero CV | Contract value 50M set on project at create-time |

## Validation gates (consolidated)

The user OKs after EACH of:
1. Phase 1 — DB wipe + restart
2. Phase 2 — Frontend smoke
3. Phase 3 — File parse + validation report
4. Phase 4 — Users created
5. Phase 5 — Project + WBS
6. Phase 6 — Master data
7. Phase 7 — Subcontractors
8. Phase 8 — Productivity norms
9. Phase 9.4 — Jan spot-check (before Feb+March import)
10. Phase 9 final — full import + recompute
11. Phase 10 — Resource planning
12. Phase 11 — App screens
13. Phase 12 — AI matrix
14. Phase 13 — Findings reviewed
15. Phase 14 — Exports + HTML report

## Open questions

None at time of writing — all critical decisions answered during brainstorming.

## Out-of-scope follow-ups (deferred)

- BOQ construction for Khasab (no source data)
- Performance baseline for bulk DPR endpoint (would need 26K-row stress test in isolation)
- Multi-project cross-validation (only one project in this run)
- Role-permission matrix re-audit (using existing 22/76)
- Frontend Playwright assertion tests (we only screenshot for visual review)

## References

- Runbook: `docs/dpr-dbs-e2e-test-runbook.md`
- Prior log to mirror: `docs/dpr-dbs-e2e-test-execution-log-2026-05-19.md`
- User's task prompt: `docs/test-prompts/dpr-dbs-e2e.md`
- Memories used: `dev_dual_postgres`, `dev_rbac_layout`, `dev_ai_kek`, `dev_dbs_module_layout`, `dev_ai_tool_layout`, `dev_project_team_hierarchy`, `feedback_parallel_agent_phases`, `feedback_ai_test_stop_on_repeat`, `dev_maven_stale_m2_gotcha`
