# Khasab QC Seeder — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Seed Quality Control master data (33 test types) and realistic sample sessions (~60) with test items (~240) for the KHASAB-2026 project so the `/qc` UI shows meaningful data across Test Records, Test Types Master, and Dashboard tabs.

**Architecture:** A single new Python script `deployment/imports/seed_qc_data.py` runs after `seed_boq_items.py` in the deploy pipeline. It populates three tables in `activity` schema: `qc_test_types` (master), `qc_sessions` (header per activity/date/chainage), `qc_test_items` (rows per session). Idempotent via SELECT-then-INSERT (no unique constraints exist). Both `deploy.ps1` and `deploy.sh` get a new invocation line + 3 new summary assertions/info lines.

**Tech Stack:** Python 3 stdlib only (subprocess + psql via docker exec). PostgreSQL 17 (`activity.*` schema). PowerShell 5.1 + Bash 4 for deploy scripts.

**Spec:** [`docs/superpowers/specs/2026-05-26-khasab-qc-seeder-design.md`](../specs/2026-05-26-khasab-qc-seeder-design.md)

---

## File Structure

| File | Action | Purpose |
|---|---|---|
| `deployment/imports/seed_qc_data.py` | **CREATE** | All three QC tables seeded in one script (test types master, sessions, items). |
| `deployment/deploy.ps1` | MODIFY | Invoke `seed_qc_data.py` after `seed_boq_items.py`; add 3 new lines to the data-quality assertion block. |
| `deployment/deploy.sh` | MODIFY | Linux/macOS parity. |

---

## Task 1: Create `seed_qc_data.py` — script skeleton + test-types master

**Files:**
- Create: `deployment/imports/seed_qc_data.py`

- [ ] **Step 1: Write the file with TEST_TYPES catalogue + Stage A (master upsert)**

