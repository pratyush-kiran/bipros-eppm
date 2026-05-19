"""
One-shot resource-planning script for OMAN-DEMO-KHASAB.

Pulls activities + roles + variants from Postgres, categorises each activity by
name pattern, and generates a SQL bundle that:
  1) DELETEs legacy resource_assignments (role_id IS NULL) for OMAN activities
  2) INSERTs realistic role-based assignments computed as crew × duration

Writes the SQL to oman-resource-plan.sql. Run separately to execute.
"""
import subprocess, json, re, sys, uuid
from collections import defaultdict

OMAN_PID = 'd901671a-cd23-41c6-8886-d2c1b0ddd3c5'

def psql(sql, tuples=True):
    cmd = ['docker','exec','-i','bipros-postgres','psql','-U','bipros','-d','bipros','-A','-F|','-t','-c',sql]
    out = subprocess.check_output(cmd, text=True).strip()
    return [line.split('|') for line in out.split('\n') if line.strip()] if tuples else out

# ---- Load inventory --------------------------------------------------------
activities = psql(f"""
SELECT a.id, a.code, REGEXP_REPLACE(a.name, E'[\\n\\r]+', ' ', 'g'), COALESCE(a.original_duration, a.remaining_duration, 30)::int
FROM activity.activities a
WHERE a.project_id = '{OMAN_PID}'
ORDER BY a.sort_order
""")
print(f"Loaded {len(activities)} OMAN activities")

# Manpower roles — canonical pick per role name (lowest sort, dedup by lowercase name)
mp_rows = psql("""
SELECT rr.id, rr.name, rr.code
FROM resource.resource_roles rr
JOIN resource.resource_types rt ON rt.id = rr.resource_type_id
WHERE rt.code IN ('MANPOWER','LABOR') AND rr.active
ORDER BY rr.name, rr.code
""")
mp_role_by_name = {}
for rid, name, code in mp_rows:
    key = name.strip().lower()
    if key not in mp_role_by_name:
        mp_role_by_name[key] = rid

# Pick cheapest active variant per manpower role
mp_variant_for_role = {}
for rid in mp_role_by_name.values():
    v = psql(f"""
        SELECT id, rate FROM resource.manpower_role_rates
        WHERE role_id = '{rid}' AND active ORDER BY rate ASC NULLS LAST LIMIT 1
    """)
    if v: mp_variant_for_role[rid] = (v[0][0], float(v[0][1] or 0))

# Equipment + material — same idea
eq_rows = psql("""
SELECT rr.id, rr.name FROM resource.resource_roles rr
JOIN resource.resource_types rt ON rt.id = rr.resource_type_id
WHERE rt.code = 'EQUIPMENT' AND rr.active ORDER BY rr.name
""")
eq_role_by_name = {n.strip().lower(): rid for rid, n in eq_rows}
eq_variant_for_role = {}
for rid in eq_role_by_name.values():
    v = psql(f"""
        SELECT id, rate FROM resource.equipment_role_variants
        WHERE role_id = '{rid}' AND active ORDER BY rate ASC NULLS LAST LIMIT 1
    """)
    if v: eq_variant_for_role[rid] = (v[0][0], float(v[0][1] or 0))

mat_rows = psql("""
SELECT rr.id, rr.name FROM resource.resource_roles rr
JOIN resource.resource_types rt ON rt.id = rr.resource_type_id
WHERE rt.code = 'MATERIAL' AND rr.active ORDER BY rr.name
""")
mat_role_by_name = {n.strip().lower(): rid for rid, n in mat_rows}
mat_variant_for_role = {}
for rid in mat_role_by_name.values():
    v = psql(f"""
        SELECT id, rate FROM resource.material_role_variants
        WHERE role_id = '{rid}' AND active ORDER BY rate ASC NULLS LAST LIMIT 1
    """)
    if v: mat_variant_for_role[rid] = (v[0][0], float(v[0][1] or 0))

print(f"Roles: {len(mp_role_by_name)} manpower / {len(eq_role_by_name)} equipment / {len(mat_role_by_name)} material")

# ---- Category templates ----------------------------------------------------
# Each template: lists of (role_name_lowercase, per_day_count) for MP/EQ
# plus list of (material_name_lowercase, total_qty) for MAT.
# Quantities are realistic for a 30-day road-construction activity window.

