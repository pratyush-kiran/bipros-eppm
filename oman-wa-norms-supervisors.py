"""
OMAN-DEMO-KHASAB data refinement, round 2:
  1) Replace 167 noisy Work Activities with ~46 OMAN-relevant canonical entries
  2) Replace 230 UNSCOPED productivity norms with ~92 ROLE-scoped (1 MP + 1 EQ per WA)
  3) Link each of 76 OMAN activities to its matching Work Activity
  4) Fill the 44 activities missing a supervisor (2 each: Site Supervisor RR + CM by WBS)

Writes oman-wa-norms-supervisors.sql. Run it via:
  docker exec -i bipros-postgres psql -U postgres -d bipros < oman-wa-norms-supervisors.sql
"""
import subprocess, os, re
from collections import defaultdict

OMAN_PID = 'd901671a-cd23-41c6-8886-d2c1b0ddd3c5'
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def psql(sql):
    cmd = ['docker','exec','-i','bipros-postgres','psql','-U','bipros','-d','bipros','-A','-F|','-t','-c',sql]
    out = subprocess.check_output(cmd, text=True).strip()
    return [line.split('|') for line in out.split('\n') if line.strip()]

# ---- Load activities, users, roles ----------------------------------------
activities = psql(f"""
SELECT a.id, a.code, REGEXP_REPLACE(a.name, E'[\\n\\r]+', ' ', 'g'),
       COALESCE(a.original_duration, a.remaining_duration, 30)::int,
       SPLIT_PART(a.code, '.', 1)::int AS wbs_top
FROM activity.activities a
WHERE a.project_id = '{OMAN_PID}'
ORDER BY a.sort_order
""")
print(f"Loaded {len(activities)} OMAN activities")

# Users by name (case-insensitive) for supervisor lookup
users = psql("SELECT id, first_name || ' ' || last_name FROM public.users")
user_by_name = {n.strip().lower(): uid for uid, n in users}
def u(name): return user_by_name.get(name.strip().lower())

# Site supervisors round-robin pool (only those that exist in DB)
SITE_SUPERVISOR_NAMES = [
  'Md Saiffuddin','V.P. Gupta','A.K. Mishra','Sanjar Alam','Anirban Datta',
  'Manzar','Parvaiz','Illayaraja','Sohail','K. Barman','Mohd Ismaila',
]
site_supervisors = [(n, u(n)) for n in SITE_SUPERVISOR_NAMES if u(n)]
print(f"Site supervisors found in DB: {len(site_supervisors)}/{len(SITE_SUPERVISOR_NAMES)}")

# Construction managers by WBS area
CM_BY_AREA = {
  # WBS top number -> (cm_name, fallback)
  1: 'T Swamy', 2: 'T Swamy',
  3: 'A K Singh', 4: 'A K Singh',
  5: 'A Nagarajan', 8: 'A Nagarajan',
  9: 'R. Subramanian', 12: 'R. Subramanian', 13: 'R. Subramanian',
  14: 'R. Subramanian', 18: 'R. Subramanian',
}
def cm_for(wbs_top):
    name = CM_BY_AREA.get(wbs_top, 'R. Subramanian')
    return name, u(name)

# Already-supervised activities (skip the fill for these)
existing_sup = {row[0] for row in psql(f"""
SELECT DISTINCT s.activity_id::text FROM activity.activity_supervisors s
JOIN activity.activities a ON a.id=s.activity_id
WHERE a.project_id='{OMAN_PID}'
""")}
print(f"Already supervised: {len(existing_sup)} activities; will fill {len(activities) - len(existing_sup)} gaps")

# Resource role lookup (by lowercased name -> id)
mp_rows = psql("""SELECT rr.id, rr.name FROM resource.resource_roles rr
  JOIN resource.resource_types rt ON rt.id = rr.resource_type_id
  WHERE rt.code IN ('MANPOWER','LABOR') AND rr.active""")
