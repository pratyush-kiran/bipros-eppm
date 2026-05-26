#!/usr/bin/env python3
"""Make the unit consistent across WorkActivity, Activity, and DPR for every activity code.

The schedule Activity has no unit column — it shows the linked WorkActivity.default_unit. That
unit is seeded from the master sheet (often "LS"), while DPRs carry the daily-data unit (e.g.
"cu.m."), so the DPR form warns of a mismatch and Capacity Utilization is skewed. This sets BOTH
resource.work_activities.default_unit AND project.daily_progress_reports.unit to the canonical
unit (activity-units.json, written by parse_khasab.py) for each activity code, joined through the
activity. Race-free SQL, idempotent. Run after activities + DPRs exist.

Env: BIPROS_WORK_DIR, BIPROS_PSQL (+ BIPROS_PG_*), same as link_dprs_to_boq.py.
"""
import os, json, subprocess

WORK = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
PROJECT_ID = open(WORK + "/project-id.txt").read().strip()
UNITS = json.load(open(WORK + "/activity-units.json"))
PSQL = os.environ.get("BIPROS_PSQL", "psql")
PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", PSQL,
           "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"),
           "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
           "-A", "-F", "|", "-t", "-c"]


def sql(q):
    out = subprocess.run(PG_BASE + [q], capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip() or out.stdout.strip())
    return [line.split("|") for line in out.stdout.strip().splitlines() if line.strip()]


def esc(s):
    return s.replace("'", "''")


def main():
    wa = dpr = 0
    for code, unit in UNITS.items():
        c, u = esc(code), esc(unit)
        sql(f"""UPDATE resource.work_activities w SET default_unit='{u}'
                FROM activity.activities a
                WHERE a.work_activity_id=w.id AND a.code='{c}' AND a.project_id='{PROJECT_ID}'
                  AND coalesce(w.default_unit,'') <> '{u}'""")
        n = sql(f"""UPDATE project.daily_progress_reports d SET unit='{u}'
                    FROM activity.activities a
                    WHERE d.activity_id=a.id AND a.code='{c}' AND d.project_id='{PROJECT_ID}'
                      AND coalesce(d.unit,'') <> '{u}'
                    RETURNING 1""")
        wa += 1
        dpr += sum(1 for r in n if r and r[0].strip() == "1")  # ignore psql's "UPDATE N" tag line
    # report consistency
    mism = sql(f"""SELECT count(*) FROM project.daily_progress_reports d
                   JOIN activity.activities a ON a.id=d.activity_id
                   JOIN resource.work_activities w ON w.id=a.work_activity_id
                   WHERE d.project_id='{PROJECT_ID}'
                     AND lower(trim(d.unit)) <> lower(trim(coalesce(w.default_unit,'')))""")[0][0]
    print(f"Aligned units for {wa} activity codes; updated {dpr} DPR unit rows.")
    print(f"DPRs still mismatching their WorkActivity unit: {mism}")


if __name__ == "__main__":
    main()
