package com.bipros.ai.tool;

import org.springframework.stereotype.Component;

/**
 * Compact entity-relationship map injected into the orchestrator's system
 * prompt. Tells the LLM which entities exist and how they join — a round
 * saver for cross-entity questions, since the model no longer has to call a
 * tool just to learn that DPRs link to Activities through {@code activity_id}
 * or that supervisors are now FKs to {@code public.users}.
 *
 * Counterpart of {@link SchemaCatalog}: that one describes the ClickHouse
 * analytics warehouse; this one describes the OLTP entities exposed by the
 * domain tools. Both ride along in the system prompt; the model picks the
 * right surface based on the question.
 *
 * <p>This catalog reflects the <b>role-owned rate book</b> (rolled out
 * 2026-05-13). Rates and demand are keyed on {@code ResourceRole} + variant
 * tables, NOT on the legacy {@code Resource} / {@code RateMaster} entities.
 * Supervisor on Activity / DPR is a {@code users} FK, not a Resource FK.
 *
 * <p>Token budget target: 700–900 tokens. Keep entries dense and avoid prose.
 */
@Component
public class DataGraphCatalog {

    private static final String COMPACT = """
            DOMAIN ENTITY GRAPH (OLTP — what the tools query). Identifiers are UUID
            unless noted. Cross-module relationships are SOFT (no JPA FK) — joins
            happen in the tool, not the database.

            ───── PROJECTS / WBS / ACTIVITIES ─────

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
                chainage_from_m, chainage_to_m,
                supervisor_user_id→public.users,
                work_activity_id→resource.work_activities (OPTIONAL),
                cost_account_id)
              the spine. supervisor_user_id = the User who manages this activity.
              work_activity_id links to the master library entry that determines
              the productivity-norm lookup (no link → no norm-driven expected output).

            activity.activity_relationships(id, project_id, predecessor_activity_id→activities,
                successor_activity_id→activities, relationship_type∈{FF,FS,SS,SF}, lag_days)

            public.users(id, username, first_name, last_name, designation,
                organisation_id, enabled)
              authoritative supervisor identity. supervisor_user_id on Activity and
              DPR points here. Display "<first_name> <last_name> (<designation>)".

            ───── ROLE-OWNED RATE BOOK (new — replaces legacy Resource/RateMaster) ─────

            resource.resource_roles(id, code, name, resource_type_id→resource_types, active)
              Pure metadata. e.g. MASON-101, BNK-ROLE-CONSTRUCTIONMANAGER, excavator-1.
              One role per archetype of work. Resource type pins the variant family:
              MANPOWER → manpower_role_rates, EQUIPMENT → equipment_role_variants,
              MATERIAL → material_role_variants.

            resource.manpower_role_rates(id, role_id→resource_roles, category_id→manpower_categories,
                grade_id→grades, unit, rate, active)
              UNIQUE(role_id, category_id, grade_id). Category = Skilled/Semi-Skilled/
              Unskilled/Staff. Grade = Grade A/B/C/... The (role × category × grade)
              triple identifies one rate row.

            resource.equipment_role_variants(id, role_id→resource_roles, make, model, unit, rate, active)
              UNIQUE(role_id, make, model). Free-text make/model (e.g. JCB India / JCB 30 Plus).

            resource.material_role_variants(id, role_id→resource_roles, spec_grade, unit, rate, active)
              UNIQUE(role_id, spec_grade). Free-text spec/grade (e.g. Premium, OPC 53).

            resource.project_manpower_role_rate_override(id, project_id, manpower_role_rate_id→manpower_role_rates,
                override_rate, active)
              Per-project rate override. UNIQUE(project_id, manpower_role_rate_id).
              Resolution chain: override (active) → variant.rate → null.

            resource.project_equipment_role_variant_override (same shape, equipment)
            resource.project_material_role_variant_override  (same shape, material)

            ───── WORK ACTIVITY + PRODUCTIVITY NORM ─────

            resource.work_activities(id, code, name, default_unit, discipline, sort_order, active)
              Master library of "kinds of work" (Blinding, Excavation, Brick Masonry…).
              Reusable across projects. Project activities reference it via work_activity_id.

            resource.productivity_norms(id, work_activity_id→work_activities, norm_type∈{MANPOWER,EQUIPMENT},
                scope∈{VARIANT,ROLE,UNSCOPED}, role_id→resource_roles (nullable),
                category_id (manpower variant only), grade_id (manpower variant only),
                make (equipment variant only), model (equipment variant only),
                unit, output_per_man_per_day, crew_size, output_per_day, output_per_hour)
              3-tier lookup: VARIANT (work_activity + role + variant qualifier)
                 → ROLE (work_activity + role)
                 → UNSCOPED (work_activity only).
              MANPOWER norms drive expected DPR output (output_per_man_per_day × nos).
              EQUIPMENT norms are informational on DPRs.

            ───── ACTIVITY DEMAND + ROLLUPS ─────

            resource.resource_assignments(id, activity_id→activities, project_id,
                role_id→resource_roles,
                manpower_role_rate_id→manpower_role_rates (manpower only),
                equipment_role_variant_id→equipment_role_variants (equipment only),
                material_role_variant_id→material_role_variants (material only),
                headcount (manpower/equipment), duration (manpower/equipment, in variant unit),
                quantity (material), unit,
                effective_rate (snapshot at creation),
                planned_units, planned_cost,
                actual_units, actual_cost,
                remaining_units, remaining_cost)
              ACTIVITY DEMAND. One row per (activity × role × variant).
              planned_cost was snapshot via RoleRateResolver(project_id, variant_id) ×
              (headcount × duration | quantity). actual_units / remaining_units roll up
              from DPR ledger; actual_cost from DPR line_cost sums.

            ───── DPR ─────

            project.daily_progress_reports(id, project_id, activity_id→activities,
                report_date, shift, supervisor_user_id→public.users,
                qty_executed, unit, weather, remarks, chainage_from_m, chainage_to_m,
                approval_status∈{DRAFT,SUBMITTED,APPROVED,REJECTED})
              DPR header. supervisor_user_id is the User who supervised on that date —
              can differ from activity.supervisor_user_id (the currently-assigned one).

            project.dpr_manpower(id, dpr_id→daily_progress_reports, role_id→resource_roles,
                manpower_role_rate_id→manpower_role_rates, nos, working_hours, ot_hours,
                idle_hours, unit_rate, line_cost)
              DPR labour line. line_cost is unit-basis-aware (Day → rate × nos;
              Hour → rate × nos × hours).

            project.dpr_equipment(id, dpr_id→daily_progress_reports, role_id→resource_roles,
                equipment_role_variant_id→equipment_role_variants, nos, working_hours,
                idle_hours, breakdown_hours, fuel_litres, unit_rate, line_cost)
              line_cost excludes idle / breakdown hours.

            project.dpr_material(id, dpr_id→daily_progress_reports, role_id→resource_roles,
                material_role_variant_id→material_role_variants, quantity, unit_rate, line_cost)
              line_cost = quantity × unit_rate.

            project.dpr_issues(id, dpr_id→daily_progress_reports, project_id,
                activity_id→activity.activities, supervisor_user_id→public.users (snapshot),
                assigned_to_user_id→public.users (WHO IS LOOKING INTO IT),
                report_date, opened_at, resolved_at,
                category∈{SAFETY,QUALITY,MATERIAL_SHORTAGE,EQUIPMENT_BREAKDOWN,MANPOWER_SHORTAGE,
                          WEATHER,DESIGN_CHANGE,LAND_ACCESS,UTILITY_CLASH,PERMIT_DELAY,
                          SUBCONTRACTOR,ENVIRONMENTAL,OTHER},
                severity∈{LOW,MEDIUM,HIGH,CRITICAL},
                status∈{OPEN,IN_PROGRESS,BLOCKED,RESOLVED,CLOSED,CANCELLED},
                title, description, resolution_notes)
              Field-issue log. CANCELLED hidden by default.

            project.daily_activity_resource_outputs(id, project_id, output_date, activity_id,
                role_id, manpower_role_rate_id|equipment_role_variant_id|material_role_variant_id,
                qty_executed, unit, hours_worked, days_worked, remarks)
              Ledger fed by DPR rollup. Used by DailyActivityResourceOutputService to
              recompute actual_units / remaining_units on resource_assignments.

            ───── BOQ / COST / EVM / BASELINE / SCHEDULE / RISK / PERMIT / CONTRACT ─────

            project.boq_items(id, project_id, item_no, description, unit, wbs_node_id,
                boq_qty, boq_rate, boq_amount, qty_executed_to_date, variance_amount)

            cost.cost_accounts(id, project_id, code, name, parent_id→cost_accounts,
                account_type∈{ELEMENT,CATEGORY,TOTAL}, status)
            cost.activity_expenses(id, activity_id→activities, project_id, cost_account_id,
                budgeted_cost, actual_cost, remaining_cost, at_completion_cost,
                percent_complete, planned_/actual_dates)
            cost.ra_bills(id, project_id, bill_no, date, total_budgeted_amount, total_actual_amount, status)

            evm.evm_calculations(id, project_id, wbs_node_id, activity_id, financial_period_id,
                data_date, BAC, PV, EV, AC, SV, CV, SPI, CPI, TCPI, EAC, ETC, VAC,
                evm_technique, etc_method, performance_percent_complete)
              CPI=EV/AC, SPI=EV/PV. Time-series; pick latest by data_date.

            baseline.baselines(id, project_id, name, baseline_type, baseline_date, is_active,
                total_activities, total_cost, project_duration, ...)
            baseline.baseline_activities(id, baseline_id→baselines, activity_id, ...)

            scheduling.schedule_results / schedule_activity_results — CPM run history.
            risk.risks(id, project_id, code, title, status, probability, impact_cost, ...)
            permit.permits(id, project_id, permit_code, status, valid_from, valid_to, ...)
            contract.contracts(id, project_id, code, contract_value, ...)

            ───── LEGACY (soft-archived 2026-05-13 — DO NOT cite in answers) ─────

            resource.resources, resource.project_resources,
            resource.manpower_rate_masters / equipment_rate_masters / material_rate_masters,
            activity.activities.responsible_resource_id (now null on new rows),
            daily_progress_reports.supervisor_resource_id (replaced by supervisor_user_id).
            These tables still exist for backward-compatibility reads but new
            flows ignore them. Historical DPR rows pre-dating 2026-04-15 may
            have null role_id / variant FKs — tools mark them "(legacy row)".

            ───── SHORTHAND PATTERNS (the LLM uses these for tool routing) ─────

            • Rate resolution (current effective rate)
                project_<type>_role_<variant>_override.override_rate (where active=true)
                → manpower_role_rates.rate | equipment_role_variants.rate | material_role_variants.rate
                → null (means "rate not set — flag in answer")
              Tool: query_role_rates (wraps RoleRateResolver).
              NEVER recompute from current rates when interpreting a DPR line — that
              row's unit_rate / line_cost is a historical snapshot. Use the snapshot.

            • Activity → planned cost
                SUM(resource_assignments.planned_cost) per activity.

            • Activity → actual cost (TOTAL — no date/supervisor filter)
                SUM(resource_assignments.actual_cost) per activity. This is the canonical
                rollup (effective_rate × actual_units) maintained by
                ResourceAssignmentCostRollupListener — exactly what the Resource Plan UI
                shows in the activity sidebar's "Actual Cost" column.
                DO NOT sum dpr_*.line_cost — those columns are unpopulated in the
                role-rate model.

            • Activity → actual cost (per-day, per-supervisor, or date-windowed)
                Computed from DPR contributions × matched assignment's effective_rate
                (assignment.actual_cost is cumulative — has no date dim):
                  manpower:  SUM(dpr_manpower.nos × a.effective_rate)
                  equipment: SUM(dpr_equipment.nos × a.effective_rate)
                  material:  SUM(dpr_material.quantity × a.effective_rate)
                joined on (activity_id, variant_id), filtered by
                daily_progress_reports.report_date / supervisor_user_id.
              Tool: get_activity_cost (preferred — picks the right query path per filter).

            • Activity → remaining cost
                SUM(resource_assignments.remaining_cost),
                OR MAX(planned_cost − actual_cost, 0) per assignment row.

            • Supervisor lookup — two senses, always disambiguate:
                (a) "currently assigned supervisor for activity X" → activity.supervisor_user_id
                (b) "who supervised the work on date D" → daily_progress_reports.supervisor_user_id
              They CAN differ when work changes hands mid-execution.

            • Productivity expected vs actual
                Activity must have work_activity_id set (otherwise scope='NONE').
                Lookup: productivity_norms via RoleProductivityNormResolver
                  (work_activity + role + variant → role → unscoped → none).
                Expected daily output = output_per_man_per_day × nos (MANPOWER)
                                       OR output_per_day × nos (EQUIPMENT).
                Actual = dpr.qty_executed. Variance % = (actual − expected) / expected.
              Tool: query_productivity_norm. For role-level rollup across roles
              under one supervisor / project, use get_capacity_utilization.

            • Capacity Utilization (across roles, across activities, across dates)
                Tool: get_capacity_utilization. Inputs: projectId, fromDate, toDate,
                supervisorUserId (optional), normType (MANPOWER|EQUIPMENT|null=both).
                Returns Manpower + Equipment sections, per-role rows with day/month/
                cumulative buckets (qty done, budget, planned, actual, util %, cost
                implication). DELEGATES to CapacityUtilizationReportService — do NOT
                re-implement the 3-tier norm chain in SQL.

            • DPR → Activity: hard FK (dpr.activity_id → activities.id).
              DPR → Supervisor (date-specific): dpr.supervisor_user_id → users.id.
              DPR → Role row: dpr_manpower / dpr_equipment / dpr_material child rows;
                each carries role_id + variant FK + line_cost.

            • Cost variance per activity: SUM(line_cost) from DPRs vs
              SUM(planned_cost) from resource_assignments. Cross-check with
              EvmCalculation (latest by data_date) for EV-based CV.

            • Baseline vs current: pull baseline_activities for is_active baseline,
              compare planned dates and planned_cost to current Activity / cost_breakdown.

            ───── TRAVERSAL RECIPES (one-shot answers — prefer over chained calls) ─────

            • "Total cost / actual cost / day cost / cost under supervisor for activity X"
              ⇒ get_activity_cost (planned, actual, remaining + optional breakdown
                 by ROLE | DAY | SUPERVISOR | RESOURCE_TYPE). Handles legacy null-role
                 DPR rows by including them in totals but flagging "(legacy)" in
                 breakdown. ALWAYS prefer this over hand-rolled SQL for activity-cost
                 questions.

            • "Manpower / equipment utilization this month for supervisor X"
              ⇒ get_capacity_utilization with supervisorUserId. Returns one row per
                 role with budget / planned / actual / util % / cost-implication —
                 day, month, cumulative.

            • "What's the project rate for Mason / Skilled / Grade A on this project"
              ⇒ query_role_rates(projectId, roleCode='MASON-101', category='Skilled',
                 grade='Grade A'). Returns override + variant + effective + source.

            • "What's the expected daily output for activity X"
              ⇒ query_productivity_norm(workActivityCode | workActivityId, roleCode,
                 variant qualifier). Tags scope=VARIANT|ROLE|UNSCOPED|NONE.

            • "Who supervised activity X on May 14" / "who's the current supervisor for X"
              ⇒ Two different questions. Date-specific → query_dpr +
                 daily_progress_reports.supervisor_user_id. Currently-assigned →
                 activity.supervisor_user_id. Resolve user via dim_user / users.

            • "What is supervisor X currently doing" / "what cost is under them"
              ⇒ get_supervisor_workload(userId, fromDate, toDate). Returns activities
                 they're assigned to + DPRs they submitted + cost they supervised.

            • "Issues per activity" / "DPRs per activity" / "which activity has the most problems"
              ⇒ activity_health_snapshot (single call, all activities in project).

            • "Walk from activity X" / "everything connected to X"
              ⇒ traverse_entity(entity_type=activity, entity_code=...).

            • "Who reports to <name>" / "what's <supervisor>'s team doing"
              ⇒ resolve_entity(kind="supervisor") → supervisor tool.
            """;

    public String compact() {
        return COMPACT;
    }
}