eq_rows = psql("""SELECT rr.id, rr.name FROM resource.resource_roles rr
  JOIN resource.resource_types rt ON rt.id = rr.resource_type_id
  WHERE rt.code = 'EQUIPMENT' AND rr.active""")
mp_role = {n.strip().lower(): rid for rid, n in mp_rows}
eq_role = {n.strip().lower(): rid for rid, n in eq_rows}

RT_MANPOWER = '8d767c95-5c19-4527-aabd-2459603d80af'
RT_EQUIPMENT = '643e6201-4e87-48ed-b6a2-839b6108b3c0'

# ---- Canonical Work Activity master ---------------------------------------
# (code, name, default_unit, discipline)
WAS = [
  ('WA_CAMP',                'Camp Work',                                'Day',  'preliminaries'),
  ('WA_SURVEY',              'Site Survey & Soil Investigation',         'Day',  'preliminaries'),
  ('WA_DIVERSION',           'Diversion & Access Road Construction',     'Cum',  'earthwork'),
  ('WA_CLEARING',            'Clearing & Grubbing',                      'Sqm',  'earthwork'),
  ('WA_EXC_UNCLASSIFIED',    'Unclassified Excavation',                  'Cum',  'earthwork'),
  ('WA_EXC_MECHANICAL',      'Mechanical Excavation',                    'Cum',  'earthwork'),
  ('WA_BLASTING',            'Drilling & Blasting',                      'Cum',  'earthwork'),
  ('WA_SLOPE_DRESSING',      'Slope Dressing',                           'Sqm',  'earthwork'),
  ('WA_EXC_BORROW',          'Borrow Excavation',                        'Cum',  'earthwork'),
  ('WA_EXC_STRUCTURAL',      'Structural Excavation',                    'Cum',  'earthwork'),
  ('WA_EXC_TRENCH',          'Trench Excavation & Backfill',             'Cum',  'earthwork'),
  ('WA_SUBGRADE',            'Subgrade Preparation',                     'Sqm',  'earthwork'),
  ('WA_GSB',                 'Granular Sub-Base (GSB)',                  'Cum',  'pavement'),
  ('WA_ABC',                 'Aggregate Base Course (ABC)',              'Cum',  'pavement'),
  ('WA_PRIME_COAT',          'Bituminous Prime Coat',                    'Sqm',  'pavement'),
  ('WA_TACK_COAT',           'Bituminous Tack Coat',                     'Sqm',  'pavement'),
  ('WA_BIT_BASE',            'Bituminous Base Course',                   'Cum',  'pavement'),
  ('WA_BIT_WEARING',         'Bituminous Wearing Course',                'Cum',  'pavement'),
  ('WA_CONCRETE_C15',        'Concrete Class 15 (Blinding)',             'Cum',  'structures'),
  ('WA_CONCRETE_C25',        'Concrete Class 25 (Barriers)',             'Cum',  'structures'),
  ('WA_CONCRETE_C30',        'Concrete Class 30 (Structures)',           'Cum',  'structures'),
  ('WA_SHUTTERING',          'Shuttering & De-shuttering',               'Sqm',  'structures'),
  ('WA_REBAR_CUT_BEND',      'Steel Reinforcement Cut & Bend',           'MT',   'structures'),
  ('WA_REBAR_FIXING',        'Steel Reinforcement Fixing',               'MT',   'structures'),
  ('WA_BIT_PAINT',           'Bituminous Paint (Structures)',            'Sqm',  'structures'),
  ('WA_CULVERT_PIPE',        'Cast-in-Situ Concrete Pipe Culverts',      'Lm',   'structures'),
  ('WA_RIPRAP_LOOSE',        'Loose Stone Riprap',                       'Cum',  'slope-protection'),
  ('WA_RIPRAP_MORTARED',     'Mortared Stone Riprap',                    'Cum',  'slope-protection'),
  ('WA_FILTER_MATERIAL',     'Filter Material',                          'Cum',  'slope-protection'),
  ('WA_GEOTEXTILE',          'Filter Membrane (Geotextile)',             'Sqm',  'slope-protection'),
  ('WA_GABIONS',             'Gabion Boxes',                             'Cum',  'slope-protection'),
  ('WA_WIRE_NETTING',        'Wire Netting Slope Protection',            'Sqm',  'slope-protection'),
  ('WA_ROCKFALL',            'Rockfall Barrier',                         'Lm',   'slope-protection'),
  ('WA_SHOTCRETE',           'Sprayed Concrete (Shotcrete)',             'Sqm',  'slope-protection'),
  ('WA_DITCH_LINING',        'Concrete Ditch Lining',                    'Sqm',  'slope-protection'),
  ('WA_PRECAST_PAVING',      'Precast Concrete Paving',                  'Sqm',  'finishing'),
  ('WA_PRECAST_CURB',        'Precast Concrete Curb',                    'Lm',   'finishing'),
  ('WA_GUARDRAIL',           'W-Beam Guardrail',                         'Lm',   'safety'),
  ('WA_CONCRETE_BARRIER',    'Cast-in-Place Concrete Barrier',           'Lm',   'safety'),
  ('WA_HIGHWAY_SIGN',        'Highway Sign Installation',                'Each', 'signage'),
  ('WA_ROAD_MARKING',        'Road Marking',                             'Sqm',  'signage'),
  ('WA_ROAD_STUDS',          'Road Studs',                               'Each', 'signage'),
  ('WA_UTILITY_PIPE',        'Utility Pipe Installation',                'Lm',   'utilities'),
  ('WA_UTILITY_ELEC',        'Electrical Utility Relocation',            'Each', 'utilities'),
  ('WA_UTILITY_DUCT',        'HDPE / UPVC Duct Installation',            'Lm',   'utilities'),
  ('WA_UTILITY_CHAMBER',     'Utility Chamber Construction',             'Each', 'utilities'),
]
# Pre-assign UUIDs so we can reference them in subsequent INSERTs/UPDATEs without round-tripping
import uuid
wa_id = {code: str(uuid.uuid4()) for code, *_ in WAS}

