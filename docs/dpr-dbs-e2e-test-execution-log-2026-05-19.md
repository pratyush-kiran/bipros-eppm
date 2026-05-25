# DPR → BOQ → DBS End-to-End Test — Execution Log

**Date:** 2026-05-19  
**Project under test:** `RUNBOOK-E2E` — *Runbook E2E Test Project*  
**Project ID:** `817bd873-f780-4be6-acd6-00259accb8b7`  
**EPS parent:** `MIG-CIV` — Civil Engineering Division  
**Period covered:** 2026-05-19 (a single day)  
**Backend commit at start:** `4883a951 fix(dbs,kpi): drop hours multiplier from cost (nos × rate)`  
**Backend commit during run:** picked up `95df0394 fix(dbs): dedupe BOQ cumulative at each rollup tier + relabel PM tiles` on a backend restart  
**Mode:** click-through walkthrough with API-driven setup + UI verification gate after each step

This file captures every step actually executed, with the hand-calculated **Expected** value and the **Actual** value returned by the API, plus any deviation or finding worth recording.

---

## Phase 1 — Master data (skipped, pre-existing)

Verified via API probe at the start of the run; no insertions needed.

| Catalogue | Endpoint | Count |
|---|---|---:|
| Manpower role rates | `GET /v1/manpower-rate-master` | 16 |
| Equipment role rates | `GET /v1/equipment-rate-master` | 57 |
| Material role rates | `GET /v1/material-rate-master` | 33 |
| Resource catalogue | `GET /v1/resources` | 33 (MATERIAL slice) |
| Resource roles | `GET /v1/resource-roles` | 207 |
| Work activities (productivity-norm catalogue) | `GET /v1/work-activities` | 178 |

**Finding 1 — endpoint name discrepancy:** the runbook draft called the endpoints `/v1/manpower-rate-masters` (plural). The real path is **singular** — `/v1/manpower-rate-master`, `/v1/equipment-rate-master`, `/v1/material-rate-master`. Fixed in this log.

---

## Step 7 — Create Project

**API:** `POST /v1/projects`

**Payload:**
```json
{
  "code": "RUNBOOK-E2E",
  "name": "Runbook E2E Test Project",
  "description": "Walk-through of the full DPR -> BOQ -> DBS chain.",
  "epsNodeId": "e38edde8-b6cb-4d2c-8e16-72a8336e7c0a",
  "plannedStartDate": "2026-05-19",
  "plannedFinishDate": "2026-11-19",
  "priority": 50
}
```

| Field | Expected | Actual | ✓ |
|---|---|---|:-:|
| `id` | UUID | `817bd873-f780-4be6-acd6-00259accb8b7` | ✓ |
| `code` | `RUNBOOK-E2E` | `RUNBOOK-E2E` | ✓ |
| `status` | `PLANNED` | `PLANNED` | ✓ |
| `plannedStartDate` | `2026-05-19` | `2026-05-19` | ✓ |
| `plannedFinishDate` | `2026-11-19` | `2026-11-19` | ✓ |

**UI verify:** Project appears in `/projects` list and lands on Overview tab when opened.

---

## Step 8 — WBS tree

**API:** `POST /v1/projects/{id}/wbs` (one call per node)

| Node | Code | Level | Parent | Resulting ID |
|---|---|---:|---|---|
| Bridge B-1 | `BB1` | 1 (root) | — | `afe25a52-22e7-4c80-a445-10fee13beb10` |
| Substructure | `BB1-SUB` | 2 | BB1 | `c9c5db98-c9a7-445a-9c4b-e5dc7664aa0b` |
| Superstructure | `BB1-SUP` | 2 | BB1 | `a343ff87-18ba-4cd0-92f8-7539ff8d0402` |
| Foundation | `BB1-SUB-FND` | 3 (leaf) | BB1-SUB | `5b46315a-1aa7-46a7-966f-9ef0a596e14d` |
| Pier | `BB1-SUB-PIE` | 3 (leaf) | BB1-SUB | `08cffb76-8f9f-4902-94a4-c1a3a753e46f` |
| Deck | `BB1-SUP-DCK` | 3 (leaf) | BB1-SUP | `148423cd-4147-4692-9f6a-3c3386855e80` |

