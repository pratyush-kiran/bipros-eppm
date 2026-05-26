-- Repair the denormalized daily_progress_reports.activity_name snapshot whenever
-- it drifts from activity.activities.name. The DPR list view groups rows by
-- d.activity_name, so any drift here surfaces as stale group headers in the UI
-- ("Khasab 1.1" instead of "Diversion of existing roads...").
--
-- Drift happens when an activity is renamed via a path that bypasses the
-- ActivityRenameDprSyncListener (e.g. a raw SQL UPDATE on activity.activities,
-- a Liquibase migration, a Flyway script, or an older copy of
-- rename_activities_add_risks_weather.py that didn't sync DPRs).
--
-- The API rename endpoint (PUT /v1/projects/{pid}/activities/{aid}) keeps DPRs
-- in sync automatically — no need to run this after using it.
--
-- Safe to run anytime; idempotent.

BEGIN;

WITH drift AS (
  SELECT d.id, a.name AS correct_name, d.activity_name AS stale_name
  FROM project.daily_progress_reports d
  JOIN activity.activities a ON a.id = d.activity_id
  WHERE d.activity_name IS DISTINCT FROM a.name
)
SELECT COUNT(*) AS dpr_rows_with_stale_activity_name FROM drift;

UPDATE project.daily_progress_reports d
SET activity_name = a.name,
    updated_at    = NOW(),
    updated_by    = COALESCE(d.updated_by, 'fix_dpr_activity_name_drift.sql')
FROM activity.activities a
WHERE d.activity_id = a.id
  AND d.activity_name IS DISTINCT FROM a.name;

-- Confirm no drift remains.
SELECT COUNT(*) AS rows_still_stale
FROM project.daily_progress_reports d
JOIN activity.activities a ON a.id = d.activity_id
WHERE d.activity_name IS DISTINCT FROM a.name;

COMMIT;
