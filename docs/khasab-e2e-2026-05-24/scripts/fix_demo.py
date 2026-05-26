import os
#!/usr/bin/env python3
"""Make KHASAB-2026 demo-ready in one pass:
1. Create work_activity master per activity (33 entries)
2. SQL-update activities: link work_activity, set dates, supervisor, duration, percent_complete, status
3. SQL-update DPR rates to use master rates (so cost lines are realistic)
4. SQL-insert EVM row with BAC
5. API: create BOQ items
6. API: create Material Consumption Logs
7. API: create Productivity Norms
8. Run schedule
9. Recompute DBS per-day (since range API has bug)
"""
import json
import urllib.request
import urllib.error
import subprocess
from collections import Counter
from datetime import date, datetime, timedelta

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", PSQL, "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"), "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"), "-A", "-F", "|", "-t", "-c"]


def sql(q):
    full = q.replace("$PROJECT_ID", PROJECT_ID)
    out = subprocess.run(PG_BASE + [full], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise Exception(f"SQL ERROR: {out.stderr}\nQUERY: {full[:200]}")
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]


def http(method, path, body=None, timeout=60):
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


# ====================================================================
# Step 1: Gather per-activity DPR aggregates + WBS leaf info
# ====================================================================
print("=== Step 1: Gather per-activity DPR aggregates ===")
rows = sql("""
SELECT
  a.code, a.id::text, a.name, a.wbs_node_id::text,
  COALESCE(MIN(d.report_date)::text, '2026-01-24') AS first_dpr,
  COALESCE(MAX(d.report_date)::text, '2026-03-29') AS last_dpr,
  COUNT(d.id) AS dpr_count,
  COALESCE(SUM(d.qty_executed), 0) AS qty_sum,
  MODE() WITHIN GROUP (ORDER BY d.supervisor_user_id)::text AS top_sup_id
FROM activity.activities a
LEFT JOIN project.daily_progress_reports d
  ON d.activity_id = a.id AND d.project_id = '$PROJECT_ID'
WHERE a.project_id = '$PROJECT_ID'
GROUP BY a.code, a.id, a.name, a.wbs_node_id
ORDER BY a.code
""")
activities = []
for r in rows:
    activities.append({
        "code": r[0], "id": r[1], "name": r[2], "wbs_node_id": r[3],
        "first_dpr": r[4], "last_dpr": r[5],
        "dpr_count": int(r[6]), "qty_sum": float(r[7]),
        "top_sup_id": r[8] if r[8] else None,
    })
print(f"  Loaded {len(activities)} activities")

# Get modal unit per activity from DPRs
unit_per_code = {}
unit_rows = sql("""
SELECT activity_id::text, unit, COUNT(*) FROM project.daily_progress_reports
WHERE project_id='$PROJECT_ID' AND unit IS NOT NULL AND unit != ''
GROUP BY activity_id, unit ORDER BY activity_id, COUNT(*) DESC
""")
seen_acts = set()
for r in unit_rows:
    if r[0] not in seen_acts:
        unit_per_code[r[0]] = r[1]
        seen_acts.add(r[0])

UNIT_MAP = {"cu.m.": "cum", "sq.m.": "sqm", "lin.m.": "m", "kg.": "kg", "t.": "MT", "Nos": "nos", "Sqm": "sqm", "Km": "km"}

for a in activities:
    raw_unit = unit_per_code.get(a["id"], "nos")
    a["unit"] = UNIT_MAP.get(raw_unit, raw_unit)


# ====================================================================
# Step 2: Create work_activities + link via SQL
# ====================================================================
print("\n=== Step 2: Create work_activities + link activities ===")
import re

# Get role ids for use in productivity norms
role_rows = sql("SELECT name, id::text FROM resource.resource_roles ORDER BY name")
role_id_by_name = {r[0]: r[1] for r in role_rows}

# Existing work_activities
existing_wa = {r[0]: r[1] for r in sql("SELECT code, id::text FROM resource.work_activities")}

wa_id_by_act = {}
for a in activities:
    safe_code = re.sub(r'[^A-Z0-9_]', '_', f"KHASAB_{a['code']}".upper())[:50]
    wa_id = existing_wa.get(safe_code)
    if not wa_id:
        # INSERT via SQL (faster than API for 33)
        name = f"Khasab {a['code']} - {a['name']}".replace("'", "''")[:150]
        unit = a["unit"][:20]
        ins = sql(f"""
INSERT INTO resource.work_activities (id, created_at, updated_at, code, name, default_unit, discipline, active, norm_combination, sort_order)
VALUES (gen_random_uuid(), now(), now(), '{safe_code}', '{name}', '{unit}', 'Civil', true, 'SERIES', 100)
RETURNING id::text
""")
        wa_id = ins[0][0]
        existing_wa[safe_code] = wa_id
    wa_id_by_act[a["id"]] = wa_id
