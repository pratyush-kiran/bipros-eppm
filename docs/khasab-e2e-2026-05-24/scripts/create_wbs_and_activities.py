#!/usr/bin/env python3
"""Create WBS tree + activities for KHASAB-2026, derived from parsed DPR codes."""
import json
import urllib.request
import urllib.error
import re

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()

# Extended WBS tree covering all 33 activity codes in the parsed data
# Format: (code, name, parent_code_or_None, wbsType)
WBS = [
    ("1.0",  "Preliminaries",            None,  "NODE"),
    ("1.1",  "Site Establishment",       "1.0", "NODE"),
    ("1.2",  "Mobilization",             "1.0", "NODE"),
    ("1.3",  "Soil Investigation",       "1.0", "NODE"),
    ("2.0",  "Sub-structure",            None,  "NODE"),
    ("2.1",  "Setting Out & Layout",     "2.0", "NODE"),
    ("2.3",  "Bored Cast In-Situ Piling","2.0", "NODE"),
    ("2.4",  "Pile Cap",                 "2.0", "NODE"),
    ("2.6",  "Pier",                     "2.0", "NODE"),
    ("2.7",  "Abutment",                 "2.0", "NODE"),
    ("2.8",  "Wing Walls",               "2.0", "NODE"),
    ("3.0",  "Super-structure",          None,  "NODE"),
    ("3.2",  "Concrete RCC",             "3.0", "NODE"),
    ("3.3",  "Pre-stressed Members",     "3.0", "NODE"),
    ("5.0",  "Bearings & Joints",        None,  "NODE"),
    ("5.1",  "Bearings",                 "5.0", "NODE"),
    ("5.2",  "Expansion Joints",         "5.0", "NODE"),
    ("5.10", "Anchor Blocks",            "5.0", "NODE"),
    ("9.0",  "Approach Slab",            None,  "NODE"),
    ("13.0", "Drainage",                 None,  "NODE"),
    ("18.0", "Pavement",                 None,  "NODE"),
    ("18.3", "Bituminous Layers",        "18.0","NODE"),
]


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


# Cleanup the test node if present
code, resp = http("GET", f"/v1/projects/{PROJECT_ID}/wbs")
existing = (resp.get("data", []) if isinstance(resp.get("data"), list) else resp.get("data", {}).get("content", [])) or []
for node in existing:
    if node.get("code") == "TEST":
        http("DELETE", f"/v1/projects/{PROJECT_ID}/wbs/{node['id']}")
        print(f"  cleaned up TEST node")

# Build WBS
print(f"\n=== Building WBS ({len(WBS)} nodes) ===")
wbs_ids = {}
for code, name, parent_code, wbs_type in WBS:
    body = {"code": code, "name": name, "wbsType": wbs_type}
    if parent_code:
        body["parentId"] = wbs_ids[parent_code]
    sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/wbs", body)
    if sc not in (200, 201):
        print(f"  {code}: FAIL {sc} {resp.get('error', {}).get('message', resp)}")
        continue
    wbs_ids[code] = resp["data"]["id"]
    print(f"  {code} {name}: {wbs_ids[code]}")

with open("/tmp/khasab/wbs-ids.json", "w") as f:
    json.dump(wbs_ids, f, indent=2)
print(f"\nWBS ids saved to /tmp/khasab/wbs-ids.json")
print(f"Total: {len(wbs_ids)} nodes")
