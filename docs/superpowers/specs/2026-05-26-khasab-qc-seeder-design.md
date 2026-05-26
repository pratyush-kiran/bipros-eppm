# Khasab QC Seeder — Design

**Date:** 2026-05-26
**Branch:** `khasab-import-data-quality-fix-2026-05-25` (continuation)
**Related:** `2026-05-26-khasab-master-dedup-design.md` (round-2 dedup); QC backend tables defined in `backend/bipros-project/src/main/java/com/bipros/project/domain/model/{QcTestType,QcSession,QcTestItem}.java`.

## 1. Context

The Quality Control menu (`/qc`) exists in the UI but is empty for the Khasab demo project. Three tabs: **Test Records** (sessions list), **Test Types (Master)**, **Dashboard**. All three are empty. The "New QC Session" dialog expects a Test Type dropdown which is also empty without master data.

Reference SQL the user shared (in `backend/bipros-api/src/main/resources/seed-data/qc-test-types-seed.sql` and `qc-test-records-seed.sql`) targets the legacy flat `qc_test_records` table; the live schema is **session+item** (`qc_sessions` header + `qc_test_items` rows). So the reference cannot be applied as-is.

User direction: don't copy the reference catalogue blindly — analyze the project nature (road + bridge) and select test types that match the WBS.

## 2. Goals

- Populate `activity.qc_test_types` with a curated catalogue (~33 tests) tailored to the Khasab road+bridge WBS.
- Populate `activity.qc_sessions` and `activity.qc_test_items` with realistic sample sessions across QC-relevant activities so the Test Records tab and Dashboard tab show meaningful data.
- All data idempotent — re-runs are no-ops on an already-seeded DB.
- Wire into the existing `deploy.ps1` / `deploy.sh` import pipeline.
- Add one assertion + two info lines to the data-quality summary block.

## 3. Non-Goals

