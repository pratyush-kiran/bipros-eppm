#!/usr/bin/env python3
"""Add role-assignments for ALL activities using fuzzy match + create-on-fly.
DPR role names like 'Helper' / 'Excavator' map to variant table rows like
'Helper / Handyman' / '20-Ton Hydraulic Excavator', or get created if no fuzzy match.
"""
import json
import urllib.request
import urllib.error
import subprocess
from collections import defaultdict

BASE = "http://localhost:8080"
TOKEN = open("/tmp/admin-token.txt").read().strip()
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
PLAN = json.load(open("/tmp/khasab/activity-plan.json"))
ACT_IDS = json.load(open("/tmp/khasab/activity-ids.json"))

PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_BASE = ["env", "PGPASSWORD=bipros_dev", PSQL, "-h", "127.0.0.1", "-U", "bipros", "-d", "bipros", "-A", "-F", "|", "-t", "-c"]


def sql(q):
    out = subprocess.run(PG_BASE + [q], capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        return None
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


# Fuzzy maps: DPR role-name → preferred master role-name
MP_MAP = {
    "Helper": "Helper / Handyman",
    "Mason": "Mason",
    "Carpenter": "Carpenter",
    "Foreman": "Foreman",
    "Steel Fixer": "Rebar Fixer",
    "Supervisor": "Supervisor",
    "Chargehand": "Foreman",
    "bankman": "Skilled Labour",
    "Bankman": "Skilled Labour",
}
EQ_MAP = {
    "Excavator": "20-Ton Hydraulic Excavator",
    "Wheel Loader": "Wheel Loader 3CY",
    "Dozer": "Bulldozer D6",
    "Grader": "Motor Grader",
    "Mobile Crane": "50-Ton Mobile Crane",
    "Crane": "50-Ton Mobile Crane",
    "Concrete Mixer": "Truck-Mounted Concrete Mixer",
    "Vibrator": "Vibratory Compactor",
    "Air Compressor": "200kVA Generator Set",
    # The rest will get NEW variants created
}


EQUIP_TYPE_ID = "709f41b3-4aa3-462e-9fc9-66deff8da325"
import re as _re
def _slug(s, n=40):
    return _re.sub(r'[^A-Z0-9]+', '-', s.upper())[:n].strip('-')

def get_or_create_equipment_variant(eq_name):
    """For equipment names not in EQ_MAP, create a new resource_role + equipment_role_variant.
    Returns (role_id, equipment_role_variant_id)."""
    mapped = EQ_MAP.get(eq_name, eq_name)
    # Try to find existing
    rows = sql(f"""
SELECT rr.id::text, erv.id::text
FROM resource.resource_roles rr
JOIN resource.equipment_role_variants erv ON erv.role_id = rr.id
WHERE rr.name = '{mapped.replace("'","''")}'
LIMIT 1
""")
    if rows:
        return rows[0][0], rows[0][1]

    # Create new role + variant for this equipment
    name_safe = eq_name.replace("'", "''")[:80]
    code = "EQ-" + _slug(eq_name, 40)
    # 1. Create resource_role (resource_type_id = EQUIPMENT)
    role_res = sql(f"""
INSERT INTO resource.resource_roles (id, created_at, updated_at, active, code, name, sort_order, resource_type_id, productivity_unit)
VALUES (gen_random_uuid(), now(), now(), true, '{code}', '{name_safe}', 100, '{EQUIP_TYPE_ID}', 'DAY')
ON CONFLICT (code) DO UPDATE SET updated_at = now()
RETURNING id::text
""")
    if not role_res:
        return None, None
    role_id = role_res[0][0]
    # 2. Create equipment_role_variant
    variant_res = sql(f"""
INSERT INTO resource.equipment_role_variants (id, created_at, updated_at, active, role_id, make, model, rate, unit)
VALUES (gen_random_uuid(), now(), now(), true, '{role_id}', 'Generic', '{name_safe}', 5000, 'DAY')
RETURNING id::text
""")
    if not variant_res:
        return role_id, None
    return role_id, variant_res[0][0]


def get_manpower_variant(role_name):
    """Lookup manpower role + rate using fuzzy map."""
    mapped = MP_MAP.get(role_name, role_name)
    rows = sql(f"""
SELECT rr.id::text, mrr.id::text
FROM resource.resource_roles rr
JOIN resource.manpower_role_rates mrr ON mrr.role_id = rr.id
WHERE rr.name = '{mapped.replace("'","''")}'
LIMIT 1
""")
    if rows:
        return rows[0][0], rows[0][1]
    return None, None


# Unlock all activities (role-assignments require DRAFT status)
print("Unlocking activities...")
for aid in ACT_IDS.values():
    http("POST", f"/v1/projects/{PROJECT_ID}/activities/{aid}/unlock")

# Wipe existing role assignments for the project (start fresh)
print("Clearing existing role-assignments for project...")
sql(f"DELETE FROM resource.role_assignments WHERE activity_id IN (SELECT id FROM activity.activities WHERE project_id = '{PROJECT_ID}')")

# Now POST role-assignments for each activity
total = 0
fails = defaultdict(int)
for code, p in PLAN.items():
    aid = ACT_IDS.get(code)
    if not aid:
        continue

    # Manpower
    for m in p["manpower_demand"]:
        role_id, variant_id = get_manpower_variant(m["trade"])
        if not role_id or not variant_id:
            fails[f"mp_no_match:{m['trade']}"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": role_id,
            "manpowerRoleRateId": variant_id,
            "headcount": m["count"],
            "duration": m["duration_days"],
            "rateType": "STANDARD",
        })
        if sc in (200, 201):
            total += 1
        else:
            err_msg = resp.get("error", {}).get("message", "?") if isinstance(resp.get("error"), dict) else str(resp.get("error"))
            fails[f"mp_post_{sc}"] += 1
            if fails[f"mp_post_{sc}"] <= 2:
                print(f"  mp fail {sc}: {err_msg[:120]}")

    # Equipment (with create-on-fly)
    for e in p["equipment_demand"]:
        role_id, variant_id = get_or_create_equipment_variant(e["name"])
        if not role_id or not variant_id:
            fails[f"eq_no_match:{e['name']}"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": role_id,
            "equipmentRoleVariantId": variant_id,
            "headcount": e["count"],
            "duration": e["duration_days"],
            "rateType": "STANDARD",
        })
        if sc in (200, 201):
            total += 1
        else:
            err_msg = resp.get("error", {}).get("message", "?") if isinstance(resp.get("error"), dict) else str(resp.get("error"))
            fails[f"eq_post_{sc}"] += 1
            if fails[f"eq_post_{sc}"] <= 2:
                print(f"  eq fail {sc}: {err_msg[:120]}")

    # Material (synthetic — for concrete activities add Cement + Steel + Aggregate)
    if any(c in code for c in ("3.2", "3.3", "5.1", "5.2", "5.10")):
        for mat_name, qty in [("Ordinary Portland Cement", 50),
                              ("Reinforcement Steel Bar", 20),
                              ("Coarse Aggregate 20mm", 200)]:
            rows = sql(f"""
SELECT rr.id::text, mrv.id::text
FROM resource.resource_roles rr
JOIN resource.material_role_variants mrv ON mrv.role_id = rr.id
WHERE rr.name = '{mat_name}' LIMIT 1
""")
            if not rows:
                fails[f"mat_no_match:{mat_name}"] += 1
                continue
            sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
                "activityId": aid,
                "roleId": rows[0][0],
                "materialRoleVariantId": rows[0][1],
                "quantity": qty,
                "rateType": "STANDARD",
            })
            if sc in (200, 201):
                total += 1
            else:
                fails[f"mat_post_{sc}"] += 1

print(f"\n=== Total role-assignments created: {total} ===")
if fails:
    print(f"Failures/skips:")
    for k, n in sorted(fails.items(), key=lambda x: -x[1]):
        print(f"  {k}: {n}")
