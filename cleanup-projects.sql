-- One-shot hard cleanup: deletes every project except OMAN-DEMO-KHASAB plus all rows
-- linked via project_id (108 tables), activity_id (10 tables w/o direct project_id), and
-- dpr_id (7 tables w/o direct project_id). Uses session_replication_role=replica to bypass
-- FK ordering. The whole thing runs in ONE transaction so it's a single commit or rollback.

BEGIN;
SET LOCAL session_replication_role = 'replica';

-- ── 1. Snapshot the doomed ids before anything is deleted ───────────────────────
CREATE TEMP TABLE _doomed_projects (id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _doomed_projects (id)
SELECT id FROM project.projects
 WHERE code IN ('6155','FLYOVER-01','HIGHWAY-101','MAHANADI-CTC-01','SC-180');

CREATE TEMP TABLE _doomed_activities (id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _doomed_activities (id)
SELECT a.id FROM activity.activities a
  JOIN _doomed_projects p ON a.project_id = p.id;

CREATE TEMP TABLE _doomed_dprs (id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _doomed_dprs (id)
SELECT d.id FROM project.daily_progress_reports d
  JOIN _doomed_projects p ON d.project_id = p.id;

SELECT 'snapshot: projects'   AS what, count(*) FROM _doomed_projects
UNION ALL SELECT 'snapshot: activities', count(*) FROM _doomed_activities
UNION ALL SELECT 'snapshot: dprs',       count(*) FROM _doomed_dprs;

-- ── 2. Child tables linked via activity_id but no project_id ───────────────────
DELETE FROM activity.activity_code_assignments     WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM activity.activity_steps                WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM activity.activity_supervisors          WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM baseline.baseline_activities           WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM baseline.baseline_expenses             WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM baseline.baseline_resource_assignments WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM risk.monte_carlo_activity_stats        WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM risk.monte_carlo_milestone_stats       WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM scheduling.pert_estimates              WHERE activity_id IN (SELECT id FROM _doomed_activities);
DELETE FROM scheduling.schedule_activity_results   WHERE activity_id IN (SELECT id FROM _doomed_activities);

-- ── 3. Child tables linked via dpr_id but no project_id ────────────────────────
DELETE FROM project.dpr_equipment          WHERE dpr_id IN (SELECT id FROM _doomed_dprs);
DELETE FROM project.dpr_equipment_lines    WHERE dpr_id IN (SELECT id FROM _doomed_dprs);
DELETE FROM project.dpr_manpower           WHERE dpr_id IN (SELECT id FROM _doomed_dprs);
DELETE FROM project.dpr_manpower_lines     WHERE dpr_id IN (SELECT id FROM _doomed_dprs);
DELETE FROM project.dpr_material           WHERE dpr_id IN (SELECT id FROM _doomed_dprs);
DELETE FROM project.dpr_material_lines     WHERE dpr_id IN (SELECT id FROM _doomed_dprs);
DELETE FROM project.dpr_subcontract_lines  WHERE dpr_id IN (SELECT id FROM _doomed_dprs);

-- ── 4. Every table carrying project_id, in one dynamic loop ────────────────────
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT n.nspname, c.relname
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE a.attname = 'project_id'
      AND a.attnum > 0 AND NOT a.attisdropped
      AND c.relkind = 'r'
      AND n.nspname NOT IN ('pg_catalog','information_schema')
  LOOP
    EXECUTE format(
      'DELETE FROM %I.%I WHERE project_id IN (SELECT id FROM _doomed_projects)',
      r.nspname, r.relname);
  END LOOP;
END $$;

-- ── 5. Finally drop the project rows themselves ────────────────────────────────
DELETE FROM project.projects WHERE id IN (SELECT id FROM _doomed_projects);

-- ── 6. Sanity-check: only OMAN should remain ───────────────────────────────────
SELECT 'post: projects' AS what, code, name FROM project.projects ORDER BY code;
SELECT 'post: activities count for OMAN' AS what, count(*)::text AS value
  FROM activity.activities WHERE project_id = 'd901671a-cd23-41c6-8886-d2c1b0ddd3c5';
SELECT 'post: activities for any deleted project (should be 0)' AS what, count(*)::text AS value
  FROM activity.activities a
  WHERE a.project_id <> 'd901671a-cd23-41c6-8886-d2c1b0ddd3c5';

RESET session_replication_role;
COMMIT;
