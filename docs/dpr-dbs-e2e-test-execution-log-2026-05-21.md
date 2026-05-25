# DPR → BOQ → DBS End-to-End Test — Execution Log (Demo)

**Date:** 2026-05-21
**Environment:** `https://demo.unitysphere.site:4001` (Unity Sphere demo)
**Project under test:** `DEMO-E2E-20260521` — *DPR-DBS E2E Demo Run 2026-05-21*
**Project ID:** `0ac16788-7074-4df6-8db7-914ead405b2d`
**EPS parent:** `CONST-01-760165` Construction Projects *(no MIG-CIV on demo)*
**Period covered:** 2026-05-21 (single day)
**Mode:** API-driven setup + Section G + UI verification gate after each phase

## Pass / Fail Summary

| Phase | Result |
|---|---|
| Phase 1 — Master data (seeded) | ✓ PASS |
| Phase 2 — Project / WBS / Activities / BOQ / Team / Lock | ✓ PASS |
| Phase 2.5 — Productivity norms | ✓ PASS *(new fix discovered)* |
| Phase 2.7 — Section G plan + actuals + daily proration | ✓ PASS |
| Phase 3 — DPRs (×3) | ✓ PASS |
| Phase 3 — MCLs (×3) | ✓ PASS after re-link to legacy `material_rate_master` |
| Phase 4 — BOQ master actual rates | ✓ PASS after DPR re-PUT (Finding 5 reproduced) |
| Phase 4 — DBS Supervisor + Engineer + PM | ✓ PASS (17/17 + 7/7 + 10/10) |
| Phase 4 — DBS CM | ✗ EMPTY (Finding B — `CONSTRUCTION_MANAGER` blocked on demo DB) |
| Phase 4 — Idempotency, range recompute, xlsx + pdf exports | ✓ PASS |

---

## Phase 1 — Master data

