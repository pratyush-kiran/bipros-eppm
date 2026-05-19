# Verifying DBS Calculations

The DBS is computed end-to-end from your raw daily data. If a number on a
report looks wrong, use this checklist to trace it back to source.

## Quick health checks (built-in)
The system flags four alert codes on every DBS tab:
| Code | Meaning |
|---|---|
| `LOW_CONTRIBUTION_PCT` | Contribution < 5 % |
| `NEGATIVE_CONTRIBUTION` | Cost > Income |
| `RUNAWAY_FUEL` | Fuel > 50 % of total expense |
| `MISSING_RATE_DATA` | At least one line has no rate (cost will be under-counted) |

If `MISSING_RATE_DATA` is shown, fix the rate first — every other number
downstream is suspect.

## How each number is computed

### Supervisor Manpower (Section A)
For each manpower row on every DPR by this supervisor on this date:
```
line_cost = nos × working_hours × hourly_rate
```
Where `hourly_rate` comes from `manpower_role_rates.rate` (matched by the
DPR row's `role_id`). Sum across rows = `manpower_amount`.

### Supervisor Machinery (Section C)
Same formula, using `equipment_rate_masters.rate` matched by `role_id`.

### Supervisor BOQ (Section F)
For each activity progressed today:
```
boq_for_the_day_amount += qty_executed × unit_rate
boq_planned_to_date    = sum of planned-to-date across all activities
boq_achieved_to_date   = sum of achieved-to-date across all activities
```
`direct_cost` and `prelim_cost` partition the total by the activity's
`is_preliminary` flag.

### Engineer / Site Manager / CM / PM rollups
Each tier is **the arithmetic sum of all rows in the tier below**, using
the reporting chain stored in `project_team`. No re-derivation from raw
data — once a supervisor's row is correct, all higher rows follow.

This is important: **if a supervisor's number is wrong, fix the supervisor's
DPR**, then trigger recompute. Don't try to patch the rollup.

## Manual verification — the spot-check workflow

1. **Pick a single supervisor, single date.**
2. Open Supervisor tab → note `manpower_amount`.
3. Open the DPR(s) for that supervisor on that date.
4. For each manpower row, multiply `nos × working_hours × rate` and sum.
5. The numbers must match. If they don't:
   - Is a row missing a `role_id`? It will have `line_cost = 0`.
   - Is the rate stale? Check `Admin → Rates`.
   - Has the DPR been edited since the last recompute? Click ⟳ on the PM tab.

## Database-level verification (advanced)

The aggregate tables are in the `dbs` schema:
| Table | What it stores |
|---|---|
| `dbs.dbs_daily_supervisor` | One row per (project, supervisor, date) |
| `dbs.dbs_daily_engineer` | One row per (project, engineer, date) |
| `dbs.dbs_daily_cm` | One row per (project, CM, date) |
| `dbs.dbs_daily_project` | One row per (project, date) |
| `dbs.dbs_equipment_register` | One row per (project, date, CM, equipment, shift) |
| `dbs.dbs_manpower_register` | One row per (project, date, CM, trade, shift) |

To spot-check a CM number, run (in DBeaver / psql):

```sql
SELECT sum(direct_cost)            AS total_direct,
       sum(prelim_cost)            AS total_prelim,
       sum(total_cost_incl_prelims) AS total_incl
FROM dbs.dbs_daily_supervisor
WHERE project_id = '<uuid>'
  AND construction_manager_user_id = '<cm uuid>'
  AND report_date = '<date>';
```

These must match the `dbs_daily_cm` row for the same `(project, cm, date)`.

## Forcing a full recompute
If you suspect cached data is stale:
1. PM tab → ⟳ "Recompute today" (single date) — hits
   `POST /v1/projects/{id}/dbs/recompute?date=YYYY-MM-DD`.
2. Or admin range rebuild:
   `POST /v1/projects/{id}/dbs/recompute-range?from=YYYY-MM-DD&to=YYYY-MM-DD`.
3. Recompute is idempotent and safe to run repeatedly.
