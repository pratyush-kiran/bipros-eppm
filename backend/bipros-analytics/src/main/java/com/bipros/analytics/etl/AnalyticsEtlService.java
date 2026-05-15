package com.bipros.analytics.etl;

import com.bipros.activity.domain.model.Activity;
import com.bipros.analytics.etl.dto.RiskSnapshotRow;
import com.bipros.analytics.store.ClickHouseTemplate;
import com.bipros.baseline.domain.Baseline;
import com.bipros.contract.domain.model.VariationOrder;
import com.bipros.cost.domain.entity.CostAccount;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.resource.domain.model.Resource;
import com.bipros.scheduling.domain.model.ScheduleResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central service for streaming inserts into ClickHouse fact tables.
 * All methods are idempotent via ReplacingMergeTree(_version).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsEtlService {

    private final ClickHouseTemplate clickHouse;

    /**
     * Visible for tests + per-dim upsert paths in this package. Live writes use this so
     * every event-driven row has a strictly newer {@code _version} than the nightly batch
     * (which fixes {@code VERSION} once per JVM start), letting ReplacingMergeTree converge
     * to the live state on merge.
     */
    long nowVersion() {
        return System.currentTimeMillis();
    }

    public void insertActivityProgressDaily(
            UUID projectId, UUID activityId, LocalDate date,
            Float pctCompletePhysical, Float pctCompleteDuration,
            Double qtyExecuted, Double cumulativeQty,
            Double chainageFromM, Double chainageToM,
            String source) {

        String sql = """
            INSERT INTO bipros_analytics.fact_activity_progress_daily
            (project_id, activity_id, date, pct_complete_physical, pct_complete_duration,
             qty_executed, cumulative_qty, chainage_from_m, chainage_to_m, source, event_ts, _version)
            VALUES (:projectId, :activityId, :date, :pctPhysical, :pctDuration,
                    :qtyExecuted, :cumulativeQty, :chainageFrom, :chainageTo, :source, now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("activityId", activityId);
        params.put("date", date);
        params.put("pctPhysical", pctCompletePhysical);
        params.put("pctDuration", pctCompleteDuration);
        params.put("qtyExecuted", qtyExecuted);
        params.put("cumulativeQty", cumulativeQty);
        params.put("chainageFrom", chainageFromM);
        params.put("chainageTo", chainageToM);
        params.put("source", source);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted activity_progress: project={} activity={} date={}", projectId, activityId, date);
    }

    public void insertResourceUsageDaily(
            UUID projectId, UUID activityId, UUID resourceId, String resourceType, LocalDate date,
            Float hoursWorked, Float daysWorked, Double qtyExecuted,
            Float productivityActual, Float productivityNorm, BigDecimal cost) {

        String sql = """
            INSERT INTO bipros_analytics.fact_resource_usage_daily
            (project_id, activity_id, resource_id, resource_type, date,
             hours_worked, days_worked, qty_executed, productivity_actual, productivity_norm, cost, event_ts, _version)
            VALUES (:projectId, :activityId, :resourceId, :resourceType, :date,
                    :hoursWorked, :daysWorked, :qtyExecuted, :prodActual, :prodNorm, :cost, now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("activityId", activityId);
        params.put("resourceId", resourceId);
        params.put("resourceType", resourceType);
        params.put("date", date);
        params.put("hoursWorked", hoursWorked);
        params.put("daysWorked", daysWorked);
        params.put("qtyExecuted", qtyExecuted);
        params.put("prodActual", productivityActual);
        params.put("prodNorm", productivityNorm);
        params.put("cost", cost);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted resource_usage: project={} activity={} resource={} date={}", projectId, activityId, resourceId, date);
    }

    public void insertCostDaily(
            UUID projectId, UUID wbsId, UUID activityId, LocalDate date, UUID costAccountId,
            BigDecimal laborCost, BigDecimal materialCost, BigDecimal equipmentCost, BigDecimal expenseCost,
            BigDecimal totalActual, BigDecimal totalPlanned, BigDecimal totalEarned) {

        String sql = """
            INSERT INTO bipros_analytics.fact_cost_daily
            (project_id, wbs_id, activity_id, date, cost_account_id,
             labor_cost, material_cost, equipment_cost, expense_cost,
             total_actual, total_planned, total_earned, event_ts, _version)
            VALUES (:projectId, :wbsId, :activityId, :date, :costAccountId,
                    :laborCost, :materialCost, :equipmentCost, :expenseCost,
                    :totalActual, :totalPlanned, :totalEarned, now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("wbsId", wbsId);
        params.put("activityId", activityId);
        params.put("date", date);
        params.put("costAccountId", costAccountId);
        params.put("laborCost", laborCost);
        params.put("materialCost", materialCost);
        params.put("equipmentCost", equipmentCost);
        params.put("expenseCost", expenseCost);
        params.put("totalActual", totalActual);
        params.put("totalPlanned", totalPlanned);
        params.put("totalEarned", totalEarned);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted cost_daily: project={} activity={} date={}", projectId, activityId, date);
    }

    public void insertEvmDaily(
            UUID projectId, UUID wbsId, UUID activityId, LocalDate date,
            BigDecimal bac, BigDecimal pv, BigDecimal ev, BigDecimal ac,
            BigDecimal cv, BigDecimal sv, Double cpi, Double spi, Double tcpi,
            BigDecimal eac, BigDecimal etcCost, BigDecimal vac,
            String periodSource, String interpolation) {

        String sql = """
            INSERT INTO bipros_analytics.fact_evm_daily
            (project_id, wbs_id, activity_id, date, bac, pv, ev, ac, cv, sv, cpi, spi, tcpi,
             eac, etc_cost, vac, period_source, interpolation, event_ts, _version)
            VALUES (:projectId, :wbsId, :activityId, :date, :bac, :pv, :ev, :ac, :cv, :sv, :cpi, :spi, :tcpi,
                    :eac, :etcCost, :vac, :periodSource, :interpolation, now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("wbsId", wbsId);
        params.put("activityId", activityId);
        params.put("date", date);
        params.put("bac", bac);
        params.put("pv", pv);
        params.put("ev", ev);
        params.put("ac", ac);
        params.put("cv", cv);
        params.put("sv", sv);
        params.put("cpi", cpi);
        params.put("spi", spi);
        params.put("tcpi", tcpi);
        params.put("eac", eac);
        params.put("etcCost", etcCost);
        params.put("vac", vac);
        params.put("periodSource", periodSource);
        params.put("interpolation", interpolation);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted evm_daily: project={} date={}", projectId, date);
    }

    /**
     * Phase 4.3: the value written into {@code supervisor_user_id} is now the supervisor's
     * USER id (FK to {@code public.users.id}) — the canonical identity after the role-only
     * assignment refactor. The ClickHouse column was added with the {@code supervisor_user_id}
     * name from the start; only the OLTP rename (Liquibase 087 / 091) and the Java contract
     * are catching up. Older fact rows written before this phase carry resource ids in the
     * same column — query callers must be aware until a backfill rewrites them. See
     * clickhouse-init.sql.
     */
    public void insertDprLog(
            UUID projectId, UUID activityId, UUID dprId, LocalDate reportDate,
            UUID supervisorUserId, String supervisorName,
            Double chainageFromM, Double chainageToM,
            Double qtyExecuted, Double cumulativeQty,
            String weather, Float temperatureC, String remarksText) {

        String sql = """
            INSERT INTO bipros_analytics.fact_dpr_logs
            (project_id, activity_id, dpr_id, report_date, supervisor_user_id, supervisor_name,
             chainage_from_m, chainage_to_m, qty_executed, cumulative_qty,
             weather, temperature_c, remarks_text, remarks_embedding, event_ts, _version)
            VALUES (:projectId, :activityId, :dprId, :reportDate, :supervisorUserId, :supervisorName,
                    :chainageFrom, :chainageTo, :qtyExecuted, :cumulativeQty,
                    :weather, :temperatureC, :remarksText, [], now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("activityId", activityId != null ? activityId : new UUID(0L, 0L));
        params.put("dprId", dprId);
        params.put("reportDate", reportDate);
        params.put("supervisorUserId", supervisorUserId != null ? supervisorUserId : new UUID(0L, 0L));
        params.put("supervisorName", supervisorName != null ? supervisorName : "");
        params.put("chainageFrom", chainageFromM);
        params.put("chainageTo", chainageToM);
        params.put("qtyExecuted", qtyExecuted != null ? qtyExecuted : 0.0);
        params.put("cumulativeQty", cumulativeQty != null ? cumulativeQty : 0.0);
        params.put("weather", weather != null ? weather : "");
        params.put("temperatureC", temperatureC);
        params.put("remarksText", remarksText != null ? remarksText : "");
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted dpr_log: project={} dpr={} date={}", projectId, dprId, reportDate);
    }

    /**
     * Manpower line items deployed under a single DPR row, denormalised into one fact row per
     * (dpr, trade-row). Expected ClickHouse DDL:
     * <pre>
     * CREATE TABLE bipros_analytics.fact_dpr_manpower_daily (
     *   project_id UUID, activity_id UUID, dpr_id UUID, manpower_row_id UUID,
     *   report_date Date, trade String, category String, contractor_name String,
     *   nos UInt16, working_hours Float32, ot_hours Float32,
     *   event_ts DateTime64(3), _version UInt64
     * ) ENGINE = ReplacingMergeTree(_version)
     * PARTITION BY toYYYYMM(report_date) ORDER BY (project_id, dpr_id, manpower_row_id);
     * </pre>
     */
    public void insertDprManpowerDaily(
            UUID projectId, UUID activityId, UUID dprId, UUID manpowerRowId, LocalDate reportDate,
            String trade, String category, String contractorName,
            Integer nos, Double workingHours, Double otHours) {

        String sql = """
            INSERT INTO bipros_analytics.fact_dpr_manpower_daily
            (project_id, activity_id, dpr_id, manpower_row_id, report_date,
             trade, category, contractor_name, nos, working_hours, ot_hours,
             event_ts, _version)
            VALUES (:projectId, :activityId, :dprId, :manpowerRowId, :reportDate,
                    :trade, :category, :contractorName, :nos, :workingHours, :otHours,
                    now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("activityId", activityId != null ? activityId : new UUID(0L, 0L));
        params.put("dprId", dprId);
        params.put("manpowerRowId", manpowerRowId);
        params.put("reportDate", reportDate);
        params.put("trade", emptyIfNull(trade));
        params.put("category", emptyIfNull(category));
        params.put("contractorName", emptyIfNull(contractorName));
        params.put("nos", nos != null ? nos : 0);
        params.put("workingHours", workingHours);
        params.put("otHours", otHours);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
    }

    /**
     * Equipment / PMV line items under a DPR row. Expected ClickHouse DDL:
     * <pre>
     * CREATE TABLE bipros_analytics.fact_dpr_equipment_daily (
     *   project_id UUID, activity_id UUID, dpr_id UUID, equipment_row_id UUID,
     *   report_date Date, equipment_type String, fleet_no String, ownership String,
     *   nos UInt16, working_hours Float32, idle_hours Float32, breakdown_hours Float32,
     *   fuel_litres Float32, operator_name String, availability_status String,
     *   event_ts DateTime64(3), _version UInt64
     * ) ENGINE = ReplacingMergeTree(_version)
     * PARTITION BY toYYYYMM(report_date) ORDER BY (project_id, dpr_id, equipment_row_id);
     * </pre>
     */
    public void insertDprEquipmentDaily(
            UUID projectId, UUID activityId, UUID dprId, UUID equipmentRowId, LocalDate reportDate,
            String equipmentType, String fleetNo, String ownership, Integer nos,
            Double workingHours, Double idleHours, Double breakdownHours,
            Double fuelLitres, String operatorName, String availabilityStatus) {

        String sql = """
            INSERT INTO bipros_analytics.fact_dpr_equipment_daily
            (project_id, activity_id, dpr_id, equipment_row_id, report_date,
             equipment_type, fleet_no, ownership, nos, working_hours, idle_hours,
             breakdown_hours, fuel_litres, operator_name, availability_status,
             event_ts, _version)
            VALUES (:projectId, :activityId, :dprId, :equipmentRowId, :reportDate,
                    :equipmentType, :fleetNo, :ownership, :nos, :workingHours, :idleHours,
                    :breakdownHours, :fuelLitres, :operatorName, :availabilityStatus,
                    now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("activityId", activityId != null ? activityId : new UUID(0L, 0L));
        params.put("dprId", dprId);
        params.put("equipmentRowId", equipmentRowId);
        params.put("reportDate", reportDate);
        params.put("equipmentType", emptyIfNull(equipmentType));
        params.put("fleetNo", emptyIfNull(fleetNo));
        params.put("ownership", emptyIfNull(ownership));
        params.put("nos", nos != null ? nos : 0);
        params.put("workingHours", workingHours);
        params.put("idleHours", idleHours);
        params.put("breakdownHours", breakdownHours);
        params.put("fuelLitres", fuelLitres);
        params.put("operatorName", emptyIfNull(operatorName));
        params.put("availabilityStatus", emptyIfNull(availabilityStatus));
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
    }

    /**
     * Material consumption line items under a DPR row. Expected ClickHouse DDL:
     * <pre>
     * CREATE TABLE bipros_analytics.fact_dpr_material_daily (
     *   project_id UUID, activity_id UUID, dpr_id UUID, material_row_id UUID,
     *   report_date Date, material_name String, unit String, quantity Float64,
     *   source String, vendor_name String, batch_no String,
     *   event_ts DateTime64(3), _version UInt64
     * ) ENGINE = ReplacingMergeTree(_version)
     * PARTITION BY toYYYYMM(report_date) ORDER BY (project_id, dpr_id, material_row_id);
     * </pre>
     */
    public void insertDprMaterialDaily(
            UUID projectId, UUID activityId, UUID dprId, UUID materialRowId, LocalDate reportDate,
            String materialName, String unit, Double quantity,
            String source, String vendorName, String batchNo) {

        String sql = """
            INSERT INTO bipros_analytics.fact_dpr_material_daily
            (project_id, activity_id, dpr_id, material_row_id, report_date,
             material_name, unit, quantity, source, vendor_name, batch_no,
             event_ts, _version)
            VALUES (:projectId, :activityId, :dprId, :materialRowId, :reportDate,
                    :materialName, :unit, :quantity, :source, :vendorName, :batchNo,
                    now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("activityId", activityId != null ? activityId : new UUID(0L, 0L));
        params.put("dprId", dprId);
        params.put("materialRowId", materialRowId);
        params.put("reportDate", reportDate);
        params.put("materialName", emptyIfNull(materialName));
        params.put("unit", emptyIfNull(unit));
        params.put("quantity", quantity);
        params.put("source", emptyIfNull(source));
        params.put("vendorName", emptyIfNull(vendorName));
        params.put("batchNo", emptyIfNull(batchNo));
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
    }

    /**
     * Field-issue log entries attached to a DPR row. Expected ClickHouse DDL is in
     * {@code docker/clickhouse-init.sql} (table {@code fact_dpr_issues_daily}). One row per
     * issue per upsert; the engine ({@code ReplacingMergeTree(_version)}) collapses to the
     * highest version on FINAL queries, so a PATCH that flips status produces a strictly
     * newer row.
     *
     * <p>{@code resolutionAgeHours} is precomputed by the caller (or null when the issue is
     * still open) to keep aggregation cheap downstream.
     */
    public void insertDprIssue(
            UUID projectId, UUID dprId, UUID issueId, UUID activityId, String activityName,
            UUID supervisorResourceId, String supervisorName,
            UUID assignedToResourceId, String assignedToName,
            LocalDate reportDate, Instant openedAt, Instant resolvedAt,
            Double resolutionAgeHours,
            String category, String severity, String status,
            String title, String description,
            Double chainageFromM, Double chainageToM) {

        String sql = """
            INSERT INTO bipros_analytics.fact_dpr_issues_daily
            (project_id, dpr_id, issue_id, activity_id, activity_name,
             supervisor_resource_id, supervisor_name,
             assigned_to_resource_id, assigned_to_name,
             report_date, opened_at, resolved_at, resolution_age_hours,
             category, severity, status, title, description,
             chainage_from_m, chainage_to_m,
             event_ts, _version)
            VALUES (:projectId, :dprId, :issueId, :activityId, :activityName,
                    :supervisorResourceId, :supervisorName,
                    :assignedToResourceId, :assignedToName,
                    :reportDate, :openedAt, :resolvedAt, :resolutionAgeHours,
                    :category, :severity, :status, :title, :description,
                    :chainageFromM, :chainageToM,
                    now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("dprId", dprId);
        params.put("issueId", issueId);
        params.put("activityId", activityId);
        params.put("activityName", emptyIfNull(activityName));
        params.put("supervisorResourceId", supervisorResourceId);
        params.put("supervisorName", emptyIfNull(supervisorName));
        params.put("assignedToResourceId", assignedToResourceId);
        params.put("assignedToName", emptyIfNull(assignedToName));
        params.put("reportDate", reportDate);
        params.put("openedAt", openedAt);
        params.put("resolvedAt", resolvedAt);
        params.put("resolutionAgeHours", resolutionAgeHours);
        params.put("category", emptyIfNull(category));
        params.put("severity", emptyIfNull(severity));
        params.put("status", emptyIfNull(status));
        params.put("title", emptyIfNull(title));
        params.put("description", emptyIfNull(description));
        params.put("chainageFromM", chainageFromM);
        params.put("chainageToM", chainageToM);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
    }

    public void insertRiskSnapshotDaily(
            UUID projectId, UUID riskId, LocalDate date,
            Float probability, BigDecimal impactCost, Integer impactDays,
            String rag, String status,
            BigDecimal mcP50, BigDecimal mcP80, BigDecimal mcP95) {
        insertRiskSnapshotDaily(RiskSnapshotRow.builder()
                .projectId(projectId).riskId(riskId).date(date)
                .probability(probability).impactCost(impactCost).impactDays(impactDays)
                .rag(rag).status(status)
                .monteCarloP50(mcP50).monteCarloP80(mcP80).monteCarloP95(mcP95)
                .build());
    }

    public void insertRiskSnapshotDaily(RiskSnapshotRow row) {
        String sql = """
            INSERT INTO bipros_analytics.fact_risk_snapshot_daily
            (project_id, risk_id, date, probability, impact_cost, impact_days,
             rag, status, monte_carlo_p50, monte_carlo_p80, monte_carlo_p95,
             risk_score, residual_risk_score, risk_type, owner_id, category_id,
             post_response_probability, post_response_impact_cost, post_response_impact_schedule,
             pre_response_exposure_cost, post_response_exposure_cost,
             exposure_start_date, exposure_finish_date,
             response_type, trend, identified_date, identified_by_id,
             event_ts, _version)
            VALUES (:projectId, :riskId, :date, :probability, :impactCost, :impactDays,
                    :rag, :status, :mcP50, :mcP80, :mcP95,
                    :riskScore, :residualRiskScore, :riskType, :ownerId, :categoryId,
                    :postProbability, :postImpactCost, :postImpactSchedule,
                    :preExposureCost, :postExposureCost,
                    :exposureStart, :exposureFinish,
                    :responseType, :trend, :identifiedDate, :identifiedById,
                    now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", row.projectId());
        params.put("riskId", row.riskId());
        params.put("date", row.date());
        params.put("probability", row.probability());
        params.put("impactCost", row.impactCost());
        params.put("impactDays", row.impactDays());
        params.put("rag", emptyIfNull(row.rag()));
        params.put("status", emptyIfNull(row.status()));
        params.put("mcP50", row.monteCarloP50());
        params.put("mcP80", row.monteCarloP80());
        params.put("mcP95", row.monteCarloP95());
        params.put("riskScore", row.riskScore());
        params.put("residualRiskScore", row.residualRiskScore());
        params.put("riskType", row.riskType() != null ? row.riskType() : "THREAT");
        params.put("ownerId", row.ownerId());
        params.put("categoryId", row.categoryId());
        params.put("postProbability", row.postResponseProbability());
        params.put("postImpactCost", row.postResponseImpactCost());
        params.put("postImpactSchedule", row.postResponseImpactSchedule());
        params.put("preExposureCost", row.preResponseExposureCost());
        params.put("postExposureCost", row.postResponseExposureCost());
        params.put("exposureStart", row.exposureStartDate());
        params.put("exposureFinish", row.exposureFinishDate());
        params.put("responseType", emptyIfNull(row.responseType()));
        params.put("trend", emptyIfNull(row.trend()));
        params.put("identifiedDate", row.identifiedDate());
        params.put("identifiedById", row.identifiedById());
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted risk_snapshot: project={} risk={} date={}",
                row.projectId(), row.riskId(), row.date());
    }

    public void insertPermitLifecycle(
            UUID projectId, UUID permitId, UUID permitTypeTemplateId,
            String eventType, Instant occurredAt, UUID actorUserId,
            String riskLevel, String permitStatus, String payloadJson,
            Float durationHoursToEvent) {

        String sql = """
            INSERT INTO bipros_analytics.fact_permit_lifecycle
            (project_id, permit_id, permit_type_template_id, event_type, occurred_at,
             actor_user_id, risk_level, permit_status, payload_json,
             duration_hours_to_event, event_ts, _version)
            VALUES (:projectId, :permitId, :typeTemplateId, :eventType, :occurredAt,
                    :actorUserId, :riskLevel, :permitStatus, :payloadJson,
                    :durationHours, now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("permitId", permitId);
        params.put("typeTemplateId", permitTypeTemplateId);
        params.put("eventType", eventType);
        params.put("occurredAt", occurredAt);
        params.put("actorUserId", actorUserId);
        params.put("riskLevel", emptyIfNull(riskLevel));
        params.put("permitStatus", emptyIfNull(permitStatus));
        params.put("payloadJson", payloadJson != null ? payloadJson : "");
        params.put("durationHours", durationHoursToEvent);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted permit_lifecycle: project={} permit={} event={} at={}",
                projectId, permitId, eventType, occurredAt);
    }

    public void insertLabourDaily(
            UUID projectId, UUID labourReturnId, UUID deploymentId, UUID designationId,
            String skillCategory, String contractorName, UUID contractorOrgId,
            UUID wbsId, String siteLocation, LocalDate date,
            Integer headCount, Float manDays, Integer plannedHeadCount,
            BigDecimal dailyRate, BigDecimal dailyCost, String source) {

        String sql = """
            INSERT INTO bipros_analytics.fact_labour_daily
            (project_id, labour_return_id, deployment_id, designation_id,
             skill_category, contractor_name, contractor_org_id, wbs_id, site_location,
             date, head_count, man_days, planned_head_count,
             daily_rate, daily_cost, source, event_ts, _version)
            VALUES (:projectId, :labourReturnId, :deploymentId, :designationId,
                    :skillCategory, :contractorName, :contractorOrgId, :wbsId, :siteLocation,
                    :date, :headCount, :manDays, :plannedHeadCount,
                    :dailyRate, :dailyCost, :source, now64(3), :version)
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("labourReturnId", labourReturnId);
        params.put("deploymentId", deploymentId);
        params.put("designationId", designationId);
        params.put("skillCategory", emptyIfNull(skillCategory));
        params.put("contractorName", contractorName != null ? contractorName : "");
        params.put("contractorOrgId", contractorOrgId);
        params.put("wbsId", wbsId);
        params.put("siteLocation", siteLocation != null ? siteLocation : "");
        params.put("date", date);
        params.put("headCount", headCount != null ? headCount : 0);
        params.put("manDays", manDays != null ? manDays : 0f);
        params.put("plannedHeadCount", plannedHeadCount);
        params.put("dailyRate", dailyRate);
        params.put("dailyCost", dailyCost);
        params.put("source", source);
        params.put("version", nowVersion());

        clickHouse.execute(sql, params);
        log.debug("Inserted labour_daily: project={} date={} contractor={} skill={} source={}",
                projectId, date, contractorName, skillCategory, source);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Live dimension upserts. Each one issues a single INSERT into a
    // ReplacingMergeTree(_version) table with _version = nowVersion(), so it always
    // overrides the most recent nightly batch row for the same key. Listeners route
    // through here from the AFTER_COMMIT phase.
    // ────────────────────────────────────────────────────────────────────────────

    public void upsertProjectDimension(Project p) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_PROJECT,
                AnalyticsDimensionSql.projectParams(p, nowVersion()));
        log.debug("Upserted dim_project: id={} code={}", p.getId(), p.getCode());
    }

    public void upsertActivityDimension(Activity a) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_ACTIVITY,
                AnalyticsDimensionSql.activityParams(a, nowVersion()));
        log.debug("Upserted dim_activity: id={} project={}", a.getId(), a.getProjectId());
    }

    /**
     * Bulk dim_activity upsert. Used by P6 imports / sweep-style updates that touch
     * thousands of activities at once. Falls back to per-row execute calls because
     * ClickHouseTemplate's NamedParameter helper does not expose a multi-row VALUES
     * builder out of the box. Internally batched via NamedParameterJdbcTemplate's
     * SqlParameterSource[] batchUpdate path inside ClickHouseTemplate (TODO once
     * batchUpdate is added there); for now this is N round-trips but with a single
     * version stamp so dedup is consistent.
     */
    public void upsertActivitiesBulkDimension(List<Activity> activities) {
        if (activities == null || activities.isEmpty()) {
            return;
        }
        long version = nowVersion();
        List<Map<String, Object>> rows = new ArrayList<>(activities.size());
        for (Activity a : activities) {
            rows.add(AnalyticsDimensionSql.activityParams(a, version));
        }
        // Reuse the existing batchInsert hook on ClickHouseTemplate. It takes a table
        // and a list of named-param maps — for ReplacingMergeTree the column order in
        // the INSERT statement does not matter, only that the keys match the columns.
        // Fall back to per-row execute if batchInsert is not appropriate here.
        for (Map<String, Object> params : rows) {
            clickHouse.execute(AnalyticsDimensionSql.INSERT_ACTIVITY, params);
        }
        log.debug("Bulk-upserted {} activities into dim_activity (version={})",
                activities.size(), version);
    }

    public void upsertResourceDimension(Resource r) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_RESOURCE,
                AnalyticsDimensionSql.resourceParams(r, nowVersion()));
        log.debug("Upserted dim_resource: id={} code={}", r.getId(), r.getCode());
    }

    public void upsertWbsDimension(WbsNode w) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_WBS,
                AnalyticsDimensionSql.wbsParams(w, nowVersion()));
        log.debug("Upserted dim_wbs: id={} project={}", w.getId(), w.getProjectId());
    }

    public void upsertCostAccountDimension(CostAccount c) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_COST_ACCOUNT,
                AnalyticsDimensionSql.costAccountParams(c, nowVersion()));
        log.debug("Upserted dim_cost_account: id={} code={}", c.getId(), c.getCode());
    }

    public void upsertBaselineDimension(Baseline b) {
        upsertBaselineDimension(b, b.getIsActive() != null && b.getIsActive());
    }

    /**
     * Explicit form for the {@code BaselineDeactivatedEvent} path: emit an is_active=0
     * row with a strictly newer _version even when the in-memory entity still has
     * {@code isActive=true} (rare race during the deactivation transaction).
     */
    public void upsertBaselineDimension(Baseline b, boolean active) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_BASELINE,
                AnalyticsDimensionSql.baselineParams(b, active, nowVersion()));
        log.debug("Upserted dim_baseline: id={} project={} active={}",
                b.getId(), b.getProjectId(), active);
    }

    public void upsertScheduleRunDimension(ScheduleResult s) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_SCHEDULE_RUN,
                AnalyticsDimensionSql.scheduleRunParams(s, nowVersion()));
        log.debug("Upserted dim_schedule_run: id={} project={}", s.getId(), s.getProjectId());
    }

    /**
     * Variation Order → dim_contract row. The VO's contract row links it to the project,
     * so the listener must pass that projectId through (the VO entity does not carry it).
     */
    public void upsertContractDimension(VariationOrder vo, UUID projectId) {
        clickHouse.execute(
                AnalyticsDimensionSql.INSERT_CONTRACT,
                AnalyticsDimensionSql.contractParams(vo, projectId, nowVersion()));
        log.debug("Upserted dim_contract: voId={} contractId={} project={}",
                vo.getId(), vo.getContractId(), projectId);
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }
}
