"""
Generate realistic DPRs for OMAN-DEMO-KHASAB:
  1) Lock all 76 activities (DPR submission precondition)
  2) Wipe existing 10,686 DPRs + ledger rows + reset resource_assignment actuals
  3) For each activity: one DPR per working day (Sat-Thu, Friday off in Oman) from
     planned_start to min(planned_finish, today), round-robin supervisor, qty_executed
     from WA's EQUIPMENT productivity norm × variation, plus per-DPR manpower+equipment
     child rows derived from the planned resource_assignments (daily slice = total / days).
  4) Update activity actual_start_date / actual_finish_date / percent_complete / status.
  5) Re-rollup resource_assignment actual_units / actual_cost / remaining* via SUM.

Writes oman-dprs-realistic.sql. Execute via:
  docker exec -i bipros-postgres psql -U postgres -d bipros < oman-dprs-realistic.sql
"""
import subprocess, os, random, datetime as dt
random.seed(42)  # deterministic — same script run twice produces the same DPRs

OMAN_PID = 'd901671a-cd23-41c6-8886-d2c1b0ddd3c5'
TODAY = dt.date(2026, 5, 16)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def psql(sql):
    cmd = ['docker','exec','-i','bipros-postgres','psql','-U','bipros','-d','bipros','-A','-F|','-t','-c',sql]
    out = subprocess.check_output(cmd, text=True).strip()
    return [line.split('|') for line in out.split('\n') if line.strip()]

# --- Load activities with WA + planned norm ---------------------------------
acts = psql(f"""
SELECT a.id, a.code, REGEXP_REPLACE(a.name, E'[\\n\\r]+', ' ', 'g') AS name,
       a.planned_start_date::text, a.planned_finish_date::text,
       COALESCE(a.original_duration, a.remaining_duration, 30)::int AS dur,
       a.work_activity_id::text, wa.default_unit, wa.name AS wa_name,
       (SELECT n.output_per_day FROM resource.productivity_norms n
          WHERE n.work_activity_id = a.work_activity_id AND n.norm_type='EQUIPMENT'
          LIMIT 1)::text AS eq_output
FROM activity.activities a
LEFT JOIN resource.work_activities wa ON wa.id = a.work_activity_id
WHERE a.project_id = '{OMAN_PID}'
ORDER BY a.sort_order
""")
print(f"Loaded {len(acts)} OMAN activities")

# --- Load supervisors per activity ------------------------------------------
sup_rows = psql(f"""
SELECT s.activity_id::text, s.user_id::text, s.user_name_snapshot
FROM activity.activity_supervisors s
JOIN activity.activities a ON a.id = s.activity_id
WHERE a.project_id = '{OMAN_PID}'
ORDER BY s.activity_id, s.created_at
""")
sup_by_act = {}
for aid, uid, uname in sup_rows:
    sup_by_act.setdefault(aid, []).append((uid, uname))

# --- Load resource_assignments per activity ---------------------------------
ra_rows = psql(f"""
SELECT ra.activity_id::text, ra.role_id::text, ra.manpower_role_rate_id::text,
       ra.equipment_role_variant_id::text, ra.material_role_variant_id::text,
       ra.headcount::text, ra.quantity::text, ra.planned_units::text, ra.effective_rate::text,
       rr.name AS role_name, rt.code AS type_code
FROM resource.resource_assignments ra
JOIN resource.resource_roles rr ON rr.id = ra.role_id
JOIN resource.resource_types rt ON rt.id = rr.resource_type_id
WHERE ra.project_id = '{OMAN_PID}'
ORDER BY ra.activity_id, rt.code, rr.name
""")
ra_by_act_mp = {}
ra_by_act_eq = {}
for aid, rid, mp_var, eq_var, mat_var, hc, qty, pu, rate, rname, tcode in ra_rows:
    record = {'role_id': rid, 'role_name': rname, 'headcount': int(hc) if hc else 0,
              'quantity': float(qty) if qty else 0, 'planned_units': float(pu) if pu else 0,
              'rate': float(rate) if rate else 0,
              'variant_id': mp_var or eq_var or mat_var}
    if tcode in ('MANPOWER','LABOR') and mp_var:
        ra_by_act_mp.setdefault(aid, []).append(record)
    elif tcode == 'EQUIPMENT' and eq_var:
        ra_by_act_eq.setdefault(aid, []).append(record)

