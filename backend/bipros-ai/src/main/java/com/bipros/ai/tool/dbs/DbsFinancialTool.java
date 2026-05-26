package com.bipros.ai.tool.dbs;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.dbs.api.dto.DbsCmDayResponse;
import com.bipros.dbs.api.dto.DbsEngineerDayResponse;
import com.bipros.dbs.api.dto.DbsEngineerPeriodResponse;
import com.bipros.dbs.api.dto.DbsProjectDayResponse;
import com.bipros.dbs.api.dto.DbsProjectPeriodResponse;
import com.bipros.dbs.api.dto.DbsSubContractLineDto;
import com.bipros.dbs.api.dto.DbsSupervisorDayResponse;
import com.bipros.dbs.api.dto.DbsSupervisorPeriodResponse;
import com.bipros.dbs.service.DbsQueryService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
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
import java.util.List;
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
    private final ProjectRepository projectRepository;
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
                .put("description", "When true and level=SUPERVISOR + periodType=DAY, includes the per-section line arrays (material/manpower/admin/machinery/fuel/BOQ/subcontract). Default false to keep responses small. Note: for level=PROJECT + periodType=DAY the per-SC line array (PM tab Section F) is always returned regardless of this flag.")
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
        // PM tab Section F (per-SC margin table) is the most-asked drill-down; never silently
        // drop it even if the caller passed includeLines=false.
        if ("PROJECT".equals(level) && "DAY".equals(periodType)) {
            includeLines = true;
        }

        String currency = projectRepository.findById(projectId)
                .map(Project::getBudgetCurrency)
                .orElse("INR");

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
                    summary = summariseEngineerDay(r, "ENGINEER", periodType, currency);
                } else {
                    DbsEngineerPeriodResponse r = dbsQueryService.getEngineerPeriod(projectId, engineerUserId, periodType, date);
                    payload = r;
                    summary = summariseEngineerDay(r.totals(), "ENGINEER", periodType, currency)
                            + periodFooter(periodType, r.from(), r.to(), engineerActiveDays(r.dailyRows()), r.dailyRows() == null ? 0 : r.dailyRows().size());
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
                summary = summariseCmDay(r, "CM", periodType, currency);
            }
            case "SUPERVISOR" -> {
                if (supervisorUserId == null) {
                    return ToolResult.error("level=SUPERVISOR requires supervisorUserId.");
                }
                if ("DAY".equals(periodType)) {
                    DbsSupervisorDayResponse r = dbsQueryService.getSupervisorDay(projectId, supervisorUserId, date);
                    payload = r;
                    summary = summariseSupervisorDay(r, "SUPERVISOR", periodType, currency);
                } else {
                    DbsSupervisorPeriodResponse r = dbsQueryService.getSupervisorPeriod(projectId, supervisorUserId, periodType, date);
                    payload = r;
                    summary = summariseSupervisorDay(r.totals(), "SUPERVISOR", periodType, currency)
                            + periodFooter(periodType, r.from(), r.to(), supervisorActiveDays(r.dailyRows()), r.dailyRows() == null ? 0 : r.dailyRows().size());
                }
            }
            default -> {
                if ("DAY".equals(periodType)) {
                    DbsProjectDayResponse r = dbsQueryService.getProjectDay(projectId, date);
                    payload = r;
                    summary = summariseProjectDay(r, "PROJECT", periodType, currency);
                } else {
                    DbsProjectPeriodResponse r = dbsQueryService.getProjectPeriod(projectId, periodType, date);
                    payload = r;
                    summary = summariseProjectDay(r.totals(), "PROJECT", periodType, currency)
                            + periodFooter(periodType, r.from(), r.to(), projectActiveDays(r.dailyRows()), r.dailyRows() == null ? 0 : r.dailyRows().size());
                }
            }
        }

        JsonNode data = objectMapper.valueToTree(payload);
        // Strip the heavy per-section line arrays unless the caller asked for them. They only live
        // on the SUPERVISOR + DAY response (period totals are zero-stripped already in the service).
        // PROJECT/DAY keeps subcontractLines unconditionally — PM tab Section F drill-down.
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

    private String summariseProjectDay(DbsProjectDayResponse r, String level, String periodType, String currency) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "DBS %s/%s %s [%s]: expense=%s income=%s contribution=%s (%s%%)",
                level, periodType, r.reportDate(), currency,
                fmtCcy(r.totalExpense(), currency), fmtCcy(r.totalIncome(), currency),
                fmtCcy(r.contribution(), currency), fmt(scalePct(r.contributionPct()))));
        sb.append(String.format(Locale.ROOT,
                "\nSections: A.manpower=%s B.admin=%s C.machinery=%s D.fuel=%s E.material=%s F.subcontract=%s G.generalExpense=%s",
                fmtCcy(r.manpowerAmount(), currency),
                fmtCcy(r.adminAmount(), currency),
                fmtCcy(r.machineryAmount(), currency),
                fmtCcy(r.fuelAmount(), currency),
                fmtCcy(r.materialAmount(), currency),
                fmtCcy(r.subcontractAmount(), currency),
                fmtCcy(r.generalExpenseAmount(), currency)));
        sb.append(String.format(Locale.ROOT,
                "\nBOQ: direct=%s prelim=%s total=%s pctAchieved=%s%%",
                fmtCcy(r.directCost(), currency),
                fmtCcy(r.prelimCost(), currency),
                fmtCcy(r.totalCostInclPrelims(), currency),
                fmt(r.pctAchieved())));
        sb.append(String.format(Locale.ROOT,
                "\nCumulative to date: expense=%s income=%s contribution=%s",
                fmtCcy(r.cumulativeExpense(), currency),
                fmtCcy(r.cumulativeIncome(), currency),
                fmtCcy(r.cumulativeContribution(), currency)));
        int engineerCount = r.engineerIds() == null ? 0 : r.engineerIds().size();
        int supervisorCount = r.supervisorCount() == null ? 0 : r.supervisorCount();
        int dprCount = r.dprCount() == null ? 0 : r.dprCount();
        sb.append(String.format(Locale.ROOT,
                "\nCounts: dprCount=%d supervisorCount=%d engineerCount=%d",
                dprCount, supervisorCount, engineerCount));
        String alerts = (r.alerts() == null || r.alerts().isEmpty()) ? "" : String.join(",", r.alerts());
        sb.append("\nAlerts: ").append(alerts);
        List<DbsSubContractLineDto> scLines = r.subcontractLines();
        if (scLines != null && !scLines.isEmpty()) {
            for (DbsSubContractLineDto line : scLines) {
                sb.append(String.format(Locale.ROOT,
                        "\n  · %s (%s) %s: %s %s × %s = %s expense / %s imputed income / %s margin",
                        line.subContractorName(),
                        line.subContractorCode(),
                        line.workTypeName(),
                        fmt(line.qty()),
                        line.unit() == null ? "" : line.unit(),
                        fmtCcy(line.scRate(), currency),
                        fmtCcy(line.scExpense(), currency),
                        fmtCcy(line.scImputedIncome(), currency),
                        fmtCcy(line.scMargin(), currency)));
            }
        }
        return sb.toString();
    }

    private String summariseEngineerDay(DbsEngineerDayResponse r, String level, String periodType, String currency) {
        return String.format(Locale.ROOT,
                "DBS %s/%s %s [%s]: expense=%s income=%s contribution=%s (%s%%)"
                        + "\nSections: A.manpower=%s B.admin=%s (project-only, 0 here) C.machinery=%s D.fuel=%s E.material=%s subcontract=%s"
                        + " — F sub-contractor & G general expense are project-only, 0 at engineer scope",
                level, periodType, r.reportDate(), currency,
                fmtCcy(r.totalExpense(), currency), fmtCcy(r.totalIncome(), currency),
                fmtCcy(r.contribution(), currency), fmt(scalePct(r.contributionPct())),
                fmtCcy(r.manpowerAmount(), currency),
                fmtCcy(r.adminAmount(), currency),
                fmtCcy(r.machineryAmount(), currency),
                fmtCcy(r.fuelAmount(), currency),
                fmtCcy(r.materialAmount(), currency),
                fmtCcy(r.subcontractAmount(), currency));
    }

    private String summariseCmDay(DbsCmDayResponse r, String level, String periodType, String currency) {
        // CM DTO doesn't carry totalExpense/totalIncome/contribution (see DBS Finding 9);
        // derive expense from the section amounts and income from boqForTheDayAmount.
        // contributionPct is persisted as a percentage on the CM tier (Finding 8) —
        // unlike supervisor/engineer/project which store a fraction — so do NOT call scalePct.
        // NOTE: DbsCmDayResponse currently does NOT carry subcontractAmount or
        // generalExpenseAmount — sections F and G are surfaced at PROJECT scope only.
        // When those fields are added to the CM DTO, include them in the sum below.
        BigDecimal expense = nz(r.materialAmount())
                .add(nz(r.manpowerAmount()))
                .add(nz(r.adminAmount()))
                .add(nz(r.machineryAmount()))
                .add(nz(r.fuelAmount()));
        BigDecimal income = nz(r.boqForTheDayAmount());
        BigDecimal contribution = income.subtract(expense);
        return String.format(Locale.ROOT,
                "DBS %s/%s %s [%s]: expense=%s income=%s contribution=%s (%s%%)"
                        + "\nSections: A.manpower=%s B.admin=%s (project-only, 0 here) C.machinery=%s D.fuel=%s E.material=%s"
                        + " — F sub-contractor & G general expense are project-only and not surfaced on the CM row",
                level, periodType, r.reportDate(), currency,
                fmtCcy(expense, currency), fmtCcy(income, currency),
                fmtCcy(contribution, currency), fmt(r.contributionPct()),
                fmtCcy(r.manpowerAmount(), currency),
                fmtCcy(r.adminAmount(), currency),
                fmtCcy(r.machineryAmount(), currency),
                fmtCcy(r.fuelAmount(), currency),
                fmtCcy(r.materialAmount(), currency));
    }

    private String summariseSupervisorDay(DbsSupervisorDayResponse r, String level, String periodType, String currency) {
        return String.format(Locale.ROOT,
                "DBS %s/%s %s [%s]: expense=%s income=%s contribution=%s (%s%%)"
                        + "\nSections: A.manpower=%s B.admin=%s (project-only, 0 here) C.machinery=%s D.fuel=%s E.material=%s"
                        + " — F sub-contractor & G general expense are project-only and always 0 at supervisor scope",
                level, periodType, r.reportDate(), currency,
                fmtCcy(r.totalExpense(), currency), fmtCcy(r.totalIncome(), currency),
                fmtCcy(r.contribution(), currency), fmt(scalePct(r.contributionPct())),
                fmtCcy(r.manpowerAmount(), currency),
                fmtCcy(r.adminAmount(), currency),
                fmtCcy(r.machineryAmount(), currency),
                fmtCcy(r.fuelAmount(), currency),
                fmtCcy(r.materialAmount(), currency));
    }

    private static String periodFooter(String periodType, LocalDate from, LocalDate to, int activeDays, int totalDays) {
        return String.format(Locale.ROOT,
                "\nPeriod: %s %s..%s, %d active days of %d"
                        + "\nDaily breakdown is in dailyRows[] — %d rows",
                periodType, from, to, activeDays, totalDays, totalDays);
    }

    private static int projectActiveDays(List<DbsProjectDayResponse> rows) {
        if (rows == null) return 0;
        int n = 0;
        for (DbsProjectDayResponse d : rows) {
            if (isNonZero(d.totalExpense()) || isNonZero(d.totalIncome())) n++;
        }
        return n;
    }

    private static int supervisorActiveDays(List<DbsSupervisorDayResponse> rows) {
        if (rows == null) return 0;
        int n = 0;
        for (DbsSupervisorDayResponse d : rows) {
            if (isNonZero(d.totalExpense()) || isNonZero(d.totalIncome())) n++;
        }
        return n;
    }

    private static int engineerActiveDays(List<DbsEngineerDayResponse> rows) {
        if (rows == null) return 0;
        int n = 0;
        for (DbsEngineerDayResponse d : rows) {
            if (isNonZero(d.totalExpense()) || isNonZero(d.totalIncome())) n++;
        }
        return n;
    }

    private static boolean isNonZero(BigDecimal v) {
        return v != null && v.signum() != 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Render an amount prefixed with the currency code (e.g. {@code "OMR 1234.50"}). */
    private static String fmtCcy(BigDecimal v, String currency) {
        return currency + " " + fmt(v);
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
