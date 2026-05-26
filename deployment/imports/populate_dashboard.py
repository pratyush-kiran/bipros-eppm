import os
#!/usr/bin/env python3
"""Populate the blank dashboard pieces:
1. Link Oman calendar to project + all activities (so Run Schedule works)
2. Populate project.daily_weather from DPR weather (so Site Conditions shows temp/wind/rain/aqi)
3. Create 6 milestone activities with FUTURE dates (so Project Timeline Preview populates)
4. Create DPR issues (so Open Issues / Active Alerts show)
"""
import subprocess
import json
import random
from datetime import date, timedelta

PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
PSQL = os.environ.get("BIPROS_PSQL", "psql")
PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", PSQL, "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"), "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"), "-A", "-F", "|", "-t", "-c"]
# Pick whatever calendar the DB has (DataSeeder creates a default "Standard"
# at first boot). If somehow none exists, the linking step below is a no-op.
_cal = subprocess.run(PG_BASE + ["SELECT id::text FROM scheduling.calendars ORDER BY created_at LIMIT 1"], capture_output=True, text=True, timeout=10)
OMAN_CAL = _cal.stdout.strip() or "07331f65-9b82-423c-b7ae-5926e0fe0658"


def sql(q, ignore=False):
    out = subprocess.run(PG_BASE + [q], capture_output=True, text=True, timeout=60)
    if out.returncode != 0 and not ignore:
        raise Exception(f"SQL: {out.stderr[:300]}")
    return [line for line in out.stdout.strip().split("\n") if line.strip()]


# ====================================================================
# 1. Link Oman 5-day calendar to project + all activities
# ====================================================================
print("=== 1. Link Oman 5-day calendar ===")
sql(f"UPDATE project.projects SET calendar_id='{OMAN_CAL}', updated_at=now() WHERE id='{PROJECT_ID}'")
sql(f"UPDATE activity.activities SET calendar_id='{OMAN_CAL}', updated_at=now() WHERE project_id='{PROJECT_ID}'")
print(f"  Project + 33 activities linked to Oman 5-day Construction Calendar")


# ====================================================================
# 2. Populate project.daily_weather with realistic Khasab climate
# ====================================================================
print("\n=== 2. Populate daily_weather (Jan-Mar 2026 + recent for Site Conditions) ===")
sql(f"DELETE FROM project.daily_weather WHERE project_id='{PROJECT_ID}'", ignore=True)

# Get all distinct DPR dates from this project
dpr_dates = sql(f"""
SELECT DISTINCT report_date::text, weather_condition
FROM project.daily_progress_reports
WHERE project_id='{PROJECT_ID}'
ORDER BY report_date
""")
print(f"  DPR dates: {len(dpr_dates)}")

# Realistic Khasab (Musandam, Oman) climate by month
KHASAB_CLIMATE = {
    1: (15, 24, 6, 12),   # Jan: 15-24°C, low rain, wind 6-12 km/h
    2: (16, 26, 8, 14),
    3: (19, 30, 5, 15),
    4: (23, 34, 2, 18),
    5: (28, 38, 1, 22),   # peak heat starts
}

# Recent dates for Site Conditions (today + last few days)
recent_dates = [(date.today() - timedelta(days=i)).isoformat() for i in range(7)]

all_weather_rows = []
random.seed(42)
for line in dpr_dates:
    parts = line.split("|")
    d_str = parts[0]
    wc = parts[1] if len(parts) > 1 else "CLEAR"
    d = date.fromisoformat(d_str)
    lo_t, hi_t, lo_w, hi_w = KHASAB_CLIMATE.get(d.month, (22, 32, 4, 16))
    temp_min = round(lo_t + random.uniform(-2, 2), 1)
    temp_max = round(hi_t + random.uniform(-2, 2), 1)
    wind = round(lo_w + random.uniform(0, hi_w - lo_w), 1)
    rain = 0
    if wc == "RAIN":
        rain = round(random.uniform(2, 18), 1)
    elif wc in ("CLOUDY", "PARTLY_CLOUDY"):
        rain = round(random.uniform(0, 1.5), 1)
    all_weather_rows.append((d_str, wc, temp_min, temp_max, wind, rain))

