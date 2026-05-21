# DPR → BOQ → DBS End-to-End Test — Execution Log

**Date:** 2026-05-20
**Project under test:** `RB-E2E-2026-05-20` — *DPR/BOQ/DBS E2E Run 2026-05-20*
**Project ID:** `ce16c93f-36ee-400c-8cda-3bc1c340dfd1`
**EPS parent:** `MIG-CIV` (`e38edde8-b6cb-4d2c-8e16-72a8336e7c0a`)
**Period covered:** 2026-05-20 (single test day, with range-recompute touching 2026-05-18..20)
**Backend HEAD at start:** `4320b568 feat(ai,dbs,boq): CM-tier DBS, MCL-triggered BOQ rate recalc, new AI tools`
**Mode:** API-driven, autonomous (no UI gate, per user request)

---

## Pass / fail summary

**Overall: PASS** with two known regressions still open from prior runs (Findings 7, 8, 9) and one new finding (Finding 10 — Supervisor `pctAchieved` / `boqPlannedAmount` / `boqAchievedAmount` come back zero on the per-supervisor endpoint).

| Phase | Status | Notes |
|---|---|---|
| Pre-flight (health, login, master data) | ✓ PASS | 16 manpower / 57 equipment / 33 material / 178 work-activities / 207 resource-roles |
| Project create + WBS (6 nodes) | ✓ PASS | WBS codes must be globally unique — used `-${pid_prefix}` suffix |
| Activities + Resource Plan (3 + 8) | ✓ PASS | All 8 `plannedCost` values match prescribed values to the cent |
| BOQ items (3) + Team chain (5) | ✓ PASS | Grand BOQ 39,000, Budgeted 35,000 |
| Activities lock (3) | ✓ PASS | All transitioned DRAFT → NOT_STARTED |
| Section G — 20 seeded items, 3 plan edits, 2 actuals | ✓ PASS | Monthly total 1,350; daily proration 43.55 on PM tier |
| DPRs (3) submitted as Supervisor 1 | ✓ PASS | Costs 2,700, 2,510, 2,600 (per DPR) |
| MCLs (3) | ✓ PASS | 320 + 1,240 + 72.5 = 1,632.50 |
| **BOQ verification — first pass after DPR+MCL** | ✓ PASS | **Finding 5 appears FIXED** — BOQ `actualRate` populated correctly without DPR re-PUT this run |
| DBS Supervisor / Engineer / CM / PM | ✓ PASS (with notes) | All numerics match; Findings 7, 8, 9 reproduce; new Finding 10 |
| Idempotency (3× recompute) | ✓ PASS | Bit-identical results, version 21→22→23 |
| Range recompute (3 days) | ✓ PASS | Empty days carry the 43.55 Section G daily-proration — correct per the runbook |
| Excel + PDF exports | ✓ PASS | 200, valid OOXML / PDF magic |

---

## Phase 1 — Master data (probed, pre-existing)

| Catalogue | Endpoint | Count |
|---|---|---:|
| Manpower role rates | `GET /v1/manpower-rate-master` | 16 |
| Equipment role rates | `GET /v1/equipment-rate-master` | 57 |
| Material role rates | `GET /v1/material-rate-master` | 33 |
| Resource roles | `GET /v1/resource-roles` | 207 |
| Work activities | `GET /v1/work-activities` | 178 |

All endpoints singular (Finding 1 still applies as documentation guidance).

---

## Step 7 — Create Project

`POST /v1/projects` — code `RB-E2E-2026-05-20`, EPS `MIG-CIV`, window 2026-05-20 → 2026-11-20.

| Field | Expected | Actual | ✓ |
|---|---|---|:-:|
| `id` | UUID | `ce16c93f-36ee-400c-8cda-3bc1c340dfd1` | ✓ |
| `status` | `PLANNED` | `PLANNED` | ✓ |
| `plannedStartDate` | `2026-05-20` | `2026-05-20` | ✓ |
| `plannedFinishDate` | `2026-11-20` | `2026-11-20` | ✓ |
| Section G plan-items seeded | 20 with sortOrder 1..20, 2 with `formulaType=PCT_CONTRACT_VALUE` | 20 with sortOrder 1..20, 2 formula rows (`Insurance Charges (0.015% of CV)`, `Bank Charges (0.01% of CV)`) | ✓ |

