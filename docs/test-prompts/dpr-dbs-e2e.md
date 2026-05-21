# Bipros DPR → BOQ → DBS E2E Test — execution prompt

Paste the block below verbatim into a new Claude Code session at the start of a
test run. It re-creates the same scenario, asserts the same numbers, and pauses
for your OK between phases. Diff the resulting execution log against
`docs/dpr-dbs-e2e-test-execution-log-2026-05-19.md` to spot regressions.

---

You are running a deterministic end-to-end test of the DPR → BOQ → DBS chain in
the Bipros EPPM repo at `/Volumes/Java/Projects/bipros-eppm/`. The full runbook
lives at `docs/dpr-dbs-e2e-test-runbook.md` and a prior execution log lives at
`docs/dpr-dbs-e2e-test-execution-log-2026-05-19.md` — read them first to anchor
on conventions, then walk the steps below. Pause for my OK after each phase.

## Pre-flight (no prompting)

1. Confirm backend is up at `http://localhost:8080/actuator/health` and frontend
   at `http://localhost:3000`. If backend is down, start it with
   `(cd backend && mvn -f bipros-api/pom.xml -am -Dmaven.test.skip=true spring-boot:run)`
   in the background; poll health with an `until curl -sf` loop.
2. Login as `admin / admin123` via `POST /v1/auth/login` (field name is
   **`username`**, not `usernameOrEmail`). Save the bearer token.
3. Probe master data — these counts must be non-zero, otherwise stop and tell me:
   `/v1/manpower-rate-master`, `/v1/equipment-rate-master`,
   `/v1/material-rate-master`, `/v1/work-activities`, `/v1/resource-roles`.

## Project setup

