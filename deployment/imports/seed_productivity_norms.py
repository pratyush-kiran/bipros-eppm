import os
#!/usr/bin/env python3
"""Backfill productivity_norms — one MANPOWER + one EQUIPMENT row per work_activity.

Ports Stage4ProductivityNorms.java. Per-activity values are read from
$BIPROS_WORK_DIR/activity-master-normalized.json when present; otherwise
unit-based defaults are used.

Idempotent: UPSERT keyed by (work_activity_id, norm_type) with all scope columns NULL.
"""
import json
import subprocess
import sys


WORKING_HOURS_PER_DAY = 8.0

# Unit → (output_per_man_per_day, output_per_hour-for-equipment).
DEFAULT_NORMS_BY_UNIT = {
    "m3":  (1.5,  2.0),
    "m2":  (8.0, 10.0),
    "m":   (5.0,  6.0),
    "Nos": (2.0,  3.0),
    "nos": (2.0,  3.0),
    "ton": (0.8,  1.0),
}
GENERIC_DEFAULT = (2.0, 3.0)

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


def load_master_overrides():
    """Optional per-activity productivity from parse_master_sheet.py output."""
    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    path = os.path.join(work, "activity-master-normalized.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    out = {}
    # Tolerant of either {code: {...}} or [{...}, ...]
    items = raw.values() if isinstance(raw, dict) else raw
    for item in items:
        code = (item.get("workActivityCode") or item.get("code") or "").strip().upper().replace(" ", "")
        if not code:
            continue
        out[code] = {
            "outputPerManPerDay": item.get("outputPerManPerDay"),
            "outputPerHour":      item.get("outputPerHour"),
        }
    return out

def derive_norms(unit, override):
    """Return (output_per_man_per_day, output_per_hour) — overrides win when present and > 0."""
    mp_default, eq_default = DEFAULT_NORMS_BY_UNIT.get(unit, GENERIC_DEFAULT)
    mp = override.get("outputPerManPerDay") if override else None
    eq = override.get("outputPerHour")      if override else None
    mp_val = float(mp) if (mp not in (None, "", 0, "0")) else mp_default
    eq_val = float(eq) if (eq not in (None, "", 0, "0")) else eq_default
    return mp_val, eq_val


def upsert_unscoped_norm(work_activity_id, activity_name, default_unit, norm_type, value, counters):
    """norm_type: 'MANPOWER' or 'EQUIPMENT'.
    Key: (work_activity_id, norm_type, role_id IS NULL, category_id IS NULL,
          grade_id IS NULL, make IS NULL, model IS NULL).
    """
    rows = sql(f"""SELECT id::text, output_per_man_per_day, output_per_hour, output_per_day
                   FROM resource.productivity_norms
                   WHERE work_activity_id = '{work_activity_id}'
                     AND norm_type = '{norm_type}'
                     AND role_id IS NULL AND category_id IS NULL AND grade_id IS NULL
                     AND make IS NULL AND model IS NULL
                   LIMIT 1""")
    if rows is None:
        # SELECT failed — don't blindly INSERT (could create duplicates if the row exists
        # but the SELECT errored). Count and bail.
        counters[f"{norm_type.lower()}_failed"] += 1
        return
    eq_per_day = round(value * WORKING_HOURS_PER_DAY, 2) if norm_type == "EQUIPMENT" else None

    def safe_lit(v):
        return "NULL" if v is None else str(v)

    if rows:
        nid = rows[0][0]
        if norm_type == "MANPOWER":
            result = sql(f"""UPDATE resource.productivity_norms
                    SET output_per_man_per_day = {value}, working_hours_per_day = {WORKING_HOURS_PER_DAY},
                        unit = '{(default_unit or 'Nos').replace("'","''")}', updated_at = now()
                    WHERE id = '{nid}'""")
        else:
            result = sql(f"""UPDATE resource.productivity_norms
                    SET output_per_hour = {value}, output_per_day = {safe_lit(eq_per_day)},
                        working_hours_per_day = {WORKING_HOURS_PER_DAY},
                        unit = '{(default_unit or 'Nos').replace("'","''")}', updated_at = now()
                    WHERE id = '{nid}'""")
        if result is None:
            counters[f"{norm_type.lower()}_failed"] += 1
        else:
            counters[f"{norm_type.lower()}_updated"] += 1
        return

    cols = ["id", "created_at", "updated_at", "norm_type", "work_activity_id",
            "activity_name", "unit", "working_hours_per_day"]
    vals = ["gen_random_uuid()", "now()", "now()",
            f"'{norm_type}'", f"'{work_activity_id}'",
            f"'{(activity_name or '').replace(chr(39), chr(39)*2)}'",
            f"'{(default_unit or 'Nos').replace(chr(39), chr(39)*2)}'",
            str(WORKING_HOURS_PER_DAY)]
    if norm_type == "MANPOWER":
        cols.append("output_per_man_per_day"); vals.append(str(value))
    else:
        cols += ["output_per_hour", "output_per_day"]
        vals += [str(value), safe_lit(eq_per_day)]
    result = sql(f"INSERT INTO resource.productivity_norms ({', '.join(cols)}) VALUES ({', '.join(vals)})")
    if result is None:
        counters[f"{norm_type.lower()}_failed"] += 1
    else:
        counters[f"{norm_type.lower()}_inserted"] += 1


def main():
    from collections import defaultdict
    counters = defaultdict(int)

    rows = sql("SELECT id::text, code, name, default_unit FROM resource.work_activities "
               "WHERE COALESCE(active, true) = true ORDER BY code")
    if rows is None:
        print("[seed_productivity_norms] ERROR: SQL query failed (see SQL ERROR above) — check DB connectivity")
        sys.exit(1)
    if not rows:
        print("[seed_productivity_norms] ERROR: no work_activities found — run rebuild_demo.py first")
        sys.exit(1)
    print(f"[seed_productivity_norms] processing {len(rows)} work activities")

    overrides = load_master_overrides()
    for wa_id, code, name, unit in rows:
        ov = overrides.get((code or "").strip().upper().replace(" ", ""), {})
        mp_val, eq_val = derive_norms(unit, ov)
        upsert_unscoped_norm(wa_id, name, unit, "MANPOWER",  mp_val, counters)
        upsert_unscoped_norm(wa_id, name, unit, "EQUIPMENT", eq_val, counters)

    mp_failed = counters['manpower_failed']
    eq_failed = counters['equipment_failed']
    status = "OK" if (mp_failed + eq_failed) == 0 else "WITH_FAILURES"
    print(f"[seed_productivity_norms] {status} | work_activities={len(rows)} | "
          f"manpower: ins={counters['manpower_inserted']} upd={counters['manpower_updated']} failed={mp_failed} | "
          f"equipment: ins={counters['equipment_inserted']} upd={counters['equipment_updated']} failed={eq_failed}")
    if mp_failed + eq_failed > 0:
        sys.exit(2)

if __name__ == "__main__":
    main()