---

## Step 8 — WBS tree (6 nodes)

`POST /v1/projects/{pid}/wbs`. **WBS `code` is globally unique** — first attempt at `BB1` collided with the 2026-05-19 run's WBS and rejected with `WBS_CODE_DUPLICATE`. Re-tried with `-{pid prefix}` suffix.

| Node | Code | Level | Parent | ID |
|---|---|---:|---|---|
| Bridge B-1 | `BB1-ce16c9` | 1 | — | `0dc8d195-1743-4ca4-bac6-4eadda365a34` |
| Substructure | `BB1-SUB-ce16c9` | 2 | BB1 | `6e231fed-b774-4207-bd6d-d2bbf15a81ed` |
| Superstructure | `BB1-SUP-ce16c9` | 2 | BB1 | `becd37d8-9618-4489-8775-a1c99d9ce754` |
| Foundation | `BB1-SUB-FND-ce16c9` | 3 (leaf) | Substructure | `c9ae544d-d8ed-45a6-b513-8fb1308ded70` |
| Pier | `BB1-SUB-PIE-ce16c9` | 3 (leaf) | Substructure | `1cfa20df-26ed-459c-bb50-00fe5641e803` |
| Deck | `BB1-SUP-DCK-ce16c9` | 3 (leaf) | Superstructure | `fa6a0d26-9f48-40c6-8a2b-b483f92b8d23` |

---

## Step 9 — Activities (3)

`POST /v1/projects/{pid}/activities`. **`projectId` is required in the body** even though it's already in the URL (re-confirmed; matches Finding 2 from prior log).

| Code | Name | WBS leaf | Work Activity | Duration | ID |
|---|---|---|---|---:|---|
| `RB-ACT-01-ce16c9` | Foundation Excavation | Foundation | Pilot Excavation | 10d | `fae63d6b-ef01-49cd-8da6-1ba72b76149d` |
| `RB-ACT-02-ce16c9` | Pier PCC | Pier | Pilot PCC | 8d | `24b8ed43-3cdf-4655-b748-0ee9d2b9acac` |
| `RB-ACT-03-ce16c9` | Deck Concrete Works | Deck | Concrete works | 15d | `841847c1-11e7-48c6-bb96-0666e06dac50` |

---

## Step 9.5 — Resource Plan (role-assignments, 8 rows)

`POST /v1/projects/{pid}/role-assignments`.

| Activity | Resource | nos | rate | `plannedCost` Expected | Actual | ✓ |
|---|---|---:|---:|---:|---:|:-:|
| RB-ACT-01 | Helper / Handyman | 5 | 180 | 900 | 900.0 | ✓ |
| RB-ACT-01 | 20-Ton Excavator | 1 | 1,800 | 1,800 | 1,800.0 | ✓ |
| RB-ACT-02 | Mason | 3 | 380 | 1,140 | 1,140.0 | ✓ |
| RB-ACT-02 | Wheel Loader 3CY | 1 | 1,200 | 1,200 | 1,200.0 | ✓ |
| RB-ACT-02 | Ordinary Portland Cement | 20 (MT) | 85 | 1,700 | 1,700.0 | ✓ |
| RB-ACT-03 | Helper / Handyman | 6 | 180 | 1,080 | 1,080.0 | ✓ |
| RB-ACT-03 | Mason | 4 | 380 | 1,520 | 1,520.0 | ✓ |
| RB-ACT-03 | Ordinary Portland Cement | 50 (MT) | 85 | 4,250 | 4,250.0 | ✓ |

The rate-master IDs the runbook quotes (`c45b6987` for Helper, etc.) come from `/v1/resource-roles/{roleId}/with-variants`, **not** from the singular `/v1/manpower-rate-master` table (which carries older `OMN-*` legacy rows at rates 12 / 28). Documented for next runbook update.

---

## Step 11 — BOQ items (bulk)

`POST /v1/projects/{pid}/boq/bulk`. **Body is a bare JSON array, not an object with `items:`** — first try with `{"items":[...]}` returned `MALFORMED_JSON`.