Create a fresh project (unique code so it's repeatable) using EPS parent
`MIG-CIV` (`e38edde8-b6cb-4d2c-8e16-72a8336e7c0a`). Then build:

- **WBS tree** (6 nodes): `Bridge B-1` → {`Substructure` → {`Foundation`, `Pier`},
  `Superstructure` → {`Deck`}}.
- **3 activities**, each on a WBS leaf and linked to a `workActivityId` from the
  catalogue: `RB-ACT-01 Foundation Excavation` (work activity *Pilot Excavation*,
  10d), `RB-ACT-02 Pier PCC` (*Pilot PCC*, 8d), `RB-ACT-03 Deck Concrete Works`
  (*Concrete works*, 15d). All unit = `Cum`, type `TASK_DEPENDENT`, % complete
  type `DURATION`. Plan dates anywhere inside the project window.
- **Resource Plan** via `POST /v1/projects/{pid}/role-assignments`:
  - RB-ACT-01: 5 Helper @ ₹180/Day, 1 Excavator @ ₹1,800/Day
  - RB-ACT-02: 3 Mason @ ₹380/Day, 1 Wheel Loader @ ₹1,200/Day, 20 MT Cement @ ₹85
  - RB-ACT-03: 6 Helper, 4 Mason, 50 MT Cement
- **3 BOQ items** via `POST /v1/projects/{pid}/boq/bulk`:

  | Item No | Description | Unit | BOQ Qty | BOQ Rate | Budgeted Rate | WBS leaf |
  |---|---|---|---:|---:|---:|---|
  | RB-03.01 | Unclassified Excavation | Cum | 500 | 14 | 12 | Foundation |
  | RB-04.05 | PCC Class C | Cum | 100 | 80 | 70 | Pier |
  | RB-09.02 | Concrete RCC Grade M30 | Cum | 200 | 120 | 110 | Deck |

  (Grand BOQ = **39,000**, Grand Budgeted = **35,000**.)
- **Project Team** with reports-to chain:
  PM `2165f85e-46b5-4c95-994d-588e73da5c7e` → CM `d2874836-…` →
  Engineer `827f5b6a-…` → Supervisor 1 `008b7b25-…` and Supervisor 2 `0dca68d4-…`.
- **Lock all 3 activities** — `POST /v1/projects/{pid}/activities/{aid}/lock`.
  Without this, DPRs reject with `ACTIVITY_DRAFT_DPR_REJECTED`. This is Step 12.5.

After each major sub-step pause and ask me to verify in the UI before continuing.

## Daily execution — DPRs

Pick today's date as report date (cannot be future). Submit **3 DPRs** by
Supervisor 1, one per activity, with `manpowerRoleRateId`, `equipmentRoleVariantId`
and `materialRoleVariantId` populated on every cost row (critical — see Gotcha 1):

| DPR | activity | BOQ | qtyExecuted | Manpower | Equipment | Material |
|---|---|---|---:|---|---|---|
| 1 | RB-ACT-01 | RB-03.01 | 50 | 5 Helper @180 | 1 Excavator @1800 | — |
| 2 | RB-ACT-02 | RB-04.05 | 20 | 3 Mason @380 | 1 Wheel Loader @1200 | 2 MT Cement @85 |
| 3 | RB-ACT-03 | RB-09.02 | 10 | 6 Helper @180 + 4 Mason @380 | — | — |

Each line cost = `unitRate × nos` only (no × hours — the new formula from
commit `4883a951`).

## Section G — General Expenses (monthly overheads)

Project creation should automatically seed **20 default plan items** (PRE-sheet
template: Electricity, Water, Rent, Staff Welfare, Safety, Medical, Printing,
Communication, Business Promotion, Travel, Legal, Consultant Overtime, Repairs,
Lab Testing, Other Misc, Depreciation (LS), Insurance (0.015 % of CV, formula
flag set), Bank Charges (0.01 % of CV, formula flag set), Contingency,
Accommodation). Verify by `GET /v1/projects/{pid}/general-expenses/plan-items`
— count must be 20 with `sortOrder` 1..20 and the two formula rows carrying
`formulaType=PCT_CONTRACT_VALUE`.

Update three plan items (`PUT /general-expenses/plan-items/{id}`):

| Item | planQty | planAmount |
|---|---:|---:|
| Electricity Charges | 12 | 12000 |
| Water & Sewage Charges | 43 | 4300 |
| Rent Land & Office, Accommodation | 100 | 100000 |

Log two May 2026 actuals (`PUT /general-expenses/actuals/{planItemId}?yearMonth=202605`):

- Electricity: `{achievedQty:1, achievedAmount:1000}`
- Water & Sewage: `{achievedQty:1, achievedAmount:350}`

Expected after upsert:
- `GET /general-expenses/actuals?yearMonth=202605` → `monthlyTotal=1350.00`,
  two rows with non-null `actual`, 18 rows with `actual: null`.
- Backend log contains
  `DbsRecomputeListener.onGeneralExpense received ... yearMonth=202605 ... type=CREATED`
  for each upsert — the listener recomputes every day in the affected month.
- `GET /dbs/project?date=2026-05-15` → `generalExpenseAmount = 43.55`
  (= 1350 / 31), `generalExpenseMonthlyTotal = 1350.00`,
  `generalExpenseLinesJson` carries Electricity (32.26) + Water (11.29).
- `GET /dbs/project?date=2026-05-15&periodType=MONTH` → `totals.generalExpenseAmount`
  is the sum of daily prorations (≈ 1350.05; ≤ 5 paise rounding drift over 31
  days is expected — the snapshot field stays at the exact 1350.00).
- `totalExpense` on the day row includes the 43.55 — verify the project P&L
  shifts when Section G changes.

Section G is **PM-tier only**: confirm that
`GET /dbs/supervisor/{userId}?date=...`,
`/engineer/{userId}?date=...`, and `/cm/{userId}?date=...` have no
`generalExpense*` fields and do **not** count this 43.55 toward their own
`totalExpense` rows.

## Material Consumption Logs

3 MCLs via `POST /v1/projects/{pid}/material-consumption`:

- 100 kg Cement OPC 43 on RB-ACT-02 (resource `ca1b87f7-…`)
- 2 MT Steel Fe500 on RB-ACT-03 (resource `2c556793-…`)
- 5 Cum Aggregate 20mm on RB-ACT-03 (resource `482bb26b-…`)

Expected MCL line_costs: **320, 1,240, 72.50** (Σ = 1,632.50).

## Verification

Hand-calculate every number, then assert against the API. Show side-by-side
Expected vs Actual tables. The targets are:

**BOQ master:**

| BOQ | Qty Exec | %compl | Actual Rate | Actual Amt | Cost Variance |
|---|---:|---:|---:|---:|---:|
| RB-03.01 | 50 | 10.00% | **54.00** | **2,700** | +2,100 |
| RB-04.05 | 20 | 20.00% | **141.50** | **2,830** | +1,430 |
| RB-09.02 | 10 | 5.00% | **391.25** | **3,912.50** | +2,812.50 |
| Grand | | 8.86% | | **9,442.50** | **+6,342.50** |

**DBS Supervisor 1 row:** income 3,500, manpower 4,640, machinery 3,000,
material 170, totalExpense 7,810, contribution **−4,310**, contributionPct
**−1.2314** (−123.14%), pctAchieved 8.9744, directCost 3,500, prelimCost 0.

**DBS Engineer row:** identical to Sup1 (Sup2 has no DPRs).

**DBS CM row:** same numbers BUT `contributionPct` returns as **−123.1429**
(percentage scale, not fraction — Gotcha 3).

**DBS PM (project) row:** materialAmount **1,802.50** (= 170 supervisor + 1,632.50
MCL fold-in at project pass), totalExpense **9,442.50**, contribution **−5,942.50**,
contributionPct **−1.6979** (−169.79%).

Then test:
- Idempotency: 3× `POST /dbs/recompute?date=...` — numbers must stay bit-identical,
  only `version` and `recomputedAt` change.
- Range recompute: `POST /dbs/recompute-range?from=X&to=Y` over 3 days — 2 empty
  days + the test day.
- Excel export: `GET /dbs/export.xlsx?date=...&level=PM` — HTTP 200, content-type
  `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, non-zero
  size, file magic `Microsoft OOXML`.
- PDF export: `GET /dbs/export.pdf?date=...` — HTTP 200, `application/pdf`,
  non-zero size, valid PDF.

## Gotchas to actively check (regression guards)

1. **DPR cost rows MUST carry `manpowerRoleRateId` / `equipmentRoleVariantId` /
   `materialRoleVariantId`** — without them `BoqActualRateRecalcListener` joins
   `resource_assignments` on a NULL key and BOQ `actualRate` stays at 0.
   In the runbook execution log, I bypassed this initially by posting bare DPR
   rows. The production UI form sends these IDs automatically.
2. **MCL events don't re-trigger BOQ actualRate** — `BoqActualRateRecalcListener`
   only listens to `DprSubmittedEvent`. If you post MCLs *after* DPRs, BOQ
   actualRate stays stale until a DPR re-fires. The clean test order is: lock
   activities → DPR (with role-rate IDs) → MCL → re-PUT DPR (or use the bug-fix
   path if it's been merged). Bug filed.
3. **CM-tier `contributionPct`** is scaled differently (percentage value, not
   fraction). Don't fail the assertion — flag it instead.
4. **`% Achieved` tile** is missing from the DBS Supervisor tab UI even though
   API returns `pctAchieved`. Flag if still missing.
5. **Activities default to DRAFT** — DPR will reject until you lock with
   `POST /v1/projects/{pid}/activities/{aid}/lock`.
6. **Rate-master endpoints are singular** (`/v1/manpower-rate-master`, not
   `/v1/equipment-rate-masters`). Don't get tricked by the runbook's earlier
   drafts.
7. **Section G seeding only fires on `ProjectCreatedEvent`** — projects created
   before the listener landed will have **zero** plan items. The page still
   loads (returns empty list); you have to either re-create the project or
   POST the 20 items manually. Memory hint: a fresh project (`POST /v1/projects`)
   should produce an INFO log line
   `Section G seeded 20 default items for project <uuid>` within ~200 ms of the
   create response.
8. **Section G writes use `SecurityContextHelper.getCurrentUserId()`** which
   throws `IllegalArgumentException` when the principal's username isn't a UUID
   (e.g. the seeded `admin` user). The controller swallows this and stores
   `loggedByUserId=null` — that's expected for admin-driven smoke tests, not a
   regression.
9. **Section G monthly entry triggers project-day recompute for every date in
   the month**, not just the entry's date. If your test date is outside the
   logged month, expect `generalExpenseAmount=0` on that day even after upsert.

## Verification gates

After EACH of these, stop and ask me OK before continuing:

- Project created (Step 7)
- WBS built (Step 8)
- Activities + Resource Plan (Steps 9 + 9.5)
- BOQ items + Team chain (Steps 11 + 12)
- Activities locked (Step 12.5)
- Section G — plan seeded (20 rows), 3 plan amounts edited, 2 monthly actuals logged, PM rollup shows daily proration ≈ 43.55 (Step 12.7)
- DPRs submitted (Step 13)
- MCLs logged (Step 14)
- BOQ verified (Step 15)
- DBS Supervisor verified (Step 16)
- Engineer / CM / PM verified (Step 17)
- Idempotency + exports (Step 18)

## Deliverables at the end

1. A markdown execution log mirroring
   `docs/dpr-dbs-e2e-test-execution-log-2026-05-19.md` — same structure, same
   Expected-vs-Actual tables, populated with the current run's UUIDs and any
   findings. Save it to `docs/dpr-dbs-e2e-test-execution-log-{TODAY}.md`.
2. A short pass/fail summary at the top of that file.
3. Updated findings list — keep Findings 5, 7, 8 from the prior run and add any
   new ones you observe; mark previously-filed ones as fixed if they no longer
   reproduce.
4. **Do not** commit or push automatically. Ask me first.
5. Use parallel agents (Explore + general-purpose) ONLY for read-only API/code
   investigation; never for mutations. All POSTs / PUTs / DELETEs happen on the
   main agent so the audit trail is one thread.

If anything diverges from the expected numbers by more than a rounding-error
amount, stop and explain the divergence before continuing. Don't paper over
mismatches.
