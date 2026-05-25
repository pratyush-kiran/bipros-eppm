# Calendar configuration + Dashboard population

This document explains every change made to:
1. **Wire up project calendar** so "Run Schedule" works
2. **Populate the Project Overview dashboard** (Site Conditions, Timeline Preview, Open Issues)
3. **Where the calendar is referenced** across the application

---

## 1. Calendar setup

### The problem
The "Run Schedule" button on the Activities tab errors out with `Calendar not set` because:
- `project.projects.calendar_id` was NULL
- `activity.activities.calendar_id` was NULL on all 33 activities

The CPM scheduler at `POST /v1/projects/{pid}/schedule` walks each activity, looks up its working calendar (or falls back to the project calendar), and refuses to schedule any activity without one.

### What we set

Used the pre-seeded **Oman 5-day Construction Calendar (Sun–Thu)** (`a74ca9d7-019f-46a7-8090-ce94c5b802cc`) — it matches Khasab's working week (Friday + Saturday off).

```sql
-- 1. Project calendar
UPDATE project.projects
SET calendar_id = 'a74ca9d7-019f-46a7-8090-ce94c5b802cc'
WHERE id = '<PROJECT_ID>';

-- 2. All 33 task activities + 6 milestones
UPDATE activity.activities
SET calendar_id = 'a74ca9d7-019f-46a7-8090-ce94c5b802cc'
WHERE project_id = '<PROJECT_ID>';
```

### Where the calendar is referenced in the application

| Reference | Layer | What uses it |
|---|---|---|
| `project.projects.calendar_id` | Schema | Default working calendar for the project. Inherited by activities that don't set their own. |
| `activity.activities.calendar_id` | Schema | Per-activity working calendar. Critical: scheduler reads this first. |
| `scheduling.calendars` (master) | Schema | The 2 pre-seeded calendars: `Standard` and `Oman 5-day Construction Calendar (Sun–Thu)`. |
| `scheduling.calendar_work_weeks` | Schema | Per-day-of-week working hours (e.g. Sun-Thu 8h, Fri-Sat 0h for Oman calendar). |
| `scheduling.calendar_exceptions` | Schema | National holidays / scheduled non-working days. |
| `POST /v1/projects/{pid}/schedule` | API | Run Schedule button — requires project + activity calendar. |
| `ScheduleController` (bipros-scheduling) | Controller | Entry point that resolves calendar per activity. |
| `CalendarService` / `WorkingDayResolver` (bipros-calendar) | Service | Computes business-days between two dates. |
| `Run Schedule` button on `/projects/{pid}/activities` | UI | Calls the schedule endpoint. |
| `Activity details drawer → Calendar` field | UI | Per-activity override picker (read from `activities.calendar_id`). |
| `Admin → Master Data → Calendars` | UI (`/admin/calendars`) | Create/edit calendars, exceptions, work-weeks. |

### To pick a different calendar

If a fresh environment doesn't have the Oman calendar, query what exists and use its UUID:

```sql
SELECT id, name, calendar_type, is_default FROM scheduling.calendars;
```

Or create one:

```sql
INSERT INTO scheduling.calendars
  (id, created_at, updated_at, calendar_type, code, name, is_default,
   standard_work_days_per_week, standard_work_hours_per_day)
VALUES
  (gen_random_uuid(), now(), now(), 'PROJECT', 'KHASAB-CAL',
   'Khasab Project Calendar', false, 5, 8)
RETURNING id;
```

Then insert work-week entries into `scheduling.calendar_work_weeks` and any holidays into `scheduling.calendar_exceptions`.

---

## 2. Dashboard population

The Project Overview hero card has 4 KPIs + 4 sub-panels. Before this round, 3 sub-panels were empty:
- "Project Timeline Preview" → "No scheduled phases in this window"
- "Site Conditions" → TEMP/WIND/RAIN/AQI all "Latest reading unavailable"
- "Active Alerts" → "No open alerts"
- "Open Issues" → 0

### Project Timeline Preview

**Source:** `ProjectInsightsController.statusSnapshot()` queries:
```sql
SELECT * FROM activity.activities
WHERE project_id = ? AND activity_type IN ('START_MILESTONE','FINISH_MILESTONE')
  AND planned_finish_date >= CURRENT_DATE
ORDER BY planned_finish_date
LIMIT 6;
```

**Fix:** All 33 existing activities were `TASK_DEPENDENT` with dates in the past (Jan–Mar 2026). We added **6 milestones with future dates** spread across the next 5 months:

| Code | Type | Date | Name |
|---|---|---|---|
| M01 | START_MILESTONE | today+7d | Site Mobilization Complete |
| M02 | START_MILESTONE | today+30d | Pavement Layer 1 (GSB) Start |
| M03 | FINISH_MILESTONE | today+60d | Bridge Deck Slab Casting |
| M04 | FINISH_MILESTONE | today+90d | Bituminous Layer Complete |
| M05 | FINISH_MILESTONE | today+120d | Roadside Furniture & Signage |
| M06 | FINISH_MILESTONE | today+150d | Final Handover |

SQL: `scripts/populate_dashboard.py` step 3.

### Site Conditions

**Source:** Frontend reads `GET /v1/projects/{pid}/dashboards/field/summary` which returns a `latestWeather` object from `project.daily_weather` ordered by `log_date DESC LIMIT 1`.

