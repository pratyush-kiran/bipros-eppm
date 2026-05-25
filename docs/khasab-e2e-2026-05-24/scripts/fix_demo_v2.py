#!/usr/bin/env python3
"""Phase B of rebuild: BOQ items (real descriptions) + Material Consumption
+ Productivity Norms + EVM + activity status. Run AFTER DPR import completes.
This version uses REAL master sheet descriptions, not synthesized names.
"""
import json
import urllib.request
import urllib.error
import subprocess
import random
from collections import defaultdict
from datetime import date, timedelta

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_BASE = ["env", "PGPASSWORD=bipros_dev", PSQL, "-h", "127.0.0.1", "-U", "bipros", "-d", "bipros", "-A", "-F", "|", "-t", "-c"]
ACT_IDS = json.load(open("/tmp/khasab/activity-ids.json"))
PLAN = json.load(open("/tmp/khasab/activity-plan.json"))
MASTER = json.load(open("/tmp/khasab/activity-master-normalized.json"))


def sql(q, ignore=False):
    out = subprocess.run(PG_BASE + [q], capture_output=True, text=True, timeout=30)
    if out.returncode != 0 and not ignore:
        raise Exception(f"SQL: {out.stderr[:200]}")
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


# Step 1: Update DPR rates (only if DPRs exist)
print("=== Step 1: Update DPR rates to realistic INR ===")
dpr_count = int(sql(f"SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='{PROJECT_ID}'")[0][0])
print(f"  Current DPRs: {dpr_count}")
if dpr_count > 0:
    manpower_rates = {"Helper":500, "Mason":800, "Carpenter":800, "Steel Fixer":900, "Foreman":1200,
                      "Supervisor":1500, "Chargehand":1000, "bankman":700, "Bankman":700}
    equipment_rates = {"Excavator":5000, "Wheel Loader":3000, "Concrete Mixer":2000, "Vibrator":500,
                       "Tipper":2500, "Dumper":4000, "Roller":3500, "Dozer":6000, "Grader":5500,
                       "Bob Cat":2500, "Water Tanker":2000, "Crane":8000, "Mobile Crane":9000,
                       "Truck":4000, "crusher":5000, "Cruhser":5000, "Powerscreen":4000,
                       "Air Compressor":1500, "Hiab":6000, "hand drilling":800, "Asphalt cutler":1200,
                       "baby Roller":2000, "Tower light":500, "Back Hoe":4500, "Plate Compactor":1000}
    for role, rate in manpower_rates.items():
        sql(f"""UPDATE project.dpr_manpower SET unit_rate={rate}, unit_rate_basis='DAY', line_cost=COALESCE(nos,1)*{rate}
WHERE trade='{role}' AND dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{PROJECT_ID}')""", ignore=True)
    for eq, rate in equipment_rates.items():
        sql(f"""UPDATE project.dpr_equipment SET unit_rate={rate}, unit_rate_basis='DAY', line_cost=COALESCE(nos,1)*{rate}
WHERE equipment_type='{eq}' AND dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{PROJECT_ID}')""", ignore=True)
    print(f"  Rates updated")


# Step 2: Activity progress + status from DPR data
print("\n=== Step 2: Update activity progress + status ===")
for code, aid in ACT_IDS.items():
    p = PLAN.get(code, {})
    dpr_for_act = int(sql(f"SELECT COUNT(*) FROM project.daily_progress_reports WHERE activity_id='{aid}'")[0][0])
    pct = min(round(dpr_for_act / max(p.get("duration_days", 60), 1) * 100, 1), 95.0)
    if dpr_for_act > 80:
        pct = max(pct, 70)
    status = "IN_PROGRESS" if pct > 0 else "NOT_STARTED"
    sql(f"""UPDATE activity.activities SET
  percent_complete={pct},
  duration_percent_complete={pct},
  units_percent_complete={pct},
  status='{status}',
  actual_start_date='2026-01-24'
WHERE id='{aid}'""")
# Mark a few as COMPLETED
sql(f"""UPDATE activity.activities SET status='COMPLETED', percent_complete=100,
duration_percent_complete=100, units_percent_complete=100, actual_finish_date=planned_finish_date
WHERE project_id='{PROJECT_ID}' AND code IN ('1', '1.1', '1.2')""")
print(f"  Updated progress on {len(ACT_IDS)} activities")


