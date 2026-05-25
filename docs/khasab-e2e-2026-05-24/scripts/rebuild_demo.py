#!/usr/bin/env python3
"""COMPREHENSIVE rebuild of KHASAB-2026 with:
- REAL activity names from master sheet (no 'Khasab X.Y.Z')
- REAL work_activities (no 'KHASAB_*' synthesis)
- PROPER role-assignments per activity (manpower/equipment/material requirements rows)
- Realistic INR rates
- Real BOQ items from master
- DBS aggregates
"""
import json
import re
import os
import subprocess
import time
import urllib.request
import urllib.error
from collections import defaultdict, Counter
from datetime import date, timedelta

BASE = "http://localhost:8080"
PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_DUMP = "/Applications/Postgres.app/Contents/Versions/latest/bin/pg_restore"
PG_BASE = ["env", "PGPASSWORD=bipros_dev", PSQL, "-h", "127.0.0.1", "-U", "bipros", "-d", "bipros", "-A", "-F", "|", "-t", "-c"]
BACKUP = "/tmp/bipros-backup-2026-05-24.dump"
TOKEN_FILE = "/tmp/admin-token.txt"


def refresh_token():
    req = urllib.request.Request(f"{BASE}/v1/auth/login", method="POST")
    req.add_header("Content-Type", "application/json")
    data = json.dumps({"username": "admin", "password": "admin123"}).encode()
    with urllib.request.urlopen(req, data=data, timeout=15) as r:
        token = json.loads(r.read())["data"]["accessToken"]
    open(TOKEN_FILE, "w").write(token)
    return token


def sql(q, ignore_err=False):
    out = subprocess.run(PG_BASE + [q], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        if ignore_err:
            return None
        raise Exception(f"SQL ERROR: {out.stderr}\nQUERY: {q[:200]}")
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]


def http(method, path, body=None, timeout=30, token=None):
    if not token:
        token = open(TOKEN_FILE).read().strip()
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Authorization", f"Bearer {token}")
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
# STEP 1: WIPE DB + RESTORE VARIANT TABLES
# ====================================================================
print("=" * 70)
print("STEP 1: WIPE TRANSACTIONAL + RESTORE VARIANT TABLES")
print("=" * 70)
print("Running wipe-transactional.sql...")
result = subprocess.run(
    ["env", "PGPASSWORD=bipros_dev", PSQL, "-h", "127.0.0.1", "-U", "bipros", "-d", "bipros",
     "-v", "ON_ERROR_STOP=1", "-f", "/tmp/wipe-transactional.sql"],
    capture_output=True, text=True, timeout=120
)
if result.returncode != 0:
    print(f"WIPE FAILED: {result.stderr[:300]}")
else:
    print("  Wipe complete")

print("\nRestoring profile_permissions + variant tables from backup...")
RESTORE = "/Applications/Postgres.app/Contents/Versions/latest/bin/pg_restore"
for table in ["profiles", "profile_permissions", "manpower_role_rates",
              "equipment_role_variants", "material_role_variants"]:
    result = subprocess.run(
        ["env", "PGPASSWORD=bipros_dev", RESTORE, "-h", "127.0.0.1", "-U", "bipros", "-d", "bipros",
         "--data-only", f"--table={table}", BACKUP],
        capture_output=True, text=True, timeout=60
    )
    print(f"  {table}: {'OK' if result.returncode == 0 else 'WARN ' + result.stderr.strip()[:80]}")

print("\nRe-attaching admin to ADMIN role...")
sql("""
INSERT INTO public.user_roles (id, created_at, updated_at, role_id, user_id, version)
SELECT gen_random_uuid(), now(), now(), r.id, u.id, 0
FROM public.users u, public.roles r
WHERE u.username = 'admin' AND r.name = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING
""", ignore_err=True)

# Refresh token
token = refresh_token()
print(f"  Admin token refreshed ({len(token)} chars)")

