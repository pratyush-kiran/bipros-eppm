# Khasab Import — Data Quality Fix

**Date**: 2026-05-25
**Scope**: `deployment/imports/*.py`, `deployment/deploy.ps1`
**Reference module**: `backend/bipros-data-bootstrap` (Java Stages 1–12; read-only reference)
**Source data**: `deployment/data/khasab-excel/` (unchanged)
**Author/owner**: rupakrpk93@gmail.com

---

## 1. Context

`deploy.ps1` invokes the Python pipeline under `deployment/imports/` to load the Khasab-2026 demo project from the Khasab Excel workbooks. The pipeline currently produces low-quality data that the UI and downstream cost/EVM modules can't render meaningfully. A live-DB audit after a recent deploy confirmed the symptoms:

| Symptom | Live DB measurement | What we want |
|---|---|---|
| DPRs with placeholder `qty_executed = 0.01` | 1,754 of 2,434 (72%) | 0 |
| DPRs with no `boq_item_id` | 2,434 of 2,434 (100%) | 0 |
| BOQ items in `project.boq_items` | **0** | one per activity (33) |
| Activities with productivity norms | 0 of 33 | 33 of 33 |
| Resource roles with rate rows | 24 manpower + 22 equipment + 10 material of 219 roles | every role has ≥ 1 rate |
| Postgres deadlocks on `dbs.dbs_manpower_register` during DPR ingest | ~1,000 lost DPRs out of 3,431 expected | 0 |

These problems are structural — they map cleanly to design gaps in the Python pipeline relative to the Java `bipros-data-bootstrap` module, which enforces every one of these contracts via its 12 stages.

### 1.1 Why not use the Java module directly

The Java module ships its own Khasab-derived dataset (`backend/bipros-data-bootstrap/src/main/resources/bootstrap-data.json`) parsed by `scripts/extract.py`. Replacing the Python pipeline with `RunAll.java` would work but the user has chosen to **keep the existing `deployment/data/khasab-excel/` source** and **keep the Python orchestration**. The Java module is therefore used here as a structural reference (rate cards, productivity norm derivation, BOQ shape, resource-variant rules), not as an execution path.

## 2. Goals and non-goals

### Goals

1. Every DPR in `project.daily_progress_reports` carries a realistic `qty_executed` (no 0.01 placeholders).
2. Every DPR links to a `boq_item` whose description matches the parent activity name.
3. Every DPR carries at least one manpower / equipment / material row.
4. Every resource referenced by a DPR resolves to an existing variant (so UI dropdowns render).
5. Every `resource_role` has at least one rate row with a realistic OMR value.
6. Every `work_activity` has a manpower + equipment productivity norm.
7. Every `resource_assignment` has `planned_units > 0` and references an existing role/variant.
8. Units are consistent across `work_activity`, `activity`, BOQ, and DPR rows.
9. The deploy is idempotent — re-running fixes partial state without duplicating.
10. No Postgres deadlocks during DPR ingest.

### Non-goals

- Replacing the Khasab Excel as the source of truth.
- Replacing Python with Java for the deploy path.
- Backend code changes (the `DbsRecomputeListener` race condition is mitigated by serializing the ingest, not by fixing the listener).
- Touching `fix_demo_v2.py`, `populate_dashboard.py`, `add_weather_risks.py`, or any Stage 12 polish script — they will work correctly once upstream data is clean.

## 3. Architecture

```
deployment/imports/
├── parse_khasab.py              (unchanged — Excel → JSON parsers)
├── parse_master_sheet.py        (unchanged)
├── analyze_resource_demand.py   (unchanged)
├── rebuild_demo.py              (unchanged — project + WBS + activities + users)
├── create_khasab_users.py       (already patched earlier in session — idempotent)
├── fix_role_assignments.py      (MODIFY — pre-flight validation + planned_units>0 guard)
├── seed_resource_rates.py       (NEW — every role gets a rate via Stage 2's card)
├── seed_productivity_norms.py   (NEW — every work_activity gets a norm)
├── seed_boq_items.py            (NEW — one BOQ per activity, name = activity name)
├── import_khasab_dprs.py        (MODIFY — single worker, BOQ link, no 0.01, resource guard)
└── ... (rest unchanged)
```

`deploy.ps1`'s `RunImportPreDpr` function gets three new script invocations inserted in the correct dependency order; `RunImportDprs` is otherwise unchanged but benefits from the modified `import_khasab_dprs.py`.

## 4. File specifications