# --- Working day calculator (Oman: skip Friday only, 6-day week) -----------
def working_days(start, end):
    """Generator of dates from start to end, skipping Fridays (weekday()==4)."""
    d = start
    while d <= end:
        if d.weekday() != 4:  # Friday off
            yield d
        d += dt.timedelta(days=1)

WEATHER = ['Clear','Sunny','Hot','Partly Cloudy','Cloudy','Windy']

# --- Generate DPRs ----------------------------------------------------------
sql = [
    "-- OMAN-DEMO-KHASAB realistic DPR generation",
    "-- 1) Lock all activities (DPR precondition)",
    "-- 2) Wipe existing DPRs",
    "-- 3) Insert fresh DPRs per working day with realistic crew + qty",
    "-- 4) Update activity actuals (start/finish dates, percent_complete, status)",
    "-- 5) Rollup resource_assignment actuals",
    "BEGIN;",
    "SET LOCAL session_replication_role = 'replica';",
    "",
    "-- 1) Lock all OMAN activities",
    f"UPDATE activity.activities SET edit_status = 'LOCKED' WHERE project_id = '{OMAN_PID}';",
    "",
    "-- 2) Wipe existing DPRs and dependent rows",
    f"DELETE FROM project.dpr_manpower  WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{OMAN_PID}');",
    f"DELETE FROM project.dpr_equipment WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{OMAN_PID}');",
    f"DELETE FROM project.dpr_material  WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{OMAN_PID}');",
    f"DELETE FROM project.dpr_issues    WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{OMAN_PID}');",
    f"DELETE FROM project.dpr_attachments WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{OMAN_PID}');",
    f"DELETE FROM project.daily_activity_resource_outputs WHERE project_id='{OMAN_PID}';",
    f"DELETE FROM project.daily_progress_reports WHERE project_id='{OMAN_PID}';",
    "",
    "-- 2b) Reset resource_assignment actuals so the rollup at the end is authoritative",
    f"UPDATE resource.resource_assignments ra SET actual_units=0, actual_cost=0, remaining_units=COALESCE(planned_units,0), remaining_cost=COALESCE(planned_cost,0) WHERE project_id='{OMAN_PID}';",
    "",
    "-- 3) Insert DPRs + child rows",
]

dpr_count = 0
mp_child = 0
eq_child = 0
act_updates = []     # (aid, actual_start, actual_finish, pct, status)
ra_actuals = {}      # (activity_id, role_id, variant_id) -> sum_nos

def sql_str(s):
    if s is None: return "NULL"
    return "'" + str(s).replace("'", "''") + "'"