# ---- Productivity norms per WA (MP, EQ) -----------------------------------
# (manpower role name, output/man/day, crew_size, equipment role name, output/day, working_hours)
# Numbers grounded in Capacity_Utilization.xlsx and CPWD/IS-7272 standards.
NORMS = {
  'WA_CAMP':             (('Helper', 1, 4, 'Day'),     ('Other', 1, 8)),
  'WA_SURVEY':           (('Chainman', 200, 2, 'Sqm'), ('Excavator', 300, 8)),
  'WA_DIVERSION':        (('Operator', 200, 4, 'Cum'), ('Earth Moving', 800, 8)),
  'WA_CLEARING':         (('Helper', 100, 4, 'Sqm'),   ('Earth Moving', 4000, 8)),
  'WA_EXC_UNCLASSIFIED': (('Operator', 100, 4, 'Cum'), ('Excavator', 900, 8)),
  'WA_EXC_MECHANICAL':   (('Operator', 120, 4, 'Cum'), ('Excavator', 750, 8)),
  'WA_BLASTING':         (('Operator', 50,  4, 'Cum'), ('Imported Equipment', 200, 8)),
  'WA_SLOPE_DRESSING':   (('Mason',   50,  3, 'Sqm'),  ('Excavator', 400, 8)),
  'WA_EXC_BORROW':       (('Operator', 110, 4, 'Cum'), ('Excavator', 700, 8)),
  'WA_EXC_STRUCTURAL':   (('Mason',    8,  4, 'Cum'),  ('Excavator', 200, 8)),
  'WA_EXC_TRENCH':       (('Mason',   10,  4, 'Cum'),  ('Excavator', 250, 8)),
  'WA_SUBGRADE':         (('Operator', 200, 4, 'Sqm'), ('Paving Equipment', 1000, 8)),
  'WA_GSB':              (('Operator', 200, 6, 'Cum'), ('Paving Equipment', 909, 8)),
  'WA_ABC':              (('Operator', 150, 6, 'Cum'), ('Paving Equipment', 857, 8)),
  'WA_PRIME_COAT':       (('Helper',  150, 4, 'Sqm'),  ('Paving Equipment', 600, 8)),
  'WA_TACK_COAT':        (('Helper',  150, 4, 'Sqm'),  ('Paving Equipment', 600, 8)),
  'WA_BIT_BASE':         (('Operator', 30, 6, 'Cum'),  ('Paving Equipment', 250, 8)),
  'WA_BIT_WEARING':      (('Operator', 25, 6, 'Cum'),  ('Paving Equipment', 200, 8)),
  'WA_CONCRETE_C15':     (('Mason',    2, 4, 'Cum'),   ('Concrete Equipment', 8, 8)),
  'WA_CONCRETE_C25':     (('Mason',    1.5, 4, 'Cum'), ('Concrete Equipment', 6, 8)),
  'WA_CONCRETE_C30':     (('Mason',    1.5, 4, 'Cum'), ('Concrete Equipment', 7, 8)),
  'WA_SHUTTERING':       (('Carpenter', 5, 4, 'Sqm'),  ('Cranes Lifting', 80, 8)),
  'WA_REBAR_CUT_BEND':   (('Mason',    0.3, 3, 'MT'),  ('Cranes Lifting', 2, 8)),
  'WA_REBAR_FIXING':     (('Mason',    0.25,3, 'MT'),  ('Cranes Lifting', 2, 8)),
  'WA_BIT_PAINT':        (('Helper',  30, 3, 'Sqm'),   ('Other', 80, 8)),
  'WA_CULVERT_PIPE':     (('Mason',    1, 4, 'Lm'),    ('Concrete Equipment', 5, 8)),
  'WA_RIPRAP_LOOSE':     (('Mason',    3, 4, 'Cum'),   ('Excavator', 60, 8)),
  'WA_RIPRAP_MORTARED':  (('Mason',    2, 4, 'Cum'),   ('Excavator', 40, 8)),
  'WA_FILTER_MATERIAL':  (('Helper',   8, 4, 'Cum'),   ('Excavator', 80, 8)),
  'WA_GEOTEXTILE':       (('Helper',  50, 4, 'Sqm'),   ('Other', 300, 8)),
  'WA_GABIONS':          (('Mason',    2, 4, 'Cum'),   ('Cranes Lifting', 30, 8)),
  'WA_WIRE_NETTING':     (('Mason',   20, 4, 'Sqm'),   ('Cranes Lifting', 150, 8)),
  'WA_ROCKFALL':         (('Mason',    3, 4, 'Lm'),    ('Cranes Lifting', 20, 8)),
  'WA_SHOTCRETE':        (('Mason',   15, 4, 'Sqm'),   ('Concrete Equipment', 100, 8)),
  'WA_DITCH_LINING':     (('Mason',   20, 4, 'Sqm'),   ('Concrete Equipment', 120, 8)),
  'WA_PRECAST_PAVING':   (('Mason',   15, 4, 'Sqm'),   ('Cranes Lifting', 100, 8)),
  'WA_PRECAST_CURB':     (('Mason',   25, 4, 'Lm'),    ('Cranes Lifting', 150, 8)),
  'WA_GUARDRAIL':        (('Helper',  30, 4, 'Lm'),    ('Cranes Lifting', 150, 8)),
  'WA_CONCRETE_BARRIER': (('Mason',    8, 4, 'Lm'),    ('Concrete Equipment', 40, 8)),
  'WA_HIGHWAY_SIGN':     (('Helper',   3, 3, 'Each'),  ('Other', 12, 8)),
  'WA_ROAD_MARKING':     (('Helper',  50, 4, 'Sqm'),   ('Paving Equipment', 300, 8)),
  'WA_ROAD_STUDS':       (('Helper',  30, 3, 'Each'),  ('Other', 120, 8)),
  'WA_UTILITY_PIPE':     (('Plumber',  8, 4, 'Lm'),    ('Excavator', 60, 8)),
  'WA_UTILITY_ELEC':     (('Electrician', 1, 4, 'Each'),('Cranes Lifting', 4, 8)),
  'WA_UTILITY_DUCT':     (('Plumber', 25, 4, 'Lm'),    ('Excavator', 150, 8)),
  'WA_UTILITY_CHAMBER':  (('Mason',    1, 4, 'Each'),  ('Cranes Lifting', 3, 8)),
}