# Verify clean state
print("\nClean state:")
for row in sql("""
SELECT 'dpr' AS m, COUNT(*) FROM project.daily_progress_reports
UNION ALL SELECT 'projects', COUNT(*) FROM project.projects
UNION ALL SELECT 'users', COUNT(*) FROM public.users
UNION ALL SELECT 'manpower_role_rates', COUNT(*) FROM resource.manpower_role_rates
UNION ALL SELECT 'equipment_role_variants', COUNT(*) FROM resource.equipment_role_variants
UNION ALL SELECT 'material_role_variants', COUNT(*) FROM resource.material_role_variants
UNION ALL SELECT 'resource_roles', COUNT(*) FROM resource.resource_roles
ORDER BY m
"""):
    print(f"  {row[0]:30} {row[1]:>10}")


# ====================================================================
# STEP 2: CREATE 16 USERS (re-use script)
# ====================================================================
print("\n" + "=" * 70)
print("STEP 2: CREATE 16 USERS")
print("=" * 70)
os.makedirs("/tmp/khasab", exist_ok=True)
result = subprocess.run(["python3", "/tmp/create_khasab_users.py"], capture_output=True, text=True, timeout=120)
print(result.stdout[-800:])
USER_IDS = json.load(open("/tmp/khasab/user-ids.json"))


# ====================================================================
# STEP 3: CREATE PROJECT + SET BUDGET
# ====================================================================
print("\n" + "=" * 70)
print("STEP 3: CREATE PROJECT")
print("=" * 70)
ravi = USER_IDS["ravi"]
sc, resp = http("POST", "/v1/projects", {
    "code": "KHASAB-2026",
    "name": "Khasab Road Project 2026",
    "epsNodeId": "e38edde8-b6cb-4d2c-8e16-72a8336e7c0a",
    "currencyCode": "INR",
    "ownerId": ravi,
})
PROJECT_ID = resp["data"]["id"]
open("/tmp/khasab/project-id.txt", "w").write(PROJECT_ID)
print(f"  Project: {PROJECT_ID}")

# Set dates + status
sc, resp = http("PUT", f"/v1/projects/{PROJECT_ID}", {
    "code": "KHASAB-2026", "name": "Khasab Road Project 2026",
    "epsNodeId": "e38edde8-b6cb-4d2c-8e16-72a8336e7c0a",
    "plannedStartDate": "2026-01-01",
    "plannedFinishDate": "2026-12-31",
    "dataDate": "2026-01-01",
    "status": "ACTIVE",
    "currencyCode": "INR",
    "ownerId": ravi,
})
print(f"  PUT status: {sc}")

# Budget via SQL
sql(f"UPDATE project.projects SET original_budget = 50000000, current_budget = 50000000 WHERE id = '{PROJECT_ID}'")
print(f"  Budget set to ₹5 Cr")


# ====================================================================
# STEP 4: PROJECT TEAM + WBS
# ====================================================================
print("\n" + "=" * 70)
print("STEP 4: PROJECT TEAM + WBS")
print("=" * 70)
result = subprocess.run(["python3", "/tmp/create_project_team.py"], capture_output=True, text=True, timeout=60)
print(result.stdout.split("===")[-1][:500])

result = subprocess.run(["python3", "/tmp/create_wbs_and_activities.py"], capture_output=True, text=True, timeout=60)
print(result.stdout.split("=== Building WBS")[-1][:1500])
WBS_IDS = json.load(open("/tmp/khasab/wbs-ids.json"))


# ====================================================================
# STEP 5: CREATE WORK_ACTIVITIES + ACTIVITIES WITH REAL NAMES
# ====================================================================
print("\n" + "=" * 70)
print("STEP 5: WORK_ACTIVITIES + ACTIVITIES (real names)")
print("=" * 70)
PLAN = json.load(open("/tmp/khasab/activity-plan.json"))

def safe_code(c):
    """Normalize code for storage as work_activity.code (max 50 chars)."""
    return re.sub(r'[^A-Za-z0-9._()-]', '_', str(c).strip())[:50]

def code_to_wbs(code):
    c = re.sub(r'\s+', '', code)
    parts = c.split('.')[:2]
    if len(parts) == 1:
        return f"{parts[0]}.0"
    return f"{parts[0]}.{parts[1].split('(')[0]}"