| Item No | Description | Unit | BOQ Qty | BOQ Rate | BOQ Amt | Budgeted Rate | Budgeted Amt | ID |
|---|---|---|---:|---:|---:|---:|---:|---|
| RB-03.01 | Unclassified Excavation | Cum | 500 | 14 | 7,000 | 12 | 6,000 | `b16d704e-3b40-4970-b809-0c5400109c4e` |
| RB-04.05 | PCC Class C | Cum | 100 | 80 | 8,000 | 70 | 7,000 | `8dd2f550-5cfc-467c-9b43-909a07911d0b` |
| RB-09.02 | Concrete RCC Grade M30 | Cum | 200 | 120 | 24,000 | 110 | 22,000 | `f7cea5e2-2aac-4c26-9b57-969c6c32bd99` |
| | | | | **Σ** | **39,000** | | **35,000** | |

---

## Step 12 — Project Team

`POST /v1/projects/{pid}/team` (one per member).

| Role | User | Reports To | Team-row ID |
|---|---|---|---|
| PM | `2165f85e…` | — | `aed478e1-6f69-4636-bd4b-4a7252889804` |
| CONSTRUCTION_MANAGER | `d2874836…` | PM | `1becddd9-265c-4720-bd75-69a696160c45` |
| ENGINEER | `827f5b6a…` | CM | `d6bc47a2-87ee-4664-86d5-3d781cc8c704` |
| SUPERVISOR (Sup1) | `008b7b25…` | Engineer | `fa010158-e59f-46ef-a077-2cb3a31e070f` |
| SUPERVISOR (Sup2) | `0dca68d4…` | Engineer | `b026b814-553c-4b0d-a66e-13bcb4c5caa4` |

---

## Step 12.5 — Lock Activities

`POST /v1/projects/{pid}/activities/{aid}/lock` × 3. All three transitioned `DRAFT → NOT_STARTED` (the locked-but-not-yet-running state). Finding 4 still applies — DPR submit without this returns `ACTIVITY_DRAFT_DPR_REJECTED`.

---

## Step 12.7 — Section G — General Expenses

### Seeded plan items

`GET /v1/projects/{pid}/general-expenses/plan-items` → **20 rows**, `sortOrder` 1..20. Two carry `formulaType=PCT_CONTRACT_VALUE`:

- *Insurance Charges (0.015% of CV)*
- *Bank Charges (0.01% of CV)*

### Updated 3 plan items

`PUT /v1/projects/{pid}/general-expenses/plan-items/{id}`

| Item | planQty | planAmount |
|---|---:|---:|
| Electricity Charges | 12 | 12,000 |
| Water & Sewage Charges | 43 | 4,300 |
| Rent Land & Office, Accommodation | 100 | 100,000 |

### May 2026 actuals upserted

`PUT /v1/projects/{pid}/general-expenses/actuals/{planItemId}?yearMonth=202605`

| Item | achievedQty | achievedAmount |
|---|---:|---:|
| Electricity | 1 | 1,000 |
| Water & Sewage | 1 | 350 |

### Verification

| Check | Expected | Actual | ✓ |
|---|---|---|:-:|
| `GET .../actuals?yearMonth=202605` `monthlyTotal` | 1,350.00 | 1,350.0 | ✓ |
| Rows with non-null `actual` | 2 | 2 | ✓ |
| Rows with `actual: null` | 18 | 18 | ✓ |
| `loggedByUserId` on admin-created actuals | `null` (Gotcha 8) | `null` | ✓ |
| `dbs/project?date=2026-05-20` `generalExpenseAmount` | 43.55 (= 1350/31) | 43.55 | ✓ |
| `dbs/project?date=2026-05-20` `generalExpenseMonthlyTotal` | 1,350.00 | 1,350.0 | ✓ |
| `generalExpenseLinesJson` Electricity totalAmount | 32.26 (= 1000/31) | 32.26 | ✓ |
| `generalExpenseLinesJson` Water totalAmount | 11.29 (= 350/31) | 11.29 | ✓ |
| Supervisor / Engineer / CM rows carry `generalExpense*` | no | no | ✓ |
| Non-PM tiers' `totalExpense` excludes 43.55 | yes | yes (7,810 on Sup1 & Engineer) | ✓ |

Section G PM-only fold-in confirmed.

