import os
#!/usr/bin/env python3
"""Just create productivity norms (the only step that failed)."""
import json
import urllib.request
import urllib.error
import subprocess

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", PSQL, "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"), "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"), "-A", "-F", "|", "-t", "-c"]


def sql(q):
    full = q.replace("$PROJECT_ID", PROJECT_ID)
    out = subprocess.run(PG_BASE + [full], capture_output=True, text=True, timeout=30)
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]


def http(method, path, body=None, timeout=15):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode() if body else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read())
        except Exception:
            err = {"error": str(e)}
        return e.code, err
    except Exception as e:
        return 0, {"error": str(e)}


# Get all work_activities we created
wa_rows = sql("SELECT id::text, code, default_unit FROM resource.work_activities WHERE code LIKE 'KHASAB_%'")
print(f"Found {len(wa_rows)} Khasab work_activities")

unit_norm = {"cum": 2, "sqm": 5, "m": 10, "kg": 100, "MT": 1, "nos": 5, "km": 0.5, "Sqm": 5, "Nos": 5, "Km": 0.5}

ok = fail = 0
for wa in wa_rows:
    wa_id, code, unit = wa[0], wa[1], wa[2] or "nos"
    base = unit_norm.get(unit, 5)
    # Manpower norm
    for nt, payload in [
        ("MANPOWER", {"normType": "MANPOWER", "workActivityId": wa_id, "unit": unit,
                      "outputPerManPerDay": float(base), "outputPerDay": float(base * 5),
                      "crewSize": 5, "workingHoursPerDay": 8.0,
                      "remarks": f"Manpower norm for {code}"}),
        ("EQUIPMENT", {"normType": "EQUIPMENT", "workActivityId": wa_id, "unit": unit,
                       "outputPerHour": float(base * 2), "outputPerDay": float(base * 16),
                       "workingHoursPerDay": 8.0, "fuelLitresPerHour": 8.0,
                       "remarks": f"Equipment norm for {code}"}),
    ]:
        sc, resp = http("POST", "/v1/productivity-norms", payload)
        if sc in (200, 201):
            ok += 1
        else:
            fail += 1
            if fail < 5:
                print(f"  {code} {nt}: FAIL {sc} {resp.get('error', {}).get('message', '?')[:120]}")

print(f"\nCreated {ok} norms ({fail} failed)")