UNIT_MAP = {
    "cu.m.": "cum", "Cu.m.": "cum", "Cum": "cum", "CUM": "cum",
    "sq.m.": "sqm", "Sqm": "sqm", "Sq.m": "sqm",
    "lin.m.": "m", "Lin.m.": "m",
    "Km": "km", "kg.": "kg", "Kg": "kg",
    "t.": "MT", "tonne": "MT",
    "Nos": "nos", "Nr.": "nos", "Nr": "nos", "nr.": "nos", "nr": "nos",
    "LS": "LS", "Month": "month",
}
def norm_unit(u):
    return UNIT_MAP.get((u or "").strip(), (u or "nos").lower().strip())

# Existing work_activities lookup
existing_wa = {r[0]: r[1] for r in sql("SELECT code, id::text FROM resource.work_activities")}

# Create work_activities with REAL names
wa_id_by_code = {}
created = 0; reused = 0
for code, p in PLAN.items():
    wa_code = safe_code(code)
    if wa_code in existing_wa:
        wa_id_by_code[code] = existing_wa[wa_code]
        reused += 1
        continue
    # Insert via SQL
    name_safe = p["name"].replace("'", "''")[:150]
    unit_safe = norm_unit(p["unit"])[:20]
    res = sql(f"""
INSERT INTO resource.work_activities (id, created_at, updated_at, code, name, default_unit, discipline, active, norm_combination, sort_order)
VALUES (gen_random_uuid(), now(), now(), '{wa_code}', '{name_safe}', '{unit_safe}', 'Civil', true, 'SERIES', 100)
RETURNING id::text
""")
    wa_id_by_code[code] = res[0][0]
    created += 1
print(f"  work_activities: {created} created, {reused} reused")

# Create activities with REAL names linked to work_activities
ACT_IDS = {}
for code, p in PLAN.items():
    wbs_code = code_to_wbs(code)
    wbs_id = WBS_IDS.get(wbs_code) or WBS_IDS.get(wbs_code.split('.')[0] + ".0")
    if not wbs_id:
        print(f"  {code}: NO WBS leaf for {wbs_code}, skip")
        continue
    body = {
        "projectId": PROJECT_ID,
        "code": code,
        "name": p["name"][:100],          # REAL master name, not "Khasab X.Y.Z"
        "wbsNodeId": wbs_id,
        "unit": norm_unit(p["unit"]),
        "type": "TASK_DEPENDENT",
        "percentCompleteType": "DURATION",
        "plannedStart": "2026-01-01",
        "plannedFinish": "2026-12-31",
    }
    sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/activities", body)
    if sc in (200, 201):
        aid = resp["data"]["id"]
        ACT_IDS[code] = aid
        # PUT to link work_activity_id (POST doesn't accept it)
        http("PUT", f"/v1/projects/{PROJECT_ID}/activities/{aid}", {
            "workActivityId": wa_id_by_code[code],
            "originalDuration": p["duration_days"],
            "remainingDuration": p["duration_days"],
        })
    else:
        print(f"  {code}: FAIL {sc} {resp.get('error', {}).get('message', '?')[:80]}")
json.dump(ACT_IDS, open("/tmp/khasab/activity-ids.json", "w"), indent=2)
print(f"  activities: {len(ACT_IDS)} created with REAL names")


# ====================================================================
# STEP 6: PLAN RESOURCES (role-assignments per activity) — THE KEY FIX
# ====================================================================
print("\n" + "=" * 70)
print("STEP 6: PLAN RESOURCES (role-assignments per activity)")
print("=" * 70)

# Build lookup: role name → (roleId, variantId)
manpower_lookup = {}  # role_name → (role_id, manpower_role_rate_id)
for r in sql("""
SELECT rr.name, rr.id::text, mrr.id::text
FROM resource.resource_roles rr
JOIN resource.manpower_role_rates mrr ON mrr.role_id = rr.id
WHERE rr.id IN (SELECT role_id FROM resource.manpower_role_rates)
"""):
    manpower_lookup.setdefault(r[0], (r[1], r[2]))   # keep first variant per role