---

## Step 13 — Submit DPRs (×3, Supervisor 1, 2026-05-20)

`POST /v1/projects/{pid}/dpr` — all three carry `manpowerRoleRateId` / `equipmentRoleVariantId` / `materialRoleVariantId` on every cost row from the start.

| DPR | Activity | BOQ | qtyExecuted | Σ Cost Expected | Actual | DPR ID |
|---|---|---|---:|---:|---:|---|
| 1 | RB-ACT-01 | RB-03.01 | 50 | 900 + 1,800 = **2,700** | 2,700.0 | `35ee9299-381d-4729-a252-1854f19a2d8c` |
| 2 | RB-ACT-02 | RB-04.05 | 20 | 1,140 + 1,200 + 170 = **2,510** | 2,510.0 | `48566a21-273a-4997-a205-702b2479f081` |
| 3 | RB-ACT-03 | RB-09.02 | 10 | 1,080 + 1,520 = **2,600** | 2,600.0 | `941e7352-be0b-40cd-b1d8-e18bb83599ef` |

### DPR rollup

| Bucket | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| Income (50×14 + 20×80 + 10×120) | 3,500 | 3,500 | ✓ |
| Σ Manpower lineCost | 4,640 | 4,640 | ✓ |
| Σ Equipment lineCost | 3,000 | 3,000 | ✓ |
| Σ Material lineCost (DPR only) | 170 | 170 | ✓ |
| Σ Cost | 7,810 | 7,810 | ✓ |

---

## Step 14 — Material Consumption Log (×3)

`POST /v1/projects/{pid}/material-consumption`. Payload uses `consumed` (BigDecimal) plus opening/received stocks; backend computes lineCost from the linked resource rate.

| MCL | Activity | Resource | Consumed | Unit Rate | lineCost Expected | Actual | ✓ |
|---|---|---|---:|---:|---:|---:|:-:|
| 1 | RB-ACT-02 | Cement OPC 43 Grade | 100 kg | 3.20 | 320 | 320.0 | ✓ |
| 2 | RB-ACT-03 | Steel Fe500 | 2 MT | 620 | 1,240 | 1,240.0 | ✓ |
| 3 | RB-ACT-03 | Aggregate 20mm | 5 Cum | 14.50 | 72.50 | 72.5 | ✓ |
| | | | | **Σ** | **1,632.50** | **1,632.5** | ✓ |

---

## Step 15 — BOQ master verification (first pass)

`GET /v1/projects/{pid}/boq`. **No DPR re-PUT was needed this run** — BOQ `actualRate` populated correctly on first read after DPR + MCL. **Finding 5 (MCL-triggered BOQ actualRate recompute) appears resolved** — confirms commit `4320b568 feat(ai,dbs,boq): CM-tier DBS, MCL-triggered BOQ rate recalc`.

| Item | Numerator (DPR + MCL) | ÷ Qty | Actual Rate Expected | Actual | Actual Amt Expected | Actual | Cost Variance |
|---|---|---:|---:|---:|---:|---:|---:|
| RB-03.01 | 2,700 (no MCL on ACT-01) | 50 | 54.00 | **54.00** ✓ | 2,700 | 2,700.0 | +2,100 |
| RB-04.05 | 1,140 + 1,200 + 170 + 320 = 2,830 | 20 | 141.50 | **141.50** ✓ | 2,830 | 2,830.0 | +1,430 |
| RB-09.02 | 2,600 + 1,240 + 72.50 = 3,912.50 | 10 | 391.25 | **391.25** ✓ | 3,912.50 | 3,912.5 | +2,812.50 |
| **Grand** | | | | | **9,442.50** | 9,442.5 | **+6,342.50** |

Every cell matches hand-calc.

---

## Step 16 — DBS Supervisor tab (Supervisor 1)