# Add recent dates so Site Conditions has data
for d_str in recent_dates:
    d = date.fromisoformat(d_str)
    lo_t, hi_t, lo_w, hi_w = KHASAB_CLIMATE.get(d.month, (24, 34, 4, 16))
    temp_min = round(lo_t + random.uniform(-1, 1), 1)
    temp_max = round(hi_t + random.uniform(-1, 1), 1)
    wind = round(lo_w + random.uniform(2, hi_w - lo_w), 1)
    wc = random.choice(['CLEAR', 'PARTLY_CLOUDY', 'CLEAR', 'CLEAR', 'WINDY'])
    rain = 0
    all_weather_rows.append((d_str, wc, temp_min, temp_max, wind, rain))

# Insert in batches
print(f"  Inserting {len(all_weather_rows)} weather rows...")
for r in all_weather_rows:
    d_str, wc, tmin, tmax, wind, rain = r
    sql(f"""
INSERT INTO project.daily_weather (id, created_at, updated_at, project_id, log_date,
  weather_condition, temp_min_c, temp_max_c, wind_kmh, rainfall_mm, working_hours)
VALUES (gen_random_uuid(), now(), now(), '{PROJECT_ID}', '{d_str}',
  '{wc}', {tmin}, {tmax}, {wind}, {rain}, 8.0)
ON CONFLICT (project_id, log_date) DO UPDATE SET
  temp_min_c=EXCLUDED.temp_min_c, temp_max_c=EXCLUDED.temp_max_c,
  wind_kmh=EXCLUDED.wind_kmh, rainfall_mm=EXCLUDED.rainfall_mm,
  weather_condition=EXCLUDED.weather_condition
""", ignore=True)
# Check
wc = sql(f"SELECT COUNT(*) FROM project.daily_weather WHERE project_id='{PROJECT_ID}'")
print(f"  Total weather rows: {wc[0]}")


# ====================================================================
# 3. Create 6 milestone activities with FUTURE dates (drives Timeline Preview)
# ====================================================================
print("\n=== 3. Create 6 milestone activities with future dates ===")
# Get a WBS leaf for the milestones
wbs_id = sql(f"""
SELECT id::text FROM project.wbs_nodes WHERE project_id='{PROJECT_ID}' AND code='1.0' LIMIT 1
""")[0]

today = date.today()
milestones = [
    ("M01", "Site Mobilization Complete", "START_MILESTONE", today + timedelta(days=7)),
    ("M02", "Pavement Layer 1 (GSB) Start", "START_MILESTONE", today + timedelta(days=30)),
    ("M03", "Bridge Deck Slab Casting", "FINISH_MILESTONE", today + timedelta(days=60)),
    ("M04", "Bituminous Layer Complete", "FINISH_MILESTONE", today + timedelta(days=90)),
    ("M05", "Roadside Furniture & Signage", "FINISH_MILESTONE", today + timedelta(days=120)),
    ("M06", "Final Handover", "FINISH_MILESTONE", today + timedelta(days=150)),
]
for code, name, mtype, when in milestones:
    sql(f"""
INSERT INTO activity.activities (id, created_at, updated_at, version, project_id, wbs_node_id, code, name,
  activity_type, duration_type, percent_complete_type, status, edit_status,
  percent_complete, is_critical, is_preliminary, calendar_id,
  original_duration, remaining_duration,
  planned_start_date, planned_finish_date,
  early_start_date, early_finish_date, late_start_date, late_finish_date,
  primary_constraint_type, primary_constraint_date)
VALUES (gen_random_uuid(), now(), now(), 0, '{PROJECT_ID}', '{wbs_id}', '{code}', '{name.replace("'", "''")}',
  '{mtype}', 'FIXED_DURATION_AND_UNITS', 'DURATION', 'NOT_STARTED', 'LOCKED',
  0, false, false, '{OMAN_CAL}',
  0, 0,
  '{when.isoformat()}', '{when.isoformat()}',
  '{when.isoformat()}', '{when.isoformat()}', '{when.isoformat()}', '{when.isoformat()}',
  'START_ON', '{when.isoformat()}')
ON CONFLICT (project_id, code) DO UPDATE SET
  planned_start_date='{when.isoformat()}', planned_finish_date='{when.isoformat()}',
  early_start_date='{when.isoformat()}', early_finish_date='{when.isoformat()}',
  late_start_date='{when.isoformat()}', late_finish_date='{when.isoformat()}',
  primary_constraint_date='{when.isoformat()}', updated_at=now()
""", ignore=True)
ms = sql(f"SELECT COUNT(*) FROM activity.activities WHERE project_id='{PROJECT_ID}' AND activity_type IN ('START_MILESTONE','FINISH_MILESTONE')")
print(f"  Milestones: {ms[0]}")


