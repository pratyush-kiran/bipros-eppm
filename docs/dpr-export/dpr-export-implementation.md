# DPR Report Export — "Daily Activity Costing" Excel

A **Generate Report** button on the DPR tab downloads an `.xlsx` "Daily Activity Costing" workbook
for the selected FROM→TO date range. The output is modelled on the site teams' `DPR monthwise.xlsx`
template, but every value is computed from our own database — the sample's external `VLOOKUP`/`SUMIFS`
links (which produce `#REF!` in a standalone file) are replaced by real numbers.

---

## 1. User flow

1. Open a project → **DPR** tab (`projects/[projectId]/dpr`).
2. Set the **FROM** / **TO** date filter and click **Refresh** (this is the *applied* range).
3. Click **Generate Report** (far right of the filter row).
4. The browser downloads `dpr-costing-<from>_<to>.xlsx` — **one sheet per calendar month** in the
   range (e.g. 19-Mar → 29-Jun produces *Mar 2026, Apr 2026, May 2026, Jun 2026*).

Only **APPROVED** DPRs are included.

---

## 2. Architecture

```
Frontend button ──HTTP GET──> ReportController ──> DprCostingReportService ──> DprCostingExcelWriter ──> .xlsx bytes
(dpr/page.tsx)               (/v1/reports/dpr/excel)   (native SQL, read DB)        (Apache POI)
```

Generation is **server-side** (Apache POI in the `bipros-reporting` module). This avoids N per-DPR
detail fetches on the client (the DPR list endpoint returns slim per-day aggregates, not child rows)
and keeps the BOQ join on the server.

---

## 3. Files

### Backend — module `bipros-reporting`

| File | Role |
|------|------|
| `application/dto/DprCostingReport.java` | DTO: project name + ordered `Block` list; nested `Manpower` / `Pmv` / `Material` / `SubContract` records. |
| `application/service/DprCostingReportService.java` | Reads DPRs + children + BOQ via cross-schema native SQL; `build(projectId, from, to)`. |
| `infrastructure/export/DprCostingExcelWriter.java` | Apache POI writer: one sheet per month, merged headers, parallel resource blocks, number formats. |
| `presentation/controller/ReportController.java` | `GET /v1/reports/dpr/excel` endpoint (added). |
| `src/test/.../export/DprCostingExcelWriterTest.java` | Round-trip tests asserting layout, costs, progress, borders. |

### Frontend — `frontend`

| File | Role |
|------|------|
| `src/lib/api/dprApi.ts` | `downloadMonthlyReport(projectId, from, to)` — authenticated blob fetch + browser save. |
| `src/app/(app)/projects/[projectId]/dpr/page.tsx` | **Generate Report** button (far right of the feed-filters row). |

---

## 4. Endpoint

```
GET /v1/reports/dpr/excel?projectId={uuid}&from={yyyy-MM-dd}&to={yyyy-MM-dd}
```