```python
import os
#!/usr/bin/env python3
"""Seed Quality Control data for the KHASAB-2026 demo project.

Three stages:
  A. activity.qc_test_types  — 33 master rows tailored to the Khasab WBS
     (road + bridge): EARTHWORK / AGGREGATE / CONCRETE / BITUMEN /
     PILE+BEARING / PAVEMENT_GEOMETRY buckets.
  B. activity.qc_sessions     — 2-4 sessions per QC-relevant activity,
     spaced across Jan-Mar 2026, with realistic chainages.
  C. activity.qc_test_items   — 3-5 test rows per session, deterministic
     pass/fail/repeat distribution ~85/10/5, result values offset from
     irc_threshold per outcome.

Idempotent: SELECT-then-INSERT keyed by:
  qc_test_types:  (project_id, name)
  qc_sessions:    (project_id, activity_id, test_date, chainage_from)
Items only INSERT when a session is freshly created — never duplicated.

Uses no randomness: every "random-looking" choice is derived from
hashlib.sha1 over a key string, so re-runs produce identical data.
"""
import hashlib
import json
import os
import subprocess
import sys
from collections import defaultdict
from datetime import date, timedelta

WORK = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")

PG_CMD = ["docker", "exec", "-i",
          "-e", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}",
          os.environ.get("BIPROS_PG_CONTAINER", "bipros-postgres"),
          "psql",
          "-U", os.environ.get("BIPROS_PG_USER", "bipros"),
          "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
          "-A", "-F", "|", "-t", "-c"]


def sql(q):
    out = subprocess.run(PG_CMD + [q], capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        print(f"  SQL ERROR: {out.stderr.strip()[:200]}", file=sys.stderr)
        return None
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]


def sql_escape(s):
    return str(s).replace("'", "''")


def det_int(key, mod):
    """Deterministic 'random' int in [0, mod) from a key string."""
    return int(hashlib.sha1(key.encode("utf-8")).hexdigest(), 16) % mod


# ─────────────────────────── Test-type catalogue ────────────────────────────
# Tuples: (name, unit, irc_threshold, bucket, sense)
#   sense: "MIN" (result >= threshold to pass), "MAX" (result <= threshold to
#          pass), "INFO" (threshold informational; outcome is judgment).
TEST_TYPES = [
    # EARTHWORK
    ("Compaction (Core Cutter)",         "%",     98.00, "EARTHWORK", "MIN"),
    ("Compaction (Sand Replacement)",    "%",     98.00, "EARTHWORK", "MIN"),
    ("CBR (Soil)",                       "%",      8.00, "EARTHWORK", "MIN"),
    ("CBR (Sub-base)",                   "%",     30.00, "EARTHWORK", "MIN"),
    ("Liquid Limit",                     "%",     50.00, "EARTHWORK", "MAX"),
    ("Plasticity Index",                 "%",     20.00, "EARTHWORK", "MAX"),
    # AGGREGATE
    ("Aggregate Impact Value",           "%",     30.00, "AGGREGATE", "MAX"),
    ("Aggregate Crushing Value",         "%",     30.00, "AGGREGATE", "MAX"),
    ("Flakiness Index",                  "%",     25.00, "AGGREGATE", "MAX"),
    ("Elongation Index",                 "%",     25.00, "AGGREGATE", "MAX"),
    ("Water Absorption",                 "%",      2.00, "AGGREGATE", "MAX"),
    ("Sieve Analysis (Gradation)",       "%",    100.00, "AGGREGATE", "INFO"),
    # CONCRETE
    ("Compressive Strength (Concrete)",  "MPa",   30.00, "CONCRETE",  "MIN"),
    ("Split Tensile Strength",           "MPa",    2.50, "CONCRETE",  "MIN"),
    ("Flexural Strength",                "MPa",    4.50, "CONCRETE",  "MIN"),
    ("Rebound Hammer (Concrete)",        "MPa",   25.00, "CONCRETE",  "MIN"),
    ("Cover Meter (Rebar)",              "mm",    40.00, "CONCRETE",  "MIN"),
    ("Slump",                            "mm",    75.00, "CONCRETE",  "INFO"),
    # BITUMEN
    ("Bitumen Content (Extraction)",     "%",      5.00, "BITUMEN",   "MIN"),
    ("Marshall Stability",               "kN",     8.00, "BITUMEN",   "MIN"),
    ("Marshall Flow",                    "mm",     2.00, "BITUMEN",   "MIN"),
    ("Softening Point",                  "°C",    45.00, "BITUMEN",   "MIN"),
    ("Penetration",                      "0.1mm", 60.00, "BITUMEN",   "MIN"),
    ("Ductility",                        "cm",    75.00, "BITUMEN",   "MIN"),
    # PILE
    ("Pile Load Test",                   "kN",  1500.00, "PILE",      "MIN"),
    ("Non-destructive Integrity Test",   None,    None,  "PILE",      "INFO"),
    # BEARING
    ("Vertical Load Capacity (Bearings)","kN",  1400.00, "BEARING",   "MIN"),
    # PAVEMENT_GEOMETRY
    ("Layer Thickness (DBM)",            "mm",    50.00, "PAVEMENT_GEOMETRY", "MIN"),
    ("Layer Thickness (BC)",             "mm",    40.00, "PAVEMENT_GEOMETRY", "MIN"),
    ("Layer Thickness (WMM)",            "mm",   225.00, "PAVEMENT_GEOMETRY", "MIN"),
    ("Layer Thickness (GSB)",            "mm",   200.00, "PAVEMENT_GEOMETRY", "MIN"),
    ("Roughness (IRI)",                  "m/km",   2.00, "PAVEMENT_GEOMETRY", "MAX"),
    ("Skid Resistance",                  "BPN",   55.00, "PAVEMENT_GEOMETRY", "MIN"),
]


# Lab inspector strings by bucket (deterministic, for snapshot).
LAB_BY_BUCKET = {
    "EARTHWORK":         "Field Lab",
    "AGGREGATE":         "Aggregate Lab",
    "CONCRETE":          "Concrete Lab B",
    "BITUMEN":           "Bitumen Lab",
    "PILE":              "Pile Test Lab",
    "BEARING":           "Bearing Test Lab",
    "PAVEMENT_GEOMETRY": "Pavement Survey Team",
}


# ─────────────────────────── Loaders ────────────────────────────────────────

def load_project_id():
    path = os.path.join(WORK, "project-id.txt")
    with open(path, encoding="utf-8") as f:
        pid = f.read().strip()
    if not pid:
        print("[seed_qc_data] ERROR: project-id.txt is empty")
        sys.exit(1)
    return pid


# ─────────────────────────── Audit-column heal ──────────────────────────────

def heal_audit_nulls():
    """Backfill NULL version/created_by/updated_by on any QC rows inserted by
    earlier (pre-fix) attempts."""
    for tbl in ("activity.qc_test_types", "activity.qc_sessions", "activity.qc_test_items"):
        sql(f"UPDATE {tbl} SET "
            f"version = COALESCE(version, 0), "
            f"created_by = COALESCE(created_by, 'SYSTEM'), "
            f"updated_by = COALESCE(updated_by, 'SYSTEM') "
            f"WHERE version IS NULL OR created_by IS NULL OR updated_by IS NULL")


# ─────────────────────────── Stage A: master upsert ─────────────────────────

def upsert_test_types(project_id, counters):
    """One row per TEST_TYPES entry, keyed by (project_id, name)."""
    for name, unit, threshold, _bucket, _sense in TEST_TYPES:
        rows = sql(f"SELECT id::text FROM activity.qc_test_types "
                   f"WHERE project_id = '{project_id}' AND name = '{sql_escape(name)}' LIMIT 1")
        if rows is None:
            counters["types_failed"] += 1
            continue
        unit_lit = "NULL" if unit is None else f"'{sql_escape(unit)}'"
        thr_lit  = "NULL" if threshold is None else f"{threshold}"
        if rows:
            tid = rows[0][0]
            res = sql(f"UPDATE activity.qc_test_types SET "
                      f"unit = {unit_lit}, irc_threshold = {thr_lit}, active = true, "
                      f"updated_at = now(), updated_by = 'SYSTEM', "
                      f"version = COALESCE(version, 0), "
                      f"created_by = COALESCE(created_by, 'SYSTEM') "
                      f"WHERE id = '{tid}'")
            if res is None:
                counters["types_failed"] += 1
            else:
                counters["types_updated"] += 1
        else:
            res = sql(f"INSERT INTO activity.qc_test_types "
                      f"(id, version, created_at, updated_at, created_by, updated_by, "
                      f" project_id, name, unit, irc_threshold, active) "
                      f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
                      f" '{project_id}', '{sql_escape(name)}', {unit_lit}, {thr_lit}, true)")
            if res is None:
                counters["types_failed"] += 1
            else:
                counters["types_inserted"] += 1


# ─────────────────────────── Main ───────────────────────────────────────────

def main():
    counters = defaultdict(int)
    heal_audit_nulls()
    project_id = load_project_id()
    print(f"[seed_qc_data] project={project_id} test_types={len(TEST_TYPES)}")
    upsert_test_types(project_id, counters)
    failed = counters["types_failed"]
    status = "OK" if failed == 0 else "WITH_FAILURES"
    print(f"[seed_qc_data] {status} | "
          f"types_inserted={counters['types_inserted']} "
          f"types_updated={counters['types_updated']} "
          f"types_failed={counters['types_failed']}")
    if failed > 0:
        sys.exit(2)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Verify parse**

Run from `C:\project\bipros-eppm`:
```
python -c "import ast; ast.parse(open('deployment/imports/seed_qc_data.py').read())"
```

Expected: no output (clean parse). If a `SyntaxError` is raised, fix it before continuing.

- [ ] **Step 3: Do NOT run the script, do NOT commit**

Per the standing rule: leave the file uncommitted. The user inspects with `git status` later.

---

## Task 2: Add session generation (Stage B)

**Files:**
- Modify: `deployment/imports/seed_qc_data.py`

- [ ] **Step 1: Add `bucket_for_code()`, `pick_buckets()`, and `seed_sessions()` between `upsert_test_types()` and `main()`**

Append these three new top-level functions (between the existing `upsert_test_types()` definition and `main()`):

```python
# ─────────────────────────── Stage B: sessions ──────────────────────────────

