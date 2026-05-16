package com.bipros.ai.tool;

import org.springframework.stereotype.Component;

/**
 * Central source of truth for the ClickHouse analytics schema description shown
 * to the agent. Both {@link QueryClickHouseTool#description()} and
 * {@link DescribeSchemaTool} read from here so the loop's view of the schema
 * never drifts.
 *
 * <p>If a developer reports that the AI keeps writing SQL the warehouse rejects
 * ("Unknown expression identifier 'uom'", "r.id type String, f.resource_id type
 * UUID", etc.), the local ClickHouse instance is stale relative to
 * {@code docker/clickhouse-init.sql}. The fix is one drop-and-replay — see
 * {@code docs/ai/local-clickhouse-reinit.md}.
 *
 * <p>The role-owned rate book (2026-05-13) lives in OLTP only for now. Role,
 * variant, rate-override, work-activity, productivity-norm, and user dimensions
 * are mirrored to ClickHouse in Phase 2; until then, route role/rate/supervisor/
 * work-activity/capacity-utilization questions to the JPA tools listed below.
 */
@Component
public class SchemaCatalog {

    private static final String FULL = """
            ClickHouse analytics warehouse `bipros_analytics`. SELECT only.
            Every query MUST include a `project_id` filter — `= '<uuid>'` for a single
            project or `IN ('<uuid1>','<uuid2>',...)` for cross-project. Quote UUIDs.

            PK column convention (READ THIS): every dim/fact has an explicit
            `<entity>_id` column — `dim_activity.activity_id`, `dim_wbs.wbs_id`,
            `fact_dpr_logs.dpr_id`, etc. There is NO plain `id` column on any
            analytics table. When aliasing (e.g. `FROM bipros_analytics.dim_activity a`),
            still write `a.activity_id`, never `a.id`. The first column of each table
            listed below IS that PK.

            Dimensions:
            - dim_project(project_id, code, name, status, portfolio_id, org_id, start_date, finish_date, currency, obs_node_id, updated_at)
            - dim_wbs(wbs_id, project_id, parent_wbs_id, code, name, level, weight, path)
            - dim_activity(activity_id, project_id, wbs_id, code, name, activity_type, uom, bq_quantity, planned_start, planned_finish, chainage_from_m, chainage_to_m, is_critical, responsible_resource_id, responsible_resource_name)
              -- responsible_resource_id is LEGACY (Resource-based supervisor) and is null on rows
              -- created after 2026-05-13. The new supervisor field is supervisor_user_id (FK to
              -- public.users) and lives in OLTP only — the dim_user dimension arrives in Phase 2.
              -- For "who supervises activity X" use the live tool list_supervisors or query the
              -- OLTP table activity.activities.supervisor_user_id via JPA.
            - dim_cost_account(cost_account_id, project_id, code, name, parent_id, category)
            - dim_calendar(date, year, quarter, month, week, iso_week, day_of_week, is_business_day, fiscal_period)
            - dim_risk(risk_id, project_id, code, title, risk_type, category_id, category_name, owner_id, owner_name, status, rag, trend, response_type, identified_date, identified_by_id, closed_date)
            - dim_permit_type(permit_type_template_id, code, name, color_hex, icon_key, max_duration_hours, requires_gas_test, requires_isolation, jsa_required, blasting_required, diving_required, default_risk_level, night_work_policy)
            - dim_permit(permit_id, project_id, permit_code, permit_type_template_id, parent_permit_id, status, risk_level, shift, contractor_org_id, location_zone, chainage_marker, supervisor_name, start_at, end_at, valid_from, valid_to, declaration_accepted_at, closed_at, closed_by, revoked_at, revoked_by, expired_at, suspended_at, total_approvals_required, approvals_completed)
            - dim_labour_designation(designation_id, code, designation, category, trade, grade, nationality, experience_years_min, default_daily_rate, skills, certifications, status)

            Legacy (frozen — DO NOT cite in answers):
            - dim_resource(resource_id, project_id, resource_type, code, name, uom, unit_rate, is_subcontractor)
              -- Soft-archived 2026-05-13. The new role-owned rate book lives in OLTP under
              -- resource.resource_roles / manpower_role_rates / equipment_role_variants /
              -- material_role_variants with project overrides. dim_resource.unit_rate is the
              -- LEGACY rate-master snapshot and is NOT reliable for any project rate question.
              -- ALWAYS route role / rate / variant questions to the JPA tools:
              --   query_role_rates              — resolve effective rate via project override → variant.
              --   find_resource_deployment     — "where is role X deployed" / per-activity demand.
              --   list_activity_resources      — what role+variant rows feed one activity.
              -- Phase 2 will add dim_resource_role, dim_manpower_role_rate,
              -- dim_equipment_role_variant, dim_material_role_variant, dim_project_rate_override,
              -- dim_work_activity, dim_productivity_norm, dim_user to this warehouse — until
              -- then, the JPA tools are the only correct source for role-based questions.

            Facts (date-partitioned, ReplacingMergeTree):
            - fact_activity_progress_daily(project_id, activity_id, date, pct_complete_physical, pct_complete_duration, qty_executed, cumulative_qty, chainage_from_m, chainage_to_m, source, event_ts)
            - fact_resource_usage_daily(project_id, activity_id, resource_id, resource_type, date, hours_worked, days_worked, qty_executed, productivity_actual, productivity_norm, cost, event_ts)
              -- LEGACY shape: resource_id is the old Resource FK. Cost was computed at ETL time
              -- using dim_resource.unit_rate (also legacy). For any rate-precise / role-aware
              -- question prefer list_activity_resources or find_resource_deployment.
              -- Phase 2 adds role_id / variant FK columns; until then this fact is project-
              -- trend-only, never authoritative for current state.
            - fact_cost_daily(project_id, wbs_id, activity_id, date, cost_account_id, labor_cost, material_cost, equipment_cost, expense_cost, total_actual, total_planned, total_earned, event_ts)
            - fact_evm_daily(project_id, wbs_id, activity_id, date, bac, pv, ev, ac, cv, sv, cpi, spi, tcpi, eac, etc_cost, vac, period_source, interpolation, event_ts)
            - fact_dpr_logs(project_id, activity_id, dpr_id, report_date, supervisor_user_id, supervisor_name, chainage_from_m, chainage_to_m, qty_executed, cumulative_qty, weather, temperature_c, remarks_text, event_ts)
              -- supervisor_user_id is the legacy Resource FK on rows pre-2026-05-13; on newer
              -- rows it carries the User FK (FK target diverged). Until Phase 2 backfills the
              -- column unambiguously, prefer the OLTP daily_progress_reports.supervisor_user_id
              -- via query_dpr for any supervisor-attributed cost or activity question.
            - fact_dpr_manpower_daily(project_id, activity_id, dpr_id, manpower_row_id, report_date, trade, category, contractor_name, nos, working_hours, ot_hours, event_ts)
              -- Phase 2 adds role_id / manpower_role_rate_id / effective_rate / line_cost /
              -- supervisor_user_id. Until then, line_cost lives only in OLTP — prefer
              -- get_activity_cost or query_dpr for any DPR cost question.
            - fact_dpr_equipment_daily(project_id, activity_id, dpr_id, equipment_row_id, report_date, equipment_type, fleet_no, ownership, nos, working_hours, idle_hours, breakdown_hours, fuel_litres, operator_name, availability_status, event_ts)
            - fact_dpr_material_daily(project_id, activity_id, dpr_id, material_row_id, report_date, material_name, unit, quantity, source, vendor_name, batch_no, event_ts)
            - fact_risk_snapshot_daily(project_id, risk_id, date, probability, impact_cost, impact_days, rag, status, monte_carlo_p50, monte_carlo_p80, monte_carlo_p95, risk_score, residual_risk_score, risk_type, owner_id, category_id, post_response_probability, post_response_impact_cost, post_response_impact_schedule, pre_response_exposure_cost, post_response_exposure_cost, exposure_start_date, exposure_finish_date, response_type, trend, identified_date, identified_by_id, event_ts)
            - fact_permit_lifecycle(project_id, permit_id, permit_type_template_id, event_type, occurred_at, occurred_date, actor_user_id, risk_level, permit_status, payload_json, duration_hours_to_event, event_ts)
            - fact_labour_daily(project_id, labour_return_id, deployment_id, designation_id, skill_category, contractor_name, contractor_org_id, wbs_id, site_location, date, head_count, man_days, planned_head_count, daily_rate, daily_cost, source, event_ts)

            Materialized views (use sumMerge / maxMerge on *_state cols):
            - mv_project_kpi_daily(project_id, date, ac, pv, ev, rows)
            - mv_portfolio_scurve_weekly(portfolio_id, week_start, pv_state, ev_state, ac_state)
            - mv_activity_weekly(project_id, activity_id, week_start, pct_state, qty_state)

            When the warehouse is not enough:
            For any question about ROLE rates, variant rates, project rate overrides,
            currently-assigned supervisors, work-activity-driven productivity norms,
            or capacity utilization across roles, the warehouse is INSUFFICIENT.
            Route to the JPA tools instead:
              - get_activity_cost            — planned / actual / remaining + breakdown by role|day|supervisor
              - get_capacity_utilization     — manpower & equipment util % by role for a date window
              - query_role_rates             — effective rate via project override → variant chain
              - query_productivity_norm      — variant → role → unscoped chain
              - get_supervisor_workload      — activities + DPRs + cost under one supervisor (User)
            """;

    public String full() {
        return FULL;
    }

    public String forTable(String table) {
        if (table == null || table.isBlank()) {
            return FULL;
        }
        String needle = table.trim().toLowerCase();
        for (String line : FULL.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- " + needle + "(")) {
                return trimmed;
            }
        }
        return "Table not in schema: " + table + ". Call describe_schema with no argument to see all tables.";
    }
}
