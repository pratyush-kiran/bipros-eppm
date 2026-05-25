-- Remove the OMAN-DEMO-KHASAB demo project and every row tied to it.
--
-- Use this when the @Profile("seed") OmanDemo seeders have already populated the
-- demo project and you want to keep only the KHASAB-001 bootstrap data. Once the
-- active Spring profile is switched away from "seed" (see bipros-api application.yml),
-- the seeders won't recreate this on next boot.
--
-- Safe to re-run — every DELETE is scoped to the OMAN-DEMO project; it touches
-- no rows in your bootstrap KHASAB-001 project.
--
-- Run from psql / pgAdmin / IntelliJ DB tool:
--   psql -h localhost -U bipros -d bipros -f backend/bipros-data-bootstrap/scripts/cleanup-oman-demo.sql

\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    omd_project_id UUID;
BEGIN
    SELECT id INTO omd_project_id FROM project.projects WHERE code = 'OMAN-DEMO-KHASAB';
    IF omd_project_id IS NULL THEN
        RAISE NOTICE 'OMAN-DEMO-KHASAB not found — nothing to clean.';
        RETURN;
    END IF;
    RAISE NOTICE 'Cleaning OMAN-DEMO-KHASAB (project_id = %)', omd_project_id;

    -- DPR children + ledger
    DELETE FROM project.dpr_issues   WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id = omd_project_id);
    DELETE FROM project.dpr_material WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id = omd_project_id);
    DELETE FROM project.dpr_equipment WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id = omd_project_id);
    DELETE FROM project.dpr_manpower WHERE dpr_id IN (SELECT id FROM project.daily_progress_reports WHERE project_id = omd_project_id);
    DELETE FROM project.daily_progress_reports WHERE project_id = omd_project_id;
    DELETE FROM project.daily_activity_resource_outputs WHERE project_id = omd_project_id;

    -- Material ledger (created by OmanDemoMaterialLedgerSeeder)
    -- Best-effort: skip if these tables don't exist on this schema.
    BEGIN
        DELETE FROM resource.material_consumption_log WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM resource.material_issue WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM resource.material WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;

    -- Resource plan
    DELETE FROM resource.resource_assignments WHERE project_id = omd_project_id;

    -- DBS aggregation cache + register tables. A startup recompute job iterates these and
    -- re-derives DBS rows even after the source DPRs are gone, so they MUST be cleaned too.
    BEGIN
        DELETE FROM dbs.dbs_daily_cm                  WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.dbs_daily_engineer            WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.dbs_daily_supervisor          WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.dbs_daily_project             WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.dbs_equipment_register        WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.dbs_manpower_register         WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.dbs_manual_expenses           WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.general_expense_monthly_entry WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM dbs.general_expense_plan_item     WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;

    -- Project-level rate overrides
    DELETE FROM resource.project_manpower_role_rate_override WHERE project_id = omd_project_id;
    DELETE FROM resource.project_equipment_role_variant_override WHERE project_id = omd_project_id;
    DELETE FROM resource.project_material_role_variant_override WHERE project_id = omd_project_id;

    -- Activity supervisors + activities
    DELETE FROM activity.activity_supervisors WHERE activity_id IN (SELECT id FROM activity.activities WHERE project_id = omd_project_id);
    DELETE FROM activity.activities WHERE project_id = omd_project_id;

    -- BOQ + WBS
    DELETE FROM project.boq_items WHERE project_id = omd_project_id;
    DELETE FROM project.wbs_nodes WHERE project_id = omd_project_id;

    -- Cost (only project-scoped tables; cost_accounts is a global tree, leave it)
    BEGIN
        DELETE FROM cost.activity_expenses        WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.dpr_estimates            WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.store_period_performance WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.ra_bills                 WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.retention_money          WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.cash_flow_forecasts      WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.project_funding          WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.budget_change_logs       WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;
    BEGIN
        DELETE FROM cost.financial_periods        WHERE project_id = omd_project_id;
    EXCEPTION WHEN undefined_table OR undefined_column THEN NULL; END;

    -- Finally the project itself
    DELETE FROM project.projects WHERE id = omd_project_id;

    RAISE NOTICE 'OMAN-DEMO-KHASAB cleanup complete.';
END $$;

COMMIT;

-- Sanity checks (read-only)
SELECT 'projects remaining' AS what, count(*) FROM project.projects;
SELECT 'OMAN-DEMO rows by table' AS what, '' AS table_name, NULL::int AS rows
WHERE FALSE
UNION ALL SELECT '', 'project.daily_progress_reports',
       count(*)::int FROM project.daily_progress_reports d JOIN project.projects p ON p.id = d.project_id WHERE p.code = 'OMAN-DEMO-KHASAB'
UNION ALL SELECT '', 'activity.activities',
       count(*)::int FROM activity.activities a JOIN project.projects p ON p.id = a.project_id WHERE p.code = 'OMAN-DEMO-KHASAB'
UNION ALL SELECT '', 'project.boq_items',
       count(*)::int FROM project.boq_items b JOIN project.projects p ON p.id = b.project_id WHERE p.code = 'OMAN-DEMO-KHASAB';