T = {  # category -> (manpower, equipment, material)
  'camp': (
    [('camp boss',1),('asst camp boss',1),('cook',2),('helper',4),('carpenter',1),('mason',1)],
    [('other',1)],
    [('cement',5)],
  ),
  'survey': (  # soil investigation / access
    [('foreman',1),('chainman',2),('lab technician',1),('helper',4)],
    [('excavator',1),('transport vehicles',1)],
    [],
  ),
  'preliminaries': (  # access roads, diversions
    [('construction manager',1),('foreman',1),('operator',2),('helper',6)],
    [('earth moving',2),('excavator',1),('transport vehicles',2)],
    [('aggregate',200)],
  ),
  'clearing': (  # 2.1.5 i, ii — clearing & grubbing
    [('foreman',1),('operator',2),('helper',6)],
    [('earth moving',2),('excavator',1),('transport vehicles',2)],
    [],
  ),
  'excavation': (  # 2.3.6 i-ii, 2.4.6 i — earthworks
    [('foreman',1),('operator',3),('helper',6),('chargehand',1)],
    [('excavator',2),('earth moving',2),('transport vehicles',3)],
    [],
  ),
  'blasting': (  # 2.3.6 i.b, i.c — drilling & blasting
    [('foreman',1),('operator',2),('helper',4),('chargehand',1)],
    [('excavator',1),('imported equipment',1),('transport vehicles',2)],
    [],
  ),
  'slope_dress': (  # 2.3.6 i.d
    [('foreman',1),('mason',2),('helper',4)],
    [('excavator',1),('transport vehicles',1)],
    [],
  ),
  'borrow': (  # 2.4.6 — borrow excavation
    [('foreman',1),('operator',2),('helper',5)],
    [('excavator',2),('earth moving',1),('transport vehicles',3)],
    [],
  ),
  'subgrade': (  # 2.6.6 — subgrade prep
    [('foreman',1),('operator',2),('helper',4)],
    [('earth moving',1),('paving equipment',1),('transport vehicles',2)],
    [('aggregate',300)],
  ),
  'struct_excav': (  # 2.7.6, 2.8.6 — structural / trench excavation
    [('foreman',1),('mason',1),('operator',1),('helper',6)],
    [('excavator',1),('transport vehicles',1)],
    [],
  ),
  'gsb_abc': (  # 3.2.6, 3.3.6 — sub-base & base courses
    [('foreman',1),('operator',2),('helper',6)],
    [('paving equipment',2),('earth moving',1),('transport vehicles',3)],
    [('aggregate',1500)],
  ),
  'bituminous_coat': (  # 4.2.8 — prime / tack coat (lighter crew)
    [('foreman',1),('operator',1),('helper',4)],
    [('paving equipment',1),('transport vehicles',1)],
    [('bitumen',8)],
  ),
  'bituminous_course': (  # 4.4.14 / 4.5.14 — base / wearing course
    [('foreman',1),('operator',3),('helper',6)],
    [('paving equipment',2),('transport vehicles',3)],
    [('bitumen',25),('aggregate',800)],
  ),
  'concrete_placing': (  # 5.1.7 — concrete works (placing)
    [('foreman',1),('mason',3),('carpenter',2),('helper',6)],
    [('concrete equipment',1),('cranes lifting',1),('transport vehicles',2)],
    [('cement',40),('aggregate',300),('ready mix concrete',150)],
  ),
  'shuttering': (  # 5.1.7 iii.a — shuttering / de-shuttering
    [('foreman',1),('carpenter',4),('helper',6)],
    [('cranes lifting',1),('transport vehicles',1)],
    [],
  ),
  'rebar': (  # 5.2.6 — cut/bend, fixing
    [('foreman',1),('mason',2),('helper',4)],
    [('cranes lifting',1),('transport vehicles',1)],
    [('steel rebar',50)],
  ),
  'bituminous_paint': (  # 5.10.6 — bituminous paint on structures
    [('foreman',1),('helper',3)],
    [('other',1)],
    [('bitumen',2)],
  ),
  'cast_pipe': (  # 8.1.6 — cast in-situ concrete pipes / culverts
    [('foreman',1),('mason',3),('carpenter',2),('helper',6)],
    [('concrete equipment',1),('cranes lifting',1),('transport vehicles',2)],
    [('cement',50),('steel rebar',30),('ready mix concrete',100)],
  ),
  'slope_protection': (  # 9.x — riprap / mortared / gabions / shotcrete
    [('foreman',1),('mason',2),('helper',5)],
    [('excavator',1),('other',1),('transport vehicles',2)],
    [('aggregate',200),('cement',10)],
  ),
  'geotextile': (  # 9.1.6 iv — filter membrane
    [('foreman',1),('helper',4)],
    [('other',1),('transport vehicles',1)],
    [],
  ),
  'precast': (  # 12.x — tile paving, curb
    [('foreman',1),('mason',2),('helper',4)],
    [('cranes lifting',1),('transport vehicles',1)],
    [('cement',8)],
  ),
  'barrier': (  # 13.x — guardrail, concrete barriers
    [('foreman',1),('mason',2),('carpenter',1),('helper',4)],
    [('cranes lifting',1),('other',1),('transport vehicles',2)],
    [('cement',15),('steel rebar',8)],
  ),
  'signage': (  # 14.1.7 — highway signs
    [('foreman',1),('mason',1),('helper',3)],
    [('other',1),('transport vehicles',1)],
    [('cement',3)],
  ),
  'markings': (  # 14.2.6 / 14.3.6 — road markings, studs
    [('foreman',1),('helper',4)],
    [('paving equipment',1),('other',1)],
    [],
  ),
  'utilities_pipe': (  # 18.1.6, 18.3 — pipes / ducts
    [('foreman',1),('plumber',2),('helper',5)],
    [('excavator',1),('other',1),('transport vehicles',1)],
    [('cement',5)],
  ),
  'utilities_elec': (  # 18.2.1 — electrical relocations
    [('foreman',1),('electrician',2),('auto electrician',1),('helper',4)],
    [('cranes lifting',1),('transport vehicles',1)],
    [('cement',2)],
  ),
  'chamber': (  # 18.3.6 i.a, i.b — chambers
    [('foreman',1),('mason',2),('carpenter',1),('helper',4)],
    [('cranes lifting',1),('transport vehicles',1)],
    [('cement',8),('steel rebar',2)],
  ),
}