**Expected:** 6 rows in the WBS tab in hierarchical layout. **Actual:** 6 rows visible, hierarchy preserved.

---

## Step 9 — Activities

**API:** `POST /v1/projects/{id}/activities`

Three activities, one per leaf, each referencing a `workActivityId` from the catalogue:

| Code | Name | WBS leaf | Work Activity | Unit | Duration | Resulting ID |
|---|---|---|---|---|---:|---|
| `RB-ACT-01` | Foundation Excavation | Foundation | Pilot Excavation | Cum | 10d | `53eda68a-1e15-49f8-a488-8362c171e9d9` |
| `RB-ACT-02` | Pier PCC | Pier | Pilot PCC | Cum | 8d | `99c7ba7e-f0df-41e5-8540-915269b91e63` |
| `RB-ACT-03` | Deck Concrete Works | Deck | Concrete works | Cum | 15d | `4896d0ca-43dc-45e9-83c8-d8cbc860bb35` |

**Finding 2 — endpoint shape:** initial guess `POST /v1/activities` returned 404. Correct path is **`POST /v1/projects/{projectId}/activities`**. Documented.

**Expected:** Activities list shows 3 rows with auto-populated `Cum` unit. **Actual:** matched.

---

## Step 9.5 — Resource Plan (role-assignments per activity)

**API:** `POST /v1/projects/{id}/role-assignments`

8 demand rows planned. Planned costs use the new `rate × nos` formula (no × duration), so they show as daily-wage-style values:

| Activity | Resource | nos | rate/Day | `plannedCost` Expected | `plannedCost` Actual | ✓ |
|---|---|---:|---:|---:|---:|:-:|
| RB-ACT-01 | Helper / Handyman | 5 | 180 | 900 | 900.0 | ✓ |
| RB-ACT-01 | 20-Ton Excavator | 1 | 1,800 | 1,800 | 1,800.0 | ✓ |
| RB-ACT-02 | Mason | 3 | 380 | 1,140 | 1,140.0 | ✓ |
| RB-ACT-02 | Wheel Loader 3CY | 1 | 1,200 | 1,200 | 1,200.0 | ✓ |
| RB-ACT-02 | Ordinary Portland Cement | 20 (MT) | 85 | 1,700 | 1,700.0 | ✓ |
| RB-ACT-03 | Helper / Handyman | 6 | 180 | 1,080 | 1,080.0 | ✓ |
| RB-ACT-03 | Mason | 4 | 380 | 1,520 | 1,520.0 | ✓ |
| RB-ACT-03 | Ordinary Portland Cement | 50 (MT) | 85 | 4,250 | 4,250.0 | ✓ |

**Finding 3 — runbook missing step:** the original runbook didn't have a "Resource Plan" step. UI panel "Resource Demand" was empty after Step 9, until role-assignments were added. New section 9.5 added to the runbook.

---

## Step 10 — Productivity-norm sanity check

Visual check on activity drawer for **RB-ACT-01** — productivity norm "1 Excavator + 5 Helpers → X Cum/day" appears (derived from linked Work Activity `Pilot Excavation`). No warning banner.

**Status: passed** (user confirmation).

---

## Step 11 — BOQ items

**API:** `POST /v1/projects/{id}/boq/bulk`

| Item No | Description | Unit | BOQ Qty | BOQ Rate | BOQ Amt (Expected) | BOQ Amt (Actual) | Budgeted Rate | Budgeted Amt | ID |
|---|---|---|---:|---:|---:|---:|---:|---:|---|
| RB-03.01 | Unclassified Excavation | Cum | 500 | 14.00 | 7,000 | 7,000.0 | 12.00 | 6,000.0 | `7c25b112…` |
| RB-04.05 | PCC Class C | Cum | 100 | 80.00 | 8,000 | 8,000.0 | 70.00 | 7,000.0 | `199d9996…` |
| RB-09.02 | Concrete RCC Grade M30 | Cum | 200 | 120.00 | 24,000 | 24,000.0 | 110.00 | 22,000.0 | `bcb1e789…` |
| | | | **Σ** | | **39,000** | **39,000.0** | | **35,000.0** | |

**All status pills:** `ACTIVE` (auto-derived). **% Complete:** 0 at this point. **Actual Rate / Amount:** 0 (no DPRs yet).

