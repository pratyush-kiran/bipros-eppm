package com.bipros.analytics.etl.batch;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.analytics.etl.AnalyticsDimensionSql;
import com.bipros.analytics.store.ClickHouseTemplate;
import com.bipros.common.scheduling.ScheduledJobLeaseRepository;
import com.bipros.cost.domain.entity.CostAccount;
import com.bipros.cost.domain.repository.CostAccountRepository;
import com.bipros.permit.domain.model.Permit;
import com.bipros.permit.domain.model.PermitTypeTemplate;
import com.bipros.permit.domain.repository.PermitRepository;
import com.bipros.permit.domain.repository.PermitTypeTemplateRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.LabourDesignation;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProjectLabourDeployment;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.model.role.EquipmentRoleVariant;
import com.bipros.resource.domain.model.role.ManpowerRoleRate;
import com.bipros.resource.domain.model.role.MaterialRoleVariant;
import com.bipros.resource.domain.model.role.ProjectEquipmentRoleVariantOverride;
import com.bipros.resource.domain.model.role.ProjectManpowerRoleRateOverride;
import com.bipros.resource.domain.model.role.ProjectMaterialRoleVariantOverride;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.LabourDesignationRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ProjectLabourDeploymentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ProjectEquipmentRoleVariantOverrideRepository;
import com.bipros.resource.domain.repository.role.ProjectManpowerRoleRateOverrideRepository;
import com.bipros.resource.domain.repository.role.ProjectMaterialRoleVariantOverrideRepository;
import com.bipros.risk.domain.model.Risk;
import com.bipros.risk.domain.repository.RiskRepository;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Nightly full refresh of ClickHouse dimension tables from Postgres.
 * Uses ReplacingMergeTree(_version) so duplicates are automatically deduped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DimensionSyncJob {

    private static final String JOB_NAME = "analytics_dimension_sync";
    private static final long VERSION = System.currentTimeMillis();

    private final ScheduledJobLeaseRepository leaseRepository;
    private final ClickHouseTemplate clickHouse;

    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final ActivityRepository activityRepository;
    private final ResourceRepository resourceRepository;
    private final CostAccountRepository costAccountRepository;
    private final RiskRepository riskRepository;
    private final PermitRepository permitRepository;
    private final PermitTypeTemplateRepository permitTypeTemplateRepository;
    private final LabourDesignationRepository labourDesignationRepository;
    private final ProjectLabourDeploymentRepository projectLabourDeploymentRepository;

    // Role-owned rate book (2026-05-13)
    private final ResourceRoleRepository resourceRoleRepository;
    private final ManpowerRoleRateRepository manpowerRoleRateRepository;
    private final EquipmentRoleVariantRepository equipmentRoleVariantRepository;
    private final MaterialRoleVariantRepository materialRoleVariantRepository;
    private final ProjectManpowerRoleRateOverrideRepository manpowerOverrideRepository;
    private final ProjectEquipmentRoleVariantOverrideRepository equipmentOverrideRepository;
    private final ProjectMaterialRoleVariantOverrideRepository materialOverrideRepository;
    private final WorkActivityRepository workActivityRepository;
    private final ProductivityNormRepository productivityNormRepository;
    private final ManpowerCategoryMasterRepository manpowerCategoryRepository;
    private final GradeMasterRepository gradeRepository;
    private final UserRepository userRepository;

    /** Public hook for the backfill endpoint (POST /v1/admin/analytics/backfill-role-model). */
    @Transactional
    public void runBackfill() {
        run();
    }

    @Scheduled(cron = "0 30 1 * * *")
    @Transactional
    public void run() {
        Instant now = Instant.now();
        Instant until = now.plusSeconds(600);
        String owner = "node-" + UUID.randomUUID();
        if (leaseRepository.tryAcquire(JOB_NAME, until, now, owner) == 0) {
            log.debug("DimensionSyncJob skipped — another node holds the lease");
            return;
        }

        long start = System.currentTimeMillis();
        // Run each sub-sync independently. One bad-data table shouldn't kill
        // the rest — log it and continue so the resync produces partial-but-
        // still-useful results.
        safeRun("projects", this::syncProjects);
        safeRun("wbs", this::syncWbs);
        safeRun("activities", this::syncActivities);
        safeRun("resources", this::syncResources);
        safeRun("cost_accounts", this::syncCostAccounts);
        safeRun("calendar", this::syncCalendar);
        safeRun("risks", this::syncRisks);
        safeRun("permit_type_templates", this::syncPermitTypeTemplates);
        safeRun("permits", this::syncPermits);
        safeRun("labour_designations", this::syncLabourDesignations);
        safeRun("labour_deployment_snapshot", this::syncLabourDeploymentSnapshot);
        // Role-owned rate book + supervisor User dimension
        safeRun("resource_roles", this::syncResourceRoles);
        safeRun("manpower_role_rates", this::syncManpowerRoleRates);
        safeRun("equipment_role_variants", this::syncEquipmentRoleVariants);
        safeRun("material_role_variants", this::syncMaterialRoleVariants);
        safeRun("project_rate_overrides", this::syncProjectRateOverrides);
        safeRun("work_activities", this::syncWorkActivities);
        safeRun("productivity_norms", this::syncProductivityNorms);
        safeRun("users", this::syncUsers);
        log.info("DimensionSyncJob completed in {} ms", System.currentTimeMillis() - start);
    }

    private void safeRun(String label, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("DimensionSyncJob sub-sync [{}] failed: {} — continuing with remaining tables.",
                    label, e.getMessage());
        }
    }

    private void syncProjects() {
        List<Project> projects = projectRepository.findAll();
        for (Project p : projects) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_PROJECT,
                    AnalyticsDimensionSql.projectParams(p, VERSION));
        }
        log.debug("Synced {} projects", projects.size());
    }

    private void syncWbs() {
        List<WbsNode> nodes = wbsNodeRepository.findAll();
        for (WbsNode n : nodes) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_WBS,
                    AnalyticsDimensionSql.wbsParams(n, VERSION));
        }
        log.debug("Synced {} WBS nodes", nodes.size());
    }

    private void syncActivities() {
        // Build work_activity_id → code lookup once so we can denormalise the master code
        // onto each dim_activity row without N+1.
        Map<UUID, String> workActivityCodes = workActivityRepository.findAll().stream()
                .collect(Collectors.toMap(WorkActivity::getId,
                        w -> w.getCode() != null ? w.getCode() : ""));

        List<Activity> activities = activityRepository.findAll();
        for (Activity a : activities) {
            String waCode = a.getWorkActivityId() != null
                    ? workActivityCodes.getOrDefault(a.getWorkActivityId(), "")
                    : "";
            clickHouse.execute(AnalyticsDimensionSql.INSERT_ACTIVITY,
                    AnalyticsDimensionSql.activityParams(a, waCode, VERSION));
        }
        log.debug("Synced {} activities", activities.size());
    }

    private void syncResources() {
        List<Resource> resources = resourceRepository.findAll();
        for (Resource r : resources) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_RESOURCE,
                    AnalyticsDimensionSql.resourceParams(r, VERSION));
        }
        log.debug("Synced {} resources", resources.size());
    }

    private void syncCostAccounts() {
        List<CostAccount> accounts = costAccountRepository.findAll();
        for (CostAccount ca : accounts) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_COST_ACCOUNT,
                    AnalyticsDimensionSql.costAccountParams(ca, VERSION));
        }
        log.debug("Synced {} cost accounts", accounts.size());
    }

    private void syncCalendar() {
        // Seed calendar for 2020-2030 if empty
        String countSql = "SELECT count() FROM bipros_analytics.dim_calendar";
        List<Map<String, Object>> rows = clickHouse.queryForList(countSql, Map.of());
        long existing = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("count()")).longValue();
        if (existing > 0) {
            return;
        }

        LocalDate date = LocalDate.of(2020, 1, 1);
        LocalDate end = LocalDate.of(2030, 12, 31);
        String sql = """
            INSERT INTO bipros_analytics.dim_calendar
            (date, year, quarter, month, week, iso_week, day_of_week, is_business_day, fiscal_period)
            VALUES (:date, :year, :quarter, :month, :week, :isoWeek, :dayOfWeek, :isBusinessDay, :fiscalPeriod)
            """;
        while (!date.isAfter(end)) {
            Map<String, Object> params = new HashMap<>();
            params.put("date", date);
            params.put("year", date.getYear());
            params.put("quarter", (date.getMonthValue() - 1) / 3 + 1);
            params.put("month", date.getMonthValue());
            params.put("week", date.getDayOfYear() / 7 + 1);
            params.put("isoWeek", date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
            params.put("dayOfWeek", date.getDayOfWeek().getValue());
            params.put("isBusinessDay", date.getDayOfWeek().getValue() <= 5 ? 1 : 0);
            params.put("fiscalPeriod", date.getMonthValue());
            clickHouse.execute(sql, params);
            date = date.plusDays(1);
        }
        log.debug("Seeded dim_calendar 2020-2030");
    }

    private void syncRisks() {
        List<Risk> risks = riskRepository.findAll();
        String sql = """
            INSERT INTO bipros_analytics.dim_risk
            (risk_id, project_id, code, title, risk_type, category_id, category_name,
             owner_id, owner_name, status, rag, trend, response_type,
             identified_date, identified_by_id, closed_date, _version)
            VALUES (:riskId, :projectId, :code, :title, :riskType, :categoryId, :categoryName,
                    :ownerId, :ownerName, :status, :rag, :trend, :responseType,
                    :identifiedDate, :identifiedById, :closedDate, :version)
            """;
        for (Risk r : risks) {
            Map<String, Object> params = new HashMap<>();
            params.put("riskId", r.getId());
            params.put("projectId", r.getProjectId());
            params.put("code", r.getCode());
            params.put("title", r.getTitle());
            params.put("riskType", r.getRiskType() != null ? r.getRiskType().name() : "THREAT");
            params.put("categoryId", r.getCategory() != null ? r.getCategory().getId() : null);
            params.put("categoryName", r.getCategory() != null ? r.getCategory().getName() : "");
            params.put("ownerId", r.getOwnerId());
            params.put("ownerName", "");
            params.put("status", r.getStatus() != null ? r.getStatus().name() : "");
            params.put("rag", r.getRag() != null ? r.getRag().name() : "");
            params.put("trend", r.getTrend() != null ? r.getTrend().name() : "");
            params.put("responseType", r.getResponseType() != null ? r.getResponseType().name() : "");
            params.put("identifiedDate", r.getIdentifiedDate());
            params.put("identifiedById", r.getIdentifiedById());
            params.put("closedDate", null);
            params.put("version", VERSION);
            clickHouse.execute(sql, params);
        }
        log.debug("Synced {} risks", risks.size());
    }

    private void syncPermitTypeTemplates() {
        List<PermitTypeTemplate> templates = permitTypeTemplateRepository.findAll();
        String sql = """
            INSERT INTO bipros_analytics.dim_permit_type
            (permit_type_template_id, code, name, color_hex, icon_key, max_duration_hours,
             requires_gas_test, requires_isolation, jsa_required, blasting_required, diving_required,
             default_risk_level, night_work_policy, _version)
            VALUES (:typeId, :code, :name, :colorHex, :iconKey, :maxDurationHours,
                    :requiresGasTest, :requiresIsolation, :jsaRequired, :blastingRequired, :divingRequired,
                    :defaultRiskLevel, :nightWorkPolicy, :version)
            """;
        for (PermitTypeTemplate t : templates) {
            Map<String, Object> params = new HashMap<>();
            params.put("typeId", t.getId());
            params.put("code", t.getCode());
            params.put("name", t.getName());
            params.put("colorHex", t.getColorHex() != null ? t.getColorHex() : "");
            params.put("iconKey", t.getIconKey() != null ? t.getIconKey() : "");
            params.put("maxDurationHours", t.getMaxDurationHours());
            params.put("requiresGasTest", t.isGasTestRequired() ? 1 : 0);
            params.put("requiresIsolation", t.isIsolationRequired() ? 1 : 0);
            params.put("jsaRequired", t.isJsaRequired() ? 1 : 0);
            params.put("blastingRequired", t.isBlastingRequired() ? 1 : 0);
            params.put("divingRequired", t.isDivingRequired() ? 1 : 0);
            params.put("defaultRiskLevel", t.getDefaultRiskLevel() != null ? t.getDefaultRiskLevel().name() : "");
            params.put("nightWorkPolicy", t.getNightWorkPolicy() != null ? t.getNightWorkPolicy().name() : "");
            params.put("version", VERSION);
            clickHouse.execute(sql, params);
        }
        log.debug("Synced {} permit type templates", templates.size());
    }

    private void syncPermits() {
        List<Permit> permits = permitRepository.findAll();
        String sql = """
            INSERT INTO bipros_analytics.dim_permit
            (permit_id, project_id, permit_code, permit_type_template_id, parent_permit_id,
             status, risk_level, shift, contractor_org_id, location_zone, chainage_marker, supervisor_name,
             start_at, end_at, valid_from, valid_to, declaration_accepted_at,
             closed_at, closed_by, revoked_at, revoked_by, expired_at, suspended_at,
             total_approvals_required, approvals_completed, _version)
            VALUES (:permitId, :projectId, :permitCode, :typeId, :parentPermitId,
                    :status, :riskLevel, :shift, :contractorOrgId, :locationZone, :chainageMarker, :supervisorName,
                    :startAt, :endAt, :validFrom, :validTo, :declarationAcceptedAt,
                    :closedAt, :closedBy, :revokedAt, :revokedBy, :expiredAt, :suspendedAt,
                    :totalApprovalsRequired, :approvalsCompleted, :version)
            """;
        for (Permit p : permits) {
            Map<String, Object> params = new HashMap<>();
            params.put("permitId", p.getId());
            params.put("projectId", p.getProjectId());
            params.put("permitCode", p.getPermitCode());
            params.put("typeId", p.getPermitTypeTemplateId());
            params.put("parentPermitId", p.getParentPermitId());
            params.put("status", p.getStatus() != null ? p.getStatus().name() : "");
            params.put("riskLevel", p.getRiskLevel() != null ? p.getRiskLevel().name() : "");
            params.put("shift", p.getShift() != null ? p.getShift().name() : "");
            params.put("contractorOrgId", p.getContractorOrgId());
            params.put("locationZone", p.getLocationZone() != null ? p.getLocationZone() : "");
            params.put("chainageMarker", p.getChainageMarker() != null ? p.getChainageMarker() : "");
            params.put("supervisorName", p.getSupervisorName() != null ? p.getSupervisorName() : "");
            params.put("startAt", p.getStartAt());
            params.put("endAt", p.getEndAt());
            params.put("validFrom", p.getValidFrom());
            params.put("validTo", p.getValidTo());
            params.put("declarationAcceptedAt", p.getDeclarationAcceptedAt());
            params.put("closedAt", p.getClosedAt());
            params.put("closedBy", p.getClosedBy());
            params.put("revokedAt", p.getRevokedAt());
            params.put("revokedBy", p.getRevokedBy());
            params.put("expiredAt", p.getExpiredAt());
            params.put("suspendedAt", p.getSuspendedAt());
            params.put("totalApprovalsRequired", p.getTotalApprovalsRequired());
            params.put("approvalsCompleted", p.getApprovalsCompleted());
            params.put("version", VERSION);
            clickHouse.execute(sql, params);
        }
        log.debug("Synced {} permits", permits.size());
    }

    private void syncLabourDesignations() {
        List<LabourDesignation> designations = labourDesignationRepository.findAll();
        String sql = """
            INSERT INTO bipros_analytics.dim_labour_designation
            (designation_id, code, designation, category, trade, grade, nationality,
             experience_years_min, default_daily_rate, skills, certifications, status, _version)
            VALUES (:designationId, :code, :designation, :category, :trade, :grade, :nationality,
                    :experienceYearsMin, :defaultDailyRate, :skills, :certifications, :status, :version)
            """;
        for (LabourDesignation d : designations) {
            Map<String, Object> params = new HashMap<>();
            params.put("designationId", d.getId());
            params.put("code", d.getCode());
            params.put("designation", d.getDesignation());
            params.put("category", d.getCategory() != null ? d.getCategory().name() : "");
            params.put("trade", d.getTrade() != null ? d.getTrade() : "");
            params.put("grade", d.getGrade() != null ? d.getGrade().name() : "");
            params.put("nationality", d.getNationality() != null ? d.getNationality().name() : "");
            params.put("experienceYearsMin", d.getExperienceYearsMin() != null ? d.getExperienceYearsMin() : 0);
            params.put("defaultDailyRate", d.getDefaultDailyRate());
            params.put("skills", d.getSkills() != null ? d.getSkills() : List.of());
            params.put("certifications", d.getCertifications() != null ? d.getCertifications() : List.of());
            params.put("status", d.getStatus() != null ? d.getStatus().name() : "");
            params.put("version", VERSION);
            clickHouse.execute(sql, params);
        }
        log.debug("Synced {} labour designations", designations.size());
    }

    private void syncLabourDeploymentSnapshot() {
        List<ProjectLabourDeployment> deployments = projectLabourDeploymentRepository.findAll();
        if (deployments.isEmpty()) {
            return;
        }
        Map<UUID, LabourDesignation> designationsById = labourDesignationRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(LabourDesignation::getId, d -> d));

        LocalDate today = LocalDate.now();
        String sql = """
            INSERT INTO bipros_analytics.fact_labour_daily
            (project_id, labour_return_id, deployment_id, designation_id,
             skill_category, contractor_name, contractor_org_id, wbs_id, site_location,
             date, head_count, man_days, planned_head_count,
             daily_rate, daily_cost, source, event_ts, _version)
            VALUES (:projectId, NULL, :deploymentId, :designationId,
                    :skillCategory, :contractorName, NULL, NULL, '',
                    :date, 0, 0, :plannedHeadCount,
                    :dailyRate, :dailyCost, 'DEPLOYMENT_SNAPSHOT', now64(3), :version)
            """;
        for (ProjectLabourDeployment dep : deployments) {
            LabourDesignation d = designationsById.get(dep.getDesignationId());
            BigDecimal rate = dep.getActualDailyRate() != null
                    ? dep.getActualDailyRate()
                    : (d != null ? d.getDefaultDailyRate() : null);
            BigDecimal cost = (rate != null && dep.getWorkerCount() != null)
                    ? rate.multiply(BigDecimal.valueOf(dep.getWorkerCount()))
                    : null;

            Map<String, Object> params = new HashMap<>();
            params.put("projectId", dep.getProjectId());
            params.put("deploymentId", dep.getId());
            params.put("designationId", dep.getDesignationId());
            params.put("skillCategory", d != null && d.getCategory() != null ? d.getCategory().name() : "");
            params.put("contractorName", d != null ? d.getDesignation() : "");
            params.put("date", today);
            params.put("plannedHeadCount", dep.getWorkerCount());
            params.put("dailyRate", rate);
            params.put("dailyCost", cost);
            params.put("version", VERSION);
            clickHouse.execute(sql, params);
        }
        log.debug("Synced {} labour deployment snapshot rows", deployments.size());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Role-owned rate book (2026-05-13) — Phase 4 sync
    // ────────────────────────────────────────────────────────────────────────────

    private void syncResourceRoles() {
        List<ResourceRole> roles = resourceRoleRepository.findAll();
        for (ResourceRole r : roles) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_RESOURCE_ROLE,
                    AnalyticsDimensionSql.resourceRoleParams(r, VERSION));
        }
        log.debug("Synced {} resource roles", roles.size());
    }

    private void syncManpowerRoleRates() {
        Map<UUID, String> roleCodes = roleCodeLookup();
        Map<UUID, String> roleNames = roleNameLookup();
        Map<UUID, String> categoryNames = manpowerCategoryRepository.findAll().stream()
                .collect(Collectors.toMap(ManpowerCategoryMaster::getId,
                        c -> c.getName() != null ? c.getName() : ""));
        Map<UUID, String> gradeNames = gradeRepository.findAll().stream()
                .collect(Collectors.toMap(GradeMaster::getId,
                        g -> g.getName() != null ? g.getName() : ""));

        List<ManpowerRoleRate> rates = manpowerRoleRateRepository.findAll();
        for (ManpowerRoleRate r : rates) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_MANPOWER_ROLE_RATE,
                    AnalyticsDimensionSql.manpowerRoleRateParams(r,
                            roleCodes.get(r.getRoleId()),
                            roleNames.get(r.getRoleId()),
                            categoryNames.get(r.getCategoryId()),
                            gradeNames.get(r.getGradeId()),
                            VERSION));
        }
        log.debug("Synced {} manpower role rates", rates.size());
    }

    private void syncEquipmentRoleVariants() {
        Map<UUID, String> roleCodes = roleCodeLookup();
        Map<UUID, String> roleNames = roleNameLookup();
        List<EquipmentRoleVariant> variants = equipmentRoleVariantRepository.findAll();
        for (EquipmentRoleVariant v : variants) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_EQUIPMENT_ROLE_VARIANT,
                    AnalyticsDimensionSql.equipmentRoleVariantParams(v,
                            roleCodes.get(v.getRoleId()),
                            roleNames.get(v.getRoleId()),
                            VERSION));
        }
        log.debug("Synced {} equipment role variants", variants.size());
    }

    private void syncMaterialRoleVariants() {
        Map<UUID, String> roleCodes = roleCodeLookup();
        Map<UUID, String> roleNames = roleNameLookup();
        List<MaterialRoleVariant> variants = materialRoleVariantRepository.findAll();
        for (MaterialRoleVariant v : variants) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_MATERIAL_ROLE_VARIANT,
                    AnalyticsDimensionSql.materialRoleVariantParams(v,
                            roleCodes.get(v.getRoleId()),
                            roleNames.get(v.getRoleId()),
                            VERSION));
        }
        log.debug("Synced {} material role variants", variants.size());
    }

    private void syncProjectRateOverrides() {
        Map<UUID, String> roleCodes = roleCodeLookup();

        // Manpower overrides — variant_id is the manpower_role_rate id. Resolve role via the
        // variant's roleId so the dim row carries role_id without an extra JPA lookup per row.
        Map<UUID, UUID> manpowerVariantToRole = manpowerRoleRateRepository.findAll().stream()
                .collect(Collectors.toMap(ManpowerRoleRate::getId, ManpowerRoleRate::getRoleId));
        List<ProjectManpowerRoleRateOverride> mp = manpowerOverrideRepository.findAll();
        for (ProjectManpowerRoleRateOverride o : mp) {
            UUID roleId = manpowerVariantToRole.get(o.getManpowerRoleRateId());
            clickHouse.execute(AnalyticsDimensionSql.INSERT_PROJECT_RATE_OVERRIDE,
                    AnalyticsDimensionSql.projectRateOverrideParams(
                            o.getId(), o.getProjectId(), "MANPOWER",
                            o.getManpowerRoleRateId(), roleId, roleCodes.get(roleId),
                            o.getOverrideRate(), Boolean.TRUE.equals(o.getActive()), VERSION));
        }

        Map<UUID, UUID> equipmentVariantToRole = equipmentRoleVariantRepository.findAll().stream()
                .collect(Collectors.toMap(EquipmentRoleVariant::getId, EquipmentRoleVariant::getRoleId));
        List<ProjectEquipmentRoleVariantOverride> eq = equipmentOverrideRepository.findAll();
        for (ProjectEquipmentRoleVariantOverride o : eq) {
            UUID roleId = equipmentVariantToRole.get(o.getEquipmentRoleVariantId());
            clickHouse.execute(AnalyticsDimensionSql.INSERT_PROJECT_RATE_OVERRIDE,
                    AnalyticsDimensionSql.projectRateOverrideParams(
                            o.getId(), o.getProjectId(), "EQUIPMENT",
                            o.getEquipmentRoleVariantId(), roleId, roleCodes.get(roleId),
                            o.getOverrideRate(), Boolean.TRUE.equals(o.getActive()), VERSION));
        }

        Map<UUID, UUID> materialVariantToRole = materialRoleVariantRepository.findAll().stream()
                .collect(Collectors.toMap(MaterialRoleVariant::getId, MaterialRoleVariant::getRoleId));
        List<ProjectMaterialRoleVariantOverride> mt = materialOverrideRepository.findAll();
        for (ProjectMaterialRoleVariantOverride o : mt) {
            UUID roleId = materialVariantToRole.get(o.getMaterialRoleVariantId());
            clickHouse.execute(AnalyticsDimensionSql.INSERT_PROJECT_RATE_OVERRIDE,
                    AnalyticsDimensionSql.projectRateOverrideParams(
                            o.getId(), o.getProjectId(), "MATERIAL",
                            o.getMaterialRoleVariantId(), roleId, roleCodes.get(roleId),
                            o.getOverrideRate(), Boolean.TRUE.equals(o.getActive()), VERSION));
        }
        log.debug("Synced {} manpower + {} equipment + {} material project rate overrides",
                mp.size(), eq.size(), mt.size());
    }

    private void syncWorkActivities() {
        List<WorkActivity> activities = workActivityRepository.findAll();
        for (WorkActivity w : activities) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_WORK_ACTIVITY,
                    AnalyticsDimensionSql.workActivityParams(w, VERSION));
        }
        log.debug("Synced {} work activities", activities.size());
    }

    private void syncProductivityNorms() {
        Map<UUID, String> roleCodes = roleCodeLookup();
        Map<UUID, String> categoryNames = manpowerCategoryRepository.findAll().stream()
                .collect(Collectors.toMap(ManpowerCategoryMaster::getId,
                        c -> c.getName() != null ? c.getName() : ""));
        Map<UUID, String> gradeNames = gradeRepository.findAll().stream()
                .collect(Collectors.toMap(GradeMaster::getId,
                        g -> g.getName() != null ? g.getName() : ""));

        List<ProductivityNorm> norms = productivityNormRepository.findAll();
        for (ProductivityNorm n : norms) {
            String scope = resolveNormScope(n);
            String waCode = n.getWorkActivity() != null
                    ? (n.getWorkActivity().getCode() != null ? n.getWorkActivity().getCode() : "")
                    : "";
            String waName = n.getWorkActivity() != null
                    ? (n.getWorkActivity().getName() != null ? n.getWorkActivity().getName() : "")
                    : "";
            clickHouse.execute(AnalyticsDimensionSql.INSERT_PRODUCTIVITY_NORM,
                    AnalyticsDimensionSql.productivityNormParams(n,
                            waCode, waName,
                            roleCodes.get(n.getRoleId()),
                            categoryNames.get(n.getCategoryId()),
                            gradeNames.get(n.getGradeId()),
                            scope, VERSION));
        }
        log.debug("Synced {} productivity norms", norms.size());
    }

    private static String resolveNormScope(ProductivityNorm n) {
        if (n.getRoleId() == null) return "UNSCOPED";
        boolean hasManpowerVariant = n.getCategoryId() != null || n.getGradeId() != null;
        boolean hasEquipmentVariant = (n.getMake() != null && !n.getMake().isBlank())
                || (n.getModel() != null && !n.getModel().isBlank());
        return (hasManpowerVariant || hasEquipmentVariant) ? "VARIANT" : "ROLE";
    }

    private void syncUsers() {
        List<User> users = userRepository.findAll();
        for (User u : users) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_USER,
                    AnalyticsDimensionSql.userParams(u.getId(), u.getUsername(),
                            u.getFirstName(), u.getLastName(), u.getDesignation(),
                            u.getOrganisationId(), u.isEnabled(), VERSION));
        }
        log.debug("Synced {} users", users.size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Map<UUID, String> roleCodeLookup() {
        return resourceRoleRepository.findAll().stream()
                .collect(Collectors.toMap(ResourceRole::getId,
                        r -> r.getCode() != null ? r.getCode() : ""));
    }

    private Map<UUID, String> roleNameLookup() {
        return resourceRoleRepository.findAll().stream()
                .collect(Collectors.toMap(ResourceRole::getId,
                        r -> r.getName() != null ? r.getName() : ""));
    }
}