print(f"  work_activities ensured for all {len(activities)} activities")


# ====================================================================
# Step 3: SQL UPDATE activities: link, dates, supervisor, duration, % complete, status
# ====================================================================
print("\n=== Step 3: SQL-update activities (dates, supervisor, work_activity, % complete) ===")
for a in activities:
    try:
        d1 = date.fromisoformat(a["first_dpr"])
        d2 = date.fromisoformat(a["last_dpr"])
    except Exception:
        d1, d2 = date(2026, 1, 24), date(2026, 3, 29)

    dur = max((d2 - d1).days + 1, 30)
    # % complete based on dpr_count vs typical workload (cap 90% so not all "done")
    pct = min(round(a["dpr_count"] / 80.0 * 100, 1), 90.0)
    if pct >= 90:
        status = "IN_PROGRESS"
    elif pct >= 50:
        status = "IN_PROGRESS"
    elif pct > 0:
        status = "IN_PROGRESS"
    else:
        status = "NOT_STARTED"
    # Some activities should be COMPLETED for demo flavor
    if a["dpr_count"] > 100:
        status = "IN_PROGRESS"
        pct = max(pct, 65.0)
    actual_finish_sql = "NULL"

    wa_id = wa_id_by_act[a["id"]]
    sup_clause = f"'{a['top_sup_id']}'" if a["top_sup_id"] else "NULL"

    sql(f"""
UPDATE activity.activities SET
  work_activity_id = '{wa_id}',
  original_duration = {dur},
  remaining_duration = {max(int(dur * (100-pct)/100), 1)},
  planned_start_date = '{d1.isoformat()}',
  planned_finish_date = '{d2.isoformat()}',
  early_start_date = '{d1.isoformat()}',
  early_finish_date = '{d2.isoformat()}',
  late_start_date = '{d1.isoformat()}',
  late_finish_date = '{d2.isoformat()}',
  primary_constraint_type = 'START_ON',
  primary_constraint_date = '{d1.isoformat()}',
  duration_type = 'FIXED_DURATION_AND_UNITS',
  percent_complete_type = 'DURATION',
  percent_complete = {pct},
  duration_percent_complete = {pct},
  units_percent_complete = {pct},
  status = '{status}',
  actual_start_date = '{d1.isoformat()}',
  supervisor_user_id = {sup_clause},
  edit_status = 'DRAFT',
  total_float = 0,
  free_float = 0,
  is_critical = false,
  updated_at = now()
WHERE id = '{a['id']}'
""")
# Mark a few "complete" for variety
sql("""
UPDATE activity.activities SET
  status = 'COMPLETED',
  percent_complete = 100,
  duration_percent_complete = 100,
  units_percent_complete = 100,
  actual_finish_date = planned_finish_date
WHERE project_id = '$PROJECT_ID' AND code IN ('1', '1.1', '1.2')
""")
print("  Updated all 33 activities + marked 3 as COMPLETED")


# ====================================================================
# Step 4: SQL UPDATE DPR rates to use realistic master rates
# ====================================================================
print("\n=== Step 4: SQL-update DPR rates to realistic INR values ===")
# Master daily rates we want (per spec)
manpower_rates = {
    "Helper": 500, "Mason": 800, "Carpenter": 800, "Steel Fixer": 900,
    "Rigger": 900, "Scaffolder": 900, "Bankman": 700, "bankman": 700,
    "Chargehand": 1000, "Foreman": 1200, "Supervisor": 1500,
}
equipment_rates = {
    "Excavator": 5000, "Wheel Loader": 3000, "Concrete Mixer": 2000,
    "Vibrator": 500, "Tipper": 2500, "Dumper": 4000, "Roller": 3500,
    "Dozer": 6000, "Grader": 5500, "Bob Cat": 2500, "Water Tanker": 2000,
    "Crane": 8000, "Mobile Crane": 9000, "Truck": 4000,
    "crusher": 5000, "Cruhser": 5000, "Powerscreen": 4000,
    "Air Compressor": 1500, "Hiab": 6000, "hand drilling": 800,
    "Asphalt cutler": 1200, "baby Roller": 2000, "Tower light": 500,
    "Back Hoe": 4500, "Plate Compactor": 1000,
}