# Step 3: EVM
print("\n=== Step 3: EVM row ===")
total_cost = float(sql(f"""
SELECT COALESCE((SELECT SUM(line_cost) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='{PROJECT_ID}'),0)
+ COALESCE((SELECT SUM(line_cost) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='{PROJECT_ID}'),0)
""")[0][0])
sql(f"""DELETE FROM evm.evm_calculations WHERE project_id='{PROJECT_ID}'""", ignore=True)
sql(f"""
INSERT INTO evm.evm_calculations (id, created_at, updated_at, project_id, data_date,
  planned_value, earned_value, actual_cost, budget_at_completion,
  schedule_variance, cost_variance, schedule_performance_index, cost_performance_index,
  estimate_at_completion, estimate_to_complete, variance_at_completion)
VALUES (gen_random_uuid(), now(), now(), '{PROJECT_ID}', '2026-03-31',
  35000000, {int(total_cost)}, {int(total_cost)}, 50000000,
  {int(total_cost) - 35000000}, 0, {round(int(total_cost) / 35000000, 4) if total_cost else 0}, 1.0,
  50000000, {50000000 - int(total_cost)}, 0)
""", ignore=True)
print(f"  EVM inserted with BAC=₹5 Cr, AC=₹{total_cost:,.0f}")


# Step 4: BOQ items from MASTER sheet (with REAL descriptions)
print("\n=== Step 4: BOQ items from master sheet ===")
sql(f"DELETE FROM project.boq_items WHERE project_id='{PROJECT_ID}'", ignore=True)
# Take 30 items from master that are relevant to road/bridge work
master_codes_for_boq = [k for k in MASTER.keys() if any(k.startswith(p) for p in ["1.3", "2.3", "2.4", "2.6", "2.7", "5.1", "5.2", "5.10", "9.1", "13.1", "18.3"])][:30]
boq_payload = []
for code in master_codes_for_boq:
    m = MASTER[code]
    qty = round(100 + (hash(code) % 500), 0)
    rate = round(50 + (hash(code + "r") % 300), 0)
    boq_payload.append({
        "itemNo": code,
        "description": m["name"][:200],
        "unit": m["unit"][:20],
        "boqQty": float(qty),
        "boqRate": float(rate),
        "budgetedRate": float(round(rate * 0.9, 0)),
        "qtyExecutedToDate": float(round(qty * 0.55, 2)),
        "actualRate": float(round(rate * 0.95, 0)),
        "chapter": code.split(".")[0],
        "status": "IN_PROGRESS",
    })
sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/boq/bulk", boq_payload)
if sc in (200, 201):
    print(f"  Created {len(resp.get('data', []))} BOQ items")
else:
    msg = resp.get("error", {}).get("message", "?") if isinstance(resp.get("error"), dict) else str(resp.get("error"))
    print(f"  BOQ FAIL {sc}: {msg[:200]}")


# Step 5: Material Consumption Logs (synthetic but realistic for road project)
print("\n=== Step 5: Material Consumption Logs ===")
random.seed(42)
materials = [
    ("Cement OPC 43", "MT", 80, 5), ("Steel Fe500", "MT", 25, 3),
    ("Aggregate 20mm", "cum", 250, 2), ("Aggregate 10mm", "cum", 180, 2),
    ("Sand", "cum", 120, 3), ("Bitumen VG30", "MT", 15, 4),
    ("GSB", "cum", 80, 1),
]
admin_id = sql("SELECT id::text FROM public.users WHERE username='admin' LIMIT 1")[0][0]
ravi_id = sql("SELECT id::text FROM public.users WHERE username='ravi' LIMIT 1")[0][0]
mcl_count = 0
for code, aid in list(ACT_IDS.items())[:15]:
    a = sql(f"SELECT wbs_node_id::text FROM activity.activities WHERE id='{aid}'")
    wbs_id = a[0][0] if a else None
    for _ in range(3):
        log_date = date(2026, random.randint(1, 3), random.randint(1, 28))
        mat = random.choice(materials)
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/material-consumption", {
            "logDate": log_date.isoformat(),
            "materialName": mat[0], "unit": mat[1],
            "openingStock": float(mat[2]),
            "received": float(mat[2] + random.randint(0, 50)),
            "consumed": float(mat[2] - random.randint(10, 30)),
            "wastagePercent": float(mat[3]),
            "activityId": aid, "wbsNodeId": wbs_id,
            "issuedBy": "Site Store", "receivedBy": PLAN[code]["name"][:50],
            "issuedByUserId": admin_id, "receivedByUserId": ravi_id,
            "enteredByRole": "ADMIN", "remarks": f"For {code}",
        })
        if sc in (200, 201):
            mcl_count += 1