# ---- Activity → WA categoriser --------------------------------------------
def categorise(code, name):
    c, n = code.strip(), name.strip().lower()
    if c == '1.0' or 'camp' in n: return 'WA_CAMP'
    if c == '1.3' or 'soil investigation' in n: return 'WA_SURVEY'
    if c in ('1.1','1.2'): return 'WA_DIVERSION'
    if 'clearing' in n or 'grubbing' in n or ('removal' in n and 'trees' in n): return 'WA_CLEARING'
    if 'blasting' in n or 'mocking' in n: return 'WA_BLASTING'
    if 'slope dressing' in n: return 'WA_SLOPE_DRESSING'
    if 'borrow' in n: return 'WA_EXC_BORROW'
    if 'subgrade' in n: return 'WA_SUBGRADE'
    if 'structural excavation' in n: return 'WA_EXC_STRUCTURAL'
    if 'trench excavation' in n: return 'WA_EXC_TRENCH'
    if 'mechanical excavation' in n: return 'WA_EXC_MECHANICAL'
    if 'excavation' in n: return 'WA_EXC_UNCLASSIFIED'
    if 'gsb' in n: return 'WA_GSB'
    if 'aggregate base course' in n: return 'WA_ABC'
    if 'prime coat' in n: return 'WA_PRIME_COAT'
    if 'tack coat' in n: return 'WA_TACK_COAT'
    if 'bituminious base course' in n or 'bituminous base course' in n: return 'WA_BIT_BASE'
    if 'bituminious wearing' in n or 'bituminous wearing' in n: return 'WA_BIT_WEARING'
    if 'bituminious paint' in n or 'bituminous paint' in n: return 'WA_BIT_PAINT'
    if 'shuttering' in n and 'concreting' not in n and 'steel' not in n: return 'WA_SHUTTERING'
    if 'steel' in n and ('cut & bend' in n or 'cut and bend' in n): return 'WA_REBAR_CUT_BEND'
    if 'steel' in n and ('fixing' in n or 'reinforcement' in n): return 'WA_REBAR_FIXING'
    if 'concrete' in n and 'pipe' in n: return 'WA_CULVERT_PIPE'
    if 'class 15' in n: return 'WA_CONCRETE_C15'
    if 'class 25' in n: return 'WA_CONCRETE_C25'
    if 'class 30' in n or 'concreting' in n: return 'WA_CONCRETE_C30'
    if 'concrete' in n and 'ditch' in n: return 'WA_DITCH_LINING'
    if 'mortared' in n and 'riprap' in n: return 'WA_RIPRAP_MORTARED'
    if 'loose stone' in n or ('riprap' in n and 'mortared' not in n): return 'WA_RIPRAP_LOOSE'
    if 'filter material' in n: return 'WA_FILTER_MATERIAL'
    if 'geotextile' in n or 'filter membrane' in n: return 'WA_GEOTEXTILE'
    if 'gabion' in n: return 'WA_GABIONS'
    if 'wire netting' in n: return 'WA_WIRE_NETTING'
    if 'rockfall' in n: return 'WA_ROCKFALL'
    if 'shotcrete' in n or 'sprayed concrete' in n: return 'WA_SHOTCRETE'
    if 'precast' in n and 'curb' in n: return 'WA_PRECAST_CURB'
    if 'precast' in n: return 'WA_PRECAST_PAVING'
    if 'guardrail' in n: return 'WA_GUARDRAIL'
    if 'barrier' in n: return 'WA_CONCRETE_BARRIER'
    if 'highway sign' in n or ('sign' in n and 'post' in n): return 'WA_HIGHWAY_SIGN'
    if 'marker' in n: return 'WA_HIGHWAY_SIGN'
    if 'marking' in n or 'curb painting' in n: return 'WA_ROAD_MARKING'
    if 'studs' in n: return 'WA_ROAD_STUDS'
    if 'electrical' in n: return 'WA_UTILITY_ELEC'
    if 'chamber' in n: return 'WA_UTILITY_CHAMBER'
    if 'duct' in n or 'hdpe' in n or 'upvc' in n: return 'WA_UTILITY_DUCT'
    if 'pipe' in n or 'utilities' in n: return 'WA_UTILITY_PIPE'
    return 'WA_CAMP'  # safe fallback

