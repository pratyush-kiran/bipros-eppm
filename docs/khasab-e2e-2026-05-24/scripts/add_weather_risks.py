import os
#!/usr/bin/env python3
"""Add weather to DPRs + create Risk entries (continuation of rename script)."""
import json
import subprocess
import urllib.request
import urllib.error

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", PSQL, "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"), "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"), "-A", "-F", "|", "-t", "-c"]


def sql(q, ignore=False):
    out = subprocess.run(PG_BASE + [q], capture_output=True, text=True, timeout=60)
    if out.returncode != 0 and not ignore:
        raise Exception(f"SQL: {out.stderr[:300]}")
    return [line for line in out.stdout.strip().split("\n") if line.strip()]


def http(method, path, body=None, timeout=20):
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


# === Weather ===
print("=== Step 3: Add weather to all DPRs ===")
sql(f"""
UPDATE project.daily_progress_reports SET weather_condition = CASE
  WHEN EXTRACT(DOW FROM report_date) IN (0, 1, 5) THEN 'CLEAR'
  WHEN EXTRACT(DOW FROM report_date) = 2 THEN 'PARTLY_CLOUDY'
  WHEN EXTRACT(DOW FROM report_date) = 3 THEN 'CLOUDY'
  WHEN EXTRACT(DOW FROM report_date) = 4 THEN 'WINDY'
  WHEN EXTRACT(DOW FROM report_date) = 6 THEN 'RAIN'
END
WHERE project_id='{PROJECT_ID}'
""")
print("  Weather distribution:")
out = subprocess.run(PG_BASE + [f"""
SELECT weather_condition, COUNT(*) FROM project.daily_progress_reports
WHERE project_id='{PROJECT_ID}' GROUP BY weather_condition ORDER BY COUNT(*) DESC
"""], capture_output=True, text=True)
print(out.stdout)


# === Probe risks endpoint ===
print("=== Step 4: Probe /v1/projects/{pid}/risks shape ===")
sc, resp = http("GET", f"/v1/projects/{PROJECT_ID}/risks")
print(f"  GET risks: HTTP {sc}")
if sc == 200:
    print(f"  Existing risks: {len(resp.get('data', []))}")

# Try POST minimal
print("\nProbing POST shape with minimal payload...")
sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/risks", {"title": "Test", "projectId": PROJECT_ID})
print(f"  POST minimal: HTTP {sc}")
if isinstance(resp, dict):
    err = resp.get("error", {})
    if isinstance(err, dict):
        print(f"    Message: {err.get('message', '?')[:200]}")
        for d in (err.get("details") or [])[:10]:
            print(f"      field {d.get('field')}: {d.get('reason')}")
