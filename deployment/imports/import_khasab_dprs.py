import os
#!/usr/bin/env python3
"""Import Khasab DPRs (Jan, then Feb, then March) into KHASAB-2026 project.

Resolves activity codes → activity UUIDs, supervisor usernames → user UUIDs,
maps manpower/equipment names, and POSTs DPRs one at a time (no bulk endpoint
visible yet; will batch in parallel if needed for performance).
"""
import json
import urllib.request
import urllib.error
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
import subprocess

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
WORK = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
with open(os.environ.get("BIPROS_TOKEN_FILE", WORK + "/admin-token.txt")) as _f:
    TOKEN = _f.read().strip()
with open(WORK + "/project-id.txt") as _f:
    PROJECT_ID = _f.read().strip()
with open(WORK + "/user-ids.json") as _f:
    USER_IDS = json.load(_f)
with open(WORK + "/activity-ids.json") as _f:
    ACTIVITY_IDS = json.load(_f)
with open(WORK + "/khasab-dpr-parsed.json", encoding="utf-8") as _f:
    DPRS = json.load(_f)

# BOQ map written by seed_boq_items.py — every DPR will link via boqItemId.
_boq_path = WORK + "/boq-by-activity.json"
if os.path.exists(_boq_path):
    with open(_boq_path, encoding="utf-8") as _f:
        BOQ_BY_ACTIVITY = json.load(_f)
else:
    BOQ_BY_ACTIVITY = {}
    print(f"[STAGE11] WARN: {_boq_path} missing — run seed_boq_items.py first; "
          f"DPRs will be skipped (boq_missing).")

# Productivity norms by activity (used to compute realistic qty when Excel is blank).
PG_CMD = ["docker", "exec", "-i",
          "-e", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}",
          os.environ.get("BIPROS_PG_CONTAINER", "bipros-postgres"),
          "psql",
          "-U", os.environ.get("BIPROS_PG_USER", "bipros"),
          "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
          "-A", "-F", "|", "-t", "-c"]

def _sql(q):
    out = subprocess.run(PG_CMD + [q], capture_output=True, text=True, timeout=20)
    if out.returncode != 0:
        return []
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]

# Build: activity_id → outputPerManPerDay (from work_activity → productivity_norms).
_NORM_ROWS = _sql(f"""
SELECT a.id::text, n.output_per_man_per_day
FROM activity.activities a
LEFT JOIN resource.productivity_norms n
  ON n.work_activity_id = a.work_activity_id AND n.norm_type = 'MANPOWER'
 AND n.role_id IS NULL AND n.category_id IS NULL AND n.grade_id IS NULL
 AND n.make IS NULL AND n.model IS NULL
WHERE a.project_id = '{PROJECT_ID}'
""")
NORMS_BY_ACTIVITY = {r[0]: (float(r[1]) if r[1] else None) for r in _NORM_ROWS}

# Username → supervisor display name (reverse of supervisor map in parser)
SUP_DISPLAY = {
    "ismaila": "Mohd Ismaila", "saiffuddin": "Md Saiffuddin",
    "illayaraja": "Illayaraja", "kbarman": "K. Barman",
    "vijaykumar": "VijayKumar", "parvaiz": "Parvaiz",
    "sanjar": "Sanjar Alam", "anirban": "Anirban Datta",
    "sohail": "Sohail", "manzar": "Manzar",
    "vpgupta": "V.P. Gupta", "akmishra": "A.K. Mishra",
}

# Activity code → activity name (from ACTIVITY_IDS keys; reuse as-is)


def http(method, path, body=None, timeout=30):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode() if body else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            err_body = json.loads(e.read())
        except Exception:
            err_body = {"error": str(e)}
        return e.code, err_body
    except Exception as e:
        return 0, {"error": str(e)}