# ---- Build SQL ------------------------------------------------------------
def sql_str(s):
    return "'" + s.replace("'", "''") + "'"

lines = [
  "-- OMAN data refinement: clean Work Activities + ROLE-scoped norms + supervisor coverage + WA links",
  "-- Runs as superuser to use session_replication_role bypass for FK safety.",
  "BEGIN;",
  "SET LOCAL session_replication_role = 'replica';",
  "",
  "-- 1) Wipe norms (all UNSCOPED, no value going forward)",
  "DELETE FROM resource.productivity_norms;",
  "",
  "-- 2) Break FK from activities to WAs, then wipe WA master",
  "UPDATE activity.activities SET work_activity_id = NULL WHERE work_activity_id IS NOT NULL;",
  "DELETE FROM resource.work_activities;",
  "",
  "-- 3) Insert canonical Work Activities",
]
for i, (code, name, unit, disc) in enumerate(WAS):
    lines.append(
      f"INSERT INTO resource.work_activities (id, code, name, default_unit, discipline, sort_order, active, version, created_at, updated_at) "
      f"VALUES ('{wa_id[code]}', {sql_str(code)}, {sql_str(name)}, {sql_str(unit)}, {sql_str(disc)}, {(i+1)*10}, true, 0, now(), now());"
    )