print(f"  Created {mcl_count} MCLs")


# Step 6: Productivity norms
print("\n=== Step 6: Productivity norms ===")
sql(f"""DELETE FROM resource.productivity_norms WHERE work_activity_id IN
(SELECT id FROM resource.work_activities WHERE code IN (
  SELECT REGEXP_REPLACE(code, '[^A-Za-z0-9._()-]', '_', 'g') FROM activity.activities WHERE project_id='{PROJECT_ID}'
))""", ignore=True)

wa_rows = sql(f"""SELECT id::text, code, default_unit FROM resource.work_activities WHERE id IN
(SELECT work_activity_id FROM activity.activities WHERE project_id='{PROJECT_ID}' AND work_activity_id IS NOT NULL)""")
unit_norm = {"cum":2, "sqm":5, "m":10, "kg":100, "MT":1, "nos":5, "km":0.5, "LS":1, "month":1}
ok = 0
for wa in wa_rows:
    wa_id, code, unit = wa[0], wa[1], (wa[2] or "nos").lower()
    base = unit_norm.get(unit, 5)
    for nt, body in [
        ("MANPOWER", {"normType":"MANPOWER", "workActivityId":wa_id, "unit":unit,
                      "outputPerManPerDay":float(base), "outputPerDay":float(base*5),
                      "crewSize":5, "workingHoursPerDay":8.0, "remarks":f"For {code}"}),
        ("EQUIPMENT", {"normType":"EQUIPMENT", "workActivityId":wa_id, "unit":unit,
                       "outputPerHour":float(base*2), "outputPerDay":float(base*16),
                       "workingHoursPerDay":8.0, "fuelLitresPerHour":8.0, "remarks":f"For {code}"}),
    ]:
        sc, _ = http("POST", "/v1/productivity-norms", body)
        if sc in (200, 201):
            ok += 1
print(f"  Created {ok} productivity norms")


# Step 7: DBS recompute per-day
print("\n=== Step 7: DBS recompute ===")
d = date(2026, 1, 24)
ok = 0
while d <= date(2026, 3, 29):
    sc, _ = http("POST", f"/v1/projects/{PROJECT_ID}/dbs/recompute?date={d.isoformat()}", timeout=20)
    if sc in (200, 201):
        ok += 1
    d += timedelta(days=1)
print(f"  Recomputed {ok}/65 days")


print("\n=== ALL DONE ===")
print(f"Project: {PROJECT_ID}")
final = sql(f"""
SELECT 'dprs' m, COUNT(*) FROM project.daily_progress_reports WHERE project_id='{PROJECT_ID}'
UNION ALL SELECT 'role_assignments', COUNT(*) FROM resource.resource_assignments WHERE activity_id IN (SELECT id FROM activity.activities WHERE project_id='{PROJECT_ID}')
UNION ALL SELECT 'boq', COUNT(*) FROM project.boq_items WHERE project_id='{PROJECT_ID}'
UNION ALL SELECT 'mcl', COUNT(*) FROM resource.material_consumption_logs WHERE project_id='{PROJECT_ID}'
UNION ALL SELECT 'norms', COUNT(*) FROM resource.productivity_norms WHERE work_activity_id IN (SELECT work_activity_id FROM activity.activities WHERE project_id='{PROJECT_ID}')
UNION ALL SELECT 'avg_pct', ROUND(AVG(percent_complete)::numeric, 1) FROM activity.activities WHERE project_id='{PROJECT_ID}'
UNION ALL SELECT 'mp_cost', ROUND(COALESCE(SUM(line_cost),0)::numeric,2) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='{PROJECT_ID}'
UNION ALL SELECT 'eq_cost', ROUND(COALESCE(SUM(line_cost),0)::numeric,2) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='{PROJECT_ID}'
ORDER BY m
""")
for r in final:
    print(f"  {r[0]:30} {r[1]:>15}")