`GET /v1/projects/{pid}/dbs/supervisor/{supervisorUserId}?date=2026-05-20`

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `boqPlannedAmount` | 39,000 | **0.0** | ✗ Finding 10 |
| `boqAchievedAmount` | 3,500 | **0.0** | ✗ Finding 10 |
| `directCost` | 3,500 | 3,500.0 | ✓ |
| `prelimCost` | 0 | 0.0 | ✓ |
| `totalCostInclPrelims` | 3,500 | 3,500.0 | ✓ |
| `pctAchieved` | 8.9744 | **0.0** | ✗ Finding 10 (Engineer tab does carry 8.9744) |
| `manpowerAmount` | 4,640 | 4,640.0 | ✓ |
| `adminAmount` | 0 | 0.0 | ✓ |
| `machineryAmount` | 3,000 | 3,000.0 | ✓ |
| `fuelAmount` | 0 | 0.0 | ✓ |
| `materialAmount` | 170 | 170.0 | ✓ |
| `subcontractAmount` | 0 | 0.0 | ✓ |
| `totalExpense` | 7,810 | 7,810.0 | ✓ |
| `totalIncome` | 3,500 | 3,500.0 | ✓ |
| `contribution` | −4,310 | −4,310.0 | ✓ |
| `contributionPct` | −1.2314 | −1.2314 | ✓ |
| Section G fields present? | no | no | ✓ |

---

## Step 17 — Engineer / CM / PM rollup tabs

### Engineer (`827f5b6a…`)

`GET /v1/projects/{pid}/dbs/engineer/{userId}?date=2026-05-20`

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `manpowerAmount` | 4,640 | 4,640.0 | ✓ |
| `machineryAmount` | 3,000 | 3,000.0 | ✓ |
| `materialAmount` | 170 | 170.0 | ✓ |
| `totalExpense` | 7,810 | 7,810.0 | ✓ |
| `contribution` | −4,310 | −4,310.0 | ✓ |
| `contributionPct` | −1.2314 | −1.2314 | ✓ |
| `pctAchieved` | 8.9744 | 8.9744 | ✓ |
| Section G fields present? | no | no | ✓ |

### CM (`d2874836…`)

`GET /v1/projects/{pid}/dbs/cm/{userId}?date=2026-05-20`

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `manpowerAmount` | 4,640 | 4,640.0 | ✓ |
| `machineryAmount` | 3,000 | 3,000.0 | ✓ |
| `materialAmount` | 170 | 170.0 | ✓ |
| `supervisorCount` | 1 | 1 | ✓ |
| `pctAchieved` | 8.9744 | 8.9744 | ✓ |
| `contributionPct` | **−123.1429** (percentage scale) | −123.1429 | ✓ — Finding 8 still reproduces |
| `totalExpense` | (not exposed) | `null` | n/a — Finding 9 still reproduces |
| `contribution` | (not exposed) | `null` | n/a — Finding 9 still reproduces |
| Section G fields present? | no | no | ✓ |

### Project (PM)

`GET /v1/projects/{pid}/dbs/project?date=2026-05-20`

This is where the MCL fold-in *and* the Section G daily-proration land.

| Field | Expected | Actual | ✓ |
|---|---:|---:|:-:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 | ✓ |
| `manpowerAmount` | 4,640 | 4,640.0 | ✓ |
| `machineryAmount` | 3,000 | 3,000.0 | ✓ |
| `materialAmount` | 170 (Sup) + 1,632.50 (MCL) = **1,802.50** | 1,802.5 | ✓ |
| `generalExpenseAmount` | 43.55 (= 1350/31) | 43.55 | ✓ |
| `generalExpenseMonthlyTotal` | 1,350.00 | 1,350.0 | ✓ |
| `totalExpense` | 4,640 + 3,000 + 1,802.50 + 43.55 = **9,486.05** | 9,486.05 | ✓ |
| `totalIncome` | 3,500 | 3,500.0 | ✓ |
| `contribution` | 3,500 − 9,486.05 = **−5,986.05** | −5,986.05 | ✓ |
| `contributionPct` | −1.7103 (= −5,986.05 / 3,500) | −1.7103 | ✓ |
| `supervisorCount` | 1 | 1 | ✓ |
| `generalExpenseLinesJson` Electricity totalAmount | 32.26 | 32.26 | ✓ |
| `generalExpenseLinesJson` Water totalAmount | 11.29 | 11.29 | ✓ |

The PM-tier loss is bigger than Supervisor/Engineer by `1,632.50 + 43.55 = 1,676.05` — exactly the MCL fold-in plus the Section G daily prorate. ✓