- **Auth:** `@PreAuthorize("hasPermission(null, 'DPR.READ')")` — anyone who can view the DPR tab can export.
- **Response:** `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (`ResponseEntity<byte[]>`).
- **Filename:** `Content-Disposition: attachment; filename="dpr-costing-<from>_<to>.xlsx"`.
- **Empty range** (no approved DPRs): returns a valid workbook with a single empty `DPR` sheet (header only).

The frontend reads the JWT from `localStorage`, fetches with `Authorization: Bearer`, then builds the
download from the blob (filename taken from `Content-Disposition`). Mirrors `dprApi.fetchPhotoBlobUrl`.

---

## 5. Sheet layout

- **Row 0:** `Project : <name>` (merged across all columns)
- **Row 1:** `Daily Activity Costing` (merged)
- **Row 2:** group-header row — scalar labels (vertically merged with row 3) + merged group banners
  **Manpower** / **PmV** / **Material** / **Subcontract**
- **Row 3:** resource sub-labels
- **Row 4+:** data — one **activity block per APPROVED DPR row**

Each activity block spans `max(#manpower, #pmv, #material, #subcontract)` rows (min 1); the resource
line-items stack **in parallel columns**. Scalar fields + progress are written on the block's **first
row only**. Records are **contiguous (no spacer rows)**, and **every cell from S.N → Progress Length
is bordered** (empty cells included).

### Columns (0-based)

| # | Column | # | Column | # | Column |
|---|--------|---|--------|---|--------|
| 0 | S.N | 12 | Manpower · Nr | 24 | Subcontract · Name |
| 1 | Date | 13 | Manpower · Rate | 25 | Subcontract · Work Description |
| 2 | Site | 14 | Manpower · Cost | 26 | Subcontract · Unit |
| 3 | Location | 15 | PmV · Detail | 27 | Subcontract · Quantity |
| 4 | From | 16 | PmV · Nr | 28 | Subcontract · Rate |
| 5 | To | 17 | PmV · Rate | 29 | Subcontract · Cost |
| 6 | Side | 18 | PmV · Cost | 30 | Remarks |
| 7 | Activity Code | 19 | Material · Description | 31 | Length |
| 8 | Unit | 20 | Material · Unit | 32 | Total Qty |
| 9 | Executed Qty | 21 | Material · Quantity | 33 | Progress Qty |
| 10 | **Name** (standalone) | 22 | Material · Rate | 34 | Progress % |
| 11 | Manpower · Category | 23 | Material · Cost | 35 | Progress Length |

> **Note:** `Name` (the supervisor) is a **standalone** column right after Executed Qty — it is *not*
> part of the Manpower group. The **Manpower** banner spans only `Category · Nr · Rate · Cost`.
> Manpower/PmV have **no hours/quantity** column (dropped); their Rate column is labelled `Rate`
> (not `Rate/Hr`).

### Number formats

| Format | Columns |
|--------|---------|
| `d-mmm-yy` | Date |
| `0\+000.00` (chainage, e.g. `7+300.00`) | From, To |
| `#,##0.00` | Executed Qty, all Nr / Rate / Cost / Quantity, Length, Total Qty, Progress Qty, Progress Length |
| `0.00%` | Progress % |

Money is shown as **raw numbers with no currency symbol** — consistent with the project's
relabel-only currency policy (a single symbol would be wrong across the many activities/projects an
export can span).

---

## 6. Field mapping & data linkage

### Tables read (cross-schema native SQL)

| Source | Used for |
|--------|----------|
| `project.daily_progress_reports` | header row per DPR (filtered `approval_status = 'APPROVED'`) |
| `project.dpr_manpower` | Manpower lines (`dpr_id` soft FK) |
| `project.dpr_equipment` | PmV lines |
| `project.dpr_material` | Material lines |
| `project.dpr_sub_contractor` | Subcontract lines |
| `resource.activity_sub_contractor_assignments` | LEFT JOIN for subcontractor `work_type_name`, `unit`, `rate_per_unit` |
| `project.boq_items` | **Total Qty** (see below) |
| `project.projects` | project name (title row) |

### Scalar (per-DPR) fields

| Excel column | DPR field |
|--------------|-----------|
| Date | `report_date` |
| Site | *(none — left blank; no per-DPR field)* |
| Location | `landmark` |
| From / To | `chainage_from_m` / `chainage_to_m` |
| Side | `side` |
| Activity Code | `boq_item_no` |
| Unit | `unit` |
| Executed Qty | `qty_executed` |
| Name | `supervisor_name` |
| Remarks | `remarks` |

### Resource line items

| Group | Excel columns ← DB |
|-------|--------------------|
| Manpower | Category ← `trade`, Nr ← `nos`, Rate ← `unit_rate`, Cost = `Nr × Rate` |
| PmV (Equipment) | Detail ← `equipment_type` (+ `fleet_no` in parens), Nr ← `nos`, Rate ← `unit_rate`, Cost = `Nr × Rate` |
| Material | Description ← `material_name`, Unit ← `unit`, Quantity ← `quantity`, Rate ← `unit_rate`, Cost ← `line_cost` |
| Subcontract | Name ← `sub_contractor_name`, Work ← `work_type_name`, Unit ← `unit`, Quantity ← `quantity`, Rate ← `rate_per_unit`, Cost = `Quantity × Rate` |

---

## 7. Formulas

All values are **computed in Java and written as literals** (not live Excel formulas), so the file
opens clean with no external links.

| Column | Formula | Notes |
|--------|---------|-------|
| Manpower Cost | `Nr × Rate` | Equals the stored `line_cost` (falls back to `line_cost` if Nr or Rate is null). |
| PmV Cost | `Nr × Rate` | Same. |
| Material Cost | `line_cost` | Stored (= quantity × rate). |
| Subcontract Cost | `Quantity × Rate` | Rate from the assignment snapshot. |
| **Length** | `chainage_to_m − chainage_from_m` | Blank if either chainage is null. |
| **Total Qty** | `boq_items.qty_executed_to_date` | Matched by **Activity Code**: `item_no = boq_item_no`. Blank if no match. |
| **Progress Qty** | `qty_executed` | The day's executed quantity. |
| **Progress %** | `Progress Qty ÷ Total Qty` | Blank if Total Qty is null/0. |
| **Progress Length** | `Progress % × Length` | Blank if either is null. |

### Total Qty — design history

The original template's `Total Qty` (cell AJ7) was:

```
=IF(OR(H7=Code!C9, H7=Code!C10, H7=Code!C11),
     SUMIFS('2.3.6(i)'!F8:F8437, '2.3.6(i)'!B8:B8437, ">="&E7, '2.3.6(i)'!B8:B8437, "<="&F7),
   IF(H7=Code!C14,
     SUMIFS('2.4.6(i)'!F7:F7031, '2.4.6(i)'!B7:B7031, ">="&#REF!, ... ), 0))
```

i.e. *"pick the per-activity survey sheet by Activity Code, then sum its quantities within this row's
chainage band From→To."* That data lives in **external workbooks** (one sheet per activity, chainage
in col B, quantity in col F) we do not have — and it already evaluates to `0`/`#REF!` in the sample.