def bucket_for_code(code):
    """Map a Khasab activity code prefix to a list of test buckets.
    Empty list = activity is not QC-relevant (skip)."""
    c = code.strip()
    if c.startswith('2.3'):  return ['PILE', 'CONCRETE', 'EARTHWORK']
    if any(c.startswith(p) for p in ('2.4', '2.6', '2.7', '2.8')):
                              return ['CONCRETE', 'EARTHWORK']
    if c.startswith('3'):    return ['CONCRETE']
    if c.startswith('5.1'):  return ['BEARING']
    if c.startswith('5.2'):  return ['CONCRETE']
    if c.startswith('5.10'): return ['CONCRETE']
    if c.startswith('9'):    return ['CONCRETE', 'EARTHWORK']
    if c.startswith('13'):   return ['CONCRETE']
    if c.startswith('18.3'): return ['BITUMEN', 'PAVEMENT_GEOMETRY']
    return []


# {bucket: [(name, unit, threshold, sense), ...]}
TYPES_BY_BUCKET = defaultdict(list)
for _name, _unit, _thr, _bucket, _sense in TEST_TYPES:
    TYPES_BY_BUCKET[_bucket].append((_name, _unit, _thr, _sense))


def pick_buckets_for_activity(activity_id, buckets):
    """Return rotation: first session uses bucket[0], next bucket[1], etc.,
    wrapping around. Returns a list of bucket names with len = session_count."""
    return buckets  # caller handles modulo


