import os
#!/usr/bin/env python3
"""Add weather to DPRs + create Risk entries (continuation of rename script)."""
import json
import subprocess
import urllib.request
import urllib.error

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()
PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
PSQL = os.environ.get("BIPROS_PSQL", "psql")
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
# === Insert 8 representative risks ===
print("\n=== Step 5: Insert risk register (8 entries) ===")
RISKS = [
    ("RISK-001", "Monsoon delays in March", "Late-March rains may delay bituminous works", "MW-CLOUDBURST-HILLY", 4, 3),
    ("RISK-002", "Excavator availability",   "High utilisation strains availability on breakdown", "MW-GENERIC", 3, 4),
    ("RISK-003", "Blasting permit delays",   "MoTC permits for hard-rock can take 2-4 weeks", "MW-GENERIC", 3, 3),
    ("RISK-004", "GSB quality non-conformance", "Source quarry CBR variance - possible rejection", "MW-GENERIC", 3, 3),
    ("RISK-005", "Skilled welder shortage",  "Limited supply of certified welders for bridge work", "MW-GENERIC", 4, 3),
    ("RISK-006", "HSE incident - work at height", "Slope dressing at high gradient - fall risk", "HSE-FALL-FROM-HEIGHT", 2, 4),
    ("RISK-007", "Procurement lead-time on bearings", "Imported elastomeric bearings - 12-week lead", "CG-PROCUREMENT-LEAD-TIME", 3, 4),
    ("RISK-008", "Site security after-hours", "Equipment theft risk on remote stretches", "HSE-GENERIC", 2, 3),
]
inserted = 0
for code, title, descr, cat, prob, impact in RISKS:
    sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/risks", {
        "code": code, "title": title, "description": descr,
        "category": cat, "riskType": "THREAT",
        "probability": prob, "impact": impact,
        "status": "IDENTIFIED",
    })
    if sc in (200, 201):
        inserted += 1
    else:
        print(f"  {code}: HTTP {sc} — {str(resp)[:120]}")
print(f"  Inserted {inserted}/8 risks")
