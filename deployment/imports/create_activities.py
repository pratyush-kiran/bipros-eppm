import os
#!/usr/bin/env python3
"""Create activities for each unique code in parsed Khasab DPRs.
Maps activity code → WBS leaf by stripping to first 2 dot-separated parts.
"""
import json
import urllib.request
import urllib.error
import re
from collections import Counter, defaultdict

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()
PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
WBS_IDS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/wbs-ids.json"))
DPRS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/parsed-pre-resolve.json")) if False else json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/khasab-dpr-parsed.json"))


def code_to_wbs(code: str) -> str:
    """5.10.6 (i) → 5.10, 2.3.6(i)b → 2.3, 18.3 → 18.3, 1 → 1.0"""
    c = code.strip().replace(' ', '')
    parts = c.split('.')[:2]
    if len(parts) == 1:
        return f"{parts[0]}.0"
    return f"{parts[0]}.{parts[1].split('(')[0]}"


def http(method, path, body=None):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode() if body else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=20) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            err_body = json.loads(e.read())
        except Exception:
            err_body = {"error": str(e)}
        return e.code, err_body
    except Exception as e:
        return 0, {"error": str(e)}


# Determine the modal unit per activity code
unit_counts = defaultdict(Counter)
for d in DPRS:
    if d.get("unit"):
        unit_counts[d["activity_code"]][d["unit"]] += 1

# Unit normalization (Khasab data has many synonyms)
UNIT_MAP = {
    "cu.m.": "cum",   "Cum": "cum",   "CUM": "cum",   "cum": "cum",
    "sq.m.": "sqm",   "Sqm": "sqm",   "SQM": "sqm",   "sqm": "sqm",
    "lin.m.": "m",    "m": "m",       "M": "m",
    "Km": "km",       "km": "km",     "KM": "km",
    "kg.": "kg",      "kg": "kg",     "KG": "kg",     "Kg": "kg",
    "t.": "MT",       "MT": "MT",     "ton": "MT",
    "Nos": "nos",     "nos": "nos",   "NOS": "nos",   "Nr": "nos",
    "trip": "trip",   "Trip": "trip",
    "layer": "layer", "Layer": "layer",
}


def normalize_unit(raw):
    return UNIT_MAP.get(raw, raw.lower() if raw else "nos")


# Build activity list — one per unique code in parsed DPRs
codes = sorted(set(d["activity_code"] for d in DPRS))
print(f"Creating {len(codes)} activities...")

activities = {}
failed = []
for code in codes:
    wbs_code = code_to_wbs(code)
    wbs_id = WBS_IDS.get(wbs_code)
    if not wbs_id:
        # Fallback to parent (e.g. 9.0 if 9.1.6(iii) → 9.1 doesn't exist)
        parent_code = wbs_code.split('.')[0] + ".0"
        wbs_id = WBS_IDS.get(parent_code)
        if not wbs_id:
            print(f"  {code}: SKIP (no WBS leaf for {wbs_code} or {parent_code})")
            failed.append((code, "no_wbs"))
            continue
    most_common_unit_raw = unit_counts[code].most_common(1)[0][0] if unit_counts[code] else "nos"
    unit = normalize_unit(most_common_unit_raw)

    body = {
        "projectId": PROJECT_ID,
        "code": code,
        "name": f"Khasab {code}",
        "wbsNodeId": wbs_id,
        "unit": unit,
        "plannedStart": "2026-01-01",
        "plannedFinish": "2026-12-31",
        "type": "TASK_DEPENDENT",
        "percentCompleteType": "DURATION",
    }
    sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/activities", body)
    if sc not in (200, 201):
        msg = resp.get("error", {}).get("message", str(resp.get("error")))[:80]
        print(f"  {code}: FAIL {sc} — {msg}")
        failed.append((code, msg))
        continue
    aid = resp["data"]["id"]
    activities[code] = aid

with open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/activity-ids.json", "w") as f:
    json.dump(activities, f, indent=2)

print(f"\nCreated {len(activities)} activities ({len(failed)} failed)")
print(f"Saved to /tmp/khasab/activity-ids.json")
if failed:
    print("\nFailed:")
    for c, m in failed:
        print(f"  {c}: {m}")