Demo started essentially empty for rate masters (1 manpower row, 1 equipment row, 0 material rows). Seeded the rates through the **unified `PUT /v1/resource-roles/{id}/with-variants`** path (the UI's canonical save), plus 1 material category + 3 legacy `material_rate_master` rows to satisfy the MCL rate-resolver.

### Manpower / Equipment / Material role variants

| Role | Variant | Unit | Rate | Variant ID |
|---|---|---|---:|---|
| ROLE-HELPER (existing) | Helper / Grade A | Day | 180 | `05eb623d-3b34-42d5-93a1-37196bbc9bad` |
| ROLE-MASON (existing) | Mason / Grade A | Day | 380 | `888b812e-6610-4e26-b6ed-2af3b0b7c039` |
| EQ_001 Excavator (existing) | Generic / 20T-Standard | Day | 1,800 | `889515df-fbeb-4237-b4ab-5e7f9ffca4c2` |
| ROLE-WHEELLOADER **(new)** | Generic / 3CY | Day | 1,200 | `390f0abc-ec19-48e4-9e58-96929c9e2950` |
| ROLE-CEMENT-OPC **(new)** | OPC 43 | MT | 85 | `42f81843-9ea8-4bc9-905a-28522bc6ad69` |
| ROLE-STEEL-FE500 **(new)** | Fe500 | MT | 620 | `fafbe850-d547-4f43-b2ea-3722f08c57f1` |
| ROLE-AGGREGATE-20MM **(new)** | 20mm | Cum | 14.50 | `e5edd0ec-a94e-42f2-8a32-cf89be9287f3` |

### Material Resources (for MCL)

| Resource | Unit | costPerUnit | rateMasterId | Resource ID |
|---|---|---:|---|---|
| Cement OPC 43 | kg | 3.20 | `dde5ac7a…` | `4a96918e-b19e-4589-8d3b-0b5bd67f0b82` |
| Steel Fe500 | MT | 620 | `bd90519b…` | `a05a0a09-59aa-4af7-a46f-a136dd08441a` |
| Aggregate 20mm | Cum | 14.50 | `0cbb04f1…` | `c3ca102b-8a8e-4e88-86ee-7adfdb8ea8ef` |

### Legacy `material_rate_master` rows (needed for MCL rate resolution)

| Spec | Unit | Rate | ID |
|---|---|---:|---|
| Cement OPC 43 | kg | 3.20 | `dde5ac7a-ca96-4ba9-94e5-834e343234db` |
| Steel Fe500 | MT | 620 | `bd90519b-ebd8-4079-877d-0a0c91e8bf5e` |
| Aggregate 20mm | Cum | 14.50 | `0cbb04f1-8c7e-4926-b0f5-0c167d257243` |

---

## Phase 2 — Project setup

### Step 7 — Project (✓)

- **Code:** `DEMO-E2E-20260521`
- **Window:** 2026-05-21 → 2026-11-21, currency INR, status PLANNED
- **EPS:** `70a5f663-…` (CONST-01-760165) — substituted; demo had no MIG-CIV

### Step 8 — WBS tree (6 nodes ✓)

```
Bridge B-1            e1d64466-…
├── Substructure      5af26b87-…
│   ├── Foundation    0744133a-…  (leaf)
│   └── Pier          6fac319a-…  (leaf)
└── Superstructure    74cfc95e-…
    └── Deck          c0008277-…  (leaf)
```

### Step 9 — Activities (3 ✓)

| Code | Activity | WBS leaf | Work Activity | ID |
|---|---|---|---|---|
| RB-ACT-01 | Foundation Excavation | Foundation | UNCLASSIFIED_EXCAVATION | `23bf7b63-6c85-49cd-aaeb-7c798946dc2c` |
| RB-ACT-02 | Pier PCC | Pier | FOUNDATION_PREPARATION_FOR_CULVERTS *(sub for "Pilot PCC")* | `13fd7c1c-6293-4357-90e8-8d10d5d81b73` |
| RB-ACT-03 | Deck Concrete Works | Deck | CONCRETE_WORKS | `5a0f6a81-6011-467a-8de9-c9c2334a6301` |

### Step 9.5 — Resource Plan (8 rows ✓)

| Activity | Resource | nos | rate/Day | plannedCost (Actual) | Expected |
|---|---|---:|---:|---:|---:|
| RB-ACT-01 | Helper @180 | 5 | 180 | 900.0 | 900 ✓ |
| RB-ACT-01 | Excavator @1800 | 1 | 1800 | 1800.0 | 1800 ✓ |
| RB-ACT-02 | Mason @380 | 3 | 380 | 1140.0 | 1140 ✓ |
| RB-ACT-02 | Wheel Loader @1200 | 1 | 1200 | 1200.0 | 1200 ✓ |
| RB-ACT-02 | Cement OPC @85/MT | 20 | 85 | 1700.0 | 1700 ✓ |
| RB-ACT-03 | Helper @180 | 6 | 180 | 1080.0 | 1080 ✓ |
| RB-ACT-03 | Mason @380 | 4 | 380 | 1520.0 | 1520 ✓ |
| RB-ACT-03 | Cement OPC @85/MT | 50 | 85 | 4250.0 | 4250 ✓ |
| | | | **Σ** | **12,690** | **12,690 ✓** |

### Step 10.5 — Productivity Norms (NEW: 6 norms across 3 work activities ✓)

The UI flagged a "no Productivity Norms" warning on all 3 activities (3 work-activity masters were missing norms). Posted via `POST /v1/productivity-norms`:

| Work Activity | Norms |
|---|---|
| UNCLASSIFIED_EXCAVATION | EQUIPMENT: 1 Excavator → 80 Cum/day · MANPOWER: 5 Helpers → 80 Cum/day |
| FOUNDATION_PREPARATION_FOR_CULVERTS | EQUIPMENT: 1 Wheel Loader → 20 Cum/day · MANPOWER: 3 Masons → 20 Cum/day |
| CONCRETE_WORKS | MANPOWER: 4 Masons → 15 Cum/day + 6 Helpers → 15 Cum/day |

Warning cleared after refresh.

### Step 11 — BOQ items (3 ✓)

| Item | Qty | Rate | BOQ Amt | Budgeted Rate | Budgeted Amt | ID |
|---|---:|---:|---:|---:|---:|---|
| RB-03.01 Unclassified Excavation | 500 | 14.00 | 7,000.0 | 12.00 | 6,000.0 | `1cbaa8a5-…` |
| RB-04.05 PCC Class C | 100 | 80.00 | 8,000.0 | 70.00 | 7,000.0 | `dcfb5db0-…` |
| RB-09.02 Concrete RCC M30 | 200 | 120.00 | 24,000.0 | 110.00 | 22,000.0 | `099570f2-…` |
| **Grand** | | | **39,000 ✓** | | **35,000 ✓** | |

### Step 12 — Project Team (5 members ✓ with deviation)

| Tier | role | Username | User ID | Reports To |
|---|---|---|---|---|
| PM | PM | pmanager | `8cc48a58-…` | — |
| CM | **SITE_MANAGER** ⚠ | dmicdc.ceo | `9eb0e6f1-…` | PM |
| Engineer | ENGINEER | diicdc.pd | `b18dd2a2-…` | CM |
| Sup1 | SUPERVISOR | Biswa | `ac1777b5-…` | Engineer |
| Sup2 | SUPERVISOR | rahul | `7c4d6fab-…` | Engineer |

⚠ See **Finding B** — `CONSTRUCTION_MANAGER` is rejected by demo DB check constraint.

### Step 12.5 — Lock activities (3 ✓ HTTP 200)

### Step 12.7 — Section G (PRE-sheet) (✓)

| Item | planQty (set) | planAmount (set) |
|---|---:|---:|
| Electricity Charges | 12 | 12,000 |
| Water & Sewage Charges | 43 | 4,300 |
| Rent Land & Office, Accommodation | 100 | 100,000 |

May 2026 actuals (`PUT /general-expenses/actuals/{id}?yearMonth=202605`):
- Electricity: achievedQty=1, achievedAmount=1,000
- Water: achievedQty=1, achievedAmount=350

| Assertion | Expected | Actual |
|---|---:|---:|
| `monthlyTotal` (2026-05) | 1,350.00 | 1,350.0 ✓ |
| `generalExpenseAmount` on 2026-05-21 | 43.55 (= 1350/31) | 43.55 ✓ |
| `generalExpenseMonthlyTotal` | 1,350.00 | 1,350.0 ✓ |
| `generalExpenseLinesJson` | Electricity 32.26 + Water 11.29 | `[{"description":"Electricity…","totalAmount":32.26},{"description":"Water & Sewage…","totalAmount":11.29}]` ✓ |
| MONTH rollup `totals.generalExpenseAmount` | ≈ 1,350.05 | 1,350.05 ✓ |
| PM-only — supervisor/engineer/cm tabs hide `generalExpense*` | yes | yes ✓ |

---

## Phase 3 — Daily execution

### Step 13 — DPRs (×3, all Supervisor 1 Biswa, 2026-05-21) (✓)

| DPR | Activity | BOQ | qty | Manpower | Equipment | Material | DPR ID |
|---|---|---|---:|---|---|---|---|
| 1 | RB-ACT-01 | RB-03.01 | 50 | Helper×5 @180 = 900 | Excavator×1 @1800 = 1800 | — | `f61d49c4-…` |
| 2 | RB-ACT-02 | RB-04.05 | 20 | Mason×3 @380 = 1140 | Wheel Loader×1 @1200 = 1200 | Cement 2MT @85 = 170 | `7420d331-…` |
| 3 | RB-ACT-03 | RB-09.02 | 10 | Helper×6 + Mason×4 = 1080+1520 = 2600 | — | — | `1c00e2c9-…` |

**Rollup across all three:**

| Bucket | Expected | Actual |
|---|---:|---:|
| Income (qty × BOQ rate) | 700+1600+1200 = **3,500** | 3,500 ✓ |
| Σ Manpower lineCost | 900+1,140+2,600 = **4,640** | 4,640 ✓ |
| Σ Equipment lineCost | 1,800+1,200+0 = **3,000** | 3,000 ✓ |
| Σ Material lineCost | 0+170+0 = **170** | 170 ✓ |

### Step 14 — Material Consumption Logs (×3, 2026-05-21) (✓)

After fixing rate resolution (linked Resources → legacy material_rate_master rows; see Phase 1 + Finding C below), MCLs reposted cleanly:

| MCL | Activity | Material | Consumed | unitRate | lineCost (Actual) | Expected |
|---|---|---|---:|---:|---:|---:|
| 1 | RB-ACT-02 | Cement OPC 43 | 100 kg | 3.20 | 320.0 | 320 ✓ |
| 2 | RB-ACT-03 | Steel Fe500 | 2 MT | 620 | 1,240.0 | 1,240 ✓ |
| 3 | RB-ACT-03 | Aggregate 20mm | 5 Cum | 14.50 | 72.5 | 72.50 ✓ |
| | | | | **Σ** | **1,632.5** | **1,632.50 ✓** |

---

## Phase 4 — Verification

### Step 15 — BOQ master (after DPR re-PUT to fold MCLs in)

Reproduced **Finding 5** verbatim: MCLs don't re-fire `BoqActualRateRecalcListener`; re-PUT DPRs to recompute.

| Item | Numerator (DPR cost + MCL) | ÷ Qty | Expected Rate | Actual Rate | ✓ |
|---|---|---:|---:|---:|:-:|
| RB-03.01 | 2,700 (DPR only) | 50 | 54.00 | **54.00** | ✓ |
| RB-04.05 | 1,140 + 1,200 + 170 + 320 = 2,830 | 20 | 141.50 | **141.50** | ✓ |
| RB-09.02 | 2,600 + 1,240 + 72.50 = 3,912.50 | 10 | 391.25 | **391.25** | ✓ |
| **Grand** | | | | actualGrandTotal=**9,442.5** / variance=**+6,342.5** | ✓ |

### Step 16 — DBS Supervisor 1 (Biswa) — all 17/17 ✓

| Field | Expected | Actual |
|---|---:|---:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 |
| `boqPlannedAmount` | 39,000 | 39,000.0 |
| `boqAchievedAmount` | 3,500 | 3,500.0 |
| `directCost` | 3,500 | 3,500.0 |
| `prelimCost` | 0 | 0.0 |
| `totalCostInclPrelims` | 3,500 | 3,500.0 |
| `pctAchieved` | 8.9744 | 8.9744 |
| `manpowerAmount` | 4,640 | 4,640.0 |
| `machineryAmount` | 3,000 | 3,000.0 |
| `materialAmount` | 170 | 170.0 |
| `adminAmount` / `fuelAmount` / `subcontractAmount` | 0 / 0 / 0 | 0.0 / 0.0 / 0.0 |
| `totalExpense` | 7,810 | 7,810.0 |
| `totalIncome` | 3,500 | 3,500.0 |
| `contribution` | −4,310 | −4,310.0 |
| `contributionPct` | −1.2314 | −1.2314 |

### Step 17 — DBS Engineer / CM / PM tabs

**Engineer (diicdc.pd) — 7/7 ✓** (same as Sup1; Sup2 had no DPRs)

| Field | Expected | Actual |
|---|---:|---:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 ✓ |
| `manpowerAmount` | 4,640 | 4,640.0 ✓ |
| `machineryAmount` | 3,000 | 3,000.0 ✓ |
| `materialAmount` | 170 | 170.0 ✓ |
| `totalExpense` | 7,810 | 7,810.0 ✓ |
| `contribution` | −4,310 | −4,310.0 ✓ |
| `contributionPct` | −1.2314 | −1.2314 ✓ |

**CM (dmicdc.ceo) — EMPTY ✗ (Finding B)**

All numeric fields returned 0 / null:

| Field | Expected | Actual |
|---|---:|---:|
| `boqForTheDayAmount` | 3,500 | **0** |
| `manpowerAmount` | 4,640 | **0** |
| `supervisorCount` | 1 | **0** |
| `contributionPct` | −123.1429 | **0** |
| `totalExpense` | (n/a) | null |

**Root cause:** The CM member was inserted with project role `SITE_MANAGER` because demo's `project_team.role` check constraint rejects `CONSTRUCTION_MANAGER`. `ProjectTeamService.resolveCmFor()` walks the chain looking for `CONSTRUCTION_MANAGER` explicitly, doesn't match, returns empty.

**PM (project) — 10/10 ✓** including Section G fold-in

| Field | Expected | Actual |
|---|---:|---:|
| `boqForTheDayAmount` | 3,500 | 3,500.0 |
| `manpowerAmount` | 4,640 | 4,640.0 |
| `machineryAmount` | 3,000 | 3,000.0 |
| `materialAmount` | 170 + 1,632.50 = **1,802.50** | 1,802.5 |
| `totalIncome` | 3,500 | 3,500.0 |
| `generalExpenseAmount` (Section G daily) | 43.55 | 43.55 |
| `generalExpenseMonthlyTotal` | 1,350.00 | 1,350.0 |
| `totalExpense` | 9,442.50 + 43.55 = **9,486.05** | 9,486.05 |
| `contribution` | 3,500 − 9,486.05 = **−5,986.05** | −5,986.05 |
| `supervisorCount` | 1 | 1 |

Note: previous runbook expected PM `totalExpense=9,442.50` / `contribution=−5,942.50`. Today's run is **larger by 43.55** because the runbook's prior log was written before the project carried a Section G entry on the test date. Both numbers are correct — the new test data simply includes Section G.

### Step 18 — Idempotency, range recompute, exports

**Idempotency (3× back-to-back recompute on 2026-05-21):**

| Run | income | expense | contribution | material |
|---|---:|---:|---:|---:|
| 1 | 3,500 | 9,486.05 | −5,986.05 | 1,802.5 |
| 2 | 3,500 | 9,486.05 | −5,986.05 | 1,802.5 |
| 3 | 3,500 | 9,486.05 | −5,986.05 | 1,802.5 |

Bit-identical ✓ (server omitted `version` in payload — minor compared to prior log).

**Range recompute 2026-05-19..21:**

| Date | income | expense | contribution |
|---|---:|---:|---:|
| 2026-05-19 | 0 | 43.55 | −43.55 |
| 2026-05-20 | 0 | 43.55 | −43.55 |
| 2026-05-21 | 3,500 | 9,486.05 | −5,986.05 |

Note: 5/19 and 5/20 carry `43.55` because Section G's monthly recompute prorates the same daily amount across **every** day in the month — the runbook's Gotcha #9 applied as expected. (Prior run had this column = 0 because Section G wasn't in scope.)

**Excel export** `GET /dbs/export.xlsx?date=2026-05-21&level=PM`:

| Field | Expected | Actual |
|---|---|---|
| HTTP | 200 | 200 ✓ |
| Content-Type | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | matched ✓ |
| Size | non-zero | 8,852 B ✓ |
| File magic | Microsoft OOXML | Microsoft OOXML ✓ |

**PDF export** `GET /dbs/export.pdf?date=2026-05-21`:

| Field | Expected | Actual |
|---|---|---|
| HTTP | 200 | 200 ✓ |
| Content-Type | `application/pdf` | matched ✓ |
| Size | non-zero | 6,486 B ✓ |
| File magic | PDF | PDF document, version 1.4, 1 pages ✓ |

---

## Findings

Findings list updated. Prior findings 1–4 + 6 unchanged (docs-only); Findings 5, 7, 8 carried forward; Finding 9 carried forward; **three new findings (A / B / C) added from this demo run.**

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | Rate-master endpoints are singular | Docs | Fixed in runbook |
| 2 | Activity create endpoint is `/v1/projects/{pid}/activities` | Docs | Fixed in runbook |
| 3 | Step 9.5 Resource Plan added | Docs | Documented |
| 4 | Activities must be locked before DPRs | Docs | Step 12.5 in runbook |
| **5** | `BoqActualRateRecalcListener` doesn't listen to `MaterialConsumptionLoggedEvent` — BOQ actualRate stays stale after MCL until DPR re-fires | **Bug** | Reproduced today — workaround works (re-PUT DPR) |
| 6 | DPR rows need `manpowerRoleRateId` / `equipmentRoleVariantId` / `materialRoleVariantId` for BOQ actualRate contribution | Docs + form contract | Documented |
| **7** | `% Achieved` tile missing from DBS Supervisor tab UI (API has `pctAchieved`) | UI gap | Carried forward — not retested in UI this run |
| **8** | CM-tier `contributionPct` scaled as percentage not fraction | API inconsistency | Could not retest — CM tab was empty on demo (Finding B) |
| **9** | CM row missing `totalExpense` / `contribution` fields | API inconsistency | Confirmed today — `totalExpense=null` on CM endpoint |
| **A (NEW)** | Demo's legacy `/v1/manpower-rate-master` / `/v1/equipment-rate-master` / `/v1/material-rate-master` endpoints don't reflect writes from the unified `PUT /v1/resource-roles/{id}/with-variants` path. UI's "Configure Role Rates" writes to `*_role_variants` tables; legacy GETs still report old counts. | API inconsistency | Filed — either deprecate the legacy GETs or have them union both tables |
| **B (NEW)** | `project_team.role` DB check constraint on demo rejects `CONSTRUCTION_MANAGER` even though the Java `ProjectRole` enum lists it. Forces all CM-tier members to be inserted as `SITE_MANAGER`, which the DBS rollup's `resolveCmFor()` doesn't match, leaving the CM tab empty. | **Bug** — DB schema lags Java enum | Filed — `ALTER TABLE project.project_team DROP CONSTRAINT project_team_role_check` (regenerate from current enum) |
| **C (NEW)** | MCL rate resolution requires `Resource.rateMasterId → MaterialRateMaster.rate`. No fallback to `Resource.costPerUnit`, no path through the new `material_role_variants` store, no body field to pass rate directly. When the demo Resource was created with only `costPerUnit=3.20`, MCL persisted `unitRate=null` / `lineCost=null` silently. | **Bug / Documentation gap** | Filed — suggest fallback chain: rateMasterId → costPerUnit → material_role_variants lookup. Or surface a validation error rather than persisting null lineCost |

---

## Final ID inventory

```
Project:        0ac16788-7074-4df6-8db7-914ead405b2d  DEMO-E2E-20260521
EPS:            70a5f663-81a5-4fac-9839-30b3e4ebd98b  CONST-01-760165 Construction Projects

WBS:
  BB1              e1d64466-f2af-4033-b786-1978e0bb269e  Bridge B-1
  BB1-SUB          5af26b87-b213-430d-af1b-f345afd640bf  Substructure
  BB1-SUP          74cfc95e-8191-4a6b-b4c2-fd3f72e91b01  Superstructure
  BB1-SUB-FND      0744133a-596c-4993-bdc3-3e1c273550c5  Foundation  (leaf)
  BB1-SUB-PIE      6fac319a-de17-4c3f-9795-93aff0eb63b9  Pier        (leaf)
  BB1-SUP-DCK      c0008277-77d6-444b-b398-692be54ee433  Deck        (leaf)

Activities (all LOCKED):
  RB-ACT-01        23bf7b63-6c85-49cd-aaeb-7c798946dc2c  Foundation Excavation
  RB-ACT-02        13fd7c1c-6293-4357-90e8-8d10d5d81b73  Pier PCC
  RB-ACT-03        5a0f6a81-6011-467a-8de9-c9c2334a6301  Deck Concrete Works

BOQ:
  RB-03.01         1cbaa8a5-b795-4d41-8409-65ec895d43c8
  RB-04.05         dcfb5db0-8df3-4db0-ae27-d930ffce160e
  RB-09.02         099570f2-9c2c-47e8-b67d-62b896857726

Team:
  PM               8cc48a58-b414-4a4c-9aff-cfb329ca1493  pmanager
  SITE_MANAGER (≈ CM)  9eb0e6f1-d5c5-4ddc-a821-bac2bedcce39  dmicdc.ceo   ⚠ Finding B
  ENGINEER         b18dd2a2-e5d1-488a-8c11-d9f62955814d  diicdc.pd
  SUPERVISOR (Sup1) ac1777b5-c09a-447c-958f-d3bea0fa8f44  Biswa
  SUPERVISOR (Sup2) 7c4d6fab-5d05-43e7-bf1e-8d462474f5e3  rahul

Role-variants used in DPRs:
  Helper Unskilled/A @180/Day  variant=05eb623d-3b34-42d5-93a1-37196bbc9bad  role=e68071fd
  Mason Skilled/A @380/Day     variant=888b812e-6610-4e26-b6ed-2af3b0b7c039  role=9bd50151
  Excavator Generic @1800/Day  variant=889515df-fbeb-4237-b4ab-5e7f9ffca4c2  role=f12865df
  Wheel Loader 3CY @1200/Day   variant=390f0abc-ec19-48e4-9e58-96929c9e2950  role=d95e13aa  (created today)
  Cement OPC @85/MT            variant=42f81843-9ea8-4bc9-905a-28522bc6ad69  role=8b0a03b8  (created today)

Material Resources used in MCLs:
  Cement OPC 43      4a96918e-b19e-4589-8d3b-0b5bd67f0b82   kg @3.20  (linked to material_rate_master dde5ac7a)
  Steel Fe500        a05a0a09-59aa-4af7-a46f-a136dd08441a   MT @620   (linked to material_rate_master bd90519b)
  Aggregate 20mm     c3ca102b-8a8e-4e88-86ee-7adfdb8ea8ef   Cum @14.50 (linked to material_rate_master 0cbb04f1)

DPRs:
  DPR-1              f61d49c4-42ec-4dfc-99fc-1f96f52ff811   RB-ACT-01 / RB-03.01 / qty 50
  DPR-2              7420d331-9803-45d6-af06-c761d1436c11   RB-ACT-02 / RB-04.05 / qty 20
  DPR-3              1c00e2c9-7d4c-4f5c-8cb7-424fe6a8b226   RB-ACT-03 / RB-09.02 / qty 10
```

---

## Substitutions made vs. runbook spec

| Spec called for | Used on demo | Reason |
|---|---|---|
| EPS `MIG-CIV` | `CONST-01-760165` (Construction Projects) | MIG-CIV doesn't exist on demo |
| Work activity `Pilot PCC` | `FOUNDATION_PREPARATION_FOR_CULVERTS` | No PCC entry in demo's work-activity catalogue |
| Project-team role `CONSTRUCTION_MANAGER` | `SITE_MANAGER` | DB check constraint rejects CONSTRUCTION_MANAGER on demo (Finding B) |
| Productivity norms expected pre-seeded on work activities | Added 6 norms across 3 work-activity masters | Work-activity catalogue had none — UI showed warnings |
| Direct rate-master rows for trades | Wrote through `with-variants` (variant tables) | Demo UI doesn't write legacy rate-master tables (Finding A) |
| MCL rate resolved from Resource catalogue | Added material category + material_rate_master + linked Resource.rateMasterId | MCL service strictly needs MaterialRateMaster — no fallbacks (Finding C) |

---

## Deliverables

1. **This file** — `docs/dpr-dbs-e2e-test-execution-log-2026-05-21.md`
2. Excel + PDF exports saved to `/tmp/dbs.xlsx`, `/tmp/dbs.pdf`
3. Findings list updated (Findings A, B, C added; 5, 7, 8, 9 carried forward)
4. **No commit / push performed** — awaiting OK to commit.