---

## Step 12 — Project Team (5 members + reporting chain)

**API:** `POST /v1/projects/{id}/team` (one call per member)

Re-used users from the global pool (originally seeded by the OMAN demo seeder).

| Role | User ID (last 8) | Reports To | ✓ |
|---|---|---|:-:|
| PM | `2165f85e…` | (none — top of chain) | ✓ |
| CONSTRUCTION_MANAGER | `d2874836…` | `2165f85e…` (PM) | ✓ |
| ENGINEER | `827f5b6a…` | `d2874836…` (CM) | ✓ |
| SUPERVISOR (Sup1) | `008b7b25…` | `827f5b6a…` (Engineer) | ✓ |
| SUPERVISOR (Sup2) | `0dca68d4…` | `827f5b6a…` (Engineer) | ✓ |

**Why this matters:** without the Reports-To chain populated, the DBS **Engineer / CM / PM** rollup tabs would all be empty — the engineer-row sums supervisors via this exact chain.

---

## Step 12.5 — Lock Activities (new step discovered)

**Finding 4 — undocumented prerequisite:** initial DPR submissions failed with `ACTIVITY_DRAFT_DPR_REJECTED`. Activities default to `DRAFT` status and must be locked before DPRs are accepted.

**Fix:** `POST /v1/projects/{id}/activities/{activityId}/lock` for each of `RB-ACT-01`, `RB-ACT-02`, `RB-ACT-03`. All returned **HTTP 200**. After locking, DPRs went through cleanly.

Added as a new step in the runbook between Phase 2 and Phase 3.

---

## Step 13 — Submit DPRs (×3, all Supervisor 1, 2026-05-19)

**API:** `POST /v1/projects/{id}/dpr`

### DPR-1 (initial, no role-rate IDs)

| Field | Expected | Actual | ✓ |
|---|---|---|:-:|
| `activityId` | RB-ACT-01 | RB-ACT-01 | ✓ |
| `boqItemNo` | RB-03.01 | RB-03.01 | ✓ |
| `qtyExecuted` | 50 | 50 | ✓ |
| Manpower (Helper × 5 @ 180) `lineCost` | 900 | 900.0 | ✓ |
| Equipment (Excavator × 1 @ 1800) `lineCost` | 1,800 | 1,800.0 | ✓ |

### DPR-2

| Field | Expected | Actual | ✓ |
|---|---|---|:-:|
| `activityId` | RB-ACT-02 | RB-ACT-02 | ✓ |
| `boqItemNo` | RB-04.05 | RB-04.05 | ✓ |
| `qtyExecuted` | 20 | 20 | ✓ |
| Mason × 3 @ 380 | 1,140 | 1,140.0 | ✓ |
| Wheel Loader × 1 @ 1200 | 1,200 | 1,200.0 | ✓ |
| Cement OPC 2 MT @ 85 | 170 | 170.0 | ✓ |

### DPR-3

| Field | Expected | Actual | ✓ |
|---|---|---|:-:|
| `activityId` | RB-ACT-03 | RB-ACT-03 | ✓ |
| `boqItemNo` | RB-09.02 | RB-09.02 | ✓ |
| `qtyExecuted` | 10 | 10 | ✓ |
| Helper × 6 @ 180 | 1,080 | 1,080.0 | ✓ |
| Mason × 4 @ 380 | 1,520 | 1,520.0 | ✓ |

### DPR rollup across all three

| Bucket | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| Income (qty × BOQ rate) | 50×14 + 20×80 + 10×120 = **3,500** | 3,500 | ✓ |
| Σ Manpower lineCost | 900 + 1,140 + 2,600 = **4,640** | 4,640 | ✓ |
| Σ Equipment lineCost | 1,800 + 1,200 + 0 = **3,000** | 3,000 | ✓ |
| Σ Material lineCost | 0 + 170 + 0 = **170** | 170 | ✓ |
| Σ Cost | **7,810** | 7,810 | ✓ |

---

## Step 14 — Material Consumption Log (×3)

**API:** `POST /v1/projects/{id}/material-consumption`