for aid, code, name, ps_s, pf_s, dur_s, wa_id, default_unit, wa_name, eq_output_s in acts:
    if not wa_id or not eq_output_s:
        continue  # cannot generate without WA + norm
    if not default_unit: default_unit = 'Cum'

    dur = int(dur_s) if dur_s else 30
    ps = dt.date.fromisoformat(ps_s)
    pf = dt.date.fromisoformat(pf_s)
    end_date = min(pf, TODAY)
    if ps > end_date:
        continue  # nothing to generate (future activity)

    eq_output_per_day = float(eq_output_s)
    days = list(working_days(ps, end_date))
    if not days: continue

    supervisors = sup_by_act.get(aid, [])
    if not supervisors:
        # No supervisor — inject a fallback so DPR still gets generated.
        # First active Site Supervisor in DB (cached at module level).
        if not hasattr(working_days, '_fallback_sup'):
            fb = psql("""SELECT id, first_name || ' ' || last_name
                FROM public.users WHERE designation = 'Site Supervisor'
                ORDER BY first_name LIMIT 1""")
            working_days._fallback_sup = (fb[0][0], fb[0][1]) if fb else None
        if working_days._fallback_sup:
            fb_id, fb_name = working_days._fallback_sup
            supervisors = [(fb_id, fb_name)]
            # Also INSERT the supervisor row so the activity is properly set up.
            sql.append(
              f"INSERT INTO activity.activity_supervisors "
              f"(id, activity_id, user_id, user_name_snapshot, version, created_at, updated_at) "
              f"VALUES (gen_random_uuid(), '{aid}', '{fb_id}', {sql_str(fb_name)}, 0, now(), now()) "
              f"ON CONFLICT DO NOTHING;"
            )
        else:
            continue

    mp_assignments = ra_by_act_mp.get(aid, [])
    eq_assignments = ra_by_act_eq.get(aid, [])
    if not mp_assignments and not eq_assignments:
        continue

    # Daily per-resource slice = total planned / activity duration
    # The total in resource_assignments is per_day_count × duration (from earlier resource-plan script)
    daily_mp = []
    for r in mp_assignments:
        per_day = max(1, round((r['headcount'] or 0) / max(dur, 1)))
        daily_mp.append({**r, 'per_day': per_day})
    daily_eq = []
    for r in eq_assignments:
        per_day = max(1, round((r['headcount'] or 0) / max(dur, 1)))
        daily_eq.append({**r, 'per_day': per_day})

    # qty_executed = eq_output × variation, then normalize so cumulative ≈ planned
    planned_total_qty = eq_output_per_day * len(days)
    raw_qtys = [eq_output_per_day * random.uniform(0.75, 1.20) for _ in days]
    scale = planned_total_qty / max(sum(raw_qtys), 0.01)
    qtys = [q * scale for q in raw_qtys]

    actual_start = days[0]
    cumulative = 0.0
    for idx, day in enumerate(days):
        sup_id, sup_name = supervisors[idx % len(supervisors)]
        qty = round(qtys[idx], 2)
        cumulative += qty
        dpr_id = f"gen_random_uuid()"  # placeholder; we'll use a CTE-style approach
        # Actually we need real UUIDs to FK child rows. Use a deterministic UUID via uuid5.
        import uuid
        dpr_uuid = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"oman-dpr-{aid}-{day.isoformat()}"))
        weather = random.choice(WEATHER)

        sql.append(
            f"INSERT INTO project.daily_progress_reports "
            f"(id, project_id, activity_id, activity_name, supervisor_user_id, supervisor_name, "
            f" report_date, qty_executed, unit, shift, approval_status, weather_condition, "
            f" version, created_at, updated_at) "
            f"VALUES ('{dpr_uuid}', '{OMAN_PID}', '{aid}', {sql_str(name)}, '{sup_id}', {sql_str(sup_name)}, "
            f" '{day.isoformat()}', {qty}, {sql_str(default_unit)}, 'DAY', 'APPROVED', {sql_str(weather)}, "
            f" 0, now(), now());"
        )
        dpr_count += 1

        for r in daily_mp:
            nos = r['per_day']
            wh = 8.0
            line_cost = nos * wh / 8 * (r['rate'] or 0)  # 1 day per nos
            sql.append(
                f"INSERT INTO project.dpr_manpower "
                f"(id, dpr_id, role_id, manpower_role_rate_id, trade, category, nos, working_hours, ot_hours, "
                f" unit_rate, unit_rate_basis, line_cost, version, created_at, updated_at) "
                f"VALUES (gen_random_uuid(), '{dpr_uuid}', '{r['role_id']}', '{r['variant_id']}', "
                f" {sql_str(r['role_name'])}, NULL, {nos}, {wh}, 0, {r['rate']}, 'DAY', {line_cost:.4f}, "
                f" 0, now(), now());"
            )
            mp_child += 1
            key = (aid, r['role_id'], r['variant_id'])
            ra_actuals[key] = ra_actuals.get(key, 0) + nos

        for r in daily_eq:
            nos = r['per_day']
            wh = 8.0
            line_cost = nos * (r['rate'] or 0)  # nos × daily-rate
            sql.append(
                f"INSERT INTO project.dpr_equipment "
                f"(id, dpr_id, role_id, equipment_role_variant_id, equipment_type, ownership, "
                f" availability_status, nos, working_hours, idle_hours, breakdown_hours, fuel_litres, "
                f" unit_rate, unit_rate_basis, line_cost, version, created_at, updated_at) "
                f"VALUES (gen_random_uuid(), '{dpr_uuid}', '{r['role_id']}', '{r['variant_id']}', "
                f" {sql_str(r['role_name'])}, 'OWNED', 'UTILIZED', {nos}, {wh}, 0, 0, 0, "
                f" {r['rate']}, 'DAY', {line_cost:.4f}, 0, now(), now());"
            )
            eq_child += 1
            key = (aid, r['role_id'], r['variant_id'])
            ra_actuals[key] = ra_actuals.get(key, 0) + nos

    # Activity actuals update
    pct = min(100.0, (cumulative / planned_total_qty * 100) if planned_total_qty > 0 else 0)
    if pct >= 99.5:
        status = 'COMPLETED'
        actual_finish = days[-1]
    elif pct > 0:
        status = 'IN_PROGRESS'
        actual_finish = None
    else:
        status = 'NOT_STARTED'
        actual_finish = None
    act_updates.append((aid, actual_start, actual_finish, round(pct, 2), status))