for role, rate in manpower_rates.items():
    sql(f"""
UPDATE project.dpr_manpower SET
  unit_rate = {rate},
  unit_rate_basis = 'DAY',
  line_cost = COALESCE(nos, 1) * {rate}
WHERE trade = '{role}'
  AND dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id = '$PROJECT_ID')
""")
print(f"  Updated manpower rates for {len(manpower_rates)} trades")

for eq, rate in equipment_rates.items():
    sql(f"""
UPDATE project.dpr_equipment SET
  unit_rate = {rate},
  unit_rate_basis = 'DAY',
  line_cost = COALESCE(nos, 1) * {rate}
WHERE equipment_type = '{eq}'
  AND dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id = '$PROJECT_ID')
""")
print(f"  Updated equipment rates for {len(equipment_rates)} types")

cost_check = sql("""
SELECT
  COALESCE(SUM(line_cost),0)::numeric(15,2) FROM project.dpr_manpower m
JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID'
""")
eq_cost_check = sql("""
SELECT
  COALESCE(SUM(line_cost),0)::numeric(15,2) FROM project.dpr_equipment e
JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID'
""")
total_cost = float(cost_check[0][0]) + float(eq_cost_check[0][0])
print(f"  New total cost: ₹{total_cost:,.2f} (mp ₹{cost_check[0][0]} + eq ₹{eq_cost_check[0][0]})")


# ====================================================================
# Step 5: Insert EVM row with BAC=50M
# ====================================================================
print("\n=== Step 5: Insert EVM row with BAC ===")
sql(f"""
INSERT INTO evm.evm_calculations (id, created_at, updated_at, project_id, data_date,
  planned_value, earned_value, actual_cost, budget_at_completion,
  schedule_variance, cost_variance, schedule_performance_index, cost_performance_index,
  estimate_at_completion, estimate_to_complete, variance_at_completion)
VALUES (gen_random_uuid(), now(), now(), '$PROJECT_ID', '2026-03-31',
  35000000, {int(total_cost)}, {int(total_cost)}, 50000000,
  {int(total_cost) - 35000000}, 0, {round(int(total_cost) / 35000000, 4) if total_cost else 0}, 1.0,
  50000000, {50000000 - int(total_cost)}, 0)
ON CONFLICT DO NOTHING
""")
print(f"  EVM row inserted with BAC=₹5 Cr")


# ====================================================================
# Step 6: Create ~20 BOQ items via API (one per major activity)
# ====================================================================
print("\n=== Step 6: Create BOQ items ===")
boq_items = []
for a in activities[:20]:  # top 20 by code
    pct = min(round(a["dpr_count"] / 80.0 * 100, 1), 90.0)
    boq_qty = round(max(a["qty_sum"] / max(pct, 1) * 100, 100), 0)
    boq_rate = round(50 + (hash(a["code"]) % 200), 0)  # 50-250 INR per unit
    qty_exec = round(a["qty_sum"], 2) if a["qty_sum"] > 1 else round(boq_qty * pct / 100, 2)
    boq_items.append({
        "itemNo": f"BOQ-{a['code'].replace(' ', '').replace('(', '_').replace(')', '_')}",
        "description": f"{a['name']} works",
        "unit": a["unit"][:20],
        "boqQty": float(boq_qty),
        "boqRate": float(boq_rate),
        "budgetedRate": float(round(boq_rate * 0.9, 0)),
        "qtyExecutedToDate": float(qty_exec),
        "actualRate": float(round(boq_rate * 0.95, 0)),
        "wbsNodeId": a["wbs_node_id"],
        "chapter": a["code"].split(".")[0] + ".0",
        "status": "IN_PROGRESS",
    })
sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/boq/bulk", boq_items)
if sc in (200, 201):
    created = len(resp.get("data", []))
    print(f"  Created {created} BOQ items")
else:
    print(f"  BOQ FAIL {sc}: {resp.get('error', {}).get('message', '?')[:200]}")


# ====================================================================
# Step 7: Create ~40 Material Consumption Logs (synthetic but realistic)
# ====================================================================
print("\n=== Step 7: Create Material Consumption Logs ===")
import random
random.seed(42)
# Pick ~20 dates spread across Jan-Mar with materials
materials = [
    ("Cement OPC 43", "MT", 50, 100, 80, 5),
    ("Steel Fe500", "MT", 20, 30, 25, 3),
    ("Aggregate 20mm", "cum", 200, 300, 250, 2),
    ("Aggregate 10mm", "cum", 150, 200, 180, 2),
    ("Sand", "cum", 100, 150, 120, 3),
    ("Bitumen VG30", "MT", 10, 20, 15, 4),
    ("GSB", "cum", 50, 100, 80, 1),
    ("Water", "litre", 5000, 10000, 8000, 0),
]
mcl_payloads = []
admin_id_rows = sql("SELECT id::text FROM public.users WHERE username='admin' LIMIT 1")
admin_id = admin_id_rows[0][0] if admin_id_rows else None
ravi_id_rows = sql("SELECT id::text FROM public.users WHERE username='ravi' LIMIT 1")
ravi_id = ravi_id_rows[0][0] if ravi_id_rows else admin_id

