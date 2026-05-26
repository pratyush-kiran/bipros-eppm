#!/usr/bin/env python3
"""Assign supervisors to each activity, derived from who actually filed DPRs for it.

rebuild_demo.py creates activities but never assigns supervisors, so the
activity.activity_supervisors join table is empty and the DPR form shows
"No activities assigned to this supervisor — showing all". This sets each activity's
supervisor set to exactly the supervisors that reported DPRs on that activity, so the form
filters correctly and every DPR's supervisor matches its activity's supervisors.

Idempotent — PUT .../supervisors replaces the whole set. Run after activities + users exist
(works on locked activities; setSupervisors has no lock guard). Env: BIPROS_API_BASE,
BIPROS_WORK_DIR, BIPROS_TOKEN_FILE — same as import_khasab_dprs.py.
"""
import os, sys, json, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_khasab_dprs as imp  # reuses http(), PROJECT_ID, USER_IDS, ACTIVITY_IDS, DPRS, SUP_DISPLAY

PID = imp.PROJECT_ID


def main():
    # activity_code -> ordered set of supervisor usernames seen in the DPRs
    sups_by_code = collections.OrderedDict()
    for d in imp.DPRS:
        sups_by_code.setdefault(d["activity_code"], dict()).setdefault(d["supervisor_username"], True)

    assigned = skipped = failed = 0
    total_links = 0
    for code, unames in sups_by_code.items():
        aid = imp.ACTIVITY_IDS.get(code)
        if not aid:
            skipped += 1
            continue
        supervisors = []
        for u in unames:
            uid = imp.USER_IDS.get(u)
            if uid:
                supervisors.append({"userId": uid, "userName": imp.SUP_DISPLAY.get(u, u)})
        if not supervisors:
            skipped += 1
            continue
        code_, resp = imp.http("PUT", f"/v1/projects/{PID}/activities/{aid}/supervisors",
                               {"supervisors": supervisors})
        if code_ in (200, 201):
            assigned += 1
            total_links += len(supervisors)
        else:
            failed += 1
            msg = resp.get("error", {}) if isinstance(resp, dict) else resp
            print(f"  FAIL {code}: {code_} {str(msg)[:120]}")
    print(f"Activities assigned supervisors: {assigned} (skipped {skipped}, failed {failed}); "
          f"{total_links} activity-supervisor links")


if __name__ == "__main__":
    main()
