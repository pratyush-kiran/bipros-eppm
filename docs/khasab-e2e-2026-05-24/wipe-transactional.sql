-- =====================================================================
-- Bipros DB cleanup — wipe transactional data, keep system masters
-- Generated 2026-05-24 for Phase 1 of the Khasab E2E run
-- Pre-run backup: /tmp/bipros-backup-2026-05-24.dump
-- Corrected by reviewer + devil's advocate synthesis:
--   - Keeps: kpi_definitions, dashboard_configs, global_settings,
--            integration_configs, report_definitions, predictions,
--            udf.formula_master, udf.user_defined_fields,
--            project.eps_nodes, project.wbs_templates
--   - Adds: explicit user_roles + project_team truncates
-- =====================================================================
BEGIN;

-- ===== ACTIVITY =====
TRUNCATE TABLE activity.activities RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.activity_code_assignments RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.activity_codes RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.activity_relationships RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.activity_steps RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.activity_supervisors RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.qc_sessions RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.qc_test_items RESTART IDENTITY CASCADE;
TRUNCATE TABLE activity.qc_test_types RESTART IDENTITY CASCADE;

-- ===== AI =====
TRUNCATE TABLE ai.ai_conversations RESTART IDENTITY CASCADE;
TRUNCATE TABLE ai.ai_insight_cache RESTART IDENTITY CASCADE;
TRUNCATE TABLE ai.ai_messages RESTART IDENTITY CASCADE;
TRUNCATE TABLE ai.ai_tool_calls RESTART IDENTITY CASCADE;
TRUNCATE TABLE ai.wbs_ai_extraction_cache RESTART IDENTITY CASCADE;
TRUNCATE TABLE ai.wbs_ai_jobs RESTART IDENTITY CASCADE;

-- ===== ANALYTICS =====
TRUNCATE TABLE analytics.etl_dead_letter RESTART IDENTITY CASCADE;
TRUNCATE TABLE analytics.etl_watermark RESTART IDENTITY CASCADE;

-- ===== BASELINE =====
TRUNCATE TABLE baseline.baseline_activities RESTART IDENTITY CASCADE;
TRUNCATE TABLE baseline.baseline_expenses RESTART IDENTITY CASCADE;
TRUNCATE TABLE baseline.baseline_relationships RESTART IDENTITY CASCADE;
TRUNCATE TABLE baseline.baseline_resource_assignments RESTART IDENTITY CASCADE;
TRUNCATE TABLE baseline.baseline_wbs RESTART IDENTITY CASCADE;
TRUNCATE TABLE baseline.baselines RESTART IDENTITY CASCADE;

-- ===== CONTRACT =====
TRUNCATE TABLE contract.bid_submissions RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.contract_attachments RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.contract_milestones RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.contractor_scorecards RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.contracts RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.performance_bonds RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.procurement_plans RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.tenders RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.variation_orders RESTART IDENTITY CASCADE;
TRUNCATE TABLE contract.vo_line_items RESTART IDENTITY CASCADE;

-- ===== COST =====
TRUNCATE TABLE cost.activity_expenses RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.budget_change_logs RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.cash_flow_forecasts RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.cost_accounts RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.dpr_estimates RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.financial_periods RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.funding_sources RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.project_funding RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.ra_bill_items RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.ra_bills RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.retention_money RESTART IDENTITY CASCADE;
TRUNCATE TABLE cost.store_period_performance RESTART IDENTITY CASCADE;

-- ===== DBS =====
TRUNCATE TABLE dbs.dbs_daily_cm RESTART IDENTITY CASCADE;
TRUNCATE TABLE dbs.dbs_daily_engineer RESTART IDENTITY CASCADE;
TRUNCATE TABLE dbs.dbs_daily_project RESTART IDENTITY CASCADE;
TRUNCATE TABLE dbs.dbs_daily_supervisor RESTART IDENTITY CASCADE;
TRUNCATE TABLE dbs.dbs_equipment_register RESTART IDENTITY CASCADE;
TRUNCATE TABLE dbs.dbs_manpower_register RESTART IDENTITY CASCADE;
TRUNCATE TABLE dbs.general_expense_monthly_entry RESTART IDENTITY CASCADE;
TRUNCATE TABLE dbs.general_expense_plan_item RESTART IDENTITY CASCADE;

-- ===== DOCUMENT =====
TRUNCATE TABLE document.document_folders RESTART IDENTITY CASCADE;
TRUNCATE TABLE document.document_versions RESTART IDENTITY CASCADE;
TRUNCATE TABLE document.documents RESTART IDENTITY CASCADE;
TRUNCATE TABLE document.drawing_registers RESTART IDENTITY CASCADE;
TRUNCATE TABLE document.rfi_registers RESTART IDENTITY CASCADE;
TRUNCATE TABLE document.transmittal_items RESTART IDENTITY CASCADE;
TRUNCATE TABLE document.transmittals RESTART IDENTITY CASCADE;