| MCL | Activity | Material | Consumed | Unit Rate | `lineCost` Expected | `lineCost` Actual | ✓ |
|---|---|---|---:|---:|---:|---:|:-:|
| 1 | RB-ACT-02 | Cement OPC 43 | 100 kg | 3.20 | 320 | 320.0 | ✓ |
| 2 | RB-ACT-03 | Steel Fe500 | 2 MT | 620 | 1,240 | 1,240.0 | ✓ |
| 3 | RB-ACT-03 | Aggregate 20mm | 5 Cum | 14.50 | 72.50 | 72.5 | ✓ |
| | | | | **Σ** | **1,632.50** | **1,632.5** | ✓ |

**Finding 5 — MCL doesn't re-trigger BOQ actualRate recompute:** MCL fires `MaterialConsumptionLoggedEvent`, which DBS listens to but `BoqActualRateRecalcListener` does not. After only Steps 13 and 14, the BOQ master showed `actualRate = 0` on every row — because (a) DPRs were posted before MCLs landed, and (b) the listener only re-fires on `DprSubmittedEvent`. Fix: re-PUT the DPRs (next section). Bug filed.

---

## Step 15 — Verify BOQ master (first try, pre-fix)

Snapshot before the DPR re-PUT — captures the gap:

| Item | Qty Exec | % Complete | Actual Rate | Actual Amount | Cost Variance |
|---|---:|---:|---:|---:|---:|
| RB-03.01 | 50 | 10.00% | **0** | **0** | −600 |
| RB-04.05 | 20 | 20.00% | **0** | **0** | −1,400 |
| RB-09.02 | 10 | 5.00% | **0** | **0** | −1,100 |
| **Grand** | | 8.86% | | **0** | **−3,100** |

**Working correctly:** `qty_executed_to_date` and `% Complete` (via `DprBoqSyncListener`).  
**Not working:** `actualRate` / `actualAmount` / `costVariance` (root cause = missing role-rate IDs on DPR child rows, plus MCL ordering — see Finding 5 and 6).

**Finding 6 — DPR rows need role-rate IDs to feed BOQ actualRate:** `BoqActualRateRecalcListener` joins `dpr_manpower` → `resource_assignments` on `(activity_id, manpower_role_rate_id)`. The initial DPR POSTs only supplied `trade="Helper"` + `unitRate=180` (free-text path); without `manpowerRoleRateId` the join produced zero contribution. **In production this is not an issue** because the UI form sources the trade from a role-rate dropdown that always populates the ID. The pure-API path bypassed it.

### Fix: re-PUT DPRs with role-rate IDs

`PUT /v1/projects/{id}/dpr/{dprId}` — re-submitted each DPR with:
- Manpower rows carrying `manpowerRoleRateId` + `roleId`
- Equipment rows carrying `equipmentRoleVariantId` + `roleId`
- Material rows carrying `materialRoleVariantId` + `roleId`

This re-fires `DprSubmittedEvent`, which the listener picks up and now finds the role-rate assignments + MCLs already in place.

### After the fix — BOQ master verified

| Item | Numerator (DPR cost + MCL) | ÷ Qty | Actual Rate Expected | Actual Rate Actual | Actual Amount | Cost Variance |
|---|---|---:|---:|---:|---:|---:|
| RB-03.01 | 2,700 (DPR-1 only; no MCL on ACT-01) | 50 | **54.00** | **54.00** ✓ | 2,700 | +2,100 |
| RB-04.05 | 1,140 + 1,200 + 170 + 320 = **2,830** | 20 | **141.50** | **141.50** ✓ | 2,830 | +1,430 |
| RB-09.02 | 2,600 + 1,240 + 72.50 = **3,912.50** | 10 | **391.25** | **391.25** ✓ | 3,912.50 | +2,812.50 |
| **Grand** | | | | | **9,442.50** | **+6,342.50** |

Every cell matches hand-calc to the cent.

---

## Step 16 — DBS Supervisor tab

**API:** `GET /v1/projects/{id}/dbs/supervisor/{supervisorId}?date=2026-05-19`