# ---- Activity → category mapping ------------------------------------------
def categorise(code, name):
    c, n = code.strip(), name.strip().lower()
    if c == '1.0' or 'camp' in n: return 'camp'
    if c == '1.3' or 'soil investigation' in n: return 'survey'
    if c in ('1.1','1.2'): return 'preliminaries'
    if 'clearing' in n or 'grubbing' in n or 'removal' in n and 'trees' in n: return 'clearing'
    if 'blasting' in n or 'mocking' in n: return 'blasting'
    if 'slope dressing' in n: return 'slope_dress'
    if 'borrow' in n: return 'borrow'
    if 'subgrade' in n: return 'subgrade'
    if 'structural excavation' in n or 'trench excavation' in n: return 'struct_excav'
    if 'excavation' in n: return 'excavation'
    if 'gsb' in n or 'aggregate base course' in n: return 'gsb_abc'
    if 'prime coat' in n or 'tack coat' in n: return 'bituminous_coat'
    if 'bituminious base course' in n or 'bituminious wearing' in n: return 'bituminous_course'
    if 'bituminious paint' in n: return 'bituminous_paint'
    if 'shuttering' in n and 'concreting' not in n and 'steel' not in n: return 'shuttering'
    if 'steel' in n and ('reinforcement' in n or 'fixing' in n or 'cut & bend' in n): return 'rebar'
    if 'concrete' in n and 'pipe' in n: return 'cast_pipe'
    if 'concrete' in n: return 'concrete_placing'
    if 'riprap' in n or 'gabion' in n or 'shotcrete' in n or 'rockfall' in n or 'filter material' in n or 'wire netting' in n or 'ditch lining' in n: return 'slope_protection'
    if 'geotextile' in n or 'filter membrane' in n: return 'geotextile'
    if 'precast' in n: return 'precast'
    if 'guardrail' in n or 'barrier' in n: return 'barrier'
    if 'sign' in n and 'highway' in n: return 'signage'
    if 'marking' in n or 'studs' in n or 'curb painting' in n: return 'markings'
    if 'electrical' in n: return 'utilities_elec'
    if 'chamber' in n: return 'chamber'
    if 'pipe' in n or 'duct' in n or 'utilities' in n: return 'utilities_pipe'
    return 'preliminaries'  # safe default

