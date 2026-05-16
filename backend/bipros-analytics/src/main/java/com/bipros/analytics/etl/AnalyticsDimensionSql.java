package com.bipros.analytics.etl;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.baseline.domain.Baseline;
import com.bipros.contract.domain.model.VariationOrder;
import com.bipros.cost.domain.entity.CostAccount;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.scheduling.domain.model.ScheduleResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared SQL templates + per-row param builders for ClickHouse dimension tables.
 * Both the nightly {@code DimensionSyncJob} and the live event listeners write through
 * these helpers so that {@code ReplacingMergeTree(_version)} dedupes cleanly: a live
 * upsert with {@code _version = currentTimeMillis()} always beats a batch row whose
 * version is fixed at JVM start.
 *
 * <p>Keep this class side-effect free — it must not call ClickHouse directly. Callers
 * pass the {@code (sql, params, version)} triplet into their existing
 * {@code ClickHouseTemplate.execute(...)} path.
 */
public final class AnalyticsDimensionSql {

    private AnalyticsDimensionSql() {}

    // ────────────────────────────────────────────────────────────────────────────
    // dim_project
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_PROJECT = """
        INSERT INTO bipros_analytics.dim_project
        (project_id, code, name, status, portfolio_id, org_id, start_date, finish_date, currency, obs_node_id, updated_at, _version)
        VALUES (:projectId, :code, :name, :status, :portfolioId, :orgId, :startDate, :finishDate, :currency, :obsNodeId, now(), :version)
        """;

