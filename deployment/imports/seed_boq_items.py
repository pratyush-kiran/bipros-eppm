import os
#!/usr/bin/env python3
"""Synthesize one BOQ item per activity (description = activity name) so every DPR
links to a BOQ. Ports Stage11BoqItems.java + the rate-derivation logic from the
design spec §4.3.

Side effect: writes $BIPROS_WORK_DIR/boq-by-activity.json — a map
{activity_id: {boq_item_id, item_no}} consumed by import_khasab_dprs.py.
"""
import json
import subprocess
import sys


# Typical OMR/day used to derive a non-zero BOQ rate (averages of seed_resource_rates.py cards).
MP_TYPICAL_RATE_OMR_PER_DAY = 15.00
EQ_TYPICAL_RATE_OMR_PER_DAY = 100.00
BOQ_MARGIN = 1.10           # +10% margin on cost-per-unit
BUDGET_CONTINGENCY = 1.05   # +5% on budgeted_rate
DEFAULT_BOQ_QTY = 100.0
LAST_RESORT_RATE = 100.00

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


# ─────────────────────────── BOQ-rate computation ───────────────────────────

def compute_boq_rate(unit, mp_norm, eq_norm):
    """Derive a deterministic OMR-per-unit rate from productivity norms + typical day rates."""
    mp_cost = 0.0
    eq_cost = 0.0
    if mp_norm and mp_norm.get("outputPerManPerDay"):
        try:
            v = float(mp_norm["outputPerManPerDay"])
            if v > 0:
                mp_cost = MP_TYPICAL_RATE_OMR_PER_DAY / v
        except (TypeError, ValueError):
            pass
    if eq_norm and eq_norm.get("outputPerDay"):
        try:
            v = float(eq_norm["outputPerDay"])
            if v > 0:
                eq_cost = EQ_TYPICAL_RATE_OMR_PER_DAY / v
        except (TypeError, ValueError):
            pass
    rate = round((mp_cost + eq_cost) * BOQ_MARGIN, 2)
    return rate if rate > 0 else LAST_RESORT_RATE


# ─────────────────────────── Loaders ───────────────────────────

def load_project_id():
    """Resolve KHASAB-2026 project id (or whichever code is in projects)."""
    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    pid_path = os.path.join(work, "project-id.txt")
    if os.path.exists(pid_path):
        with open(pid_path, encoding="utf-8") as f:
            pid = f.read().strip()
        if pid:
            return pid
    rows = sql("SELECT id::text FROM project.projects ORDER BY created_at LIMIT 1")
    if rows:
        return rows[0][0]
    print("[seed_boq_items] ERROR: no project found — run rebuild_demo.py first")
    sys.exit(1)

def load_activities(project_id):
    """Return list of dicts: {id, code, name, unit, wbs_node_id, wbs_code, planned_qty,
                              wa_id, mp_norm, eq_norm}."""
    rows = sql(f"""
SELECT a.id::text, a.code, a.name,
       COALESCE(wa.default_unit, a.unit, 'Nos') AS unit,
       a.wbs_node_id::text, w.code,
       COALESCE(a.planned_quantity, 0)::float,
       a.work_activity_id::text
FROM activity.activities a
LEFT JOIN project.wbs_nodes w  ON w.id = a.wbs_node_id
LEFT JOIN resource.work_activities wa ON wa.id = a.work_activity_id
WHERE a.project_id = '{project_id}'
ORDER BY a.code
""") or []
    out = []
    for r in rows:
        wa_id = r[7] if r[7] else None
        mp_norm, eq_norm = None, None
        if wa_id:
            nrows = sql(f"""SELECT norm_type, output_per_man_per_day, output_per_hour, output_per_day
                            FROM resource.productivity_norms
                            WHERE work_activity_id = '{wa_id}'
                              AND role_id IS NULL AND category_id IS NULL AND grade_id IS NULL
                              AND make IS NULL AND model IS NULL""") or []
            for nr in nrows:
                norm = {"outputPerManPerDay": nr[1], "outputPerHour": nr[2], "outputPerDay": nr[3]}
                if nr[0] == "MANPOWER":  mp_norm = norm
                else:                    eq_norm = norm
        out.append({
            "id": r[0], "code": r[1], "name": r[2], "unit": r[3],
            "wbs_node_id": r[4] if r[4] else None, "wbs_code": r[5],
            "planned_qty": float(r[6]) if r[6] else 0.0,
            "wa_id": wa_id, "mp_norm": mp_norm, "eq_norm": eq_norm,
        })
    return out