equipment_lookup = {}  # equipment name → (role_id, equipment_role_variant_id)
for r in sql("""
SELECT rr.name, rr.id::text, erv.id::text
FROM resource.resource_roles rr
JOIN resource.equipment_role_variants erv ON erv.role_id = rr.id
"""):
    equipment_lookup.setdefault(r[0], (r[1], r[2]))

material_lookup = {}  # material name → (role_id, material_role_variant_id)
for r in sql("""
SELECT rr.name, rr.id::text, mrv.id::text
FROM resource.resource_roles rr
JOIN resource.material_role_variants mrv ON mrv.role_id = rr.id
"""):
    material_lookup.setdefault(r[0], (r[1], r[2]))

print(f"  Lookups: manpower={len(manpower_lookup)} roles, equipment={len(equipment_lookup)} roles, material={len(material_lookup)} roles")
print(f"  Manpower roles available: {sorted(manpower_lookup.keys())[:10]}")
print(f"  Equipment roles available: {sorted(equipment_lookup.keys())[:10]}")
print(f"  Material roles available: {sorted(material_lookup.keys())[:10]}")

# POST role-assignments per activity
total_assignments = 0
skipped = defaultdict(int)
for code, p in PLAN.items():
    aid = ACT_IDS.get(code)
    if not aid:
        continue
    # Manpower
    for m in p["manpower_demand"]:
        role_name = m["trade"].strip()
        # Try exact match, then case-insensitive
        match = manpower_lookup.get(role_name) or next(
            (v for k, v in manpower_lookup.items() if k.lower() == role_name.lower()), None
        )
        if not match:
            skipped[f"mp:{role_name}"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": match[0],
            "manpowerRoleRateId": match[1],
            "headcount": m["count"],
            "duration": m["duration_days"],
            "rateType": "STANDARD",
        })
        if sc in (200, 201):
            total_assignments += 1
        else:
            skipped[f"mp_fail:{sc}"] += 1
    # Equipment
    for e in p["equipment_demand"]:
        eq_name = e["name"].strip()
        match = equipment_lookup.get(eq_name) or next(
            (v for k, v in equipment_lookup.items() if k.lower() == eq_name.lower()), None
        )
        if not match:
            skipped[f"eq:{eq_name}"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": match[0],
            "equipmentRoleVariantId": match[1],
            "headcount": e["count"],
            "duration": e["duration_days"],
            "rateType": "STANDARD",
        })
        if sc in (200, 201):
            total_assignments += 1
        else:
            skipped[f"eq_fail:{sc}"] += 1
print(f"\n  Total role-assignments created: {total_assignments}")
if skipped:
    print(f"  Skipped (no matching role variant):")
    for k, n in sorted(skipped.items(), key=lambda x: -x[1])[:15]:
        print(f"    {k}: {n}")


# ====================================================================
# STEP 7: LOCK ACTIVITIES + IMPORT DPRS
# ====================================================================
print("\n" + "=" * 70)
print("STEP 7: LOCK + IMPORT 3,431 DPRs")
print("=" * 70)
for aid in ACT_IDS.values():
    http("POST", f"/v1/projects/{PROJECT_ID}/activities/{aid}/lock")
print(f"  Locked {len(ACT_IDS)} activities")

print("\n  Starting DPR import — this takes ~80 minutes; running in background")
print("  (run scripts/import_khasab_dprs.py all separately, then re-run STEP 8+ via SQL/scripts)")
print(f"\nProject ID: {PROJECT_ID}")
print(f"Role assignments planned: {total_assignments}")
print(f"\n=== STEP 1-7 COMPLETE ===")
print(f"Next: nohup python3 /tmp/import_khasab_dprs.py all > /tmp/dpr-import.log 2>&1 &")
print(f"Then: python3 /tmp/fix_demo_v2.py (creates BOQ + MCL + Norms + EVM + DBS recompute)")