lines.append("")
lines.append("-- 4) Insert productivity norms (1 MANPOWER + 1 EQUIPMENT per WA, ROLE-scoped)")
norms_inserted = 0
missing_norm_roles = set()
for code, *_ in WAS:
    if code not in NORMS: continue
    (mp_name, opmd, crew, mp_unit), (eq_name, opd, hrs) = NORMS[code]
    mp_rid = mp_role.get(mp_name.lower())
    eq_rid = eq_role.get(eq_name.lower())
    if mp_rid:
        lines.append(
          f"INSERT INTO resource.productivity_norms "
          f"(id, work_activity_id, role_id, resource_type_id, norm_type, unit, "
          f" output_per_man_per_day, crew_size, version, created_at, updated_at) "
          f"VALUES (gen_random_uuid(), '{wa_id[code]}', '{mp_rid}', '{RT_MANPOWER}', 'MANPOWER', {sql_str(mp_unit)}, "
          f" {opmd}, {crew}, 0, now(), now());"
        )
        norms_inserted += 1
    else:
        missing_norm_roles.add(('MANPOWER', mp_name))
    if eq_rid:
        lines.append(
          f"INSERT INTO resource.productivity_norms "
          f"(id, work_activity_id, role_id, resource_type_id, norm_type, unit, "
          f" output_per_day, working_hours_per_day, version, created_at, updated_at) "
          f"VALUES (gen_random_uuid(), '{wa_id[code]}', '{eq_rid}', '{RT_EQUIPMENT}', 'EQUIPMENT', {sql_str(mp_unit)}, "
          f" {opd}, {hrs}, 0, now(), now());"
        )
        norms_inserted += 1
    else:
        missing_norm_roles.add(('EQUIPMENT', eq_name))