def load_observed_qty_by_activity():
    """Sum qty_executed per activity_code from parsed JSON as a planned_qty fallback."""
    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    path = os.path.join(work, "khasab-dpr-parsed.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        parsed = json.load(f)
    totals = {}
    for d in parsed:
        ac = (d.get("activity_code") or "").strip()
        qty = float(d.get("qty_executed") or 0)
        if ac:
            totals[ac] = totals.get(ac, 0.0) + max(qty, 0.0)
    return totals


# ─────────────────────────── BOQ UPSERT ───────────────────────────

def upsert_boq_item(project_id, item_no, description, unit, chapter, wbs_node_id,
                    boq_qty, boq_rate, counters):
    """Returns boq_item_id (str) on success, or None on failure (counter incremented)."""
    budgeted_rate    = round(boq_rate * BUDGET_CONTINGENCY, 2)
    boq_amount       = round(boq_qty * boq_rate, 2)
    budgeted_amount  = round(boq_qty * budgeted_rate, 2)
    chap_lit = "NULL" if not chapter    else f"'{sql_escape(chapter)}'"
    wbs_lit  = "NULL" if not wbs_node_id else f"'{wbs_node_id}'"

    rows = sql(f"SELECT id::text FROM project.boq_items "
               f"WHERE project_id = '{project_id}' AND item_no = '{sql_escape(item_no)}' LIMIT 1")
    if rows is None:
        counters["failed"] += 1
        return None
    if rows:
        bid = rows[0][0]
        result = sql(f"""UPDATE project.boq_items SET
                  description    = '{sql_escape(description)}',
                  unit           = '{sql_escape(unit)}',
                  chapter        = {chap_lit},
                  wbs_node_id    = {wbs_lit},
                  boq_qty        = {boq_qty},
                  boq_rate       = {boq_rate},
                  boq_amount     = {boq_amount},
                  budgeted_rate  = {budgeted_rate},
                  budgeted_amount= {budgeted_amount},
                  updated_at     = now()
                WHERE id = '{bid}'""")
        if result is None:
            counters["failed"] += 1
            return None
        counters["updated"] += 1
        return bid

    ins = sql(f"""INSERT INTO project.boq_items
        (id, created_at, updated_at, project_id, item_no, description, unit, chapter,
         wbs_node_id, boq_qty, boq_rate, boq_amount, budgeted_rate, budgeted_amount,
         qty_executed_to_date, actual_rate, actual_amount, status)
        VALUES (gen_random_uuid(), now(), now(),
                '{project_id}', '{sql_escape(item_no)}', '{sql_escape(description)}', '{sql_escape(unit)}',
                {chap_lit}, {wbs_lit},
                {boq_qty}, {boq_rate}, {boq_amount}, {budgeted_rate}, {budgeted_amount},
                0, 0, 0, 'ACTIVE')
        RETURNING id::text""")
    if not ins:
        counters["failed"] += 1
        return None
    counters["inserted"] += 1
    return ins[0][0]


# ─────────────────────────── Main ───────────────────────────

def main():
    from collections import defaultdict
    counters = defaultdict(int)

    project_id = load_project_id()
    activities = load_activities(project_id)
    if not activities:
        print(f"[seed_boq_items] ERROR: no activities found for project {project_id}")
        sys.exit(1)
    print(f"[seed_boq_items] project={project_id} activities={len(activities)}")

    observed_qty = load_observed_qty_by_activity()
    boq_map = {}
    for a in activities:
        item_no = f"BOQ-{a['code']}"
        boq_qty = a["planned_qty"] if a["planned_qty"] > 0 else observed_qty.get(a["code"], 0.0)
        if boq_qty <= 0:
            boq_qty = DEFAULT_BOQ_QTY
        boq_rate = compute_boq_rate(a["unit"], a["mp_norm"], a["eq_norm"])
        boq_id = upsert_boq_item(
            project_id=project_id, item_no=item_no,
            description=a["name"], unit=a["unit"],
            chapter=a["wbs_code"], wbs_node_id=a["wbs_node_id"],
            boq_qty=round(boq_qty, 2), boq_rate=boq_rate, counters=counters,
        )
        if boq_id:
            boq_map[a["id"]] = {"boq_item_id": boq_id, "item_no": item_no}

    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    os.makedirs(work, exist_ok=True)
    out_path = os.path.join(work, "boq-by-activity.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(boq_map, f, indent=2)

    failed = counters['failed']
    status = "OK" if failed == 0 else "WITH_FAILURES"
    print(f"[seed_boq_items] {status} | inserted={counters['inserted']} updated={counters['updated']} "
          f"failed={failed} | wrote {out_path} ({len(boq_map)} entries)")
    if failed > 0:
        sys.exit(2)

if __name__ == "__main__":
    main()
