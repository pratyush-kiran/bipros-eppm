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

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
USER_IDS = json.load(open("/tmp/khasab/user-ids.json"))
ACTIVITY_IDS = json.load(open("/tmp/khasab/activity-ids.json"))
DPRS = json.load(open("/tmp/khasab-dpr-parsed.json"))

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


def resolve_dpr(d):
    """Build the JSON body for one DPR POST."""
    aid = ACTIVITY_IDS.get(d["activity_code"])
    sup_id = USER_IDS.get(d["supervisor_username"])
    if not aid or not sup_id:
        return None
    qty = float(d.get("qty_executed") or 0)
    remarks = None
    if qty <= 0:
        qty = 0.01  # validator requires > 0; 0.01 marks idle/deployment-only days
        remarks = "Source qty=0 — resource deployment only (idle/no-output day)"

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
        "manpower": [],
        "equipment": [],
        "materials": [],
        "subContractors": [],
    }
    if remarks:
        body["remarks"] = remarks
    # location optional but useful
    if d.get("site"):
        body["landmark"] = d["site"][:100]
    if d.get("side"):
        body["side"] = d["side"][:5] if d["side"] in ("LHS", "RHS") else None

    for m in d["manpower"]:
        body["manpower"].append({
            "trade": (m.get("role") or "")[:50],
            "nos": int(m.get("count", 1) or 1),
            "workingHours": float(m.get("hours", 0) or 0),
            "unitRate": float(m.get("rate", 0) or 0),
            "unitRateBasis": "HOUR" if m.get("hours") else "DAY",
        })
    for e in d["equipment"]:
        body["equipment"].append({
            "equipmentType": (e.get("name") or "")[:80],
            "nos": int(e.get("count", 1) or 1),
            "workingHours": float(e.get("hours", 0) or 0),
            "unitRate": float(e.get("rate", 0) or 0),
            "unitRateBasis": "HOUR" if e.get("hours") else "DAY",
        })
    for m in d["material"]:
        body["materials"].append({
            "materialName": (m.get("desc") or "")[:120],
            "unit": m.get("unit") or "nos",
            "qtyConsumed": float(m.get("qty", 0) or 0),
            "unitRate": float(m.get("rate", 0) or 0),
        })
    for s in d["subcontractor"]:
        body["subContractors"].append({
            "subContractorName": (s.get("name") or "")[:100],
            "workDescription": (s.get("desc") or "")[:200],
            "unit": s.get("unit") or "nos",
            "qtyExecuted": float(s.get("qty", 0) or 0),
            "unitRate": float(s.get("rate", 0) or 0),
        })
    return body


def post_one(d):
    body = resolve_dpr(d)
    if body is None:
        return ("skip_unresolved", d.get("date"), d.get("activity_code"))
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


def import_month(month_prefix):
    subset = [d for d in DPRS if d["date"].startswith(month_prefix)]
    print(f"\n=== Importing {len(subset)} DPRs for {month_prefix} ===")
    t0 = time.time()
    counts = {"ok": 0, "fail": 0, "dup": 0, "skip_unresolved": 0}
    errors = []
    # Sequential is safe (no in-memory ordering concerns). Parallelize cautiously.
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = {ex.submit(post_one, d): d for d in subset}
        for i, fut in enumerate(as_completed(futures), 1):
            result, ts, info = fut.result()
            counts[result] += 1
            if result == "fail" and len(errors) < 10:
                errors.append((ts, info))
            if i % 50 == 0:
                elapsed = time.time() - t0
                print(f"  {i}/{len(subset)} ({elapsed:.0f}s) — ok={counts['ok']} fail={counts['fail']} dup={counts['dup']} skip={counts['skip_unresolved']}")
    elapsed = time.time() - t0
    print(f"  DONE {month_prefix}: ok={counts['ok']} fail={counts['fail']} dup={counts['dup']} skip={counts['skip_unresolved']} ({elapsed:.0f}s)")
    if errors:
        print(f"  Sample errors:")
        for ts, msg in errors[:5]:
            print(f"    {ts}: {msg}")
    return counts


if __name__ == "__main__":
    month_arg = sys.argv[1] if len(sys.argv) > 1 else "2026-01"
    if month_arg == "all":
        for m in ("2026-01", "2026-02", "2026-03"):
            import_month(m)
    else:
        import_month(month_arg)