**Fix:** Populated 60 rows in `project.daily_weather`:
- 53 historical (Jan–Mar 2026 — same dates as DPRs) — `weather_condition`, `temp_min_c`, `temp_max_c`, `wind_kmh`, `rainfall_mm` derived from Khasab climate norms (15–38°C range, low rainfall, 6–18 km/h wind)
- 7 recent (today, today-1, … today-6) — so the "latest reading" picks up

After:
```sql
SELECT log_date, weather_condition, temp_min_c, temp_max_c, wind_kmh, rainfall_mm
FROM project.daily_weather
WHERE project_id = '<PROJECT_ID>'
ORDER BY log_date DESC LIMIT 1;
```
Returns today's row → Site Conditions tile shows actual numbers.

**Note on AQI:** The schema doesn't have an `aqi` column on `daily_weather`. The UI shows "Not available" for AQI because the backend doesn't expose it. To add AQI we'd need to extend `project.daily_weather` with `aqi` integer, update the entity + response DTO. Not done in this round — would require a code+migration change.

### Open Issues + Active Alerts

**Source:** `ProjectInsightsController.statusSnapshot()` counts rows in `project.dpr_issues` for the project:
```sql
SELECT COUNT(*) FROM project.dpr_issues
WHERE project_id = ? AND status IN ('OPEN', 'IN_PROGRESS', 'BLOCKED');
```

**Fix:** Created **6 DPR issues** attached to random DPRs — mix of categories + severities + statuses:

| Title | Category | Severity | Status |
|---|---|---|---|
| Excavator breakdown — hydraulic line burst | EQUIPMENT_BREAKDOWN | HIGH | OPEN |
| Concrete pour delayed — RMC truck late by 3 hrs | SUBCONTRACTOR | MEDIUM | OPEN |
| Safety near-miss — helper near excavation edge | SAFETY | HIGH | IN_PROGRESS |
| Quality non-conformance — slump test failed | QUALITY | MEDIUM | RESOLVED |
| Material shortage — Steel rebar arrival delay | MATERIAL_SHORTAGE | HIGH | OPEN |
| Weather impact — wind > 25 km/h, crane suspended | WEATHER | LOW | RESOLVED |

After: Open Issues tile shows **4** (3 OPEN + 1 IN_PROGRESS), Active Alerts list shows the HIGH-severity OPEN ones.

### Valid enum values (gotchas)

| Field | Valid values |
|---|---|
| `dpr_issues.category` | SAFETY, QUALITY, MATERIAL_SHORTAGE, EQUIPMENT_BREAKDOWN, MANPOWER_SHORTAGE, WEATHER, DESIGN_CHANGE, LAND_ACCESS, UTILITY_CLASH, PERMIT_DELAY, SUBCONTRACTOR, ENVIRONMENTAL, OTHER |
| `dpr_issues.severity` | LOW, MEDIUM, HIGH, CRITICAL |
| `dpr_issues.status` | OPEN, IN_PROGRESS, BLOCKED, RESOLVED, CLOSED, CANCELLED |
| `risks.status` | IDENTIFIED, ANALYZING, MITIGATING, RESOLVED, CLOSED, ACCEPTED, REJECTED, REALISED |
| `risks.category` | Must be a code from `risk.risk_category_master` (e.g. `MW-GENERIC`, `HSE-GENERIC`, `CG-PROCUREMENT-LEAD-TIME`) — NOT free text |
| `activity.activity_type` | TASK_DEPENDENT, RESOURCE_DEPENDENT, LEVEL_OF_EFFORT, START_MILESTONE, FINISH_MILESTONE, WBS_SUMMARY |

---

## 3. Running this in a new environment

See [`RUNBOOK.md`](RUNBOOK.md) — the Path D section now includes the calendar + dashboard steps at the end.

For just the calendar + dashboard populate (assuming you have a project with DPRs already):

```bash
python3 scripts/populate_dashboard.py   # calendar + weather + milestones (DPR issues handled inline)
```

After this, "Run Schedule" succeeds + Project Overview tiles are populated.

---

## 4. Verification queries

```sql
-- Project + activities have calendar
SELECT
  (SELECT calendar_id FROM project.projects WHERE id = '<PROJECT_ID>') AS project_cal,
  (SELECT COUNT(*) FROM activity.activities
   WHERE project_id = '<PROJECT_ID>' AND calendar_id IS NOT NULL) AS activities_with_cal,
  (SELECT COUNT(*) FROM activity.activities
   WHERE project_id = '<PROJECT_ID>' AND activity_type IN ('START_MILESTONE','FINISH_MILESTONE')
     AND planned_finish_date >= CURRENT_DATE) AS future_milestones,
  (SELECT COUNT(*) FROM project.daily_weather WHERE project_id = '<PROJECT_ID>') AS weather_rows,
  (SELECT COUNT(*) FROM project.dpr_issues
   WHERE project_id = '<PROJECT_ID>' AND status IN ('OPEN','IN_PROGRESS')) AS open_issues,
  (SELECT COUNT(*) FROM risk.risks WHERE project_id = '<PROJECT_ID>') AS risks;
```

Expected: project_cal SET, activities_with_cal=39, future_milestones=6, weather_rows≥60, open_issues=4, risks=8.