For Supervisor 1 (`008b7b25…`):

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `boqPlannedAmount` | 39,000 | 39,000.0 | ✓ |
| `boqAchievedAmount` | 3,500 | 3,500.0 | ✓ |
| `directCost` | 3,500 | 3,500.0 | ✓ |
| `prelimCost` | 0 | 0.0 | ✓ |
| `totalCostInclPrelims` | 3,500 | 3,500.0 | ✓ |
| `pctAchieved` | 8.9744 | 8.9744 | ✓ |
| `manpowerAmount` (A) | 4,640 | 4,640.0 | ✓ |
| `adminAmount` (B) | 0 | 0.0 | ✓ |
| `machineryAmount` (C) | 3,000 | 3,000.0 | ✓ |
| `fuelAmount` (D) | 0 | 0.0 | ✓ |
| `materialAmount` (E) | 170 | 170.0 | ✓ |
| `subcontractAmount` (F) | 0 | 0.0 | ✓ |
| `totalExpense` | 7,810 | 7,810.0 | ✓ |
| `totalIncome` | 3,500 | 3,500.0 | ✓ |
| `contribution` | −4,310 | −4,310.0 | ✓ |
| `contributionPct` | −1.2314 (−123.14%) | −1.2314 | ✓ |

**UI:** Supervisor tab confirmed visually. **% Achieved tile is missing** (user flagged) — bug logged.

**Finding 7 — `% Achieved` tile missing on Supervisor DBS tab:** API returns `pctAchieved = 8.9744`, but no tile renders that value on the Supervisor tab. Engineer / CM / PM tabs need checking too.

---

## Step 17 — Engineer / CM / PM rollup tabs

### Engineer row (`827f5b6a…`)

`GET /v1/projects/{id}/dbs/engineer/{engineerId}?date=2026-05-19`

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `manpowerAmount` | 4,640 | 4,640.0 | ✓ |
| `machineryAmount` | 3,000 | 3,000.0 | ✓ |
| `materialAmount` | 170 | 170.0 | ✓ |
| `totalExpense` | 7,810 | 7,810.0 | ✓ |
| `contribution` | −4,310 | −4,310.0 | ✓ |
| `contributionPct` | −1.2314 | −1.2314 | ✓ |

Engineer row = sum of Sup1 + Sup2. Sup2 had zero DPRs, so totals equal Sup1's.

### CM row (`d2874836…`)

`GET /v1/projects/{id}/dbs/cm/{cmId}?date=2026-05-19`

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `manpowerAmount` | 4,640 | 4,640.0 | ✓ |
| `machineryAmount` | 3,000 | 3,000.0 | ✓ |
| `materialAmount` | 170 | 170.0 | ✓ |
| `supervisorCount` | 1 | 1 | ✓ |
| `contributionPct` | −123.1429 | −123.1429 | ✓ |
| `totalExpense` | (not exposed on CM row) | — | (n/a) |

**Finding 8 — CM `contributionPct` scaled differently:** CM tier returns the percentage value (`−123.1429`), while Supervisor / Engineer / PM return the fraction (`−1.2314`). Same number, different scale — API inconsistency. Filed.

**Finding 9 — CM row missing `totalExpense` / `contribution` fields:** the `DbsDailyCm` entity doesn't persist those columns; derivable from the supervisor sum. Minor data-shape inconsistency between tiers.

### Project (PM) row

`GET /v1/projects/{id}/dbs/project?date=2026-05-19`

This is where the MCL fold-in lands (MCLs lack a supervisor FK):

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `manpowerAmount` | 4,640 | 4,640.0 | ✓ |
| `machineryAmount` | 3,000 | 3,000.0 | ✓ |
| `materialAmount` | 170 (Sup) + 1,632.50 (MCL fold-in) = **1,802.50** | **1,802.5** | ✓ |
| `totalExpense` | 4,640 + 0 + 3,000 + 0 + 1,802.50 + 0 = **9,442.50** | **9,442.5** | ✓ |
| `totalIncome` | 3,500 | 3,500.0 | ✓ |
| `contribution` | 3,500 − 9,442.50 = **−5,942.50** | **−5,942.5** | ✓ |
| `contributionPct` | −1.6979 (−169.79%) | −1.6979 | ✓ |
| `supervisorCount` | 1 | 1 | ✓ |

PM-tier loss is bigger than Supervisor-tier loss by exactly the MCL fold-in (`1,632.50`).

---

## Step 18 — Idempotency + range recompute + exports

