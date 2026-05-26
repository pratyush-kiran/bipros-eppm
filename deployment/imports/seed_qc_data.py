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


# ─────────────────────────── Stage B: sessions ──────────────────────────────

def bucket_for_code(code):
    """Map a Khasab activity code prefix to a list of test buckets.
    Empty list = activity is not QC-relevant (skip)."""
    c = code.strip()
    if c.startswith('2.3'):  return ['PILE', 'CONCRETE', 'EARTHWORK']
    if any(c.startswith(p) for p in ('2.4', '2.6', '2.7', '2.8')):
                              return ['CONCRETE', 'EARTHWORK']
    if c.startswith('3'):    return ['CONCRETE']
    if c.startswith('5.10'): return ['CONCRETE']
    if c.startswith('5.1'):  return ['BEARING']
    if c.startswith('5.2'):  return ['CONCRETE']
    if c.startswith('9'):    return ['CONCRETE', 'EARTHWORK']
    if c.startswith('13'):   return ['CONCRETE']
    if c.startswith('18.3'): return ['BITUMEN', 'PAVEMENT_GEOMETRY']
    return []


# {bucket: [(name, unit, threshold, sense), ...]}
TYPES_BY_BUCKET = defaultdict(list)
for _name, _unit, _thr, _bucket, _sense in TEST_TYPES:
    TYPES_BY_BUCKET[_bucket].append((_name, _unit, _thr, _sense))


def compute_result(threshold, sense, outcome, item_key):
    """Deterministic numeric result derived from threshold + outcome + a small
    offset hashed from item_key. Returns None when threshold is NULL (e.g.
    NDT)."""
    if threshold is None:
        return None
    offset_pct = det_int(item_key + "/r", 100) / 100.0  # 0.00 .. 0.99
    if sense == "INFO":
        mult = 0.95 + offset_pct * 0.10                  # 0.95 .. 1.05
        return round(threshold * mult, 2)
    if sense == "MIN":
        if outcome == "PASS":
            mult = 1.02 + offset_pct * 0.08              # 1.02 .. 1.10
        elif outcome == "FAIL":
            mult = 0.85 + offset_pct * 0.12              # 0.85 .. 0.97
        else:
            mult = 0.96 + offset_pct * 0.08              # 0.96 .. 1.04
        return round(threshold * mult, 2)
    # MAX: lower is better
    if outcome == "PASS":
        mult = 0.90 + offset_pct * 0.08                  # 0.90 .. 0.98
    elif outcome == "FAIL":
        mult = 1.03 + offset_pct * 0.12                  # 1.03 .. 1.15
    else:
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
    deterministically per activity. Dates are clamped to
    PROJECT_WINDOW_END so re-runs never produce dates outside the
    project window."""
    base_offset = det_int(activity_id + "/dbase", max(PROJECT_WINDOW_DAYS // (n + 1), 1))
    step = PROJECT_WINDOW_DAYS // (n + 1)
    out = []
    for i in range(n):
        d = PROJECT_WINDOW_START + timedelta(days=base_offset + step * (i + 1))
        if d > PROJECT_WINDOW_END:
            d = PROJECT_WINDOW_END
        out.append(d)
    return out


def seed_sessions(project_id, counters):
    """Stage B — create 2-4 QC sessions per QC-relevant activity, each with
    3-5 items, deterministic outcomes."""

    acts = sql(f"SELECT id::text, code, name FROM activity.activities "
               f"WHERE project_id = '{project_id}' ORDER BY code") or []

    type_rows = sql(f"SELECT id::text, name FROM activity.qc_test_types "
                    f"WHERE project_id = '{project_id}' AND active = true") or []
    type_id_by_name = {r[1]: r[0] for r in type_rows}

    # Reverse lookup: name -> bucket (for lab snapshot).
    bucket_by_name = {}
    for b, lst in TYPES_BY_BUCKET.items():
        for t in lst:
            bucket_by_name[t[0]] = b

    for aid, code, name in acts:
        buckets = bucket_for_code(code)
        if not buckets:
            counters["acts_skipped_no_bucket"] += 1
            continue

        pool = []
        for b in buckets:
            pool.extend(TYPES_BY_BUCKET[b])
        if not pool:
            counters["acts_skipped_empty_pool"] += 1
            continue

        session_count = det_int(aid + "/sc", 3) + 2  # 2, 3, or 4
        dates = spread_dates(aid, session_count)

        for s_idx, sess_date in enumerate(dates):
            ch_from_km = 45 + s_idx * 0.5 + (det_int(aid + f"/c{s_idx}", 100) / 1000.0)
            ch_to_km   = ch_from_km + 0.5
            ch_from = f"{int(ch_from_km)}+{int((ch_from_km % 1) * 1000):03d}"
            ch_to   = f"{int(ch_to_km)}+{int((ch_to_km % 1) * 1000):03d}"

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

            item_count = det_int(aid + f"/{s_idx}/ic", 3) + 3  # 3, 4, or 5
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
                bucket  = bucket_by_name.get(tt_name, "CONCRETE")
                lab     = LAB_BY_BUCKET.get(bucket, "Field Lab")
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


# ─────────────────────────── Main ───────────────────────────────────────────

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
          f"acts_skipped_empty_pool={counters['acts_skipped_empty_pool']} "
          f"types_failed={counters['types_failed']} "
          f"sessions_failed={counters['sessions_failed']} "
          f"items_failed={counters['items_failed']} "
          f"items_no_type={counters['items_no_type']}")
    if failed > 0:
        sys.exit(2)


if __name__ == "__main__":
    main()
