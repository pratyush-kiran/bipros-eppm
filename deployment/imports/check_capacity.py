import os
#!/usr/bin/env python3
import json, urllib.request

TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()
PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
url = f"http://localhost:8080/v1/reports/capacity-utilization?projectId={PROJECT_ID}&fromDate=2026-01-24&toDate=2026-03-29&groupBy=RESOURCE_TYPE"
req = urllib.request.Request(url)
req.add_header("Authorization", f"Bearer {TOKEN}")
with urllib.request.urlopen(req, timeout=30) as r:
    d = json.load(r)["data"]
print(f"Range: {d['fromDate']} to {d['toDate']}, workDays={d.get('workDays')}")
print()
print("=== Manpower utilization (after tuning) ===")
print(f"{'Role':<15} {'Qty':>10} {'BudgetDays':>11} {'ActualDays':>11} {'Util %':>8}  {'normSource':<10}")
for r in d.get("manpower", {}).get("rows", []):
    c = r.get("cumulative", {})
    print(f"{r['roleName']:<15} {c.get('qty',0):>10.0f} {c.get('budgetDays',0):>11.1f} {c.get('actualDays',0):>11.0f} {c.get('utilizationPct',0):>7.1f}%  {r.get('normSource','—'):<10}")
print()
print("=== Equipment utilization ===")
print(f"{'Equipment':<25} {'Qty':>10} {'BudgetDays':>11} {'ActualDays':>11} {'Util %':>8}")
for r in d.get("equipment", {}).get("rows", [])[:15]:
    c = r.get("cumulative", {})
    print(f"{r['roleName']:<25} {c.get('qty',0):>10.0f} {c.get('budgetDays',0):>11.1f} {c.get('actualDays',0):>11.0f} {c.get('utilizationPct',0):>7.1f}%")