def compute_result(threshold, sense, outcome, item_key):
    """Deterministic numeric result derived from threshold + outcome + a small
    offset hashed from item_key. Returns None when threshold is NULL (e.g.
    NDT)."""
    if threshold is None:
        return None
    # Offset selector in [0, 100) → mapped to a percentage offset.
    offset_pct = det_int(item_key + "/r", 100) / 100.0  # 0.00 .. 0.99
    if sense == "INFO":
        # Drift around the threshold by ±5%.
        mult = 0.95 + offset_pct * 0.10                  # 0.95 .. 1.05
        return round(threshold * mult, 2)
    # MIN: higher is better
    if sense == "MIN":
        if outcome == "PASS":
            mult = 1.02 + offset_pct * 0.08              # 1.02 .. 1.10
        elif outcome == "FAIL":
            mult = 0.85 + offset_pct * 0.12              # 0.85 .. 0.97
        else:  # REPEAT — near threshold
            mult = 0.96 + offset_pct * 0.08              # 0.96 .. 1.04
        return round(threshold * mult, 2)
    # MAX: lower is better (inverted)
    if outcome == "PASS":
        mult = 0.90 + offset_pct * 0.08                  # 0.90 .. 0.98
    elif outcome == "FAIL":
        mult = 1.03 + offset_pct * 0.12                  # 1.03 .. 1.15
    else:  # REPEAT
        mult = 0.96 + offset_pct * 0.08                  # 0.96 .. 1.04
    return round(threshold * mult, 2)


def compute_outcome(item_key):
    """Deterministic outcome: 85% PASS, 10% FAIL, 5% REPEAT."""
    bucket = det_int(item_key + "/o", 100)
    if bucket < 85:  return "PASS"
    if bucket < 95:  return "FAIL"
    return "REPEAT"


def short_code(activity_code):
    """Shrink an activity code to an alnum-only suffix for sample_ref_no use."""
    keep = "".join(ch for ch in activity_code if ch.isalnum())
    return keep[-6:].upper() or "ACT"


# Internal short codes for sample_ref_no per test name.
TEST_REF_CODE = {
    "Compaction (Core Cutter)":           "CCT",
    "Compaction (Sand Replacement)":      "CSR",
    "CBR (Soil)":                         "CBRS",
    "CBR (Sub-base)":                     "CBRB",
    "Liquid Limit":                       "LL",
    "Plasticity Index":                   "PI",
    "Aggregate Impact Value":             "AIV",
    "Aggregate Crushing Value":           "ACV",
    "Flakiness Index":                    "FI",
    "Elongation Index":                   "EI",
    "Water Absorption":                   "WA",
    "Sieve Analysis (Gradation)":         "SIEVE",
    "Compressive Strength (Concrete)":    "CS",
    "Split Tensile Strength":             "STS",
    "Flexural Strength":                  "FLEX",
    "Rebound Hammer (Concrete)":          "RH",
    "Cover Meter (Rebar)":                "CM",
    "Slump":                              "SLMP",
    "Bitumen Content (Extraction)":       "BIT",
    "Marshall Stability":                 "MS",
    "Marshall Flow":                      "MF",
    "Softening Point":                    "SP",
    "Penetration":                        "PEN",
    "Ductility":                          "DUC",
    "Pile Load Test":                     "PLT",
    "Non-destructive Integrity Test":     "NDT",
    "Vertical Load Capacity (Bearings)":  "VLC",
    "Layer Thickness (DBM)":              "TKDBM",
    "Layer Thickness (BC)":               "TKBC",
    "Layer Thickness (WMM)":              "TKWMM",
    "Layer Thickness (GSB)":              "TKGSB",
    "Roughness (IRI)":                    "IRI",
    "Skid Resistance":                    "SKID",
}