# ---- Build SQL bundle ------------------------------------------------------
sql_lines = [
    "-- OMAN-DEMO-KHASAB realistic resource plan",
    "-- 1) Delete legacy (role_id IS NULL) ResourceAssignment rows",
    "-- 2) Insert role-based rows derived from per-activity crew templates",
    "BEGIN;",
    f"DELETE FROM resource.resource_assignments WHERE project_id = '{OMAN_PID}' AND role_id IS NULL;",
    "",
]

inserted = 0
unknown_roles = set()
report = defaultdict(list)
for aid, code, name, dur_s in activities:
    dur = int(dur_s) if dur_s else 30
    cat = categorise(code, name)
    mp, eq, mat = T.get(cat, ([],[],[]))
    report[cat].append(f"{code} — {name}")

    def emit(role_id, variant_col, variant_id, rate, headcount, quantity, unit, type_code):
        global inserted
        units = (headcount or 0) if type_code != 'MATERIAL' else (quantity or 0)
        cost = units * (rate or 0)
        sql_lines.append(
            f"INSERT INTO resource.resource_assignments "
            f"(id, activity_id, project_id, role_id, {variant_col}, "
            f"headcount, duration, quantity, planned_units, budgeted_units, remaining_units, actual_units, "
            f"planned_cost, budgeted_cost, remaining_cost, actual_cost, effective_rate, unit, rate_type, "
            f"version, created_at, updated_at) "
            f"VALUES (gen_random_uuid(), '{aid}', '{OMAN_PID}', '{role_id}', '{variant_id}', "
            f"{headcount or 'NULL'}, NULL, {('%g' % quantity) if quantity else 'NULL'}, "
            f"{units}, {units}, {units}, 0, "
            f"{cost:.4f}, {cost:.4f}, {cost:.4f}, 0, {rate or 0:.4f}, '{unit}', 'STANDARD', "
            f"0, now(), now());"
        )
        inserted += 1

    for rname, per_day in mp:
        rid = mp_role_by_name.get(rname.lower())
        if not rid: unknown_roles.add(('MANPOWER', rname)); continue
        vid_rate = mp_variant_for_role.get(rid)
        if not vid_rate: continue
        vid, rate = vid_rate
        emit(rid, 'manpower_role_rate_id', vid, rate, per_day*dur, None, 'Day', 'MANPOWER')

    for rname, per_day in eq:
        rid = eq_role_by_name.get(rname.lower())
        if not rid: unknown_roles.add(('EQUIPMENT', rname)); continue
        vid_rate = eq_variant_for_role.get(rid)
        if not vid_rate: continue
        vid, rate = vid_rate
        emit(rid, 'equipment_role_variant_id', vid, rate, per_day*dur, None, 'Day', 'EQUIPMENT')

    for rname, total_qty in mat:
        rid = mat_role_by_name.get(rname.lower())
        if not rid: unknown_roles.add(('MATERIAL', rname)); continue
        vid_rate = mat_variant_for_role.get(rid)
        if not vid_rate: continue
        vid, rate = vid_rate
        emit(rid, 'material_role_variant_id', vid, rate, None, total_qty, 'MT', 'MATERIAL')

sql_lines += ["", "-- Verification", f"SELECT count(*) AS new_role_rows FROM resource.resource_assignments WHERE project_id='{OMAN_PID}' AND role_id IS NOT NULL;",
              f"SELECT count(*) AS legacy_remaining FROM resource.resource_assignments WHERE project_id='{OMAN_PID}' AND role_id IS NULL;",
              "COMMIT;"]

import os
_out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'oman-resource-plan.sql')
with open(_out, 'w') as f:
    f.write('\n'.join(sql_lines))

print(f"\nCategorised activities:")
for cat in sorted(report):
    print(f"  {cat:22s} ({len(report[cat]):2d}): {report[cat][0]}{('  +'+str(len(report[cat])-1)+' more') if len(report[cat])>1 else ''}")

print(f"\nGenerated {inserted} INSERT statements -> {_out}")
if unknown_roles:
    print(f"Unmapped role names (these are missing — would normally auto-create):")
    for typ, name in sorted(unknown_roles): print(f"  {typ}: {name}")