### Idempotency (3 back-to-back recomputes)

`POST /v1/projects/{id}/dbs/recompute?date=2026-05-19`

| Run | Income | Expense | Contribution | Material | `version` |
|---|---:|---:|---:|---:|---:|
| 1 | 3,500 | 9,442.50 | −5,942.50 | 1,802.50 | 15 |
| 2 | 3,500 | 9,442.50 | −5,942.50 | 1,802.50 | 16 |
| 3 | 3,500 | 9,442.50 | −5,942.50 | 1,802.50 | 17 |

Numbers bit-identical; only `version` and `recomputedAt` change. Upsert semantics correct.

### Range recompute (3 days)

`POST /v1/projects/{id}/dbs/recompute-range?from=2026-05-17&to=2026-05-19`

| Date | Income | Expense | Contribution | DPR count |
|---|---:|---:|---:|---:|
| 2026-05-17 | 0 | 0 | 0 | 0 |
| 2026-05-18 | 0 | 0 | 0 | 0 |
| 2026-05-19 | 3,500 | 9,442.50 | −5,942.50 | 1 |

**Status: ✓ Each day reconciles independently.**

### Excel export

`GET /v1/projects/{id}/dbs/export.xlsx?date=2026-05-19&level=PM`

| Field | Expected | Actual |
|---|---|---|
| HTTP status | 200 | **200** |
| Content-Type | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | **matched** |
| Size | non-zero | **8,948 B** |
| File magic | Microsoft OOXML | **Microsoft OOXML** |

Saved to `/tmp/dbs-export.xlsx`.

### PDF export

`GET /v1/projects/{id}/dbs/export.pdf?date=2026-05-19`

| Field | Expected | Actual |
|---|---|---|
| HTTP status | 200 | **200** |
| Content-Type | `application/pdf` | **matched** |
| Size | non-zero | **7,240 B** |
| File magic | PDF | **PDF document, version 1.4, 1 pages** |

Saved to `/tmp/dbs-export.pdf`.

---

## Project nav re-order (post-runbook)

After completing the walkthrough, the project navigation was re-organised to follow the runbook flow:

```
Overview · WBS · Activities · BOQ · Team · DPR · DBS · Costs · EVM · Capacity Util. ·
Gantt · Network · Insights · Risks · GIS · Baselines · Contracts · More
```

Changes:
- **Promoted** `Team` from the More dropdown to a first-class tab (load-bearing for DBS rollup).
- **Hidden** Phase-C site-ops modules from the top nav: Workfronts, Snags, Handovers, Attendance, Checklists, Indents, NCRs (still reachable via direct URL).
- **Resources** tab temporarily commented out.

Commit: `80054d9d feat(nav): reorder project tabs to match runbook flow + hide site-ops modules`

---