def realistic_qty(d, activity_id):
    """Return qty_executed, or None to signal 'skip this idle DPR'.

    Logic:
      1. If Excel has qty > 0 → trust it.
      2. Else if there's crew/equipment present and we know the productivity norm
         → qty = headcount × hours × (norm / 8.0)
      3. Else if no resources at all → return None (skip; truly idle).
      4. Else → 0.5 (crew/equipment present but no norm) — small but realistic, never 0.01.
    """
    raw = d.get("qty_executed")
    try:
        rawf = float(raw) if raw is not None else 0.0
    except (TypeError, ValueError):
        rawf = 0.0
    if rawf > 0:
        return rawf

    hc = sum(int((m.get("count") or 1) or 1) for m in (d.get("manpower") or []))
    hrs_list = [float(m.get("hours") or 0) for m in (d.get("manpower") or []) if m.get("hours")]
    hrs = hrs_list[0] if hrs_list else 8.0
    eq_present = bool(d.get("equipment"))

    norm = NORMS_BY_ACTIVITY.get(activity_id)
    if hc > 0 and norm and norm > 0:
        return round(hc * hrs * (norm / 8.0), 2)

    if hc == 0 and not eq_present:
        return None    # truly idle — skip the DPR entirely

    return 0.5         # small but realistic fallback


def resolve_dpr(d):
    """Build the JSON body for one DPR POST. Returns (body, skip_reason) where
    body is None and skip_reason is a string when the DPR should NOT be posted."""
    aid = ACTIVITY_IDS.get(d["activity_code"])
    sup_id = USER_IDS.get(d["supervisor_username"])
    if not aid or not sup_id:
        return None, "unresolved_activity_or_user"

    # BOQ link is mandatory per spec §4.5(b).
    boq = BOQ_BY_ACTIVITY.get(aid)
    if not boq:
        return None, "boq_missing"

    qty = realistic_qty(d, aid)
    if qty is None:
        return None, "idle_no_resources"

    # Build resource arrays first so we can reject empty DPRs before assembling the body.
    manpower = []
    for m in (d.get("manpower") or []):
        nos = int(m.get("count") or 1) or 1
        hours = float(m.get("hours") or 0)
        manpower.append({
            "trade": (m.get("role") or "")[:50] or "Skilled Labour",
            "nos": nos,
            "workingHours": hours,
            "unitRate": float(m.get("rate") or 0),
            "unitRateBasis": "HOUR" if hours else "DAY",
        })
    equipment = []
    for e in (d.get("equipment") or []):
        nos = int(e.get("count") or 1) or 1
        hours = float(e.get("hours") or 0)
        equipment.append({
            "equipmentType": (e.get("name") or "")[:80] or "GENERIC",
            "nos": nos,
            "workingHours": hours,
            "unitRate": float(e.get("rate") or 0),
            "unitRateBasis": "HOUR" if hours else "DAY",
        })
    materials = []
    for m in (d.get("material") or []):
        materials.append({
            "materialName": (m.get("desc") or "")[:120] or "Generic Material",
            "unit": m.get("unit") or "nos",
            "qtyConsumed": float(m.get("qty") or 0),
            "unitRate": float(m.get("rate") or 0),
        })
    sub_contractors = []
    for s in (d.get("subcontractor") or []):
        sub_contractors.append({
            "subContractorName": (s.get("name") or "")[:100],
            "workDescription": (s.get("desc") or "")[:200],
            "unit": s.get("unit") or "nos",
            "qtyExecuted": float(s.get("qty") or 0),
            "unitRate": float(s.get("rate") or 0),
        })

    if not manpower and not equipment and not materials:
        return None, "no_resources"

    body = {
        "projectId": PROJECT_ID,
        "activityId": aid,
        "activityName": f"Khasab {d['activity_code']}",
        "reportDate": d["date"],
        "reportedByUserId": sup_id,
        "supervisorUserId": sup_id,
        "supervisorName": SUP_DISPLAY.get(d["supervisor_username"], d["supervisor_username"]),
        "qtyExecuted": qty,
        "unit": d.get("unit") or "nos",
        "boqItemId": boq["boq_item_id"],
        "boqItemNo": boq["item_no"],
        "manpower": manpower,
        "equipment": equipment,
        "materials": materials,
        "subContractors": sub_contractors,
    }
    if d.get("site"):
        body["landmark"] = d["site"][:100]
    if d.get("side"):
        body["side"] = d["side"][:5] if d["side"] in ("LHS", "RHS") else None
    return body, None