- Changing backend schema or adding DB unique constraints (none exist today on these tables — we'll work around with SELECT-then-INSERT).
- Backfilling QC data for any project other than KHASAB-2026.
- Modeling labs / inspectors as a master entity — the `lab_inspector` field stays a free-text snapshot per item (matches the current schema).
- Cleaning up unrelated seed-data SQL files in `backend/bipros-api/src/main/resources/seed-data/`.
- Sub-types or test "method" classification (single flat list keyed by name).

## 4. Schema Summary

From the live JPA models:

```
activity.qc_test_types
  id (UUID, PK), project_id (UUID, NOT NULL), name (≤100, NOT NULL),
  unit (≤30, nullable), irc_threshold (NUMERIC(19,4), nullable), active (BOOL, NOT NULL),
  + BaseEntity audit cols (version, created_at, updated_at, created_by, updated_by)

activity.qc_sessions
  id (UUID, PK), project_id (UUID, NOT NULL), activity_id (UUID, NOT NULL),
  activity_name (≤150, NOT NULL), test_date (DATE, NOT NULL),
  chainage_from (≤30, nullable), chainage_to (≤30, nullable),
  + BaseEntity audit cols

activity.qc_test_items
  id (UUID, PK), session_id (UUID FK, NOT NULL),
  test_type_id (UUID, NOT NULL — soft FK), test_type_name (≤100, NOT NULL),
  sample_ref_no (≤50, nullable), test_result (NUMERIC(19,4), nullable),
  required_irc (NUMERIC(19,4), nullable),
  outcome (ENUM PASS|FAIL|REPEAT, NOT NULL), lab_inspector (≤150, nullable),
  + BaseEntity audit cols
```

**No unique constraints** on `qc_test_types` (project_id+name) or `qc_sessions` (project_id+activity_id+test_date+chainage_from). Idempotence will come from SELECT-then-INSERT in the seeder, not from `ON CONFLICT`.

`BaseEntity` requires `version`, `created_by`, `updated_by` non-null at insert time — round-1 lesson, applied here too.

`outcome` is a JPA `@Enumerated(EnumType.STRING)` enum (`QcOutcome` — values `PASS`, `FAIL`, `REPEAT`). Insert literal strings.

## 5. Design

### 5.1 New script: `deployment/imports/seed_qc_data.py`

Single file, three numbered stages mirroring sibling scripts (`seed_resource_rates.py`, `seed_productivity_norms.py`, `seed_boq_items.py`, `seed_activity_supervisors.py`, `dedupe_master_data.py`).

**Inputs:** `$BIPROS_WORK_DIR/project-id.txt`, `$BIPROS_WORK_DIR/activity-ids.json` (already produced by `create_activities.py`), plus the activities table itself for `activity.name` (now `[code]`-suffixed by round-2 dedup).

**Stage A: `upsert_test_types(project_id, counters)`**
- For each entry in the in-script `TEST_TYPES` list (33 tuples of `(name, unit, irc_threshold, bucket, sense)`):
  - `SELECT id FROM activity.qc_test_types WHERE project_id=… AND name=… LIMIT 1`
  - If present: UPDATE `unit, irc_threshold, active=true, updated_at, updated_by`, `version=COALESCE(version,0)`, `created_by=COALESCE(created_by,'SYSTEM')`. Counter: `types_updated`.
  - If absent: INSERT with `version=0, created_by/updated_by='SYSTEM', active=true`. Counter: `types_inserted`.

**Stage B: `seed_sessions(project_id, counters)`**

1. Load activities for the project: `SELECT id, code, name, planned_start, planned_finish FROM activity.activities WHERE project_id=…`.
2. Load all test type ids: `SELECT id, name FROM activity.qc_test_types WHERE project_id=… AND active=true` into a `{name: id}` map.
3. Classify each activity by code prefix → one of seven WBS groups:

   | Code prefix | WBS group | Test bucket(s) |
   |---|---|---|
   | `1` | Preliminaries | (none — skip) |
   | `2.3` | Bored Piling | `PILE` + `CONCRETE` + `EARTHWORK` |
   | `2.4`, `2.6`, `2.7`, `2.8` | Pile Cap / Pier / Abutment / Wing Walls | `CONCRETE` + `EARTHWORK` |
   | `3` | Super-structure | `CONCRETE` |
   | `5.1` | Bearings | `BEARING` |
   | `5.2` | Expansion Joints | `CONCRETE` |
   | `5.10` | Anchor Blocks | `CONCRETE` |
   | `9` | Approach Slab | `CONCRETE` + `EARTHWORK` |
   | `13` | Drainage | `CONCRETE` |
   | `18.3` | Bituminous Layers | `BITUMEN` + `PAVEMENT_GEOMETRY` |
   | _other_ | (skip) | — |

4. For each QC-relevant activity, deterministically pick N sessions (N = 2, 3, or 4 based on `hash(activity_id) % 3 + 2`):
   - Distribute test dates evenly across Jan–Mar 2026 (e.g. `2026-01-15`, `2026-02-12`, `2026-03-08` for N=3).
   - Chainage: `f"{45 + (offset * 50):d}+000"` to `f"{45 + (offset * 50) + 50:d}+000"` — varies per session — placeholder values (this is demo data; chainages don't need to be realistic GPS-correct).
   - For each session: check exists by `(project_id, activity_id, test_date, chainage_from)` — skip if present.
   - Otherwise INSERT session, capture id, then INSERT 3–5 test items chosen deterministically from the activity's bucket(s).

5. **Test-item generation**:
   - From the activity's bucket(s), pick `M` test types where `M = hash(activity_id + session_idx) % 3 + 3` (3, 4, or 5 items).
   - For each item:
     - `sample_ref_no = f"{activity.code[-6:].upper().replace('.', '').replace('(', '').replace(')', '')}-{test_type.code}-{seq:03d}"` — short alphanumeric refs (e.g. `26IB-CMP-001`).
     - `outcome`: deterministic from `hash(activity_id + session_idx + item_idx) % 100` →
       - 0–84 → `PASS` (85%)
       - 85–94 → `FAIL` (10%)
       - 95–99 → `REPEAT` (5%)
     - `test_result`: computed from `irc_threshold` + `outcome` + a small deterministic offset:
       - For "higher-is-better" tests (compaction, strength, CBR, etc.): PASS = threshold × 1.02–1.10; FAIL = threshold × 0.85–0.97; REPEAT = threshold × 0.96–1.04.
       - For "lower-is-better" tests (AIV/ACV/FI/EI/LL/PI/water absorption/IRI/penetration thresholds-as-max): inverted multipliers.
       - For tests with NULL threshold (NDT): `test_result = NULL`; outcome still set.
     - `required_irc`: snapshot the test type's `irc_threshold` (may be NULL).
     - `lab_inspector`: per-bucket fixed string (e.g. `"Soil Lab A"`, `"Concrete Lab B"`, `"Bitumen Lab"`, `"Pile Test Lab"`, `"Field Lab"`).
   - INSERT with audit cols + `version=0`.

**Stage C: `heal_audit_nulls()` + `main()` summary**
- Same pattern as siblings — `UPDATE … SET version = COALESCE(version, 0), created_by = COALESCE(created_by, 'SYSTEM'), updated_by = COALESCE(updated_by, 'SYSTEM') WHERE version IS NULL OR …` on all three tables, run at top of `main()`. Insurance against any pre-existing NULL rows.
- Summary line: `[seed_qc_data] OK | types_inserted=N types_updated=N sessions_inserted=N sessions_skipped=N items_inserted=N pass=N fail=N repeat=N`.
- Non-zero exit if any insert failed (`types_failed + sessions_failed + items_failed > 0`).

### 5.2 The 30 test types

Fixed in-script constant. Threshold semantics:
- `MIN` = test result must be **≥ threshold** to pass (compaction, strength, CBR, layer thickness, ductility, penetration_min)
- `MAX` = test result must be **≤ threshold** to pass (AIV, ACV, FI, EI, LL, PI, water absorption, IRI)
- `INFO` = threshold is informational only (sieve gradation curve, NDT yes/no)

(Internal classification used only for result-value generation — not persisted; the DB just stores the numeric `irc_threshold`.)

| # | Bucket | Name | Unit | Threshold | Sense |
|---|---|---|---|---|---|
| 1 | EARTHWORK | Compaction (Core Cutter) | % | 98.00 | MIN |
| 2 | EARTHWORK | Compaction (Sand Replacement) | % | 98.00 | MIN |
| 3 | EARTHWORK | CBR (Soil) | % | 8.00 | MIN |
| 4 | EARTHWORK | CBR (Sub-base) | % | 30.00 | MIN |
| 5 | EARTHWORK | Liquid Limit | % | 50.00 | MAX |
| 6 | EARTHWORK | Plasticity Index | % | 20.00 | MAX |
| 7 | AGGREGATE | Aggregate Impact Value | % | 30.00 | MAX |
| 8 | AGGREGATE | Aggregate Crushing Value | % | 30.00 | MAX |
| 9 | AGGREGATE | Flakiness Index | % | 25.00 | MAX |
| 10 | AGGREGATE | Elongation Index | % | 25.00 | MAX |
| 11 | AGGREGATE | Water Absorption | % | 2.00 | MAX |
| 12 | AGGREGATE | Sieve Analysis (Gradation) | % | 100.00 | INFO |
| 13 | CONCRETE | Compressive Strength (Concrete) | MPa | 30.00 | MIN |
| 14 | CONCRETE | Split Tensile Strength | MPa | 2.50 | MIN |
| 15 | CONCRETE | Flexural Strength | MPa | 4.50 | MIN |
| 16 | CONCRETE | Rebound Hammer (Concrete) | MPa | 25.00 | MIN |
| 17 | CONCRETE | Cover Meter (Rebar) | mm | 40.00 | MIN |
| 18 | CONCRETE | Slump | mm | 75.00 | INFO |
| 19 | BITUMEN | Bitumen Content (Extraction) | % | 5.00 | MIN |
| 20 | BITUMEN | Marshall Stability | kN | 8.00 | MIN |
| 21 | BITUMEN | Marshall Flow | mm | 2.00 | MIN |
| 22 | BITUMEN | Softening Point | °C | 45.00 | MIN |
| 23 | BITUMEN | Penetration | 0.1mm | 60.00 | MIN |
| 24 | BITUMEN | Ductility | cm | 75.00 | MIN |
| 25 | PILE | Pile Load Test | kN | 1500.00 | MIN |
| 26 | PILE | Non-destructive Integrity Test | (null) | (null) | INFO |
| 27 | BEARING | Vertical Load Capacity (Bearings) | kN | 1400.00 | MIN |
| 28 | PAVEMENT_GEOMETRY | Layer Thickness (DBM) | mm | 50.00 | MIN |
| 29 | PAVEMENT_GEOMETRY | Layer Thickness (BC) | mm | 40.00 | MIN |
| 30 | PAVEMENT_GEOMETRY | Layer Thickness (WMM) | mm | 225.00 | MIN |
| 31 | PAVEMENT_GEOMETRY | Layer Thickness (GSB) | mm | 200.00 | MIN |
| 32 | PAVEMENT_GEOMETRY | Roughness (IRI) | m/km | 2.00 | MAX |
| 33 | PAVEMENT_GEOMETRY | Skid Resistance | BPN | 55.00 | MIN |

Total: 33 test types. (Internal `code` field used only for sample_ref_no generation: e.g. `CMP_CC`, `AIV`, `FI`, `BIT_X`, `MARSH_S`, `THK_DBM`, `IRI`.)

### 5.3 Bucket-to-activity mapping (rule-based)

In-script function `bucket_for_code(code: str) -> list[str]`:

```python
def bucket_for_code(code: str) -> list[str]:
    c = code.strip()
    if c.startswith('2.3'): return ['PILE', 'CONCRETE', 'EARTHWORK']
    if any(c.startswith(p) for p in ('2.4', '2.6', '2.7', '2.8')):
                            return ['CONCRETE', 'EARTHWORK']
    if c.startswith('3'):   return ['CONCRETE']
    if c.startswith('5.1'): return ['BEARING']
    if c.startswith('5.2'): return ['CONCRETE']
    if c.startswith('5.10'):return ['CONCRETE']
    if c.startswith('9'):   return ['CONCRETE', 'EARTHWORK']
    if c.startswith('13'):  return ['CONCRETE']
    if c.startswith('18.3'):return ['BITUMEN', 'PAVEMENT_GEOMETRY']
    if c.startswith('1'):   return []     # Preliminaries — skip
    if c.startswith('2.1'): return []     # Setting out — skip
    return []                              # default: skip
```

The bucket pool is `{name → BUCKET}` derived from the in-script `TEST_TYPES` list (column 2 of §5.2). Picking M items from a multi-bucket activity rotates through the buckets to give variety.

### 5.4 Deterministic helpers

- `det_int(key: str, mod: int) -> int`: returns `int(hashlib.sha1(key.encode()).hexdigest(), 16) % mod`. Used everywhere a "random-looking but reproducible" choice is needed (session count, item count, item picks, outcome bucket, result offset).
- Pure-Python stdlib only. Same `subprocess.run(["docker", "exec", ...])` pattern as siblings.

### 5.5 Sequencing in `deploy.ps1` and `deploy.sh`

Slot AFTER `seed_boq_items.py` (treat QC as the last master/transactional seed before DPR ingest):

```
…
seed_boq_items.py
seed_qc_data.py                  ← NEW
…
import_khasab_dprs.py
```

### 5.6 Summary assertions (extend both `print_summary` blocks)

Add to the existing UNION ALL query (round-2 fashion):

```sql
UNION ALL SELECT 'qc_test_types',  COUNT(*) FROM activity.qc_test_types  WHERE project_id = '<PID>' AND active = true
UNION ALL SELECT 'qc_sessions',    COUNT(*) FROM activity.qc_sessions    WHERE project_id = '<PID>'
UNION ALL SELECT 'qc_test_items',  COUNT(*) FROM activity.qc_test_items i JOIN activity.qc_sessions s ON s.id = i.session_id WHERE s.project_id = '<PID>'
```

Switch / case branches:
- `qc_test_types` → `$n -gt 0` (expect > 0, target ~33)
- `qc_sessions` → info-only label `QC sessions          : $n`
- `qc_test_items` → info-only label `QC test items        : $n`

### 5.7 Idempotency

| Step | Re-run behaviour |
|---|---|
| Audit-heal | UPDATE … COALESCE(...) — stable. |
| Test types | SELECT-then-UPDATE/INSERT keyed by `(project_id, name)` — re-runs UPDATE existing rows; counts go to `types_updated` instead of `types_inserted`. |
| Sessions | SELECT-then-INSERT keyed by `(project_id, activity_id, test_date, chainage_from)` — second run sees the row and bumps `sessions_skipped`. |
| Items | Items only INSERT when a session is freshly created — never duplicated. |

### 5.8 Verification queries (also covered by §5.6 assertions)

```sql
-- All test types exist
SELECT COUNT(*) FROM activity.qc_test_types WHERE project_id = '<PID>' AND active = true;
-- expect ≥ 33

-- Sessions exist and items hang off them
SELECT s.id, COUNT(i.id) FROM activity.qc_sessions s
  LEFT JOIN activity.qc_test_items i ON i.session_id = s.id
  WHERE s.project_id = '<PID>' GROUP BY s.id HAVING COUNT(i.id) = 0;
-- expect: empty (every session has items)

-- Outcome distribution
SELECT outcome, COUNT(*) FROM activity.qc_test_items i
  JOIN activity.qc_sessions s ON s.id = i.session_id
  WHERE s.project_id = '<PID>' GROUP BY outcome;
-- expect roughly 85/10/5 PASS/FAIL/REPEAT
```

## 6. Risks / Open Questions

- **`activity.activities.planned_start/finish` are nullable** — if Khasab activities lack dates, the session-spread logic falls back to a fixed Jan–Mar 2026 spread (the project default window, hard-coded in script).
- **Inspector strings are demo placeholders** — not connected to any user master. Acceptable for demo; real engagements would extend the schema.
- **Sample chainages** are fabricated. The Khasab project actually starts ~45+000 (matches the placeholder shown in the New QC Session dialog); using `45+000` baseline keeps it plausible.
- **No backend test types existed for KHASAB-2026 before** — so the first run is purely additive, no risk of name collisions with system data. Idempotence guards re-runs.
- **Backend DataSeeder reads `seed-data/qc-test-types-seed.sql`?** Inspected — the file is just a hand-runnable SQL template; not invoked by Spring DataSeeder. Our Python script is the only seeder touching the QC tables in the demo pipeline.

## 7. Files Changed

| File | Type | Notes |
|---|---|---|
| `deployment/imports/seed_qc_data.py` | NEW | Implements §5.1–§5.4 |
| `deployment/deploy.ps1` | MOD | Insert `seed_qc_data.py` after `seed_boq_items.py`; extend assertions block per §5.6 |
| `deployment/deploy.sh` | MOD | Same |