-- ===== EVM =====
TRUNCATE TABLE evm.evm_calculations RESTART IDENTITY CASCADE;

-- ===== GIS =====
TRUNCATE TABLE gis.construction_progress_snapshots RESTART IDENTITY CASCADE;
TRUNCATE TABLE gis.satellite_images RESTART IDENTITY CASCADE;
TRUNCATE TABLE gis.satellite_scene_ingestion_log RESTART IDENTITY CASCADE;
TRUNCATE TABLE gis.wbs_polygons RESTART IDENTITY CASCADE;

-- ===== HDS =====
TRUNCATE TABLE hds.hds_chunk RESTART IDENTITY CASCADE;
TRUNCATE TABLE hds.hds_document RESTART IDENTITY CASCADE;
TRUNCATE TABLE hds.hds_ingestion_job RESTART IDENTITY CASCADE;
TRUNCATE TABLE hds.hds_query_log RESTART IDENTITY CASCADE;
TRUNCATE TABLE hds.hds_version RESTART IDENTITY CASCADE;

-- ===== NCR =====
TRUNCATE TABLE ncr.ncrs RESTART IDENTITY CASCADE;

-- ===== PERMIT =====
TRUNCATE TABLE permit.approval_step_template RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_approval RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_attachment RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_code_sequence RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_gas_test RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_isolation_point RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_lifecycle_event RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_pack RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_pack_type RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_ppe_check RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_type_ppe RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_type_template RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.permit_worker RESTART IDENTITY CASCADE;
TRUNCATE TABLE permit.ppe_item_template RESTART IDENTITY CASCADE;

-- ===== PORTFOLIO =====
TRUNCATE TABLE portfolio.portfolio_projects RESTART IDENTITY CASCADE;
TRUNCATE TABLE portfolio.portfolio_scenario_projects RESTART IDENTITY CASCADE;
TRUNCATE TABLE portfolio.portfolio_scenarios RESTART IDENTITY CASCADE;
TRUNCATE TABLE portfolio.portfolios RESTART IDENTITY CASCADE;
TRUNCATE TABLE portfolio.project_scores RESTART IDENTITY CASCADE;
TRUNCATE TABLE portfolio.scoring_criteria RESTART IDENTITY CASCADE;
TRUNCATE TABLE portfolio.scoring_models RESTART IDENTITY CASCADE;

-- ===== PROJECT (keeps project_category_master, eps_nodes, wbs_templates) =====
TRUNCATE TABLE project.boq_items RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.concrete_pour RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.corridor_codes RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.daily_activity_resource_outputs RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.daily_progress_reports RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.daily_resource_deployments RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.daily_weather RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.dpr_attachments RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.dpr_equipment RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.dpr_issues RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.dpr_manpower RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.dpr_material RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.dpr_sub_contractor RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.next_day_plans RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.obs_nodes RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.project_codes RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.project_team RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.projects RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.stretch RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.stretch_activity_link RESTART IDENTITY CASCADE;
TRUNCATE TABLE project.wbs_nodes RESTART IDENTITY CASCADE;

-- ===== PUBLIC (keeps roles, permissions, currencies, configs, admin user) =====
TRUNCATE TABLE public.audit_log RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.admin_categories RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.analytics_queries RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.cppp_tenders RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.fund_transfers RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.gem_orders RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.gstn_verifications RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.import_export_jobs RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.import_export_logs RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.integration_logs RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.kpi_node_snapshots RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.kpi_snapshots RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.monthly_evm_snapshots RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.organisation_project_link RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.organisations RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.profiles RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.project_members RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.report_executions RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.scheduled_job_lease RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.user_auth_methods RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.user_corridor_scope RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.user_module_access RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.user_obs_assignments RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.user_roles RESTART IDENTITY CASCADE;

-- Delete non-admin users (preserve admin via WHERE clause)
DELETE FROM public.users WHERE username != 'admin';

-- ===== RESOURCE (keeps all _master, work_activities, resource_roles, sub_contractor_master) =====
TRUNCATE TABLE resource.activity_sub_contractor_assignments RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.equipment_logs RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.equipment_role_variants RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.goods_receipt_note RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.labour_returns RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.manpower_allocation RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.manpower_attendance RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.manpower_compliance RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.manpower_financials RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.manpower_role_rates RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.manpower_skills RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_boq_link RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_consumption_logs RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_issue RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_reconciliations RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_role_variants RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_source RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_source_lab_test RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.material_stock RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.productivity_norms RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.project_equipment_role_variant_override RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.project_labour_deployments RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.project_manpower_role_rate_override RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.project_material_role_variant_override RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.project_resources RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.resource_assignments RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.resource_curves RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.resource_daily_logs RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.resource_equipment_details RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.resource_material_details RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.resource_rates RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.resources RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.sub_contractor_work_activity_mappings RESTART IDENTITY CASCADE;
TRUNCATE TABLE resource.subcontractor_work_types RESTART IDENTITY CASCADE;

