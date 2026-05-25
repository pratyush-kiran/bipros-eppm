#!/usr/bin/env python3
"""Create the project team (reports-to chain) for KHASAB-2026."""
import json
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
USER_IDS = json.load(open("/tmp/khasab/user-ids.json"))

# (username, project_role, reports_to_username_or_None)
# ProjectRole enum: PM, CONSTRUCTION_MANAGER, SITE_MANAGER, ENGINEER, SUPERVISOR, QS, SAFETY
TEAM = [
    ("ravi",        "PM",                  None),
    ("rahul",       "CONSTRUCTION_MANAGER", "ravi"),
    ("hemendrase",  "ENGINEER",             "rahul"),
    ("subratse",    "ENGINEER",             "rahul"),
    ("anirban",     "SUPERVISOR",        "hemendrase"),
    ("illayaraja",  "SUPERVISOR",        "hemendrase"),
    ("kbarman",     "SUPERVISOR",        "hemendrase"),
    ("parvaiz",     "SUPERVISOR",        "hemendrase"),
    ("sohail",      "SUPERVISOR",        "hemendrase"),
    ("vpgupta",     "SUPERVISOR",        "hemendrase"),
    ("saiffuddin",  "SUPERVISOR",        "subratse"),
    ("ismaila",     "SUPERVISOR",        "subratse"),
    ("sanjar",      "SUPERVISOR",        "subratse"),
    ("vijaykumar",  "SUPERVISOR",        "subratse"),
    ("manzar",      "SUPERVISOR",        "subratse"),
    ("akmishra",    "SUPERVISOR",        "subratse"),
]


def http(method, path, body=None):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode() if body else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            err_body = json.loads(e.read())
        except Exception:
            err_body = {"error": str(e)}
        return e.code, err_body
    except Exception as e:
        return 0, {"error": str(e)}


for username, role, reports_to in TEAM:
    body = {
        "userId": USER_IDS[username],
        "role": role,
        "activeFrom": "2026-01-01",
    }
    if reports_to:
        body["reportsToUserId"] = USER_IDS[reports_to]

    code, resp = http("POST", f"/v1/projects/{PROJECT_ID}/team", body)
    if code in (200, 201):
        m = resp.get("data", {})
        rt = m.get("reportsToUsername") or "—"
        print(f"  {username}: {role} → reports_to={rt}")
    else:
        msg = resp.get("error", {}).get("message", str(resp.get("error")))
        print(f"  {username}: FAIL {code} {msg}")

# Verify
print("\n=== Project Team (from API) ===")
code, resp = http("GET", f"/v1/projects/{PROJECT_ID}/team")
if code == 200:
    for m in resp.get("data", []):
        u = m.get("username") or m.get("userId", "")[:8]
        rt = m.get("reportsToUsername") or (m.get("reportsToUserId") or "")[:8] or "—"
        role = m.get("role") or "?"
        print(f"  {str(u):15} {role:25} → {rt}")