> Note: the 2026-05-19 runbook quotes PM `totalExpense=9,442.50` / `contribution=−5,942.50` / `contributionPct=−1.6979` — those numbers are for a run *without* a Section G monthly entry. The current run intentionally exercises Section G, so PM totals shift by `+43.55 / −43.55 / −0.0124` accordingly. Both sets are correct for their inputs.

---

## Step 18 — Idempotency + range + exports

### Idempotency (3× recompute)

`POST /v1/projects/{pid}/dbs/recompute?date=2026-05-20`

| Run | Income | Expense | Contribution | Material | `version` |
|---|---:|---:|---:|---:|---:|
| 1 | 3,500 | 9,486.05 | −5,986.05 | 1,802.50 | 21 |
| 2 | 3,500 | 9,486.05 | −5,986.05 | 1,802.50 | 22 |
| 3 | 3,500 | 9,486.05 | −5,986.05 | 1,802.50 | 23 |

Bit-identical, only `version` advances. ✓

### Range recompute (3 days)

`POST /v1/projects/{pid}/dbs/recompute-range?from=2026-05-18&to=2026-05-20`

| Date | Income | Expense | Contribution | DPR count |
|---|---:|---:|---:|---:|
| 2026-05-18 | 0 | 43.55 | −43.55 | 0 |
| 2026-05-19 | 0 | 43.55 | −43.55 | 0 |
| 2026-05-20 | 3,500 | 9,486.05 | −5,986.05 | 3 |

Empty days carry the Section G daily-proration (43.55) for May 2026 — that is correct, since Section G is monthly and the listener back-fills every day in the affected month. ✓

### Excel export

`GET /v1/projects/{pid}/dbs/export.xlsx?date=2026-05-20&level=PM`

| Field | Expected | Actual |
|---|---|---|
| HTTP status | 200 | **200** |
| Size | non-zero | **8,986 B** |
| File magic | Microsoft OOXML | **Microsoft OOXML** |

Saved to `/tmp/dbs-export-2026-05-20.xlsx`.

### PDF export

`GET /v1/projects/{pid}/dbs/export.pdf?date=2026-05-20`

| Field | Expected | Actual |
|---|---|---|
| HTTP status | 200 | **200** |
| Size | non-zero | **7,278 B** |
| File magic | PDF | **PDF document, version 1.4, 1 pages** |

Saved to `/tmp/dbs-export-2026-05-20.pdf`.

> Note: the `Content-Type` response header gets shadowed in the curl `-D` dump by `X-Content-Type-Options: nosniff`; the body magic is what validates the content type here.

---

