package com.bipros.ai.tool;

import org.springframework.stereotype.Component;

/**
 * Compact entity-relationship map injected into the orchestrator's system
 * prompt. Tells the LLM which entities exist and how they join — a round
 * saver for cross-entity questions, since the model no longer has to call a
 * tool just to learn that DPRs link to Activities through {@code activity_name}
 * (no FK) or that supervisors link to subordinates through both
 * {@code Resource.parent_id} (org tree) and
 * {@code ManpowerMaster.reporting_manager_id} (HR tree).
 *
 * Counterpart of {@link SchemaCatalog}: that one describes the ClickHouse
 * analytics warehouse; this one describes the OLTP entities exposed by the
 * domain tools. Both ride along in the system prompt; the model picks the
 * right surface based on the question.
 *
 * <p>Token budget target: 700–900 tokens. Keep entries dense and avoid prose.
 * The {@link #compact()} string is what the orchestrator concatenates into
 * the system prompt; it's intentionally terse.
 */
@Component
public class DataGraphCatalog {

    private static final String COMPACT = """
            DOMAIN ENTITY GRAPH (OLTP — what the tools query). Identifiers are UUID
            unless noted. Cross-module relationships are SOFT (no JPA FK) — joins
            happen in the tool, not the database.

            project.projects(id, code, name, status, planned_start, planned_finish,
                data_date, eps_node_id, industry, chainage_from_m, chainage_to_m)
              parents EVERYTHING below; nearly every tool requires a project in scope.

            project.wbs_nodes(id, code, name, parent_id→wbs_nodes, project_id,
                level, type, phase, status, budget_crores, chainage_from_m, chainage_to_m,
                cost_account_id→cost.cost_accounts)
              tree (self-FK). Activities anchor here via wbs_node_id.

            activity.activities(id, code, name, project_id, wbs_node_id→wbs_nodes,
                activity_type, status, original_duration, remaining_duration,
                planned_start_date, planned_finish_date, early_/late_/actual_dates,
                total_float, free_float, percent_complete, is_critical,
                chainage_from_m, chainage_to_m, assigned_to, responsible_user_id,
                responsible_resource_id→resource.resources, responsible_resource_name,
                work_activity_id→resource.work_activities, cost_account_id)
              the spine. DPRs join by activity_name, ResourceAssignments by id.
              responsible_resource_id = the SUPERVISOR (Labor Resource) managing this
              activity — written by the bulk-supervisor-assignment flow. PRIMARY way
              to enumerate "activities supervised by X". responsible_user_id is the
              older user-based owner field, separate concept.

            activity.activity_relationships(id, project_id, predecessor_activity_id→activities,
                successor_activity_id→activities, relationship_type∈{FF,FS,SS,SF}, lag_days)

            resource.resources(id, code, name, role_id→resource_roles, resource_type_id→resource_types,
                availability, cost_per_unit, unit, status, calendar_id, parent_id→resources, user_id)
              parent_id self-FK = ORG hierarchy (one supervisor view).

            resource.resource_roles(id, code, name)         e.g. Project Manager, Foreman, Mason
            resource.resource_types(id, code, name, category∈{EQUIPMENT,MATERIAL,MANPOWER,SUBCONTRACT})

            resource.resource_assignments(id, activity_id→activities, resource_id→resources,
                role_id→resource_roles, project_id, planned_units, actual_units,
                remaining_units, at_completion_units, planned_cost, actual_cost,
                rate_type, planned_/actual_dates)
              the WBS→Activity→Resource→Cost link.

            resource.resource_rates(id, resource_id→resources, rate_type, price_per_unit,
                effective_date, budgeted_rate, actual_rate, category)
              variance = actual_rate - budgeted_rate, per resource per rate_type.

            resource.manpower_master(resource_id PK→resources [1:1], employee_code,
                full_name, category∈{SKILLED,UNSKILLED,STAFF}, sub_category, designation,
                department, reporting_manager_id→manpower_master, joining_date, exit_date,
                employment_type)
              reporting_manager_id self-FK = HR hierarchy (a SECOND supervisor view).

            resource.manpower_skills(resource_id PK→resources [1:1], primary_skill (JSONB),
                secondary_skills (JSONB), skill_level, certifications (JSONB),
                license_details (JSONB), training_records (JSONB), experience_years)

            resource.skill_master(id, code, name, description, active)
            resource.productivity_norms(id, work_activity_id→work_activities,
                resource_id→resources, resource_type_id→resource_types, norm_type, output_per_day, unit)
              budgeted output for a (work_activity × resource_type) tuple.

            project.daily_progress_reports(id, project_id, report_date,
                supervisor_resource_id→resource.resources, supervisor_name,
                activity_name (string match to activities.name — NO FK), wbs_node_id→wbs_nodes,
                qty_executed, cumulative_qty, unit, boq_item_no→boq_items.item_no,
                weather_condition, remarks, chainage_from_m, chainage_to_m)
              field-level work record. Filter by date range, supervisor_resource_id
              (preferred — stable FK), supervisor_name (string fallback), activity, WBS.

            project.dpr_issues(id, dpr_id→daily_progress_reports, project_id,
                activity_id→activity.activities, supervisor_resource_id→resource.resources
                (who LOGGED it), assigned_to_resource_id→resource.resources (WHO IS LOOKING
                INTO IT), report_date, opened_at, resolved_at,
                category∈{SAFETY,QUALITY,MATERIAL_SHORTAGE,EQUIPMENT_BREAKDOWN,MANPOWER_SHORTAGE,
                          WEATHER,DESIGN_CHANGE,LAND_ACCESS,UTILITY_CLASH,PERMIT_DELAY,
                          SUBCONTRACTOR,ENVIRONMENTAL,OTHER},
                severity∈{LOW,MEDIUM,HIGH,CRITICAL},
                status∈{OPEN,IN_PROGRESS,BLOCKED,RESOLVED,CLOSED,CANCELLED},
                title, description, resolution_notes)
              Field-issue log entries (obstacles supervisors recorded against a DPR).
              activity_id, supervisor_resource_id, report_date are SNAPSHOTTED at create
              from the parent DPR and do not re-sync if the parent is later edited.
              Counts per activity / supervisor / category are the headline rollups.
              CANCELLED issues are excluded from default queries.

            project.daily_activity_resource_outputs(id, project_id, output_date, activity_id,
                resource_id, qty_executed, unit, hours_worked, days_worked, remarks)
              GOLD productivity table. Join to productivity_norms via activity.work_activity_id.

            project.boq_items(id, project_id, item_no, description, unit, wbs_node_id,
                boq_qty, boq_rate, boq_amount, qty_executed_to_date, variance_amount)

            cost.cost_accounts(id, project_id, code, name, parent_id→cost_accounts,
                account_type∈{ELEMENT,CATEGORY,TOTAL}, status)
            cost.activity_expenses(id, activity_id→activities, project_id, cost_account_id,
                budgeted_cost, actual_cost, remaining_cost, at_completion_cost,
                percent_complete, planned_/actual_dates)
              per-activity cost tracking. Variance = actual_cost - budgeted_cost.
            cost.ra_bills(id, project_id, bill_no, date, total_budgeted_amount,
                total_actual_amount, status)
            cost.ra_bill_items(id, ra_bill_id→ra_bills, description, budgeted_amount, actual_amount)

            evm.evm_calculations(id, project_id, wbs_node_id, activity_id, financial_period_id,
                data_date, BAC, PV, EV, AC, SV, CV, SPI, CPI, TCPI, EAC, ETC, VAC,
                evm_technique, etc_method, performance_percent_complete)
              CPI=EV/AC, SPI=EV/PV, CV=EV-AC, SV=EV-PV. Time-series; pick latest by data_date.

            baseline.baselines(id, project_id, name, baseline_type, baseline_date, is_active,
                total_activities, total_cost, project_duration, project_start_date,
                project_finish_date)
            baseline.baseline_activities(id, baseline_id→baselines, activity_id, early_/late_dates,
                original_duration, planned_cost, actual_cost, percent_complete)
              FROZEN snapshot at baseline_date. Compare to current activity for variance.

            scheduling.schedule_results(id, project_id, data_date, project_start_date,
                project_finish_date, critical_path_length, critical_activities,
                total_activities, status)
            scheduling.schedule_activity_results(id, schedule_result_id, activity_id,
                early_/late_dates, total_float, free_float, is_critical)

            risk.risks(id, project_id, code, title, status, category_id, owner_id,
                probability, impact_cost, impact_days, rag, response_type)

            permit.permits(id, project_id, permit_code, permit_type_template_id, status,
                risk_level, contractor_org_id, supervisor_name, valid_from, valid_to,
                approvals_completed, total_approvals_required)

            contract.contracts(id, project_id, code, contract_value, party_id, signed_date,
                start_date, end_date, status)

            ───── SHORTHAND PATTERNS (the LLM uses these for tool routing) ─────
            • WBS→Activity→Resource chain: WbsNode.id → Activity.wbs_node_id;
              Activity.id → ResourceAssignment.activity_id; ResourceAssignment.resource_id → Resource.id
            • Supervisor→Activity (DIRECT, PREFERRED): Activity.responsible_resource_id
              → Resource.id. The bulk-supervisor-assignment flow writes this; use it
              first for "which activities does X supervise". Fall back to DPR
              supervisor_name string-match only when responsible_resource_id is null.
            • Supervisor→Manpower (TWO links): walk Resource.parent_id (ORG) AND
              ManpowerMaster.reporting_manager_id (HR). Some teams use one, some both.
            • DPR→Activity: string match daily_progress_reports.activity_name to
              activities.name (case-insensitive). Optional wbs_node_id link.
            • DPR→Issue: dpr_issues.dpr_id = daily_progress_reports.id.
              Issue→Activity: dpr_issues.activity_id (snapshot — may differ from current
              DPR.activity_id if the parent DPR was edited).
              Issue questions ("how many issues on activity X", "which supervisor logged
              the most issues", "what is the reason of …") ⇒ call list_issues with the
              matching group_by axis (activity / supervisor / category / severity / status).
              For drill-down on one issue use get_issue_details.
            • Productivity actual vs norm: DailyActivityResourceOutput × ProductivityNorm
              via activities.work_activity_id (NOT activity_id directly).
            • Cost variance per activity: ActivityExpense (project_id, activity_id) ⇒
              budgeted_cost vs actual_cost. Cross-check with EvmCalculation (latest
              by data_date) for EV-based cost variance (CV).
            • Baseline vs current: pull baseline_activities for is_active baseline,
              compare planned dates and planned_cost to current Activity / ActivityExpense.

            ───── TRAVERSAL RECIPES (one-shot answers — prefer over chained calls) ─────
            • "Issues per activity" / "DPRs per activity" / "what's going on with each
              activity" / "which activity has the most problems"
              ⇒ activity_health_snapshot (single call, all activities in project).
              Returns per activity: dpr_count, latest_dpr_date, issue counts (total /
              open / by_severity / by_category / top_category). Use this INSTEAD OF
              list_activities + per-activity list_issues — never deflect with
              "I have activities but not issue counts".
            • "Everything connected to activity X" / "walk from activity ACT-…"
              ⇒ traverse_entity(entity_type=activity, entity_code=…). Returns parents
              (project, wbs_node), child counts (dpr, issue, predecessor, successor,
              supervisor) and recent samples in one hop.
            • "Walk from a DPR" / "what's around DPR X"
              ⇒ traverse_entity(entity_type=dpr, entity_id=…). Returns parent project +
              activity, plus all issues filed under that DPR.
            • "Walk from an issue" / "what is issue X linked to"
              ⇒ traverse_entity(entity_type=issue, entity_id=…). Returns parent DPR,
              parent activity (snapshot at time of filing), supervisor who logged.
            • "Walk from a supervisor" / "what does supervisor X cover"
              ⇒ traverse_entity(entity_type=supervisor, entity_code=…). Returns
              activities supervised, DPRs filed, issues logged, direct reports.
            """;

    public String compact() {
        return COMPACT;
    }
}