## Findings summary

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | Rate-master endpoints are singular (`/manpower-rate-master`), not plural | Docs only | Fixed in runbook |
| 2 | Activity create endpoint is `/v1/projects/{pid}/activities`, not `/v1/activities` | Docs only | Fixed in runbook |
| 3 | Runbook missing Step 9.5 — Resource Plan (role-assignments) | Docs only | Added |
| 4 | Activities must be locked (`POST .../{aid}/lock`) before DPRs accepted | Docs only | Added as Step 12.5 |
| 5 | **`BoqActualRateRecalcListener` doesn't listen to `MaterialConsumptionLoggedEvent`** — BOQ actualRate stays stale after an MCL unless a DPR re-fires | Bug — real defect | Filed (workaround: re-PUT DPR) |
| 6 | DPR rows without `manpowerRoleRateId` / `equipmentRoleVariantId` / `materialRoleVariantId` produce zero BOQ actualRate contribution | Documentation + form contract | Mitigated in production via dropdown-only forms; documented |
| 7 | **`% Achieved` tile missing from DBS Supervisor tab** (API has `pctAchieved`, UI doesn't render it) | UI gap | Filed |
| 8 | CM-tier `contributionPct` returns percentage (`−123.14`), other tiers return fraction (`−1.2314`) | API inconsistency | Filed |
| 9 | CM row missing `totalExpense` / `contribution` fields (not persisted on `DbsDailyCm`) | API inconsistency | Filed |

---

## Final ID inventory (for re-running tests against this same project)

```
Project:        817bd873-f780-4be6-acd6-00259accb8b7  RUNBOOK-E2E
EPS:            e38edde8-b6cb-4d2c-8e16-72a8336e7c0a  MIG-CIV

WBS:
  BB1           afe25a52-22e7-4c80-a445-10fee13beb10  Bridge B-1
  BB1-SUB       c9c5db98-c9a7-445a-9c4b-e5dc7664aa0b  Substructure
  BB1-SUP       a343ff87-18ba-4cd0-92f8-7539ff8d0402  Superstructure
  BB1-SUB-FND   5b46315a-1aa7-46a7-966f-9ef0a596e14d  Foundation (leaf)
  BB1-SUB-PIE   08cffb76-8f9f-4902-94a4-c1a3a753e46f  Pier (leaf)
  BB1-SUP-DCK   148423cd-4147-4692-9f6a-3c3386855e80  Deck (leaf)

Activities:
  RB-ACT-01     53eda68a-1e15-49f8-a488-8362c171e9d9  Foundation Excavation  (LOCKED)
  RB-ACT-02     99c7ba7e-f0df-41e5-8540-915269b91e63  Pier PCC               (LOCKED)
  RB-ACT-03     4896d0ca-43dc-45e9-83c8-d8cbc860bb35  Deck Concrete Works    (LOCKED)

BOQ items:
  RB-03.01      7c25b112-7520-4b62-a869-29adfa29c7a3  Unclassified Excavation
  RB-04.05      199d9996-1eb5-4633-9175-4689372c26c6  PCC Class C
  RB-09.02      bcb1e789-9bda-48e1-9fe5-08f80ce7c4ce  Concrete RCC Grade M30

Team:
  PM            2165f85e-46b5-4c95-994d-588e73da5c7e
  CM            d2874836-3f7d-4c76-bb7e-98cd1e6c29ee  reports to PM
  Engineer      827f5b6a-b189-456e-8f4e-69451dbc442e  reports to CM
  Supervisor 1  008b7b25-1b3f-455f-a155-e3a80e510957  reports to Engineer
  Supervisor 2  0dca68d4-9539-4226-aa34-26c69833ea11  reports to Engineer

Role-rate variants used in DPRs (post-fix):
  Helper        roleId=6c649285…  rateId=c45b6987…  @ ₹180/Day
  Mason         roleId=089ae277…  rateId=f6238c87…  @ ₹380/Day
  Excavator     roleId=ae995257…  variantId=4d68bfca… @ ₹1,800/Day
  Wheel Loader  roleId=eeac4776…  variantId=2cfc2294… @ ₹1,200/Day
  Cement OPC    roleId=f36e4ac1…  variantId=e4d71944… @ ₹85/MT

Material resources used in MCLs:
  Cement OPC 43 Grade   ca1b87f7-e605-4c63-aa19-021097f73651  @ ₹3.20/kg
  Steel Fe500           2c556793-0f6d-42c7-8889-a07bf99e943e  @ ₹620/MT
  Aggregate 20mm        482bb26b-2cc1-4b34-82f5-66d348f3029f  @ ₹14.50/Cum
```

---

## Related artefacts

- Runbook: `docs/dpr-dbs-e2e-test-runbook.md`
- Screenshots from the run: `dbs-supervisor-e2e.png`, `dbs-pm-with-loss-e2e.png`, `boq-e2e.png`, `boq-with-actuals-e2e.png`, `project-tabs-reordered.png` (repo root)
- Excel/PDF outputs: `/tmp/dbs-export.xlsx`, `/tmp/dbs-export.pdf` (local)
- Backend commits this run interacted with:
  - `4883a951 fix(dbs,kpi): drop hours multiplier from cost (nos × rate)`
  - `95df0394 fix(dbs): dedupe BOQ cumulative at each rollup tier + relabel PM tiles`
- Frontend commits added during this session:
  - `e08d0197 docs(dpr,dbs): add click-by-click E2E test runbook ...`
  - `178619ad feat(boq): add BOQ tab to project nav + migrate BOQ table to VirtualDataTable`
  - `80054d9d feat(nav): reorder project tabs to match runbook flow + hide site-ops modules`