sql.append("")
sql.append("-- 4) Update activity actuals based on generated DPRs")
for aid, actual_start, actual_finish, pct, status in act_updates:
    af = f"'{actual_finish.isoformat()}'" if actual_finish else "NULL"
    sql.append(
        f"UPDATE activity.activities SET "
        f"actual_start_date='{actual_start.isoformat()}', "
        f"actual_finish_date={af}, "
        f"percent_complete={pct}, "
        f"status='{status}' "
        f"WHERE id='{aid}';"
    )

sql.append("")
sql.append("-- 5) Rollup resource_assignment actuals from DPR child SUMs")
for (aid, rid, vid), total_nos in ra_actuals.items():
    sql.append(
        f"UPDATE resource.resource_assignments ra SET "
        f"actual_units={total_nos}, "
        f"actual_cost={total_nos} * COALESCE(ra.effective_rate, 0), "
        f"remaining_units=GREATEST(COALESCE(ra.planned_units,0) - {total_nos}, 0), "
        f"remaining_cost=GREATEST(COALESCE(ra.planned_cost,0) - {total_nos} * COALESCE(ra.effective_rate,0), 0) "
        f"WHERE activity_id='{aid}' AND role_id='{rid}' AND ("
        f"manpower_role_rate_id='{vid}' OR equipment_role_variant_id='{vid}');"
    )

sql.append("")
sql.append("-- Verification")
sql.append(f"SELECT 'dprs' AS what, count(*)::text FROM project.daily_progress_reports WHERE project_id='{OMAN_PID}'")
sql.append("UNION ALL SELECT 'dpr_manpower', count(*)::text FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='" + OMAN_PID + "'")
sql.append("UNION ALL SELECT 'dpr_equipment', count(*)::text FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='" + OMAN_PID + "'")
sql.append(f"UNION ALL SELECT 'activities locked', count(*)::text FROM activity.activities WHERE project_id='{OMAN_PID}' AND edit_status='LOCKED'")
sql.append(f"UNION ALL SELECT 'activities completed', count(*)::text FROM activity.activities WHERE project_id='{OMAN_PID}' AND status='COMPLETED'")
sql.append("ORDER BY what;")
sql.append("")
sql.append("RESET session_replication_role;")
sql.append("COMMIT;")

out_path = os.path.join(SCRIPT_DIR, 'oman-dprs-realistic.sql')
with open(out_path, 'w') as f:
    f.write('\n'.join(sql))

print(f"\nGenerated {dpr_count} DPRs, {mp_child} manpower rows, {eq_child} equipment rows")
print(f"Activity updates queued: {len(act_updates)}")
print(f"ResourceAssignment rollups: {len(ra_actuals)}")
print(f"SQL bundle -> {out_path}")