PROJECT_WINDOW_START = date(2026, 1, 15)
PROJECT_WINDOW_END   = date(2026, 3, 31)
PROJECT_WINDOW_DAYS  = (PROJECT_WINDOW_END - PROJECT_WINDOW_START).days


def spread_dates(activity_id, n):
    """Return n dates evenly spread across Jan-Mar 2026, anchored
    deterministically per activity."""
    base_offset = det_int(activity_id + "/dbase", PROJECT_WINDOW_DAYS // max(n, 1))
    step = PROJECT_WINDOW_DAYS // (n + 1)
    return [PROJECT_WINDOW_START + timedelta(days=base_offset + step * (i + 1))
            for i in range(n)]


def seed_sessions(project_id, counters):
    """Stage B — create 2-4 QC sessions per QC-relevant activity, each with
    3-5 items, deterministic outcomes."""

    # 1. Activities for the project.
    acts = sql(f"SELECT id::text, code, name FROM activity.activities "
               f"WHERE project_id = '{project_id}' ORDER BY code") or []

    # 2. Test type id lookup.
    type_rows = sql(f"SELECT id::text, name FROM activity.qc_test_types "
                    f"WHERE project_id = '{project_id}' AND active = true") or []
    type_id_by_name = {r[1]: r[0] for r in type_rows}

    for aid, code, name in acts:
        buckets = bucket_for_code(code)
        if not buckets:
            counters["acts_skipped_no_bucket"] += 1
            continue

        # Pool of (name, unit, threshold, sense) from this activity's buckets.
        pool = []
        for b in buckets:
            pool.extend(TYPES_BY_BUCKET[b])
        if not pool:
            counters["acts_skipped_empty_pool"] += 1
            continue

        # 2..4 sessions per activity.
        session_count = det_int(aid + "/sc", 3) + 2  # 2, 3, or 4
        dates = spread_dates(aid, session_count)

        for s_idx, sess_date in enumerate(dates):
            # Chainage anchored at 45+000 (matches the UI placeholder).
            ch_from_km = 45 + s_idx * 0.5 + (det_int(aid + f"/c{s_idx}", 100) / 1000.0)
            ch_to_km   = ch_from_km + 0.5
            ch_from = f"{int(ch_from_km)}+{int((ch_from_km % 1) * 1000):03d}"
            ch_to   = f"{int(ch_to_km)}+{int((ch_to_km % 1) * 1000):03d}"

            # Idempotence check.
            existing = sql(f"SELECT id::text FROM activity.qc_sessions "
                           f"WHERE project_id = '{project_id}' "
                           f"AND activity_id = '{aid}' "
                           f"AND test_date = '{sess_date.isoformat()}' "
                           f"AND chainage_from = '{ch_from}' LIMIT 1")
            if existing is None:
                counters["sessions_failed"] += 1
                continue
            if existing:
                counters["sessions_skipped"] += 1
                continue

            ins = sql(f"INSERT INTO activity.qc_sessions "
                      f"(id, version, created_at, updated_at, created_by, updated_by, "
                      f" project_id, activity_id, activity_name, test_date, "
                      f" chainage_from, chainage_to) "
                      f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
                      f" '{project_id}', '{aid}', '{sql_escape(name)}', "
                      f" '{sess_date.isoformat()}', '{ch_from}', '{ch_to}') "
                      f"RETURNING id::text")
            if not ins:
                counters["sessions_failed"] += 1
                continue
            session_id = ins[0][0]
            counters["sessions_inserted"] += 1

            # 3..5 items per session.
            item_count = det_int(aid + f"/{s_idx}/ic", 3) + 3  # 3, 4, or 5
            # Rotate through pool, starting at a deterministic offset.
            start = det_int(aid + f"/{s_idx}/start", len(pool))
            for i_idx in range(item_count):
                tt = pool[(start + i_idx) % len(pool)]
                tt_name, _tt_unit, tt_threshold, tt_sense = tt
                tt_id = type_id_by_name.get(tt_name)
                if not tt_id:
                    counters["items_no_type"] += 1
                    continue
                item_key = f"{aid}/{s_idx}/{i_idx}"
                outcome = compute_outcome(item_key)
                result  = compute_result(tt_threshold, tt_sense, outcome, item_key)
                ref     = f"{short_code(code)}-{TEST_REF_CODE.get(tt_name, 'X')}-{i_idx + 1:03d}"
                # Find the bucket for this test (for lab snapshot).
                bucket = next((b for b, lst in TYPES_BY_BUCKET.items()
                               if any(t[0] == tt_name for t in lst)), "CONCRETE")
                lab = LAB_BY_BUCKET.get(bucket, "Field Lab")
                result_lit = "NULL" if result is None else f"{result}"
                req_lit    = "NULL" if tt_threshold is None else f"{tt_threshold}"
                item_ins = sql(f"INSERT INTO activity.qc_test_items "
                               f"(id, version, created_at, updated_at, created_by, updated_by, "
                               f" session_id, test_type_id, test_type_name, sample_ref_no, "
                               f" test_result, required_irc, outcome, lab_inspector) "
                               f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
                               f" '{session_id}', '{tt_id}', '{sql_escape(tt_name)}', "
                               f" '{sql_escape(ref)}', {result_lit}, {req_lit}, "
                               f" '{outcome}', '{sql_escape(lab)}')")
                if item_ins is None:
                    counters["items_failed"] += 1
                else:
                    counters["items_inserted"] += 1
                    counters[f"outcome_{outcome.lower()}"] += 1
```

- [ ] **Step 2: Wire `seed_sessions()` into `main()` and extend the summary print**

Update `main()` (replace the existing function body):

```python
def main():
    counters = defaultdict(int)
    heal_audit_nulls()
    project_id = load_project_id()
    print(f"[seed_qc_data] project={project_id} test_types={len(TEST_TYPES)}")
    upsert_test_types(project_id, counters)
    seed_sessions(project_id, counters)
    failed = counters["types_failed"] + counters["sessions_failed"] + counters["items_failed"]
    status = "OK" if failed == 0 else "WITH_FAILURES"
    print(f"[seed_qc_data] {status} | "
          f"types_inserted={counters['types_inserted']} "
          f"types_updated={counters['types_updated']} "
          f"sessions_inserted={counters['sessions_inserted']} "
          f"sessions_skipped={counters['sessions_skipped']} "
          f"items_inserted={counters['items_inserted']} "
          f"pass={counters['outcome_pass']} "
          f"fail={counters['outcome_fail']} "
          f"repeat={counters['outcome_repeat']} "
          f"acts_skipped_no_bucket={counters['acts_skipped_no_bucket']} "
          f"types_failed={counters['types_failed']} "
          f"sessions_failed={counters['sessions_failed']} "
          f"items_failed={counters['items_failed']} "
          f"items_no_type={counters['items_no_type']}")
    if failed > 0:
        sys.exit(2)
```

- [ ] **Step 3: Verify parse**

```
python -c "import ast; ast.parse(open('deployment/imports/seed_qc_data.py').read())"
```

Expected: no output.

- [ ] **Step 4: Do NOT run the script, do NOT commit**

---

## Task 3: Wire `seed_qc_data.py` into `deploy.ps1`

**Files:**
- Modify: `deployment/deploy.ps1`

- [ ] **Step 1: Insert the new invocation line after `seed_boq_items.py`**

Find this block (around lines 322-323):
```powershell
  Write-Info '  seed_boq_items.py';          & python "$imp\seed_boq_items.py"          2>&1 | Select-Object -Last 5

  Write-Info '  Re-locking activities for DPR ingest'
```

Insert the QC seeder line between them:
```powershell
  Write-Info '  seed_boq_items.py';          & python "$imp\seed_boq_items.py"          2>&1 | Select-Object -Last 5
  Write-Info '  seed_qc_data.py';            & python "$imp\seed_qc_data.py"            2>&1 | Select-Object -Last 5

  Write-Info '  Re-locking activities for DPR ingest'
```

- [ ] **Step 2: Verify the file still looks coherent**

Run from `C:\project\bipros-eppm`:
```
grep -n "seed_boq_items\|seed_qc_data" deployment/deploy.ps1
```

Expected: 3 lines — the boq_items invocation, the new qc_data invocation, and the boq_items mention in the post-DPR block (if it exists, ignore).

- [ ] **Step 3: Do NOT commit**

---

## Task 4: Wire `seed_qc_data.py` into `deploy.sh`

**Files:**
- Modify: `deployment/deploy.sh`

- [ ] **Step 1: Insert the new invocation line after `seed_boq_items.py`**

Find this block (around lines 463-466 — uses 2-space indent):
```bash
  log_info "  seed_boq_items.py (one BOQ per activity, name = activity name)"
  python3 "$imp/seed_boq_items.py" 2>&1 | tail -5 | tee -a "$DEPLOY_LOG" >&2 || true

  # fix_role_assignments unlocks activities; re-lock so DPRs can post
```

Insert the QC seeder between the boq_items invocation and the re-lock comment:
```bash
  log_info "  seed_boq_items.py (one BOQ per activity, name = activity name)"
  python3 "$imp/seed_boq_items.py" 2>&1 | tail -5 | tee -a "$DEPLOY_LOG" >&2 || true
  log_info "  seed_qc_data.py (QC test-types master + ~60 sample sessions with items)"
  python3 "$imp/seed_qc_data.py" 2>&1 | tail -5 | tee -a "$DEPLOY_LOG" >&2 || true

  # fix_role_assignments unlocks activities; re-lock so DPRs can post
```

- [ ] **Step 2: Syntax check**

```
bash -n C:/project/bipros-eppm/deployment/deploy.sh
```

Expected: exit 0, no output.

- [ ] **Step 3: Do NOT commit**

---

## Task 5: Add 3 new QC assertions to `deploy.ps1` PrintSummary

**Files:**
- Modify: `deployment/deploy.ps1`

- [ ] **Step 1: Extend the UNION ALL query in the `$qrows` block**

Find the existing UNION ALL block inside `PrintSummary` (around lines 400-410 — extended in round-2). After the line:
```powershell
UNION ALL SELECT 'acts_no_suffix', COUNT(*) FROM activity.activities WHERE project_id = '$khasabPid' AND name NOT LIKE '% [%]'
```

Insert three new UNION legs (before the closing `"@`):
```powershell
UNION ALL SELECT 'qc_test_types', COUNT(*) FROM activity.qc_test_types WHERE project_id = '$khasabPid' AND active = true
UNION ALL SELECT 'qc_sessions', COUNT(*) FROM activity.qc_sessions WHERE project_id = '$khasabPid'
UNION ALL SELECT 'qc_test_items', COUNT(*) FROM activity.qc_test_items i JOIN activity.qc_sessions s ON s.id = i.session_id WHERE s.project_id = '$khasabPid'
```

- [ ] **Step 2: Extend the switch statement**

Find the existing switch (around lines 421-432 — extended in round-2). Add three new cases before the `default` branch:

```powershell
      'qc_test_types'       { $ok = ($n -gt 0); if (-not $ok) { $bad = $true }; $label = "QC test types (master)  : $n (expect > 0)" }
      'qc_sessions'         { $ok = $true; $label = "QC sessions             : $n" }
      'qc_test_items'       { $ok = $true; $label = "QC test items           : $n" }
```

(The two info-only checks always pass — they just surface the count.)

- [ ] **Step 3: Do NOT commit**

---

## Task 6: Add 3 new QC assertions to `deploy.sh` print_summary

**Files:**
- Modify: `deployment/deploy.sh`

- [ ] **Step 1: Extend the UNION ALL SQL block in `print_summary`**

Find the existing block (around lines 613-624 — extended in round-2). After the line:
```bash
UNION ALL SELECT 'acts_no_suffix', COUNT(*) FROM activity.activities WHERE project_id = '$_PID' AND name NOT LIKE '% [%]'
```

Insert three new UNION legs (before the closing `" 2>/dev/null)`):
```bash
UNION ALL SELECT 'qc_test_types', COUNT(*) FROM activity.qc_test_types WHERE project_id = '$_PID' AND active = true
UNION ALL SELECT 'qc_sessions', COUNT(*) FROM activity.qc_sessions WHERE project_id = '$_PID'
UNION ALL SELECT 'qc_test_items', COUNT(*) FROM activity.qc_test_items i JOIN activity.qc_sessions s ON s.id = i.session_id WHERE s.project_id = '$_PID'
```

- [ ] **Step 2: Extend the case statement**

Find the case block (around lines 594-606). Add three new branches before the `*)` default:
```bash
        qc_test_types)      [ "$n" -gt 0 ] 2>/dev/null || _ok=0; _label="QC test types (master)  : $n (expect > 0)" ;;
        qc_sessions)        _label="QC sessions             : $n" ;;
        qc_test_items)      _label="QC test items           : $n" ;;
```

(The two info-only branches don't bump `_ok`; they always print as `[OK]`.)

- [ ] **Step 3: Syntax check**

```
bash -n C:/project/bipros-eppm/deployment/deploy.sh
```

Expected: exit 0.

- [ ] **Step 4: Do NOT commit**

---

## Task 7: End-to-end re-deploy + UI verification (USER-DRIVEN)

**Files:** none (verification only).

- [ ] **Step 1: Re-run the deploy**

PowerShell:
```powershell
cd C:\project\bipros-eppm\deployment
.\deploy.ps1 -SkipBuild
```

Or Linux/macOS:
```bash
cd deployment && ./deploy.sh --skip-build
```

- [ ] **Step 2: Read the deploy summary**

Expected near the bottom:
```
[OK]   QC test types (master)  : 33 (expect > 0)
[OK]   QC sessions             : ~60
[OK]   QC test items           : ~240
```

- [ ] **Step 3: UI checks**

Open the browser:
- `/qc` → KHASAB-2026 → **Test Types (Master)** tab → should show 33 rows, each with name/unit/threshold.
- `/qc` → KHASAB-2026 → **Test Records** tab → should list ~60 sessions with activity names ending in `[<code>]`, dates Jan–Mar 2026, chainages around 45+000.
- Click into one session → should show 3-5 test items, each with sample ref, result value, IRC threshold, and outcome chip (PASS/FAIL/REPEAT).
- **Dashboard** tab → should show non-empty pass/fail/repeat counts and a chart.

- [ ] **Step 4: No commit** — this task is verification only. If everything passes, the implementation is complete.

---

## Notes for Implementer

- Per user's standing rule (`feedback_no_auto_commit.md`): never `git add` or `git commit`. The user commits manually after testing.
- All UPSERT/INSERT statements MUST include `version = 0` (or `COALESCE(version, 0)` for UPDATEs) and `created_by/updated_by = 'SYSTEM'`. Round-1 lesson — Hibernate `@Version Long` cannot be NULL.
- `subprocess.run(PG_CMD + [q], ...)` is the project's standard psql call pattern — keep using it (do NOT introduce psycopg2).
- The `outcome` column is a PG enum/string (`PASS|FAIL|REPEAT`) — use uppercase literals.
- The script uses `hashlib.sha1` (stdlib) — no external deps.
- File header convention: `import os` on line 1 BEFORE the shebang `#!/usr/bin/env python3`. Intentional per the import directory's existing style. Do NOT "fix" this.

---

## Self-Review

**Spec coverage:**
- §5.1 stage A (test types upsert): Task 1.
- §5.1 stage B (sessions + items): Task 2.
- §5.1 stage C (heal + summary): Task 1 (heal) + Task 2 (summary extended).
- §5.2 (33 test types catalogue): Task 1 `TEST_TYPES` list.
- §5.3 (bucket-to-activity mapping): Task 2 `bucket_for_code()`.
- §5.4 (deterministic helpers `det_int`, `compute_result`, `compute_outcome`, `short_code`, `spread_dates`): Task 1 (`det_int`) + Task 2 (rest).
- §5.5 (pipeline slot after seed_boq_items): Tasks 3 + 4.
- §5.6 (summary assertions + 2 info lines): Tasks 5 + 6.
- §5.7 (idempotency): explicit in Stage A SELECT-then-UPDATE/INSERT, Stage B existence check before insert.
- §5.8 (verification queries): covered by Tasks 5+6 summary + Task 7 UI check.

**Placeholder scan:** no TBDs/TODOs/incomplete code. Every step has either a complete code block or an exact command. Numbers in Task 7 expected output ("~60", "~240") are approximate intentionally — actual counts depend on activity classification.

**Type / signature consistency:**
- `det_int(key: str, mod: int) -> int` — used identically in Task 2 functions and TEST_REF_CODE lookups.
- `compute_outcome(item_key)` returns `"PASS" | "FAIL" | "REPEAT"`, used directly as enum literal in INSERT.
- `bucket_for_code(code: str) -> list[str]` keys (`'PILE'`, `'CONCRETE'`, etc.) match keys in `TYPES_BY_BUCKET` and `LAB_BY_BUCKET`.
- `TEST_REF_CODE` keys exactly mirror `TEST_TYPES` first-column values — verify when implementing.
