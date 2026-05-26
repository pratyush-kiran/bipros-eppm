import os
#!/usr/bin/env python3
"""Create the 16 Khasab users via the backend API."""
import json
import urllib.request
import urllib.error
import time

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()

USERS = [
    {"username":"ravi","firstName":"RAVI","lastName":"","role":"PROJECT_MANAGER"},
    {"username":"rahul","firstName":"Rahul","lastName":"","role":"SITE_MANAGER"},
    {"username":"hemendrase","firstName":"Hemendra","lastName":"SE","role":"SITE_ENGINEER"},
    {"username":"subratse","firstName":"Subrat","lastName":"SE","role":"SITE_ENGINEER"},
    {"username":"anirban","firstName":"Anirban","lastName":"Datta","role":"SUPERVISOR"},
    {"username":"illayaraja","firstName":"Illayaraja","lastName":"","role":"SUPERVISOR"},
    {"username":"kbarman","firstName":"K","lastName":"Barman","role":"SUPERVISOR"},
    {"username":"parvaiz","firstName":"Parvaiz","lastName":"","role":"SUPERVISOR"},
    {"username":"saiffuddin","firstName":"Md","lastName":"Saiffuddin","role":"SUPERVISOR"},
    {"username":"ismaila","firstName":"Mohd","lastName":"Ismaila","role":"SUPERVISOR"},
    {"username":"sanjar","firstName":"Sanjar","lastName":"Alam","role":"SUPERVISOR"},
    {"username":"vijaykumar","firstName":"Vijay","lastName":"Kumar","role":"SUPERVISOR"},
    {"username":"sohail","firstName":"Sohail","lastName":"","role":"SUPERVISOR"},
    {"username":"manzar","firstName":"Manzar","lastName":"","role":"SUPERVISOR"},
    {"username":"vpgupta","firstName":"V.P.","lastName":"Gupta","role":"SUPERVISOR"},
    {"username":"akmishra","firstName":"A.K.","lastName":"Mishra","role":"SUPERVISOR"},
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

user_ids = {}

for u in USERS:
    body = {
        "username": u["username"],
        "email": f"{u['username']}@bipros.test",
        "password": "Password@123",
        "firstName": u["firstName"],
        "lastName": u["lastName"] or " ",
    }
    code, resp = http("POST", "/v1/users", body)
    if code != 200 and code != 201:
        msg = resp.get("error", {}).get("message", resp.get("error") if isinstance(resp.get("error"), str) else "")
        print(f"  {u['username']}: CREATE FAIL {code} {msg}")
        continue
    uid = resp.get("data", {}).get("id")
    user_ids[u["username"]] = uid

    # Assign role
    rc, rr = http("PUT", f"/v1/users/{uid}/roles", {"roles": [u["role"]]})
    role_names = ",".join(rr.get("data", {}).get("roles", [])) if rc == 200 else "FAIL"
    print(f"  {u['username']}: {uid} → role={role_names}")
    time.sleep(0.1)

with open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/user-ids.json", "w") as f:
    json.dump(user_ids, f, indent=2)

print(f"\nCreated {len(user_ids)} users")
print(f"Wrote /tmp/khasab/user-ids.json")