    public static Map<String, Object> projectParams(Project p, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", p.getId());
        params.put("code", p.getCode());
        params.put("name", p.getName());
        params.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        params.put("portfolioId", null);
        params.put("orgId", null);
        params.put("startDate", p.getPlannedStartDate());
        params.put("finishDate", p.getPlannedFinishDate());
        params.put("currency", "INR");
        params.put("obsNodeId", p.getObsNodeId());
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_wbs
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_WBS = """
        INSERT INTO bipros_analytics.dim_wbs
        (wbs_id, project_id, parent_wbs_id, code, name, level, weight, path, _version)
        VALUES (:wbsId, :projectId, :parentId, :code, :name, :level, :weight, :path, :version)
        """;

    public static Map<String, Object> wbsParams(WbsNode n, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("wbsId", n.getId());
        params.put("projectId", n.getProjectId());
        params.put("parentId", n.getParentId());
        params.put("code", n.getCode());
        params.put("name", n.getName());
        params.put("level", n.getWbsLevel() != null ? n.getWbsLevel() : 0);
        params.put("weight", 1.0);
        params.put("path", n.getCode());
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_activity
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_ACTIVITY = """
        INSERT INTO bipros_analytics.dim_activity
        (activity_id, project_id, wbs_id, code, name, activity_type, edit_status, uom, bq_quantity,
         planned_start, planned_finish, chainage_from_m, chainage_to_m, is_critical,
         supervisor_user_id, supervisor_user_name, work_activity_id, work_activity_code,
         _version)
        VALUES (:activityId, :projectId, :wbsId, :code, :name, :activityType, :editStatus, :uom, :bqQty,
                :plannedStart, :plannedFinish, :chainageFrom, :chainageTo, :isCritical,
                :supervisorUserId, :supervisorUserName, :workActivityId, :workActivityCode,
                :version)
        """;

    /**
     * Build activity params. {@code workActivityCode} is denormalised — the listener
     * resolves it from {@code resource.work_activities.code} before invoking this
     * method. Supervisor name comes from {@link Activity#getSupervisorUserName()}
     * (an OLTP-side snapshot maintained whenever supervisorUserId changes), so the
     * analytics module needs no cross-module User lookup.
     */
    public static Map<String, Object> activityParams(Activity a,
                                                     String workActivityCode,
                                                     long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("activityId", a.getId());
        params.put("projectId", a.getProjectId());
        params.put("wbsId", a.getWbsNodeId());
        params.put("code", a.getCode() != null ? a.getCode() : "");
        params.put("name", a.getName() != null ? a.getName() : "");
        // CH columns activity_type, uom, bq_quantity are non-nullable. Coalesce
        // before insert so the sync doesn't abort when OLTP rows have nulls.
        params.put("activityType", a.getActivityType() != null ? a.getActivityType().name() : "");
        // Null guard: pre-migration in-memory entities may not yet have the field set.
        params.put("editStatus", a.getEditStatus() == null ? ActivityEditStatus.LOCKED.name() : a.getEditStatus().name());
        params.put("uom", "");
        params.put("bqQty", 0.0);
        params.put("plannedStart", a.getPlannedStartDate());
        params.put("plannedFinish", a.getPlannedFinishDate());
        params.put("chainageFrom", a.getChainageFromM() != null ? a.getChainageFromM().doubleValue() : null);
        params.put("chainageTo", a.getChainageToM() != null ? a.getChainageToM().doubleValue() : null);
        params.put("isCritical", a.getIsCritical() != null && a.getIsCritical() ? 1 : 0);
        // User-based supervisor (post RBAC phase 4 cutover) + master work-activity link.
        // The legacy responsibleResourceId / responsibleResourceName columns were dropped
        // from Activity by commit 88c850fd; the dim_activity CH columns survive for
        // historical reads but are no longer populated.
        params.put("supervisorUserId", a.getSupervisorUserId());
        params.put("supervisorUserName",
                a.getSupervisorUserName() != null ? a.getSupervisorUserName() : "");
        params.put("workActivityId", a.getWorkActivityId());
        params.put("workActivityCode", workActivityCode != null ? workActivityCode : "");
        params.put("version", version);
        return params;
    }

    /** Back-compat overload — listeners that haven't resolved work-activity code yet. */
    public static Map<String, Object> activityParams(Activity a, long version) {
        return activityParams(a, null, version);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_resource
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_RESOURCE = """
        INSERT INTO bipros_analytics.dim_resource
        (resource_id, project_id, resource_type, role_code, role_name,
         code, name, uom, unit_rate, is_subcontractor, _version)
        VALUES (:resourceId, :projectId, :resourceType, :roleCode, :roleName,
                :code, :name, :uom, :unitRate, :isSubcontractor, :version)
        """;

    public static Map<String, Object> resourceParams(Resource r, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("resourceId", r.getId());
        params.put("projectId", null);
        // CH non-nullable columns — coalesce so the sync doesn't abort when
        // OLTP rows have nulls (e.g. a resource not yet linked to a rate master).
        params.put("resourceType", r.getResourceType() != null ? r.getResourceType().getCode() : "");
        // role_code/role_name denormalised so AI supervisor queries can filter
        // by role without joining back to Postgres. Empty string when role is null.
        params.put("roleCode", r.getRole() == null ? "" : (r.getRole().getCode() == null ? "" : r.getRole().getCode()));
        params.put("roleName", r.getRole() == null ? "" : (r.getRole().getName() == null ? "" : r.getRole().getName()));
        params.put("code", r.getCode() != null ? r.getCode() : "");
        params.put("name", r.getName() != null ? r.getName() : "");
        params.put("uom", r.getUnit() != null ? r.getUnit() : "");
        params.put("unitRate", r.getCostPerUnit() != null ? r.getCostPerUnit().doubleValue() : 0.0);
        params.put("isSubcontractor", 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_cost_account
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_COST_ACCOUNT = """
        INSERT INTO bipros_analytics.dim_cost_account
        (cost_account_id, project_id, code, name, parent_id, category, _version)
        VALUES (:costAccountId, :projectId, :code, :name, :parentId, :category, :version)
        """;

    public static Map<String, Object> costAccountParams(CostAccount ca, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("costAccountId", ca.getId());
        // CH `project_id` on dim_cost_account is non-nullable per init SQL.
        // OLTP CostAccount has no project_id today (cost accounts are global) —
        // emit a sentinel zero UUID instead of null.
        params.put("projectId", new UUID(0L, 0L));
        params.put("code", ca.getCode() != null ? ca.getCode() : "");
        params.put("name", ca.getName() != null ? ca.getName() : "");
        params.put("parentId", ca.getParentId());
        params.put("category", "");
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_baseline
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_BASELINE = """
        INSERT INTO bipros_analytics.dim_baseline
        (baseline_id, project_id, name, description, baseline_type, baseline_date,
         is_active, total_activities, total_cost, project_duration,
         project_start_date, project_finish_date, updated_at, _version)
        VALUES (:baselineId, :projectId, :name, :description, :baselineType, :baselineDate,
                :isActive, :totalActivities, :totalCost, :projectDuration,
                :projectStartDate, :projectFinishDate, now(), :version)
        """;

    public static Map<String, Object> baselineParams(Baseline b, boolean active, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("baselineId", b.getId());
        params.put("projectId", b.getProjectId());
        params.put("name", b.getName() != null ? b.getName() : "");
        params.put("description", b.getDescription() != null ? b.getDescription() : "");
        params.put("baselineType", b.getBaselineType() != null ? b.getBaselineType().name() : "");
        params.put("baselineDate", b.getBaselineDate());
        params.put("isActive", active ? 1 : 0);
        params.put("totalActivities", b.getTotalActivities());
        params.put("totalCost", b.getTotalCost());
        params.put("projectDuration", b.getProjectDuration());
        params.put("projectStartDate", b.getProjectStartDate());
        params.put("projectFinishDate", b.getProjectFinishDate());
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_schedule_run
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_SCHEDULE_RUN = """
        INSERT INTO bipros_analytics.dim_schedule_run
        (schedule_run_id, project_id, data_date, project_start_date, project_finish_date,
         critical_path_length, total_activities, critical_activities,
         scheduling_option, status, duration_seconds, calculated_at, _version)
        VALUES (:scheduleRunId, :projectId, :dataDate, :projectStartDate, :projectFinishDate,
                :criticalPathLength, :totalActivities, :criticalActivities,
                :schedulingOption, :status, :durationSeconds, :calculatedAt, :version)
        """;

    public static Map<String, Object> scheduleRunParams(ScheduleResult s, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("scheduleRunId", s.getId());
        params.put("projectId", s.getProjectId());
        params.put("dataDate", s.getDataDate());
        params.put("projectStartDate", s.getProjectStartDate());
        params.put("projectFinishDate", s.getProjectFinishDate());
        params.put("criticalPathLength", s.getCriticalPathLength());
        params.put("totalActivities", s.getTotalActivities());
        params.put("criticalActivities", s.getCriticalActivities());
        params.put("schedulingOption", s.getSchedulingOption() != null ? s.getSchedulingOption().name() : "");
        params.put("status", s.getStatus() != null ? s.getStatus().name() : "");
        params.put("durationSeconds", s.getDurationSeconds());
        params.put("calculatedAt", s.getCalculatedAt());
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_contract (Variation Order)
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_CONTRACT = """
        INSERT INTO bipros_analytics.dim_contract
        (vo_id, contract_id, project_id, vo_number, description, vo_value,
         impact_on_budget, impact_on_schedule_days, status, approved_by, approved_at,
         updated_at, _version)
        VALUES (:voId, :contractId, :projectId, :voNumber, :description, :voValue,
                :impactOnBudget, :impactOnScheduleDays, :status, :approvedBy, :approvedAt,
                now(), :version)
        """;

    public static Map<String, Object> contractParams(VariationOrder vo, UUID projectId, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("voId", vo.getId());
        params.put("contractId", vo.getContractId());
        params.put("projectId", projectId);
        params.put("voNumber", vo.getVoNumber() != null ? vo.getVoNumber() : "");
        params.put("description", vo.getDescription() != null ? vo.getDescription() : "");
        params.put("voValue", vo.getVoValue());
        params.put("impactOnBudget", vo.getImpactOnBudget());
        params.put("impactOnScheduleDays", vo.getImpactOnScheduleDays());
        params.put("status", vo.getStatus() != null ? vo.getStatus().name() : "");
        params.put("approvedBy", vo.getApprovedBy() != null ? vo.getApprovedBy() : "");
        params.put("approvedAt", vo.getApprovedAt());
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_resource_role  (Phase 2 — role-owned rate book)
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_RESOURCE_ROLE = """
        INSERT INTO bipros_analytics.dim_resource_role
        (role_id, code, name, description, resource_type, sort_order, active, _version)
        VALUES (:roleId, :code, :name, :description, :resourceType, :sortOrder, :active, :version)
        """;

    public static Map<String, Object> resourceRoleParams(ResourceRole r, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("roleId", r.getId());
        params.put("code", r.getCode() != null ? r.getCode() : "");
        params.put("name", r.getName() != null ? r.getName() : "");
        params.put("description", r.getDescription() != null ? r.getDescription() : "");
        params.put("resourceType", r.getResourceType() != null && r.getResourceType().getCode() != null
                ? r.getResourceType().getCode() : "");
        params.put("sortOrder", r.getSortOrder() != null ? r.getSortOrder() : 0);
        params.put("active", Boolean.TRUE.equals(r.getActive()) ? 1 : 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_manpower_role_rate
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_MANPOWER_ROLE_RATE = """
        INSERT INTO bipros_analytics.dim_manpower_role_rate
        (manpower_role_rate_id, role_id, role_code, role_name,
         category_id, category_name, grade_id, grade_name,
         unit, rate, active, _version)
        VALUES (:id, :roleId, :roleCode, :roleName,
                :categoryId, :categoryName, :gradeId, :gradeName,
                :unit, :rate, :active, :version)
        """;

    /**
     * Build manpower-rate params. role_code/role_name and category_name/grade_name
     * are denormalised — listener must resolve them from their respective lookup
     * tables before invoking this method.
     */
    public static Map<String, Object> manpowerRoleRateParams(ManpowerRoleRate r,
                                                             String roleCode,
                                                             String roleName,
                                                             String categoryName,
                                                             String gradeName,
                                                             long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", r.getId());
        params.put("roleId", r.getRoleId());
        params.put("roleCode", roleCode != null ? roleCode : "");
        params.put("roleName", roleName != null ? roleName : "");
        params.put("categoryId", r.getCategoryId());
        params.put("categoryName", categoryName != null ? categoryName : "");
        params.put("gradeId", r.getGradeId());
        params.put("gradeName", gradeName != null ? gradeName : "");
        params.put("unit", r.getUnit() != null ? r.getUnit() : "");
        params.put("rate", r.getRate() != null ? r.getRate() : BigDecimal.ZERO);
        params.put("active", Boolean.TRUE.equals(r.getActive()) ? 1 : 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_equipment_role_variant
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_EQUIPMENT_ROLE_VARIANT = """
        INSERT INTO bipros_analytics.dim_equipment_role_variant
        (equipment_role_variant_id, role_id, role_code, role_name,
         make, model, unit, rate, active, _version)
        VALUES (:id, :roleId, :roleCode, :roleName,
                :make, :model, :unit, :rate, :active, :version)
        """;

    public static Map<String, Object> equipmentRoleVariantParams(EquipmentRoleVariant v,
                                                                 String roleCode,
                                                                 String roleName,
                                                                 long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", v.getId());
        params.put("roleId", v.getRoleId());
        params.put("roleCode", roleCode != null ? roleCode : "");
        params.put("roleName", roleName != null ? roleName : "");
        params.put("make", v.getMake() != null ? v.getMake() : "");
        params.put("model", v.getModel() != null ? v.getModel() : "");
        params.put("unit", v.getUnit() != null ? v.getUnit() : "");
        params.put("rate", v.getRate() != null ? v.getRate() : BigDecimal.ZERO);
        params.put("active", Boolean.TRUE.equals(v.getActive()) ? 1 : 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_material_role_variant
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_MATERIAL_ROLE_VARIANT = """
        INSERT INTO bipros_analytics.dim_material_role_variant
        (material_role_variant_id, role_id, role_code, role_name,
         spec_grade, unit, rate, active, _version)
        VALUES (:id, :roleId, :roleCode, :roleName,
                :specGrade, :unit, :rate, :active, :version)
        """;

    public static Map<String, Object> materialRoleVariantParams(MaterialRoleVariant v,
                                                                String roleCode,
                                                                String roleName,
                                                                long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", v.getId());
        params.put("roleId", v.getRoleId());
        params.put("roleCode", roleCode != null ? roleCode : "");
        params.put("roleName", roleName != null ? roleName : "");
        params.put("specGrade", v.getSpecGrade() != null ? v.getSpecGrade() : "");
        params.put("unit", v.getUnit() != null ? v.getUnit() : "");
        params.put("rate", v.getRate() != null ? v.getRate() : BigDecimal.ZERO);
        params.put("active", Boolean.TRUE.equals(v.getActive()) ? 1 : 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_project_rate_override  (unified across MANPOWER/EQUIPMENT/MATERIAL)
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_PROJECT_RATE_OVERRIDE = """
        INSERT INTO bipros_analytics.dim_project_rate_override
        (override_id, project_id, variant_type, variant_id, role_id, role_code,
         override_rate, active, _version)
        VALUES (:overrideId, :projectId, :variantType, :variantId, :roleId, :roleCode,
                :overrideRate, :active, :version)
        """;

    public static Map<String, Object> projectRateOverrideParams(UUID overrideId,
                                                                UUID projectId,
                                                                String variantType,
                                                                UUID variantId,
                                                                UUID roleId,
                                                                String roleCode,
                                                                BigDecimal overrideRate,
                                                                boolean active,
                                                                long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("overrideId", overrideId);
        params.put("projectId", projectId);
        params.put("variantType", variantType != null ? variantType : "");
        params.put("variantId", variantId);
        params.put("roleId", roleId);
        params.put("roleCode", roleCode != null ? roleCode : "");
        params.put("overrideRate", overrideRate != null ? overrideRate : BigDecimal.ZERO);
        params.put("active", active ? 1 : 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_work_activity
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_WORK_ACTIVITY = """
        INSERT INTO bipros_analytics.dim_work_activity
        (work_activity_id, code, name, default_unit, discipline, sort_order, active, _version)
        VALUES (:id, :code, :name, :defaultUnit, :discipline, :sortOrder, :active, :version)
        """;

    public static Map<String, Object> workActivityParams(WorkActivity w, long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", w.getId());
        params.put("code", w.getCode() != null ? w.getCode() : "");
        params.put("name", w.getName() != null ? w.getName() : "");
        params.put("defaultUnit", w.getDefaultUnit() != null ? w.getDefaultUnit() : "");
        params.put("discipline", w.getDiscipline() != null ? w.getDiscipline() : "");
        params.put("sortOrder", w.getSortOrder() != null ? w.getSortOrder() : 0);
        params.put("active", Boolean.TRUE.equals(w.getActive()) ? 1 : 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_productivity_norm
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_PRODUCTIVITY_NORM = """
        INSERT INTO bipros_analytics.dim_productivity_norm
        (productivity_norm_id, work_activity_id, work_activity_code, work_activity_name,
         norm_type, scope, role_id, role_code,
         category_id, category_name, grade_id, grade_name, make, model,
         unit, output_per_man_per_day, crew_size, output_per_day, output_per_hour,
         active, _version)
        VALUES (:id, :workActivityId, :workActivityCode, :workActivityName,
                :normType, :scope, :roleId, :roleCode,
                :categoryId, :categoryName, :gradeId, :gradeName, :make, :model,
                :unit, :outputPerManPerDay, :crewSize, :outputPerDay, :outputPerHour,
                :active, :version)
        """;

    /**
     * Build productivity-norm params. Listener pre-resolves work_activity name/code
     * + role code + category/grade display names from their respective lookups.
     * Scope (VARIANT|ROLE|UNSCOPED) is derived by the listener from non-null
     * variant-qualifier fields.
     */
    public static Map<String, Object> productivityNormParams(ProductivityNorm n,
                                                             String workActivityCode,
                                                             String workActivityName,
                                                             String roleCode,
                                                             String categoryName,
                                                             String gradeName,
                                                             String scope,
                                                             long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", n.getId());
        params.put("workActivityId", n.getWorkActivity() != null ? n.getWorkActivity().getId() : null);
        params.put("workActivityCode", workActivityCode != null ? workActivityCode : "");
        params.put("workActivityName", workActivityName != null ? workActivityName : "");
        params.put("normType", n.getNormType() != null ? n.getNormType().name() : "");
        params.put("scope", scope != null ? scope : "UNSCOPED");
        params.put("roleId", n.getRoleId());
        params.put("roleCode", roleCode != null ? roleCode : "");
        params.put("categoryId", n.getCategoryId());
        params.put("categoryName", categoryName != null ? categoryName : "");
        params.put("gradeId", n.getGradeId());
        params.put("gradeName", gradeName != null ? gradeName : "");
        params.put("make", n.getMake() != null ? n.getMake() : "");
        params.put("model", n.getModel() != null ? n.getModel() : "");
        params.put("unit", n.getUnit() != null ? n.getUnit() : "");
        params.put("outputPerManPerDay", n.getOutputPerManPerDay());
        params.put("crewSize", n.getCrewSize());
        params.put("outputPerDay", n.getOutputPerDay());
        params.put("outputPerHour", n.getOutputPerHour());
        // ProductivityNorm has no `active` column today — assume active.
        params.put("active", 1);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // dim_user  (User dimension — supervisor identity)
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_USER = """
        INSERT INTO bipros_analytics.dim_user
        (user_id, username, first_name, last_name, display_name, designation,
         organisation_id, enabled, _version)
        VALUES (:userId, :username, :firstName, :lastName, :displayName, :designation,
                :organisationId, :enabled, :version)
        """;

    /**
     * Primitive-typed builder so this module need not depend on bipros-security.
     * Listener resolves User → fields and calls this.
     */
    public static Map<String, Object> userParams(UUID userId,
                                                 String username,
                                                 String firstName,
                                                 String lastName,
                                                 String designation,
                                                 UUID organisationId,
                                                 boolean enabled,
                                                 long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("username", username != null ? username : "");
        params.put("firstName", firstName != null ? firstName : "");
        params.put("lastName", lastName != null ? lastName : "");
        String first = firstName != null ? firstName : "";
        String last = lastName != null ? lastName : "";
        String display = (first + " " + last).trim();
        if (display.isEmpty() && username != null) display = username;
        params.put("displayName", display);
        params.put("designation", designation != null ? designation : "");
        params.put("organisationId", organisationId);
        params.put("enabled", enabled ? 1 : 0);
        params.put("version", version);
        return params;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // fact_activity_cost_daily  (pre-aggregated activity × date × role cost rollup)
    // ────────────────────────────────────────────────────────────────────────────

    public static final String INSERT_ACTIVITY_COST_DAILY = """
        INSERT INTO bipros_analytics.fact_activity_cost_daily
        (project_id, activity_id, activity_code, report_date, role_id, role_code,
         resource_type, planned_units, actual_units, remaining_units,
         planned_cost, actual_cost, remaining_cost,
         supervisor_user_id, supervisor_user_name,
         event_ts, _version)
        VALUES (:projectId, :activityId, :activityCode, :reportDate, :roleId, :roleCode,
                :resourceType, :plannedUnits, :actualUnits, :remainingUnits,
                :plannedCost, :actualCost, :remainingCost,
                :supervisorUserId, :supervisorUserName,
                now64(3), :version)
        """;

    public static Map<String, Object> activityCostDailyParams(UUID projectId,
                                                              UUID activityId,
                                                              String activityCode,
                                                              LocalDate reportDate,
                                                              UUID roleId,
                                                              String roleCode,
                                                              String resourceType,
                                                              BigDecimal plannedUnits,
                                                              BigDecimal actualUnits,
                                                              BigDecimal remainingUnits,
                                                              BigDecimal plannedCost,
                                                              BigDecimal actualCost,
                                                              BigDecimal remainingCost,
                                                              UUID supervisorUserId,
                                                              String supervisorUserName,
                                                              long version) {
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("activityId", activityId);
        params.put("activityCode", activityCode != null ? activityCode : "");
        params.put("reportDate", reportDate);
        params.put("roleId", roleId);
        params.put("roleCode", roleCode != null ? roleCode : "");
        params.put("resourceType", resourceType != null ? resourceType : "ALL");
        params.put("plannedUnits", plannedUnits != null ? plannedUnits : BigDecimal.ZERO);
        params.put("actualUnits", actualUnits != null ? actualUnits : BigDecimal.ZERO);
        params.put("remainingUnits", remainingUnits != null ? remainingUnits : BigDecimal.ZERO);
        params.put("plannedCost", plannedCost != null ? plannedCost : BigDecimal.ZERO);
        params.put("actualCost", actualCost != null ? actualCost : BigDecimal.ZERO);
        params.put("remainingCost", remainingCost != null ? remainingCost : BigDecimal.ZERO);
        params.put("supervisorUserId", supervisorUserId);
        params.put("supervisorUserName", supervisorUserName != null ? supervisorUserName : "");
        params.put("version", version);
        return params;
    }
}