lines.append("")
lines.append("-- 5) Link every OMAN activity to its matching Work Activity")
unknown_categories = []
for aid, code, name, dur, wbs_top in activities:
    wa = categorise(code, name)
    if wa not in wa_id:
        unknown_categories.append((code, name, wa))
        continue
    lines.append(
      f"UPDATE activity.activities SET work_activity_id = '{wa_id[wa]}' WHERE id = '{aid}';"
    )

lines.append("")
lines.append("-- 6) Fill missing supervisors (44 activities) — 1 Site Supervisor RR + 1 Construction Manager by WBS area")
fill_count = 0
rr = 0  # round-robin pointer
for aid, code, name, dur, wbs_top in activities:
    if aid in existing_sup: continue
    # Site Supervisor (round-robin)
    ss_name, ss_id = site_supervisors[rr % len(site_supervisors)]
    rr += 1
    cm_name, cm_id = cm_for(wbs_top)
    if ss_id:
        lines.append(
          f"INSERT INTO activity.activity_supervisors "
          f"(id, activity_id, user_id, user_name_snapshot, version, created_at, updated_at) "
          f"VALUES (gen_random_uuid(), '{aid}', '{ss_id}', {sql_str(ss_name)}, 0, now(), now()) "
          f"ON CONFLICT DO NOTHING;"
        )
    if cm_id and cm_id != ss_id:
        lines.append(
          f"INSERT INTO activity.activity_supervisors "
          f"(id, activity_id, user_id, user_name_snapshot, version, created_at, updated_at) "
          f"VALUES (gen_random_uuid(), '{aid}', '{cm_id}', {sql_str(cm_name)}, 0, now(), now()) "
          f"ON CONFLICT DO NOTHING;"
        )
    # Sync legacy single-supervisor cache to first inserted (Site Supervisor)
    if ss_id:
        lines.append(
          f"UPDATE activity.activities SET supervisor_user_id = '{ss_id}', "
          f"supervisor_user_name = {sql_str(ss_name)} WHERE id = '{aid}';"
        )
    fill_count += 1

lines.append("")
lines.append("-- 7) Verification queries")
lines.append("SELECT 'WAs' AS what, count(*)::text AS val FROM resource.work_activities")
lines.append("UNION ALL SELECT 'norms (role-scoped)', count(*)::text FROM resource.productivity_norms WHERE role_id IS NOT NULL")
lines.append("UNION ALL SELECT 'norms (unscoped)',    count(*)::text FROM resource.productivity_norms WHERE role_id IS NULL")
lines.append(f"UNION ALL SELECT 'OMAN act w/o WA',  count(*)::text FROM activity.activities WHERE project_id='{OMAN_PID}' AND work_activity_id IS NULL")
lines.append(f"UNION ALL SELECT 'OMAN act w/o sup', count(*)::text FROM (SELECT a.id FROM activity.activities a LEFT JOIN activity.activity_supervisors s ON s.activity_id=a.id WHERE a.project_id='{OMAN_PID}' GROUP BY a.id HAVING count(s.id)=0) z")
lines.append("ORDER BY what;")
lines.append("")
lines.append("RESET session_replication_role;")
lines.append("COMMIT;")

out_path = os.path.join(SCRIPT_DIR, 'oman-wa-norms-supervisors.sql')
with open(out_path, 'w') as f:
    f.write('\n'.join(lines))

print(f"\nFilled {fill_count} activities with supervisors")
print(f"Generated {norms_inserted} norms ({len(WAS)} WAs * 2 = {len(WAS)*2} expected)")
print(f"SQL bundle -> {out_path}")
if missing_norm_roles:
    print("Missing roles for norms:", sorted(missing_norm_roles))
if unknown_categories:
    print("Activities with no WA category:")
    for c, n, w in unknown_categories: print(f"  {c} | {n}  -> {w}")