-- ===== RISK (keeps risk_category_master, risk_category_type, risk_templates, risk_template_project_categories, risk_scoring_*) =====
TRUNCATE TABLE risk.activity_correlations RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.monte_carlo_activity_stats RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.monte_carlo_cashflow_buckets RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.monte_carlo_milestone_stats RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.monte_carlo_results RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.monte_carlo_risk_contributions RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.monte_carlo_simulations RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.risk_activity_assignments RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.risk_responses RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.risk_triggers RESTART IDENTITY CASCADE;
TRUNCATE TABLE risk.risks RESTART IDENTITY CASCADE;

-- ===== SAFETY =====
TRUNCATE TABLE safety.safety_records RESTART IDENTITY CASCADE;

-- ===== SCHEDULING (keeps calendars, calendar_exceptions, calendar_work_weeks) =====
TRUNCATE TABLE scheduling.compression_analyses RESTART IDENTITY CASCADE;
TRUNCATE TABLE scheduling.pert_estimates RESTART IDENTITY CASCADE;
TRUNCATE TABLE scheduling.schedule_activity_results RESTART IDENTITY CASCADE;
TRUNCATE TABLE scheduling.schedule_health_indices RESTART IDENTITY CASCADE;
TRUNCATE TABLE scheduling.schedule_results RESTART IDENTITY CASCADE;
TRUNCATE TABLE scheduling.schedule_scenarios RESTART IDENTITY CASCADE;

-- ===== SITE_OPS =====
TRUNCATE TABLE site_ops.attendance_records RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.checklist_answers RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.checklist_instances RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.checklist_template_items RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.checklist_templates RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.material_indent_items RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.material_indents RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.shift_handovers RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.snags RESTART IDENTITY CASCADE;
TRUNCATE TABLE site_ops.workfronts RESTART IDENTITY CASCADE;

-- ===== UDF (keeps formula_master, user_defined_fields) =====
TRUNCATE TABLE udf.formula_override RESTART IDENTITY CASCADE;
TRUNCATE TABLE udf.formula_version RESTART IDENTITY CASCADE;
TRUNCATE TABLE udf.udf_values RESTART IDENTITY CASCADE;

COMMIT;

-- =====================================================================
-- Verification queries (run after COMMIT)
-- =====================================================================
\echo '=== Verification ==='
SELECT 'dpr' AS metric, COUNT(*) AS count FROM project.daily_progress_reports
UNION ALL SELECT 'projects', COUNT(*) FROM project.projects
UNION ALL SELECT 'wbs_nodes', COUNT(*) FROM project.wbs_nodes
UNION ALL SELECT 'project_team', COUNT(*) FROM project.project_team
UNION ALL SELECT 'eps_nodes (keep)', COUNT(*) FROM project.eps_nodes
UNION ALL SELECT 'wbs_templates (keep)', COUNT(*) FROM project.wbs_templates
UNION ALL SELECT 'project_category_master (keep)', COUNT(*) FROM project.project_category_master
UNION ALL SELECT 'users (admin only)', COUNT(*) FROM public.users
UNION ALL SELECT 'roles (keep)', COUNT(*) FROM public.roles
UNION ALL SELECT 'profile_permissions (keep)', COUNT(*) FROM public.profile_permissions
UNION ALL SELECT 'kpi_definitions (keep)', COUNT(*) FROM public.kpi_definitions
UNION ALL SELECT 'global_settings (keep)', COUNT(*) FROM public.global_settings
UNION ALL SELECT 'currencies (keep)', COUNT(*) FROM public.currencies
UNION ALL SELECT 'manpower_rates (keep)', COUNT(*) FROM resource.manpower_rate_masters
UNION ALL SELECT 'equipment_rates (keep)', COUNT(*) FROM resource.equipment_rate_masters
UNION ALL SELECT 'material_rates (keep)', COUNT(*) FROM resource.material_rate_masters
UNION ALL SELECT 'work_activities (keep)', COUNT(*) FROM resource.work_activities
UNION ALL SELECT 'resource_roles (keep)', COUNT(*) FROM resource.resource_roles
UNION ALL SELECT 'sub_contractor_master (keep)', COUNT(*) FROM resource.sub_contractor_master
UNION ALL SELECT 'calendars (keep)', COUNT(*) FROM scheduling.calendars
UNION ALL SELECT 'risk_category_master (keep)', COUNT(*) FROM risk.risk_category_master
UNION ALL SELECT 'risk_templates (keep)', COUNT(*) FROM risk.risk_templates
UNION ALL SELECT 'formula_master (keep)', COUNT(*) FROM udf.formula_master
UNION ALL SELECT 'resource_assignments (wipe)', COUNT(*) FROM resource.resource_assignments
UNION ALL SELECT 'productivity_norms (wipe)', COUNT(*) FROM resource.productivity_norms
UNION ALL SELECT 'ai.ai_conversations (wipe)', COUNT(*) FROM ai.ai_conversations
ORDER BY metric;