def post_one(d):
    body, skip = resolve_dpr(d)
    if body is None:
        return (f"skip_{skip}", d.get("date"), d.get("activity_code"))
    code, resp = http("POST", f"/v1/projects/{PROJECT_ID}/dpr", body, timeout=20)
    if code in (200, 201):
        return ("ok", body["reportDate"], body["activityId"])
    err_raw = resp.get("error", {}) if isinstance(resp, dict) else {}
    if isinstance(err_raw, dict):
        msg = err_raw.get("message", str(err_raw))[:120]
        details = err_raw.get("details") or []
        code_str = err_raw.get("code", "")
    else:
        msg = str(err_raw)[:120]
        details = []
        code_str = ""
    if "ALREADY" in msg.upper() or code_str == "DPR_ALREADY_EXISTS_FOR_ACTIVITY":
        return ("dup", body["reportDate"], msg)
    detail_str = "; ".join(f"{dd.get('field')}={dd.get('reason')}" for dd in details[:5]) if details else ""
    return ("fail", body["reportDate"], f"{msg}|{detail_str}")


def import_month(month_prefix, workers=1):
    subset = [d for d in DPRS if d["date"].startswith(month_prefix)]
    print(f"\n=== Importing {len(subset)} DPRs for {month_prefix} (workers={workers}) ===")
    t0 = time.time()
    counts = {}
    errors = []
    # Single-thread (workers=1) eliminates the Postgres deadlocks on
    # dbs.dbs_manpower_register observed under parallel ingest.
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futures = {ex.submit(post_one, d): d for d in subset}
        for i, fut in enumerate(as_completed(futures), 1):
            result, ts, info = fut.result()
            counts[result] = counts.get(result, 0) + 1
            if result == "fail" and len(errors) < 10:
                errors.append((ts, info))
            if i % 50 == 0:
                elapsed = time.time() - t0
                ok = counts.get("ok", 0); fail = counts.get("fail", 0)
                dup = counts.get("dup", 0)
                skip = sum(v for k, v in counts.items() if k.startswith("skip_"))
                print(f"  {i}/{len(subset)} ({elapsed:.0f}s) — ok={ok} fail={fail} dup={dup} skip={skip}")
    elapsed = time.time() - t0
    ok = counts.get("ok", 0); fail = counts.get("fail", 0); dup = counts.get("dup", 0)
    skip_total = sum(v for k, v in counts.items() if k.startswith("skip_"))
    skip_detail = {k.replace("skip_", ""): v for k, v in counts.items() if k.startswith("skip_") and v > 0}
    print(f"  DONE {month_prefix}: ok={ok} fail={fail} dup={dup} skip={skip_total} "
          f"({elapsed:.0f}s)  skip_detail={skip_detail}")
    if errors:
        print("  Sample errors:")
        for ts, msg in errors[:5]:
            print(f"    {ts}: {msg}")
    return counts


if __name__ == "__main__":
    month_arg = sys.argv[1] if len(sys.argv) > 1 else "2026-01"
    # Optional --workers N override (default 1 = single-thread, deadlock-safe).
    workers = 1
    if "--workers" in sys.argv:
        i = sys.argv.index("--workers")
        if i + 1 < len(sys.argv):
            try:
                workers = max(1, int(sys.argv[i + 1]))
            except ValueError:
                workers = 1
        if workers > 1:
            print(f"[STAGE11] WARN: workers={workers} re-enables parallel ingest; "
                  f"Postgres deadlocks on dbs.dbs_manpower_register are likely.")

    grand = {}
    months = ("2026-01", "2026-02", "2026-03") if month_arg == "all" else (month_arg,)
    for m in months:
        c = import_month(m, workers=workers)
        for k, v in c.items():
            grand[k] = grand.get(k, 0) + v
    ok = grand.get("ok", 0); fail = grand.get("fail", 0); dup = grand.get("dup", 0)
    skip_boq = grand.get("skip_boq_missing", 0)
    skip_idle = grand.get("skip_idle_no_resources", 0)
    skip_empty = grand.get("skip_no_resources", 0)
    skip_unres = grand.get("skip_unresolved_activity_or_user", 0)
    print(f"\n[STAGE11] DPR ingest done | posted={ok} fail={fail} dup={dup} "
          f"boq_missing={skip_boq} idle_skipped={skip_idle} empty_skipped={skip_empty} "
          f"unresolved={skip_unres}")
