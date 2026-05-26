import os
#!/usr/bin/env python3
"""Create 8 realistic risks for KHASAB-2026."""
import json
import subprocess
import urllib.request
import urllib.error

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()
PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
PSQL = os.environ.get("BIPROS_PSQL", "psql")
PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", PSQL, "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"), "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"), "-A", "-F", "|", "-t", "-c"]


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


# Wipe the test risk first
import subprocess as sp
sp.run(PG_BASE + [f"DELETE FROM risk.risks WHERE project_id='{PROJECT_ID}'"], check=False)

risks = [
    {"title": "Monsoon / weather delays in March-April",
     "description": "Late-March / early-April rains may delay bituminous works (4.2, 4.4, 4.5 series) and slope dressing. Site survey shows 470 rain-day DPRs in the historical pattern.",
     "category": "MW-GENERIC", "phase": "EXECUTION",
     "probability": 4, "impactCost": 3, "impactSchedule": 4},
    {"title": "Excavator fleet availability",
     "description": "Capacity Utilization shows Excavator at >80% across Jan-Mar. Any breakdown of the 2-3 primary 20-Ton hydraulic units would create immediate schedule pressure.",
     "category": "RES-EQUIPMENT-MOBILISATION", "phase": "EXECUTION",
     "probability": 3, "impactCost": 3, "impactSchedule": 4},
    {"title": "Blasting / explosive permit lead time",
     "description": "MoTC blasting permits for hard-rock excavation (2.3.6(i)b) typically take 2-4 weeks. Pre-applied permits are valid for 90 days; ensure renewal cycle.",
     "category": "MIN-EXPLOSIVE-PROCUREMENT", "phase": "EXECUTION",
     "probability": 3, "impactCost": 2, "impactSchedule": 5},
    {"title": "Concrete supply continuity for bridge",
     "description": "Local batching plant capacity may not match peak pour demand for 5.1.7(iii) Class-30 bridge structure concrete. Backup supplier identified but not on contract.",
     "category": "CG-PROCUREMENT-LEAD-TIME", "phase": "EXECUTION",
     "probability": 2, "impactCost": 4, "impactSchedule": 3},
    {"title": "Skilled labour shortage - Steel Fixers",
     "description": "Regional shortage of certified rebar fixers may slow 5.2.6(ii) reinforcement and 13.1.7(ix) barrier rebar activities. Currently 5 deployed; need 8 at peak.",
     "category": "RES-PROCUREMENT-DELAY", "phase": "EXECUTION",
     "probability": 3, "impactCost": 2, "impactSchedule": 3},
    {"title": "Underground utility strike risk",
     "description": "Existing 11kV electrical lines (18.2.1) + water mains in alignment per as-built drawings. Relocation drawings incomplete for chainage 2+800 - 3+200.",
     "category": "HSE-GENERIC", "phase": "EXECUTION",
     "probability": 3, "impactCost": 4, "impactSchedule": 3},
    {"title": "Borrow pit yield variability",
     "description": "Borrow source for 2.4.6(i) embankment shows variable gradation; extra Powerscreen passes may push GSB rate down by 15-20%.",
     "category": "CG-PROCUREMENT-LEAD-TIME", "phase": "EXECUTION",
     "probability": 4, "impactCost": 3, "impactSchedule": 2},
    {"title": "Bridge bearing import customs delay",
     "description": "Elastomeric bearings 400x350x80mm (11.2.6(i)) have 14-week ex-Korea lead time + ~2 weeks customs. Need PO release by week-10 to meet erection sequence.",
     "category": "CG-IMPORTED-EQUIPMENT-CUSTOMS", "phase": "EXECUTION",
     "probability": 3, "impactCost": 2, "impactSchedule": 4},
]

ok = fail = 0
for r in risks:
    body = {
        "projectId": PROJECT_ID,
        "title": r["title"],
        "description": r["description"],
        "category": r["category"],
        "phase": r["phase"],
        "probabilityRating": r["probability"],
        "impactCostRating": r["impactCost"],
        "impactScheduleRating": r["impactSchedule"],
        "status": "IDENTIFIED",
        "identifiedDate": "2026-01-20",
    }
    sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/risks", body)
    if sc in (200, 201):
        ok += 1
        print(f"  + {r['title'][:60]}")
    else:
        fail += 1
        err = resp.get("error", {}).get("message", str(resp.get("error")))[:120] if isinstance(resp.get("error"), dict) else str(resp.get("error"))[:120]
        print(f"  ! {r['title'][:60]} → {sc} {err}")

print(f"\nCreated {ok} risks ({fail} failed)")