### 4.1 NEW — `seed_resource_rates.py`

**Purpose**: Ensure every `resource_role` has at least one rate row in its corresponding variant table with a realistic OMR value. Ports `Stage2ResourceRoles.java`.

**Inputs**

- Env: `BIPROS_PG_HOST`, `BIPROS_PG_PORT`, `BIPROS_PG_USER`, `BIPROS_PG_PASS`, `BIPROS_PG_DB`, `BIPROS_PG_CONTAINER` (default `bipros-postgres`)
- Files: `$BIPROS_WORK_DIR/khasab-dpr-parsed.json` (already written by `parse_khasab.py`) — used to discover (role, category, grade) / (role, make, model) / (role, spec) tuples actually referenced in DPRs

**Outputs**

- DB writes: `resource.manpower_role_rates`, `resource.equipment_role_variants`, `resource.material_role_variants`
- Possibly creates missing `resource.manpower_category_masters` or `resource.grade_masters` rows (matches Stage 2 behaviour)
- Stdout: one summary line per resource type

**Rate card (ported verbatim from `Stage2ResourceRoles.java`)**

```python
MANPOWER_DAY_RATE_OMR = {     # base rate at category=SKILLED, grade=A
    "HELPER": 8.00, "MASON": 18.00, "CARPENTER": 18.00, "STEEL_FIXER": 18.00,
    "SCAFFOLDER": 16.00, "RIGGER": 16.00, "BANKMAN": 15.00,
    "CHARGEHAND": 22.00, "FOREMAN": 30.00, "SUPERVISOR": 45.00,
}
EQUIPMENT_DAY_RATE_OMR = {
    "AIR_COMPRESSOR": 30.00, "ASPHALT_CUTLER": 40.00, "BACK_HOE": 90.00,
    "BOB_CAT": 65.00, "CONCRETE_MIXER": 50.00, "CRANE": 200.00, "CRUSHER": 250.00,
    "DOZER": 220.00, "DUMPER": 100.00, "EXCAVATOR": 180.00, "GRADER": 180.00,
    "HIAB": 150.00, "MOBILE_CRANE": 220.00, "PLATE_COMPACTOR": 25.00,
    "POWERSCREEN": 280.00, "ROLLER": 120.00, "TIPPER": 95.00, "TOWER_LIGHT": 20.00,
    "WATER_TANKER": 95.00, "WHEEL_LOADER": 140.00, "BABY_ROLLER": 60.00,
    "HAND_DRILLING": 25.00,
}
MATERIAL_RATE_OMR = {         # keyed by ROLE|SPEC
    "CONCRETE|C15": 35.00, "CONCRETE|C25": 50.00,
    "CONCRETE|C30": 55.00, "CONCRETE|C35": 62.00,
}
CATEGORY_MULTIPLIER = {
    "UNSKILLED": 0.60, "MC-UNSKILLED": 0.60,
    "SEMISKILLED": 0.80, "SEMI-SKILLED": 0.80, "MC-SEMISKILLED": 0.80,
    "SKILLED": 1.00, "MC-SKILLED": 1.00,
    "HIGHLYSKILLED": 1.20, "HIGHLY-SKILLED": 1.20, "MC-HIGHLYSKILLED": 1.20,
    "STAFF": 1.50, "MC-STAFF": 1.50,
}
GRADE_MULTIPLIER = {"A": 1.00, "B": 0.85, "C": 0.75}
MANPOWER_DEFAULT_DAY_OMR = 10.00
EQUIPMENT_DEFAULT_DAY_OMR = 50.00
MATERIAL_UNIT = {"CONCRETE": "m3"}
UNIT_DAY = "Day"
```

**Algorithm**

1. Load `resource_roles` joined to `resource_types` (`type ∈ {MANPOWER, EQUIPMENT, MATERIAL}`).
2. Scan `khasab-dpr-parsed.json` once and build sets:
   - `manpower_combos[role_code]` → set of `(category_name, grade_name)` pairs observed
   - `equipment_combos[role_code]` → set of `(make, model)` pairs observed
   - `material_combos[role_code]` → set of `spec_grade` strings observed
