# DPR → BOQ → DBS End-to-End Test Runbook

A click-by-click walkthrough from an empty install to a fully populated DBS (Daily Balance Sheet) supervisor row, with income on the BOQ side and all six expense buckets resolved on the cost side.

Login first as `admin / admin123` at `/auth/login`.

---

## Phase 1 — Master data (once per environment, not per project)

These live outside any single project and must exist before BOQ-rate / DPR-cost math has anything to resolve against.

### 1. Manpower Rate Master

**Where:** `/admin/manpower-rates` → button **"Add Role"** then **"Add Rate"** per role.

- Add roles: `Mason`, `Helper`, `Carpenter`, `Steel Fixer`, `Electrician`, etc.
- For each role enter a **Rate per Day** (the spreadsheet column "Rate") and Unit = `Day`. The DPR-cost formula reads this when the user doesn't override on the DPR row.

### 2. Equipment Rate Master

**Where:** `/admin/equipment-rates` → **"Add Equipment Type"** + **"Add Rate"**.

- Add: `Excavator`, `Wheel Loader`, `Tipper`, `Grader`, `Roller`, `Generator 30 kva`, etc.
- Rate per Day. Even though the spreadsheet says "Rate per Hr", our policy is **rate × nos** so enter the daily hire cost.

### 3. Material Rate Master

**Where:** `/admin/material-rates` → **"Add Material"** + **"Add Rate"**.

- Add: `Cement OPC 43 Grade` (kg) / `Steel Fe500` (kg / MT) / `Aggregate 20mm` (Cum) / `Diesel` (litre) / etc., each with a unit and rate.

### 4. Resource catalogue

**Where:** `/resources` → **"+ New Resource"**.

- For each material above, create a **Resource** of type MATERIAL, code (e.g. `OMD-MAT-CEMENT-OPC-43`), and **link it to its Material Rate Master row** (`rateMasterId`).
- Same for equipment (`resourceType=EQUIPMENT`) and manpower (`resourceType=LABOR`) — but the DPR doesn't require these as long as you supply `unitRate` directly on the DPR child row.

### 5. Work Activities + Productivity Norms

**Where:** `/admin/work-activities` → **"+ New Work Activity"** → fill name + default unit (`Cum`/`MT`/`Sqm`).

- Inside each work activity, click **"Add Productivity Norm"** and pick **Manpower** + **Equipment** combinations with output per day.
- Example: *Foundation Excavation, default Cum, 1 Excavator + 5 Helpers → 80 Cum/day*.
- **Why this matters:** every Activity you create later must reference a Work Activity. Without one, the DPR form can't suggest a default unit or pre-populate the productivity preview.

### 6. Users for the project team

**Where:** `/admin/users` → **"+ New User"**.

Create at least **one user per tier** of the team chain:

- 1 × `PM` role
- 1 × `CONSTRUCTION_MANAGER`
- 1 × `ENGINEER` (also called Site Manager)
- 2 × `SUPERVISOR`

These get attached to a project in step 12 below.

---

## Phase 2 — Project setup

### 7. Create Project

**Where:** `/projects` → button **"+ New project"**.

- Code: `PILOT-001`, Name: `Pilot Construction Project`, Type: `Construction`, currency, start/finish dates.
- Hit **Create** → you land on `/projects/{id}` Overview.

### 8. WBS

**Where:** **WBS** tab → **"+ Add Root Node"** or right-click a node → **"+ Add Child"**.

Build a small tree, e.g.:

```
Bridge B-1
  ├── Substructure
  │    ├── Foundation
  │    └── Pier
  └── Superstructure
       └── Deck
```

Each leaf WBS node is what Activities and BOQ items will attach to.

### 9. Activities

**Where:** **Activities** tab → **"+ New Activity"** (or import from CSV).

- For each leaf WBS node, create one or more activities:
  - Code: `PILOT-ACT-01`, Name: `Foundation Excavation`
  - WBS node: pick from dropdown
  - **Work Activity:** pick from the productivity-norm catalogue (step 5). The default unit (`Cum`) fills automatically.
  - Activity Type, Original Duration, Planned Start/Finish.
  - **Preliminary?** Tick only for mobilisation / site office / diversions — those costs go to Section B not the BOQ-direct revenue side.
- Optionally assign supervisors to the activity (button **"Manage Supervisors"** on the activity row).

### 10. Productivity Norm sanity-check (optional but recommended)

On the activity row, click **"View Norm"**. You should see *"1 Excavator + 5 Helpers → 80 Cum/day"* with no warning banners. A warning here means the Work Activity wasn't picked or has no norm.

### 11. BOQ items

**Where:** **BOQ** tab → button **"Add BOQ Item"**.

For each contract line, enter:

- **Item No** (e.g. `E2E-03.01`)
- **Description**, **Unit** (Cum / MT / lin.m / etc.)
- **BOQ Qty** (contract quantity), **BOQ Rate** (₹/unit) — *Total BOQ Amount auto-derives*
- **Budgeted Rate** (your internal target rate, used for variance)
- **Chapter** (MoRTH grouping)

Save. Verify the Grand Total at the bottom matches your contract value.

**Today there is no Excel-import button** — for >20 rows, the developer-only path is `POST /v1/projects/{id}/boq/bulk` with a JSON array; ask an engineer to run it.

### 12. Project Team (critical for DBS rollup)

**Where:** **Team** tab → **"+ Add Member"**.

Add the users from step 6 with their roles, **and set "Reports To"** for each:

- `PM` → null
- `CONSTRUCTION_MANAGER` → reports to PM
- `ENGINEER` (Site Manager) → reports to CM
- `SUPERVISOR` → reports to Engineer

Without this chain, the DBS Engineer / CM / PM tabs will be empty even when the Supervisor tab has data.

---

## Phase 3 — Daily execution

Done daily by the site supervisor.

### 13. Submit a DPR

**Where:** **DPR** tab → button **"+ New DPR"**.

Fill the form:

#### Header section

- **Report Date** — today (cannot be future).
- **Supervisor** — pick from project supervisors (drives the DBS supervisor row).
- **Activity** — pick from the project's activities. Default unit auto-fills.
- **BOQ Item** — the dropdown is filtered to BOQ items relevant to the activity (via the `/boq/by-activity` endpoint). Pick the one this work bills against.
- **Qty Executed** — the number you actually did today (e.g. `100`). This drives `boqForTheDayAmount = qty × BOQ rate` and `BOQ.qty_executed_to_date` (via `DprBoqSyncListener`).
- Chainage From / To, Weather, Shift, Side — optional but useful for filtering.

#### Manpower section

Click **"+ Add Manpower Row"** for each trade:

- Trade: `Mason`, Nos: `5`, Working Hours: `8` (logging-only), **Unit Rate: 150**, Unit Rate Basis: `HOUR` (basis is preserved but doesn't multiply — `lineCost = rate × nos = 750`).
- Repeat for Helper / Steel Fixer / etc.
- If you leave Unit Rate blank and the role has a master rate, the calculator falls back to that.

#### Equipment section

Click **"+ Add Equipment Row"**:

- Equipment Type: `Excavator`, Nos: `1`, Working Hours: `6`, Unit Rate: `500` → `lineCost = 500`.

#### Materials section

Click **"+ Add Material Row"** if you want material cost attributed to this supervisor's DPR (rather than via MCL):

- Material: pick from catalogue, Quantity, Unit Rate.

#### Issues section

Optional safety / quality / progress issues.

Click **Save**. Behind the scenes this:

- Writes `daily_progress_reports` + `dpr_manpower` + `dpr_equipment` + `dpr_material`.
- Fires `DprSubmittedEvent` → DBS recompute + BOQ actual-rate recompute.
- Updates `boq_items.qty_executed_to_date`.

#### Validation gotchas

- One DPR per (supervisor, activity, day) — system rejects duplicates with `DPR_ALREADY_EXISTS_FOR_ACTIVITY`. To log against a 3rd BOQ line on the same day, use a 3rd activity or edit the existing DPR.
- `qtyExecuted` must be `> 0`.
- Report date can't be in the future.

### 14. (Optional) Material Consumption Log

**Where:** **Materials** tab → **"+ Log Consumption"** (or one of the Storekeeper views).

- Date, Resource (from catalogue → drives `lineCost = consumed × rate`), Material Name, Unit, **Opening Stock**, **Received**, **Consumed** (the cost driver), Activity (links to the BOQ via the activity → BOQ chain).
- Save. Fires `MaterialConsumptionLoggedEvent` → DBS material-amount recompute (project-level only) + BOQ actualRate update.

---

## Phase 4 — Verify the numbers

### 15. BOQ page

**Where:** **BOQ** tab. After the DPR, the row for the BOQ item you billed should show:

- **Qty Executed** = today's DPR qty (cumulative if multiple DPRs landed)
- **% Complete** = qty executed / BOQ qty
- **Actual Rate** = (Σ DPR cost lines + Σ MCL costs on this activity) ÷ qty executed
- **Actual Amount** = qty × actualRate
- **Cost Variance / Var %** — turn red when actual > budgeted

If Actual Rate stays 0 → you posted a bare DPR with no manpower/equipment/material cost lines and no MCL → expected.

### 16. DBS — Supervisor tab

**Where:** **DBS** tab → **Supervisor** sub-tab → pick the supervisor + date.

You should see:

- **Total Income** = `boqForTheDayAmount` = qty × BOQ rate
- **Material / Manpower / Admin / Machinery / Fuel / Subcontractor** tiles
  - *Material is 0 here unless you posted DPR material rows (MCL doesn't carry supervisor FK)*
- **Total Expense** = Σ of those six
- **Contribution** = Income − Expense (red if negative)
- **Contribution %**

Each section card (A. Manpower, C. Machinery, etc.) expands to show the individual rows from your DPR.

### 17. DBS — Engineer / CM / PM tabs

**Where:** **DBS** tab → **Engineer / Site Manager** sub-tab.

- The engineer row sums **all supervisor rows whose engineer-of-record = this engineer**, derived from the Project Team chain (step 12).
- Same logic up the chain to CM and PM. PM tab additionally folds in any DRD / MCL rows that lack a supervisor FK.
- If a tab is empty: most likely cause is the reporting chain in Project Team isn't set up. Open Team tab and verify "Reports To" is populated.

### 18. Force recompute (if numbers look stale)

**Where:** **DBS** tab → **PM** sub-tab → button **"Recompute (this date)"** or **"Recompute range"**.

The event-driven recompute is reliable for new DPRs, but useful after a backfill or schema change.

---

## Quick happy-path you can run in 5 minutes

If the master data (Phase 1) and project (steps 7–12) already exist:

1. **BOQ tab** → Add BOQ Item: `E2E-03.01 / Unclassified Excavation / Cum / Qty 1000 / Rate 12.50 / Budgeted 11`.
2. **Activities tab** → ensure `PILOT-ACT-01 Foundation Excavation` exists and references a Work Activity with a norm.
3. **DPR tab** → + New DPR:
   - Supervisor, Activity = PILOT-ACT-01, BOQ = E2E-03.01, Qty Executed = **100**
   - Manpower row: Mason × 5 @ ₹150 (lineCost = 750)
   - Equipment row: Excavator × 1 @ ₹500 (lineCost = 500)
   - Save.
4. **BOQ tab** → row should now show Qty Executed = 100, % Complete = 10%, Actual Rate = 12.50, Actual Amount = 1,250 (or higher if MCL logged).
5. **DBS tab → Supervisor** → expect: Total Income ₹1,250, Manpower ₹750, Machinery ₹500, Total Expense ₹1,250, Contribution ₹0.

If those numbers match, the whole event chain (DPR → BOQ sync → BOQ actualRate recalc → DBS recompute → 4-tier rollup) is working.

---

## Troubleshooting — what to check when something doesn't show up

| Symptom | Likely cause | Where to fix |
|---|---|---|
| DPR form has no BOQ items in dropdown | Activity has no BOQ candidates and the BOQ list itself is empty | BOQ tab → Add items |
| BOQ Actual Rate = 0 after DPR | DPR had no manpower / equipment / material cost lines, and no MCL was logged for the activity | Edit the DPR, add cost rows |
| DBS Supervisor tab empty | Supervisor user wasn't in Project Team, or DPR was logged against a different supervisor | Team tab → add member |
| DBS Engineer / CM / PM tab empty | Reports-To chain missing | Team tab → set "Reports To" |
| Section B (Admin) always 0 | Section B accrues from monthly preliminary records; no UI for those yet | Engineering task — uses `daily_resource_deployments` of type ADMIN |
| Section F (Subcontractor) always 0 | Not implemented yet (hard-coded to 0) | Engineering task |

---

## Cost formula reference

Per the latest policy (merged at commit `4883a951 fix(dbs,kpi): drop hours multiplier from cost (nos × rate)`):

- **Manpower line cost** = `unitRate × nos` *(working hours and OT hours persist on the row for analytics but never enter the cost or unit math)*
- **Equipment line cost** = `unitRate × nos` *(idle / breakdown / fuel litres are informational only)*
- **Material line cost** = `unitRate × quantity`
- **BOQ For-the-day amount** = `qtyExecuted × BOQ rate`
- **BOQ Actual Rate** = `(Σ DPR cost lines + Σ MCL.line_cost where activity matches) ÷ Σ DPR.qty_executed for the BOQ item`

This mirrors the spreadsheet's A. Manpower section (`Description | Rate | Nos | For the day | Total Amount` — no hours column) and the Resource Plan rollup formula `actualCost = rate × actualUnits` in `ResourceAssignmentCostRollupListener`.

---

## Related docs

- `docs/dbs-and-material-consumption-guide.md` — DBS architecture and MCL semantics
- `docs/dpr-issues-api.md` — DPR issue tracking sub-section
- `docs/E2E_TESTING_GUIDE.md` — broader E2E testing notes
- `docs/rbac-implementation-guide.md` — role / permission model that gates the Team tab and DBS visibility
