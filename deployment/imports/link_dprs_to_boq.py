#!/usr/bin/env python3
"""Make sure every DPR references a BOQ item.

Rule: link a DPR to the BOQ item whose item_no matches the DPR's activity code; if no BOQ
item matches that code, assign one of the existing BOQ items (stable pseudo-random pick by
activity code, so a re-deploy maps the same activity to the same BOQ). Uses ONLY the
existing BOQ register — it does not create BOQ items.

Why a separate step (not in import_khasab_dprs.py): linking fires the in-transaction
DprBoqSyncListener, a @Version read-modify-write on the shared BoqItem row; under the
by-date parallel import, concurrent DPRs for one activity (different dates) would collide
→ OptimisticLock → failed POSTs. So we import without boqItemNo and set the link here
race-free with one SQL UPDATE per activity. Idempotent. Run AFTER the DPR import.

Env: BIPROS_WORK_DIR, BIPROS_PSQL (+ BIPROS_PG_*), same as fix_demo_v2.py.
"""
import os, zlib, subprocess

WORK = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
PROJECT_ID = open(WORK + "/project-id.txt").read().strip()
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


def main():
    boq = sql(f"SELECT id, item_no FROM project.boq_items "
              f"WHERE project_id='{PROJECT_ID}' AND item_no IS NOT NULL ORDER BY item_no")
    if not boq:
        print("No BOQ items exist for this project — run fix_demo_v2.py first. Aborting.")
        return
    by_itemno = {r[1]: (r[0], r[1]) for r in boq}
    pool = [(r[0], r[1]) for r in boq]

    acts = sql(f"""SELECT a.id, a.code FROM activity.activities a
                   WHERE a.id IN (SELECT DISTINCT activity_id FROM project.daily_progress_reports
                                  WHERE project_id='{PROJECT_ID}')""")
    matched = random_assigned = 0
    for aid, code in acts:
        if code in by_itemno:
            bid, bno = by_itemno[code]; matched += 1
        else:  # stable pseudo-random pick keyed on the activity code
            bid, bno = pool[zlib.crc32(code.encode()) % len(pool)]; random_assigned += 1
        bno_esc = bno.replace("'", "''")
        sql(f"""UPDATE project.daily_progress_reports SET boq_item_id='{bid}', boq_item_no='{bno_esc}'
                WHERE project_id='{PROJECT_ID}' AND activity_id='{aid}'""")

    print(f"BOQ register: {len(pool)} items. Activities with DPRs: {len(acts)} "
          f"({matched} matched by code, {random_assigned} assigned a random BOQ).")
    total = int(sql(f"SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='{PROJECT_ID}'")[0][0])
    linked = int(sql(f"SELECT COUNT(*) FROM project.daily_progress_reports "
                     f"WHERE project_id='{PROJECT_ID}' AND boq_item_no IS NOT NULL")[0][0])
    print(f"DPRs linked to a BOQ item: {linked}/{total}")


if __name__ == "__main__":
    main()