3. For each role, if its observed-combos set is empty, fall back to a single default combo: `(SKILLED, A)` / `(GENERIC, STD)` / `STD`.
4. For each combo, UPSERT:
   - **MANPOWER**: `rate = MANPOWER_DAY_RATE_OMR[role_code or default] × CATEGORY_MULTIPLIER[cat] × GRADE_MULTIPLIER[grade]`, rounded to 2 dp. Unit = `"Day"`. Resolve `category_id` by name-then-code (case-insensitive); create if missing. Resolve `grade_id` similarly. Key: `(role_id, category_id, grade_id)`.
   - **EQUIPMENT**: `rate = EQUIPMENT_DAY_RATE_OMR[role_code or default]`. Unit = `"Day"`. Substitute blank/null make→`"GENERIC"`, model→`"STD"`. Key: `(role_id, make, model)`.
   - **MATERIAL**: `rate = MATERIAL_RATE_OMR.get(f"{role_code}|{spec}")` else 0. Unit = `MATERIAL_UNIT.get(role_code)` else `"Unit"`. Substitute blank spec→`"STD"`. Key: `(role_id, spec_grade)`.
5. Log summary: `[seed_resource_rates] roles=N | manpower: inserted=A updated=B | equipment: inserted=C updated=D | material: inserted=E updated=F`.

**Idempotency**: UPSERT keyed on each table's natural unique constraint. Re-running updates only when the computed rate or unit has changed.

**Failure modes**