# Generate ~40 entries across activities + dates
sample_activities = [a for a in activities if "3." in a["code"] or "2." in a["code"]][:10]
for a in sample_activities:
    try:
        d1 = date.fromisoformat(a["first_dpr"])
        d2 = date.fromisoformat(a["last_dpr"])
    except Exception:
        d1, d2 = date(2026, 1, 24), date(2026, 3, 29)
    days_span = (d2 - d1).days
    for i in range(4):  # 4 MCLs per activity
        log_date = d1 + timedelta(days=random.randint(0, max(days_span, 1)))
        mat = random.choice(materials)
        consumed = mat[3] + random.randint(-mat[3]//4, mat[3]//4)
        mcl_payloads.append({
            "logDate": log_date.isoformat(),
            "materialName": mat[0],
            "unit": mat[1],
            "openingStock": float(mat[2]),
            "received": float(mat[3] + random.randint(0, 50)),
            "consumed": float(consumed),
            "wastagePercent": float(mat[5]),
            "activityId": a["id"],
            "wbsNodeId": a["wbs_node_id"],
            "issuedBy": "Site Store",
            "receivedBy": a["name"],
            "issuedByUserId": admin_id,
            "receivedByUserId": ravi_id,
            "enteredByRole": "ADMIN",
            "remarks": f"Daily issue for {a['code']}",
        })

ok = fail = 0
for payload in mcl_payloads:
    sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/material-consumption", payload)
    if sc in (200, 201):
        ok += 1
    else:
        fail += 1
        if fail < 3:
            print(f"  MCL fail {sc}: {resp.get('error', {}).get('message', '?')[:150]}")
print(f"  Created {ok} MCLs ({fail} failed)")


# ====================================================================
# Step 8: Create Productivity Norms per work_activity
# ====================================================================
print("\n=== Step 8: Create Productivity Norms ===")
norms = []
for a in activities:
    wa_id = wa_id_by_act[a["id"]]
    # Norm per work-activity, generic role-scoped
    unit = a["unit"]
    base_output = {"cum": 2, "sqm": 5, "m": 10, "kg": 100, "MT": 1, "nos": 5}.get(unit, 5)
    norms.append({
        "normType": "MANPOWER",
        "workActivityId": wa_id,
        "unit": unit,
        "outputPerManPerDay": float(base_output),
        "outputPerDay": float(base_output * 5),  # crew of 5
        "crewSize": 5,
        "workingHoursPerDay": 8.0,
        "remarks": f"Manpower norm for Khasab {a['code']}",
    })
    # Also add EQUIPMENT norm
    norms.append({
        "normType": "EQUIPMENT",
        "workActivityId": wa_id,
        "unit": unit,
        "outputPerHour": float(base_output * 2),
        "outputPerDay": float(base_output * 16),
        "workingHoursPerDay": 8.0,
        "fuelLitresPerHour": 8.0,
        "remarks": f"Equipment norm for Khasab {a['code']}",
    })
ok = fail = 0
for n in norms:
    sc, resp = http("POST", "/v1/productivity-norms", n)
    if sc in (200, 201):
        ok += 1
    else:
        fail += 1
        if fail < 3:
            print(f"  Norm fail {sc}: {resp.get('error', {}).get('message', '?')[:150]}")
print(f"  Created {ok} norms ({fail} failed)")


# ====================================================================
# Step 9: DBS recompute (per-day workaround since range has bug)
# ====================================================================
print("\n=== Step 9: DBS recompute per-day ===")
ok = fail = 0
d = date(2026, 1, 24)
while d <= date(2026, 3, 29):
    sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/dbs/recompute?date={d.isoformat()}", timeout=30)
    if sc in (200, 201):
        ok += 1
    else:
        fail += 1
    d += timedelta(days=1)
print(f"  Recomputed {ok}/{ok+fail} days")


# ====================================================================
# Step 10: Re-lock all activities
# ====================================================================
print("\n=== Step 10: Re-lock all activities ===")
sql("UPDATE activity.activities SET edit_status='LOCKED' WHERE project_id='$PROJECT_ID'")
print("  All 33 activities locked")

print("\n=== ALL FIXES COMPLETE ===")