**Decision (confirmed with the user):** source Total Qty from our own
`project.boq_items.qty_executed_to_date`, matched by **`item_no = boq_item_no`** (the Activity Code).
An earlier iteration matched by `boq_item_id`, but that FK is unlinked for some projects, leaving the
column blank — matching by Activity Code is robust.

---

## 8. Important notes

- **APPROVED only.** Draft/Submitted/Rejected DPRs never appear (`approval_status = 'APPROVED'` filter).
- **One sheet per month**, named `MMM yyyy` (e.g. `Jun 2026`). Multi-month ranges → multiple sheets.
- **Total Qty already includes today.** `qty_executed_to_date` is cumulative *including* the current
  day's approved DPR, so a single same-day DPR can read **Progress % = 100%**. If month-to-date-only
  or contract-capped behaviour is wanted later, change the source field in `loadExecutedToDateByItemNo`.
- **No currency symbol** in money columns (relabel-only policy; an export can span many projects).
- **Site** column has no per-DPR source and renders blank.
- **Subcontract** Work/Unit/Rate come from the assignment snapshot; if a row isn't linked to an
  assignment they stay blank (Name/Quantity still show).
- **Build requires JDK 23+.** The backend targets `release 23`; the default `JAVA_HOME` (jdk-21) fails
  with *"release version 23 not supported."* Build with an override, e.g.
  `JAVA_HOME="/c/Program Files/Java/jdk-25" mvn -pl bipros-reporting -am ...`.

---

## 9. Verification

### Unit test (no DB required)

`DprCostingExcelWriterTest` builds a report mirroring a real sample DPR and round-trips the bytes
through POI, asserting: sheet name `Jun 2026`; `Name` standalone at col 10 with `Manpower` banner at
col 11; Material `Quantity` header at col 21; manpower `Nr 5 × Rate 180 = Cost 900`; `Length 100`,
`Total Qty 2500`, `Progress % 0.72`, `Progress Length 72`; an empty cell is bordered; no spacer row.

```bash
cd backend
JAVA_HOME="/c/Program Files/Java/jdk-25" mvn -q -pl bipros-reporting test -Dtest=DprCostingExcelWriterTest
```

### End-to-end (stack running)

```bash
# token with DPR.READ
GET /v1/reports/dpr/excel?projectId=<uuid>&from=2026-03-19&to=2026-06-29
```

Expect HTTP 200, spreadsheet content-type, one sheet per month. Open the file and confirm: only
approved DPRs, correct column set, costs = Nr×Rate, Total Qty from the BOQ item's executed-to-date.

Frontend: `cd frontend && pnpm dev` → DPR tab → set range → **Generate Report** → file downloads.