# ====================================================================
# 4. Create DPR issues for Open Issues / Active Alerts (~6 entries)
# ====================================================================
print("\n=== 4. Create DPR issues for Alerts ===")
sql(f"""DELETE FROM project.dpr_issues WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id='{PROJECT_ID}')""", ignore=True)
# Insert 6 representative issues bound to random DPRs with qty_executed > 1
sql(f"""
WITH sample_dprs AS (
  SELECT d.id AS dpr_id, d.activity_id, d.activity_name, d.report_date,
         d.supervisor_user_id, d.supervisor_name,
         ROW_NUMBER() OVER (ORDER BY random()) AS rn
  FROM project.daily_progress_reports d
  WHERE d.project_id = '{PROJECT_ID}' AND d.qty_executed > 1
  LIMIT 6
),
issues(ord, title, descr, cat, sev, status) AS (VALUES
  (1, 'Excavator breakdown - Front 1', 'Hydraulic hose failure on EX-04. Repair in progress.', 'EQUIPMENT_BREAKDOWN', 'HIGH', 'IN_PROGRESS'),
  (2, 'Cement supply delay',            'Truck delayed at customs - 8 hrs late, holding RCC pour.', 'MATERIAL_SHORTAGE', 'MEDIUM', 'OPEN'),
  (3, 'PPE non-compliance',             'Three workers without hard hats at face. Retraining scheduled.', 'SAFETY', 'MEDIUM', 'RESOLVED'),
  (4, 'Sandstorm forecast - Friday',    'NCM advisory for high wind 25-Mar 14:00. Defer crane lifts.', 'WEATHER', 'HIGH', 'OPEN'),
  (5, 'GSB blend below CBR spec',       'Batch-12 returned CBR 28% (spec 30%). Retest scheduled.', 'QUALITY', 'HIGH', 'IN_PROGRESS'),
  (6, 'Land access denied at km 4+200', 'Landowner refusing access. PD to escalate to district authority.', 'LAND_ACCESS', 'CRITICAL', 'OPEN')
)
INSERT INTO project.dpr_issues
  (id, created_at, updated_at, version, project_id, dpr_id, activity_id, activity_name,
   report_date, opened_at, title, description, category, severity, status,
   supervisor_user_id, supervisor_name)
SELECT
  gen_random_uuid(), now(), now(), 0, '{PROJECT_ID}',
  s.dpr_id, s.activity_id, s.activity_name, s.report_date, s.report_date::timestamptz,
  i.title, i.descr, i.cat, i.sev, i.status,
  s.supervisor_user_id, s.supervisor_name
FROM sample_dprs s
JOIN issues i ON i.ord = s.rn
""")
ci = sql(f"SELECT COUNT(*) FROM project.dpr_issues WHERE project_id='{PROJECT_ID}'")
print(f"  DPR issues created: {ci[0]}")