- Resource role rows missing → log warning, skip (don't auto-create roles; that's `rebuild_demo.py`'s job).
- DB write conflict (concurrent run) → retry once, then fail.

---

### 4.2 NEW — `seed_productivity_norms.py`

**Purpose**: Every `work_activity` gets exactly one MANPOWER and one EQUIPMENT productivity norm row in `resource.productivity_norms`, so DPR ingest can compute a realistic `qty_executed` value when the Excel row is blank. Ports `Stage4ProductivityNorms.java`.

**Inputs**

- Env: standard `BIPROS_PG_*`
- Files: `$BIPROS_WORK_DIR/activity-master-normalized.json` (already produced by `parse_master_sheet.py`) — used when per-activity productivity values are present

**Outputs**

- DB writes to `resource.productivity_norms`
- Stdout: summary

**Norm derivation per work_activity**

1. If `activity-master-normalized.json` has values for that work_activity code, use them: `outputPerManPerDay = json.outputPerManPerDay`, `outputPerHour = json.outputPerHour`.
2. Else use unit-based defaults (table baked into the script):

   ```python
   DEFAULT_NORMS_BY_UNIT = {     # unit → (outputPerManPerDay, outputPerHour)
       "m3":  (1.5,  2.0),
       "m2":  (8.0, 10.0),
       "m":   (5.0,  6.0),
       "Nos": (2.0,  3.0),
       "ton": (0.8,  1.0),
   }
   ```
   Unmatched unit → `(2.0, 3.0)` as a generic fallback.
3. `outputPerDay` (equipment only) = `outputPerHour × 8`; `workingHoursPerDay = 8.0` always.
4. UPSERT keyed by `(work_activity_id, norm_type=MANPOWER, all scope columns NULL)` and `(work_activity_id, norm_type=EQUIPMENT, all scope columns NULL)`. Matches the unique constraint Java Stage 4 uses (`findFirstByWorkActivityIdAndRoleIdIsNull...`).

**Idempotency**: same as 4.1 — UPSERT keyed by composite unique index. Re-runs only update if values changed.

**Failure modes**

- `work_activity` referenced in master JSON but missing from DB → log warning, skip (means Stage 3 equivalent didn't run; not our problem to fix).

---

### 4.3 NEW — `seed_boq_items.py`

**Purpose**: Synthesize one BOQ item per activity, with `description = activity.name`, so DPR ingest can link each row to a BOQ. Ports `Stage11BoqItems.java`.

**Inputs**

- Env: `BIPROS_PG_*`, `BIPROS_TOKEN_FILE`, `BIPROS_API_BASE`, `BIPROS_WORK_DIR`
- DB: `project.projects`, `activity.activities` joined to `project.wbs_nodes` and `resource.work_activities` and `resource.productivity_norms` (for rate computation)

**Outputs**

- DB writes to `project.boq_items`
- File: `$BIPROS_WORK_DIR/boq-by-activity.json` — map of `{activity_id_string: {boq_item_id, item_no}}`, consumed by `import_khasab_dprs.py`
- Stdout: summary

**Field derivation per activity**

| Field | Derivation |
|---|---|
| `item_no` | `"BOQ-" + activity.code` (unique per project) |
| `description` | `activity.name` |
| `unit` | `work_activity.default_unit` if work_activity_id set, else `activity.unit`, else `"Nos"` |
| `chapter` | `wbs_node.code` of activity's WBS chapter |
| `wbs_node_id` | `activity.wbs_node_id` |
| `boq_qty` | `activity.planned_quantity` if > 0; else sum of `qty_executed` for activity from parsed Khasab JSON; else `100` |
| `boq_rate` | computed (see below); fallback `100.00` OMR if norms missing |
| `budgeted_rate` | `boq_rate × 1.05` (5% contingency) |
| `boq_amount` | `boq_qty × boq_rate` |
| `budgeted_amount` | `boq_qty × budgeted_rate` |
| `status` | `ACTIVE` |
| `qty_executed_to_date`, `actual_rate`, `actual_amount` | `0` on insert; do not touch on update (listeners own them) |

**Rate computation** (deterministic, defends against missing norms)

```python
# Typical day rates — averages of the cards in seed_resource_rates.py.
# Picking averages rather than per-activity rates keeps the BOQ rate stable
# (Stage 12 polish will overwrite from real actuals via DprBoqSyncListener anyway).
MP_TYPICAL_RATE_OMR_PER_DAY = 15.00   # ≈ avg of MANPOWER_DAY_RATE_OMR
EQ_TYPICAL_RATE_OMR_PER_DAY = 100.00  # ≈ avg of EQUIPMENT_DAY_RATE_OMR

norm_mp = productivity_norm.get((work_activity_id, "MANPOWER"))
norm_eq = productivity_norm.get((work_activity_id, "EQUIPMENT"))

mp_cost_per_unit = (
    MP_TYPICAL_RATE_OMR_PER_DAY / float(norm_mp["outputPerManPerDay"])
    if norm_mp and norm_mp["outputPerManPerDay"] and float(norm_mp["outputPerManPerDay"]) > 0
    else 0.0
)
eq_cost_per_unit = (
    EQ_TYPICAL_RATE_OMR_PER_DAY / float(norm_eq["outputPerDay"])
    if norm_eq and norm_eq["outputPerDay"] and float(norm_eq["outputPerDay"]) > 0
    else 0.0
)

boq_rate = round((mp_cost_per_unit + eq_cost_per_unit) * 1.10, 2)  # +10% margin
if boq_rate <= 0:
    boq_rate = 100.00   # last-resort fallback so boq_amount is never 0
```

**Idempotency**: UPSERT keyed by `(project_id, item_no)`. Re-runs update mutable fields; preserve execution counters.

**Write path**: direct SQL UPSERT against `project.boq_items` (matches what `Stage11BoqItems.java` does via `boqItemRepository.save()` — no domain logic in the BOQ insert path that warrants going through the REST API). Consistent with the other two seed scripts.

---

### 4.4 MODIFY — `fix_role_assignments.py`

**Why touching**: currently posts assignments without validating `planned_units > 0` and silently moves on if a role isn't found. Per Stage 9 it should reject zero-unit assignments and fail loud on missing roles.

**Changes**

1. **Pre-flight validation pass** (new, before any HTTP POST):
   - Build set of (role_code, category, grade) / (role_code, make, model) / (role_code, spec) tuples from parsed data.
   - Resolve each against DB via SELECT. If any missing, exit 1 with a clear list:
     ```
     [STAGE9] Missing variants — run seed_resource_rates.py first:
       manpower: EXCAVATOR/SKILLED/A, FOREMAN/STAFF/A
       equipment: CRUSHER/(blank)/(blank)
     ```

2. **Planned-units guard**: in the existing assignment-building loop, after computing `planned_units` for each row:
   - If `planned_units <= 0` → log warning and skip (do not POST).

3. **Stricter response tracking**: print a final summary line:
   ```
   [STAGE9] OK | created=N skipped_zero=X http_fail=Y
   ```
   Exit 0 unless the missing-variants preflight failed.

**Idempotency**: unchanged — backend POST is already upsert-by-(activity, role, variant).

---

### 4.5 MODIFY — `import_khasab_dprs.py` (biggest change)

Five distinct changes:

**(a) Single worker.** Remove `multiprocessing.Process` workers (currently 4) and process DPRs serially. This eliminates the `dbs.dbs_manpower_register` deadlocks observed at ~30% loss rate during the last run. Cost: wall time ~3× — acceptable for correctness. Add a `--workers N` CLI flag with default 1, max 1 warned-against, so a future fix can re-enable parallelism without code changes.

**(b) BOQ-item linkage on every DPR.** At start, load `boq-by-activity.json`. For each DPR payload:

```python
boq = boq_by_activity.get(activity_id)
if not boq:
    log_warn(f"DPR skipped — no BOQ for activity {activity_id}")
    skipped_boq_missing += 1
    continue
payload["boqItemId"] = boq["boq_item_id"]
payload["boqItemNo"] = boq["item_no"]
```

A `boq_missing > 0` count at the end indicates `seed_boq_items.py` produced an incomplete map — the deploy log should call this out.

**(c) Replace the 0.01 placeholder.** Current code: when Excel row has `workdone = 0 / null`, set `qty = 0.01` with `[deployment-only filler]` remark. New logic:

```python
def realistic_qty(row, norm):
    """Return qty_executed, or None to signal 'skip this DPR'."""
    raw = row.get("workDoneQty")
    if raw and float(raw) > 0:
        return float(raw)
    hc  = sum(m["nos"] for m in row.get("manpower", []))
    hrs = row.get("workingHours") or 8.0
    if hc > 0 and norm and norm.get("outputPerManPerDay"):
        per_hr = norm["outputPerManPerDay"] / 8.0
        return round(hc * hrs * per_hr, 2)
    if hc == 0 and not row.get("equipment"):
        return None   # truly idle — skip
    return 0.5        # crew/equipment present but no norm → small but realistic
```

Drop the `[deployment-only filler]` remark string.

**(d) Resource non-empty guard.** Before POST:

```python
if not (payload.get("manpower") or payload.get("equipment") or payload.get("materials")):
    log_warn(f"DPR skipped — no resources for activity {ac} date {dt}")
    skipped_no_resource += 1
    continue
```

**(e) Variant existence guard.** Cache `(role_code, make, model) → variant_id` and `(role_code, cat, grade) → rate_id` at script start (one SELECT each). When building a DPR row, if the parsed tuple doesn't resolve, **substitute the GENERIC/STD fallback** (matches Stage 2 behaviour) so the variant always resolves. This is the fix for the "dropdown shows blank" UI symptom.

**Final summary**

```
[STAGE11] DPR ingest | posted=N boq_missing=A idle_skipped=B empty_skipped=C http_fail=D
```

---

### 4.6 MODIFY — `deploy.ps1` (RunImportPreDpr function)

Insert the three new script invocations in `$imp` block:

```powershell
Write-Info '  parse_khasab.py';            & python "$imp\parse_khasab.py"            2>&1 | Select-Object -Last 3
Write-Info '  parse_master_sheet.py';      & python "$imp\parse_master_sheet.py"      2>&1 | Select-Object -Last 3
Write-Info '  analyze_resource_demand.py'; & python "$imp\analyze_resource_demand.py" 2>&1 | Select-Object -Last 3
Write-Info '  rebuild_demo.py';            & python "$imp\rebuild_demo.py"            2>&1 | Select-Object -Last 15
Write-Info '  seed_resource_rates.py (NEW)';      & python "$imp\seed_resource_rates.py"      2>&1 | Select-Object -Last 10
Write-Info '  seed_productivity_norms.py (NEW)';  & python "$imp\seed_productivity_norms.py"  2>&1 | Select-Object -Last 10
Write-Info '  fix_role_assignments.py';    & python "$imp\fix_role_assignments.py"    2>&1 | Select-String -Pattern 'Total|created|skipped|missing'
Write-Info '  seed_boq_items.py (NEW)';           & python "$imp\seed_boq_items.py"           2>&1 | Select-Object -Last 10
# (existing activity re-lock block follows unchanged)
```

Stage 11 (`RunImportDprs`) and Stage 12 (`RunImportPostDpr`) functions are unchanged.

### 4.7 NEW — post-deploy assertion block in `deploy.ps1`

After `RunImportPostDpr` returns, emit a non-fatal warning if data-quality invariants don't hold:

```powershell
$counts = docker exec bipros-postgres psql -U $PgUser -d $PgDb -At -F "|" -c "
  SELECT COUNT(*) FROM project.daily_progress_reports WHERE qty_executed = 0.01
  UNION ALL SELECT COUNT(*) FROM project.daily_progress_reports WHERE boq_item_id IS NULL
  UNION ALL SELECT COUNT(*) FROM project.boq_items
  UNION ALL SELECT COUNT(*) FROM resource.productivity_norms"
# Parse $counts and warn if [0]>0, [1]>0, [2]==0, or [3]==0
```

Doesn't fail the deploy; just makes the success/failure visible in the log.

## 5. Dependency order

```
rebuild_demo.py            → creates project, WBS, activities (with work_activity_id), users
seed_resource_rates.py     → every role gets a rate row (no project dependency)
seed_productivity_norms.py → every work_activity gets a norm (no project dependency)
fix_role_assignments.py    → validates rates + norms exist; posts resource_assignments
re-lock activities (API)   → backend allows DPR submission
seed_boq_items.py          → reads activities + work_activities + norms + rates; writes BOQs + boq-by-activity.json
import_khasab_dprs.py      → reads boq-by-activity.json; posts DPRs with full data
fix_demo_v2.py + dashboard + risks (unchanged Stage 12 polish)
```

Each step's pre-conditions are satisfied exactly by the predecessor. None can run earlier.

## 6. Idempotency contract

Every new and modified script must:

| Property | Mechanism |
|---|---|
| Re-runnable on partial state | UPSERTs keyed on natural unique constraints |
| Detects "you forgot a prerequisite" | First action is SELECT to confirm dependency; exit 1 with clear message if missing |
| Safe to run individually | Each invokable standalone via `python imports/<name>.py` for debugging |
| Doesn't duplicate rows | All key checks before insert |
| Failure-mode visibility | Final summary line with `inserted/updated/skipped/failed` counts |

## 7. Verification

After deploy, the following queries should yield the indicated results:

```sql
-- (a) No placeholder qty
SELECT COUNT(*) FROM project.daily_progress_reports WHERE qty_executed = 0.01;
-- expect: 0

-- (b) Every DPR linked to BOQ
SELECT COUNT(*) FROM project.daily_progress_reports WHERE boq_item_id IS NULL;
-- expect: 0

-- (c) BOQ items exist, one per activity
SELECT COUNT(*) FROM project.boq_items WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026');
-- expect: 33

-- (d) Every work_activity has a manpower + equipment norm
SELECT COUNT(DISTINCT work_activity_id) FROM resource.productivity_norms;
-- expect: 33 (or the count of work_activities)

-- (e) Every role has a rate
SELECT rt.code, COUNT(rr.id) AS roles, COUNT(DISTINCT v.role_id) AS roles_with_rate
FROM resource.resource_types rt
JOIN resource.resource_roles rr ON rr.resource_type_id = rt.id
LEFT JOIN resource.manpower_role_rates v ON v.role_id = rr.id AND rt.code='MANPOWER'
GROUP BY rt.code;
-- expect: roles_with_rate == roles for MANPOWER (similar checks for EQUIPMENT, MATERIAL)

-- (f) No DPR with empty resources (will be enforced at ingest, not in DB; this is a smoke test)
SELECT COUNT(*) FROM project.daily_progress_reports d
WHERE NOT EXISTS (SELECT 1 FROM project.dpr_manpower WHERE dpr_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM project.dpr_equipment WHERE dpr_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM project.dpr_materials WHERE dpr_id = d.id);
-- expect: 0

-- (g) No deadlock errors during DPR import
docker logs bipros-api 2>&1 | grep -c "deadlock detected"
-- expect: 0
```

The new `deploy.ps1` assertion block in 4.7 runs (a), (b), (c), (d) automatically and prints a warning if any fails.

## 8. Out of scope (for follow-up)

- Backend fix for `DbsRecomputeListener`'s read-then-write race. Proper fix: `INSERT … ON CONFLICT DO UPDATE` or a `SELECT FOR UPDATE` lock acquisition order. This spec mitigates symptoms by serializing the ingest.
- Replacing `parse_khasab.py`'s ad-hoc JSON shape with the canonical `ParsedDataset` schema. Useful future refactor; not needed for this fix.
- Deleting the unused `psql-wrapper.cmd` / `.ps1` files (the Python pipeline no longer goes through them after the `docker exec` direct-call patch made earlier this session).

## 9. Estimated effort

| Task | Lines | Risk |
|---|---|---|
| `seed_resource_rates.py` (new) | ~250 | low — table-driven |
| `seed_productivity_norms.py` (new) | ~150 | low |
| `seed_boq_items.py` (new) | ~200 | medium — BOQ rate computation has edge cases |
| `fix_role_assignments.py` (modify) | ~50 lines added | low |
| `import_khasab_dprs.py` (modify) | ~150 lines modified | medium — multiple integration points |
| `deploy.ps1` (modify) | ~20 lines added | low |

Total: ~820 lines of new/modified Python; one PowerShell function change. No backend changes.
