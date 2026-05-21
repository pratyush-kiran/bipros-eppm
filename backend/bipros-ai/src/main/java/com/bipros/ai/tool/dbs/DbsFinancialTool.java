package com.bipros.ai.tool.dbs;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.dbs.api.dto.DbsCmDayResponse;
import com.bipros.dbs.api.dto.DbsEngineerDayResponse;
import com.bipros.dbs.api.dto.DbsEngineerPeriodResponse;
import com.bipros.dbs.api.dto.DbsProjectDayResponse;
import com.bipros.dbs.api.dto.DbsProjectPeriodResponse;
import com.bipros.dbs.api.dto.DbsSupervisorDayResponse;
import com.bipros.dbs.api.dto.DbsSupervisorPeriodResponse;
import com.bipros.dbs.service.DbsQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Financial Daily Balance Sheet — material, manpower, admin, machinery, fuel, subcontract
 * amounts; BOQ planned vs achieved; expense, income, contribution margin. Backed by the
 * {@code dbs_daily_project|engineer|supervisor} rollup tables (recomputed on DPR/material/
 * deployment events). Distinct from {@code dbs_report}, which is the headcount/utilization
 * resource ledger derived from raw DPR rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbsFinancialTool extends ProjectScopedTool {

    private final DbsQueryService dbsQueryService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "dbs_financial";
    }

    @Override
    public String description() {
        return "Financial Daily Balance Sheet for a project — expense (material/manpower/admin/"
                + "machinery/fuel/subcontract), income (BOQ achieved), contribution margin, and "
                + "contribution %, at PROJECT / ENGINEER / CM / SUPERVISOR level for a DAY / WEEK / "
                + "MONTH window. Use for questions like 'what was the contribution margin yesterday', "
                + "'show me the expense breakdown for last week', 'what did supervisor X spend on "
                + "fuel this month'. This is NOT the manpower/equipment headcount ledger — for "
                + "headcount and utilization use `dbs_report`.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        props.set("projectId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Optional — falls back to the project in scope."));

        ObjectNode level = objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Aggregation level. PROJECT rolls up everything; CM rolls up that CM's engineers + their supervisors; ENGINEER rolls up that engineer's supervisors; SUPERVISOR is the leaf row.");
        level.putArray("enum").add("PROJECT").add("ENGINEER").add("SUPERVISOR").add("CM");
        level.put("default", "PROJECT");
        props.set("level", level);

        ObjectNode periodType = objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "DAY = single day. WEEK = ISO Mon–Sun containing `date`. MONTH = calendar month containing `date`.");
        periodType.putArray("enum").add("DAY").add("WEEK").add("MONTH");
        periodType.put("default", "DAY");
        props.set("periodType", periodType);

        props.set("date", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "Reference ISO date. For DAY this is the day; for WEEK/MONTH this is any date inside the window. Defaults to today."));
        props.set("engineerUserId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Engineer user UUID — required when level=ENGINEER."));
        props.set("supervisorUserId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Supervisor user UUID — required when level=SUPERVISOR."));
        props.set("cmUserId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Construction Manager user UUID — required when level=CM."));
        props.set("includeLines", objectMapper.createObjectNode()
                .put("type", "boolean")
                .put("description", "When true and level=SUPERVISOR + periodType=DAY, includes the per-section line arrays (material/manpower/admin/machinery/fuel/BOQ/subcontract). Default false to keep responses small.")
                .put("default", false));

        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("dbs_financial needs a projectId (or a project in scope).");
        }

        String level = normaliseEnum(input.path("level").asText(null), "PROJECT");
        String periodType = normaliseEnum(input.path("periodType").asText(null), "DAY");
        LocalDate date = parseDate(input.path("date").asText(null));
        if (date == null) date = LocalDate.now();
        UUID engineerUserId = parseUuid(input.path("engineerUserId").asText(null));
        UUID supervisorUserId = parseUuid(input.path("supervisorUserId").asText(null));
        UUID cmUserId = parseUuid(input.path("cmUserId").asText(null));
        boolean includeLines = input.path("includeLines").asBoolean(false);

        Object payload;
        String summary;
        switch (level) {
            case "ENGINEER" -> {
                if (engineerUserId == null) {
                    return ToolResult.error("level=ENGINEER requires engineerUserId.");
                }
                if ("DAY".equals(periodType)) {
                    DbsEngineerDayResponse r = dbsQueryService.getEngineerDay(projectId, engineerUserId, date);
                    payload = r;
                    summary = summariseEngineerDay(r, "ENGINEER", periodType);
                } else {
                    DbsEngineerPeriodResponse r = dbsQueryService.getEngineerPeriod(projectId, engineerUserId, periodType, date);
                    payload = r;
                    summary = summariseEngineerDay(r.totals(), "ENGINEER", periodType);
                }
            }
            case "CM" -> {
                if (cmUserId == null) {
                    return ToolResult.error("level=CM requires cmUserId.");
                }
                DbsCmDayResponse r = "DAY".equals(periodType)
                        ? dbsQueryService.getCmDay(projectId, cmUserId, date)
                        : dbsQueryService.getCmPeriod(projectId, cmUserId, periodType, date);
                payload = r;
                summary = summariseCmDay(r, "CM", periodType);
            }
            case "SUPERVISOR" -> {
                if (supervisorUserId == null) {
                    return ToolResult.error("level=SUPERVISOR requires supervisorUserId.");
                }
                if ("DAY".equals(periodType)) {
                    DbsSupervisorDayResponse r = dbsQueryService.getSupervisorDay(projectId, supervisorUserId, date);
                    payload = r;
                    summary = summariseSupervisorDay(r, "SUPERVISOR", periodType);
                } else {
                    DbsSupervisorPeriodResponse r = dbsQueryService.getSupervisorPeriod(projectId, supervisorUserId, periodType, date);
                    payload = r;
                    summary = summariseSupervisorDay(r.totals(), "SUPERVISOR", periodType);
                }
            }
            default -> {
                if ("DAY".equals(periodType)) {
                    DbsProjectDayResponse r = dbsQueryService.getProjectDay(projectId, date);
                    payload = r;
                    summary = summariseProjectDay(r, "PROJECT", periodType);
                } else {
                    DbsProjectPeriodResponse r = dbsQueryService.getProjectPeriod(projectId, periodType, date);
                    payload = r;
                    summary = summariseProjectDay(r.totals(), "PROJECT", periodType);
                }
            }
        }

        JsonNode data = objectMapper.valueToTree(payload);
        // Strip the heavy per-section line arrays unless the caller asked for them. They only live
        // on the SUPERVISOR + DAY response (period totals are zero-stripped already in the service).
        if (!includeLines && "SUPERVISOR".equals(level) && "DAY".equals(periodType) && data instanceof ObjectNode obj) {
            obj.remove("materialLines");
            obj.remove("manpowerLines");
            obj.remove("adminLines");
            obj.remove("machineryLines");
            obj.remove("fuelLines");
            obj.remove("boqLines");
            obj.remove("subcontractLines");
        }
        return ToolResult.ok(summary, data);
    }

    // ── summary lines ──────────────────────────────────────────────────────────

    private String summariseProjectDay(DbsProjectDayResponse r, String level, String periodType) {
        return String.format(Locale.ROOT,
                "DBS %s/%s %s: expense=%s income=%s contribution=%s (%s%%)",
                level, periodType, r.reportDate(),
                fmt(r.totalExpense()), fmt(r.totalIncome()),
                fmt(r.contribution()), fmt(scalePct(r.contributionPct())));
    }

    private String summariseEngineerDay(DbsEngineerDayResponse r, String level, String periodType) {
        return String.format(Locale.ROOT,
                "DBS %s/%s %s: expense=%s income=%s contribution=%s (%s%%)",
                level, periodType, r.reportDate(),
                fmt(r.totalExpense()), fmt(r.totalIncome()),
                fmt(r.contribution()), fmt(scalePct(r.contributionPct())));
    }

    private String summariseCmDay(DbsCmDayResponse r, String level, String periodType) {
        // CM DTO doesn't carry totalExpense/totalIncome/contribution (see DBS Finding 9);
        // derive expense from the section amounts and income from boqForTheDayAmount.
        // contributionPct is persisted as a percentage on the CM tier (Finding 8) —
        // unlike supervisor/engineer/project which store a fraction — so do NOT call scalePct.
        BigDecimal expense = nz(r.materialAmount())
                .add(nz(r.manpowerAmount()))
                .add(nz(r.adminAmount()))
                .add(nz(r.machineryAmount()))
                .add(nz(r.fuelAmount()));
        BigDecimal income = nz(r.boqForTheDayAmount());
        BigDecimal contribution = income.subtract(expense);
        return String.format(Locale.ROOT,
                "DBS %s/%s %s: expense=%s income=%s contribution=%s (%s%%)",
                level, periodType, r.reportDate(),
                fmt(expense), fmt(income), fmt(contribution), fmt(r.contributionPct()));
    }

    private String summariseSupervisorDay(DbsSupervisorDayResponse r, String level, String periodType) {
        return String.format(Locale.ROOT,
                "DBS %s/%s %s: expense=%s income=%s contribution=%s (%s%%)",
                level, periodType, r.reportDate(),
                fmt(r.totalExpense()), fmt(r.totalIncome()),
                fmt(r.contribution()), fmt(scalePct(r.contributionPct())));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code contributionPct} is stored as a fraction (0.12 = 12%); render as a percentage. */
    private static BigDecimal scalePct(BigDecimal pct) {
        if (pct == null) return BigDecimal.ZERO;
        return pct.multiply(new BigDecimal("100"));
    }

    private static String normaliseEnum(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