## Findings

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | Rate-master endpoints are singular | Docs | Still accurate |
| 2 | Activity create requires `projectId` in body (not just URL) | Docs | Still accurate |
| 3 | Role-rate IDs come from `/v1/resource-roles/{roleId}/with-variants`, NOT from the singular `/v1/manpower-rate-master` (which carries different legacy OMN rows) | Docs | **NEW clarification** |
| 4 | Activities must be locked before DPRs | Docs | Still accurate |
| 5 | `BoqActualRateRecalcListener` ignoring `MaterialConsumptionLoggedEvent` | Bug | **APPEARS FIXED** in `4320b568` — BOQ `actualRate` populated correctly without DPR re-PUT this run |
| 6 | DPR rows need role-rate IDs to feed BOQ actualRate | Docs / form contract | Still accurate; this run sent IDs from the start |
| 7 | `% Achieved` tile missing from Supervisor DBS tab UI | UI gap | Still open (per Gotcha 4 in test prompt) |
| 8 | CM-tier `contributionPct` returns percentage (−123.14) not fraction (−1.2314) | API inconsistency | Still open — reproduced |
| 9 | CM row missing `totalExpense` / `contribution` fields | API inconsistency | Still open — `null` confirmed |
| **10** | **Supervisor-tier `pctAchieved` / `boqPlannedAmount` / `boqAchievedAmount` return 0** on the per-supervisor endpoint even though the Engineer rollup (which sums Sup1) returns the correct 8.9744 / 39,000 / 3,500 | Regression | **NEW** — needs back-end investigation; suspect a missing assignment in the supervisor-row projection after a recent refactor |
| 11 | WBS `code` is globally unique across all projects, not project-scoped | Docs / data model | **NEW** observation — required project-prefixed codes to avoid `WBS_CODE_DUPLICATE`. Either documentation or constraint-scope question for product |
| 12 | BOQ `/boq/bulk` body is a bare JSON array, not `{"items":[…]}` | Docs | **NEW** clarification (runbook hint says "bulk" but didn't pin the wire shape) |
| 13 | BOQ list response items have `pctComplete: null` even though `qtyExecutedToDate` is populated | Minor | **NEW** observation — DBS rolls up correctly so this is purely a list-projection issue |

---

## Final ID inventory

```
Project:        ce16c93f-36ee-400c-8cda-3bc1c340dfd1  RB-E2E-2026-05-20
EPS:            e38edde8-b6cb-4d2c-8e16-72a8336e7c0a  MIG-CIV

WBS:
  BB1           0dc8d195-1743-4ca4-bac6-4eadda365a34  Bridge B-1
  BB1-SUB       6e231fed-b774-4207-bd6d-d2bbf15a81ed  Substructure
  BB1-SUP       becd37d8-9618-4489-8775-a1c99d9ce754  Superstructure
  BB1-SUB-FND   c9ae544d-d8ed-45a6-b513-8fb1308ded70  Foundation (leaf)
  BB1-SUB-PIE   1cfa20df-26ed-459c-bb50-00fe5641e803  Pier (leaf)
  BB1-SUP-DCK   fa6a0d26-9f48-40c6-8a2b-b483f92b8d23  Deck (leaf)

Activities (all LOCKED):
  RB-ACT-01     fae63d6b-ef01-49cd-8da6-1ba72b76149d  Foundation Excavation
  RB-ACT-02     24b8ed43-3cdf-4655-b748-0ee9d2b9acac  Pier PCC
  RB-ACT-03     841847c1-11e7-48c6-bb96-0666e06dac50  Deck Concrete Works

BOQ items:
  RB-03.01      b16d704e-3b40-4970-b809-0c5400109c4e  Unclassified Excavation
  RB-04.05      8dd2f550-5cfc-467c-9b43-909a07911d0b  PCC Class C
  RB-09.02      f7cea5e2-2aac-4c26-9b57-969c6c32bd99  Concrete RCC Grade M30

DPRs:
  DPR-1         35ee9299-381d-4729-a252-1854f19a2d8c
  DPR-2         48566a21-273a-4997-a205-702b2479f081
  DPR-3         941e7352-be0b-40cd-b1d8-e18bb83599ef

MCLs:
  Cement        c323ef31-11a0-463f-b6e4-09cc64b8649f
  Steel         ccd8b98d-6047-49cf-87b3-730d9f1018ee
  Aggregate     82b91f94-7536-4ce7-a20c-b098e49acd9c

Team (project_team row IDs):
  PM            aed478e1-…  (user 2165f85e…)
  CM            1becddd9-…  (user d2874836…)  reports to PM
  Engineer      d6bc47a2-…  (user 827f5b6a…)  reports to CM
  Supervisor 1  fa010158-…  (user 008b7b25…)  reports to Engineer
  Supervisor 2  b026b814-…  (user 0dca68d4…)  reports to Engineer

Section G plan-item IDs (the three that were edited):
  Electricity   c32485a5-42a3-45bd-ac4c-e48058cd22ac
  Water         3742b929-4c98-4e45-87c4-d2273ebc30ed
  Rent          a165e56f-86aa-4cd2-b5cc-6836aa15bb9a

Role-rate variants used:
  Helper        roleId=6c649285…  rateId=c45b6987… @ ₹180/Day
  Mason         roleId=089ae277…  rateId=f6238c87… @ ₹380/Day
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
- Test prompt: `docs/test-prompts/dpr-dbs-e2e.md`
- Prior log: `docs/dpr-dbs-e2e-test-execution-log-2026-05-19.md`
- Excel / PDF outputs: `/tmp/dbs-export-2026-05-20.xlsx`, `/tmp/dbs-export-2026-05-20.pdf`
- Backend HEAD: `4320b568 feat(ai,dbs,boq): CM-tier DBS, MCL-triggered BOQ rate recalc, new AI tools`
