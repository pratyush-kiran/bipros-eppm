package com.bipros.ai.tool.formula;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Given a metric (CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI, MANPOWER_UTIL_PCT, EQUIP_UTIL_PCT,
 * PRODUCTIVITY_RATIO) and a scope (project / supervisor / activity / date range), returns
 * the formula in human-readable form, the named numeric inputs that were used, the computed
 * value, and a small source-rows envelope so callers can audit the math.
 *
 * <p>EVM metrics are anchored on the most recent {@link EvmCalculation} matching the scope
 * (activity if supplied, else project). Utilization metrics aggregate DPR child rows
 * (manpower / equipment) for the scope; budget hours are derived from {@code nos × 11h × workingDays}
 * when no explicit budget exists — that fallback is reported verbatim in {@code inputs.notes}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormulaValidatorTool implements Tool {

    /** Hours per shift assumed by SC-180 site operations when no budget hours exist. */
    private static final BigDecimal SHIFT_HOURS = new BigDecimal("11");

    private final EvmCalculationRepository evmRepository;
    private final DailyProgressReportRepository dprRepository;
    private final DprManpowerRepository manpowerRepository;
    private final DprEquipmentRepository equipmentRepository;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "formula_validate";
    }

    @Override
    public String description() {
        return "Given a metric (CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI, MANPOWER_UTIL_PCT, "
                + "EQUIP_UTIL_PCT, PRODUCTIVITY_RATIO) and scope (project / supervisor / activity / "
                + "date range), return the formula, the named numeric inputs, the computed value, "
                + "and the source rows so callers can verify the math. Use this any time the user "
                + "asks 'how did you compute X', 'show the formula for CPI', 'why is variance Y', "
                + "or 'what's the manpower utilization on activity Z'. Numbers are returned exactly "
                + "as stored — no rounding, no recomputation in prose. "
                + "EVM SCOPE — IMPORTANT: for EVM metrics (CPI/SPI/CV/SV/EAC/ETC/VAC/TCPI), the "
                + "response carries `source.scope` ∈ {activity | project | project_fallback}. "
                + "'activity' = we found an activity-level EvmCalculation row matching activityCode. "
                + "'project' = no activityCode was asked, so the latest project-level row was used. "
                + "'project_fallback' = activityCode WAS asked but no activity-level EVM row "
                + "exists for it, so the tool returned the project-level snapshot as a fallback. "
                + "When scope='project_fallback' you MUST NOT report the numbers as activity-specific. "
                + "Either skip the EVM block in your answer, or explicitly say 'no activity-level "
                + "earned value yet for <activityCode>; the project-level CPI/SPI/CV is X'. The "
                + "`source.note` field will spell out the fallback condition — disclose it.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        ArrayNode metricEnum = objectMapper.createArrayNode();
        for (Metric m : Metric.values()) metricEnum.add(m.name());
        ObjectNode metricNode = objectMapper.createObjectNode();
        metricNode.put("type", "string");
        metricNode.set("enum", metricEnum);
        metricNode.put("description", "Metric to validate. Required.");
        props.set("metric", metricNode);

        props.set("projectId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Falls back to the current project in scope."));
        props.set("supervisorUserId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Optional supervisor (user) filter for utilization metrics."));
        props.set("activityCode", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Activity code (e.g. ACT-1.3.5). Narrows EVM lookup to one activity."));
        props.set("fromDate", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "ISO date — inclusive lower bound for DPR-based metrics."));
        props.set("toDate", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "ISO date — inclusive upper bound for DPR-based metrics."));

        schema.set("properties", props);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("metric");
        schema.set("required", required);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String metricRaw = input.path("metric").asText(null);
        if (metricRaw == null || metricRaw.isBlank()) {
            return ToolResult.error("metric is required.");
        }
        Metric metric;
        try {
            metric = Metric.valueOf(metricRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Unknown metric: " + metricRaw
                    + ". Allowed: CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI, "
                    + "MANPOWER_UTIL_PCT, EQUIP_UTIL_PCT, PRODUCTIVITY_RATIO.");
        }

        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("formula_validate needs a projectId (or a project in scope).");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        UUID supervisorUserId = parseUuid(input.path("supervisorUserId").asText(null));
        String activityCode = orNull(input.path("activityCode").asText(null));
        LocalDate fromDate = parseDate(input.path("fromDate").asText(null));
        LocalDate toDate = parseDate(input.path("toDate").asText(null));
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            LocalDate t = fromDate;
            fromDate = toDate;
            toDate = t;
        }

        return switch (metric) {
            case CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI ->
                    evaluateEvm(metric, projectId, activityCode, fromDate, toDate);
            case MANPOWER_UTIL_PCT ->
                    evaluateManpowerUtil(projectId, supervisorUserId, activityCode, fromDate, toDate);
            case EQUIP_UTIL_PCT ->
                    evaluateEquipmentUtil(projectId, activityCode, fromDate, toDate);
            case PRODUCTIVITY_RATIO ->
                    evaluateProductivityRatio(projectId, supervisorUserId, activityCode, fromDate, toDate);
        };
    }

    // ──────────────────────────────────────────────────────────────────────
    // EVM metrics
    // ──────────────────────────────────────────────────────────────────────

    private ToolResult evaluateEvm(Metric metric, UUID projectId, String activityCode,
                                   LocalDate fromDate, LocalDate toDate) {
        EvmCalculation row = null;
        UUID activityId = null;
        boolean activityRequested = activityCode != null;
        boolean activityResolved = false;
        boolean activityEvmMatched = false;
        if (activityRequested) {
            Optional<Activity> a = activityRepository.findByProjectIdAndCode(projectId, activityCode);
            if (a.isPresent()) {
                activityId = a.get().getId();
                activityResolved = true;
                row = evmRepository
                        .findTopByProjectIdAndActivityIdOrderByDataDateDesc(projectId, activityId)
                        .orElse(null);
                if (row != null) activityEvmMatched = true;
            }
        }
        if (row == null) {
            row = evmRepository.findTopByProjectIdOrderByDataDateDesc(projectId).orElse(null);
        }
        if (row == null) {
            return ToolResult.error("No EvmCalculation rows for this scope. Run the EVM job first.");
        }
        // If the user asked about an activity but we only have project-level EVM,
        // record that fact explicitly so the caller (AI) doesn't quote the project
        // numbers as if they were activity-specific.
        String evmScope = activityEvmMatched ? "activity"
                : (activityRequested ? "project_fallback" : "project");
        String fallbackNote = null;
        if ("project_fallback".equals(evmScope)) {
            fallbackNote = activityResolved
                    ? "No activity-level EvmCalculation row exists for " + activityCode
                            + ". The numbers below are the latest PROJECT-level EVM snapshot — "
                            + "they are NOT specific to " + activityCode + "."
                    : "Activity code '" + activityCode + "' could not be resolved on this project. "
                            + "Returning the latest project-level EVM snapshot.";
        }

        BigDecimal bac = nz(row.getBudgetAtCompletion());
        BigDecimal pv = nz(row.getPlannedValue());
        BigDecimal ev = nz(row.getEarnedValue());
        BigDecimal ac = nz(row.getActualCost());

        ObjectNode out = objectMapper.createObjectNode();
        out.put("metric", metric.name());

        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.put("BAC", bac.toPlainString());
        inputs.put("PV", pv.toPlainString());
        inputs.put("EV", ev.toPlainString());
        inputs.put("AC", ac.toPlainString());
        inputs.put("currency", "OMR");
        inputs.put("data_date", row.getDataDate() == null ? null : row.getDataDate().toString());

        String formula;
        String computed;
        String interpretation;
        switch (metric) {
            case CPI -> {
                formula = "CPI = EV / AC";
                if (ac.signum() == 0) {
                    computed = "n/a (AC = 0)";
                    interpretation = "Actual cost is zero — CPI is undefined.";
                } else {
                    BigDecimal cpi = ev.divide(ac, 4, RoundingMode.HALF_UP);
                    computed = cpi.toPlainString();
                    interpretation = cpi.compareTo(BigDecimal.ONE) >= 0
                            ? "At or above 1.0 → on / under budget."
                            : "Below 1.0 → over budget; for every 1 OMR spent we earned " + computed + " OMR of work.";
                }
            }
            case SPI -> {
                formula = "SPI = EV / PV";
                if (pv.signum() == 0) {
                    computed = "n/a (PV = 0)";
                    interpretation = "Planned value is zero — SPI is undefined.";
                } else {
                    BigDecimal spi = ev.divide(pv, 4, RoundingMode.HALF_UP);
                    computed = spi.toPlainString();
                    interpretation = spi.compareTo(BigDecimal.ONE) >= 0
                            ? "At or above 1.0 → on / ahead of schedule."
                            : "Below 1.0 → behind schedule.";
                }
            }
            case CV -> {
                formula = "CV = EV − AC";
                BigDecimal cv = ev.subtract(ac);
                computed = cv.toPlainString();
                interpretation = cv.signum() >= 0
                        ? "Positive → under budget by " + cv.abs().toPlainString() + " OMR."
                        : "Negative → over budget by " + cv.abs().toPlainString() + " OMR.";
            }
            case SV -> {
                formula = "SV = EV − PV";
                BigDecimal sv = ev.subtract(pv);
                computed = sv.toPlainString();
                interpretation = sv.signum() >= 0
                        ? "Positive → ahead of schedule by " + sv.abs().toPlainString() + " OMR of work."
                        : "Negative → behind schedule by " + sv.abs().toPlainString() + " OMR of work.";
            }
            case EAC -> {
                formula = "EAC = BAC / CPI";
                if (ac.signum() == 0 || ev.signum() == 0) {
                    computed = "n/a (AC or EV = 0)";
                    interpretation = "CPI undefined → EAC cannot be derived from CPI.";
                } else {
                    BigDecimal cpi = ev.divide(ac, 6, RoundingMode.HALF_UP);
                    BigDecimal eac = bac.divide(cpi, 2, RoundingMode.HALF_UP);
                    inputs.put("CPI", cpi.setScale(4, RoundingMode.HALF_UP).toPlainString());
                    computed = eac.toPlainString();
                    interpretation = "Forecast cost at completion assuming the same cost performance continues.";
                }
            }
            case ETC -> {
                formula = "ETC = EAC − AC";
                if (ac.signum() == 0 || ev.signum() == 0) {
                    computed = "n/a (AC or EV = 0)";
                    interpretation = "CPI undefined → ETC cannot be derived.";
                } else {
                    BigDecimal cpi = ev.divide(ac, 6, RoundingMode.HALF_UP);
                    BigDecimal eac = bac.divide(cpi, 2, RoundingMode.HALF_UP);
                    BigDecimal etc = eac.subtract(ac);
                    inputs.put("CPI", cpi.setScale(4, RoundingMode.HALF_UP).toPlainString());
                    inputs.put("EAC", eac.toPlainString());
                    computed = etc.toPlainString();
                    interpretation = "Estimated remaining cost to finish the work.";
                }
            }
            case VAC -> {
                formula = "VAC = BAC − EAC";
                if (ac.signum() == 0 || ev.signum() == 0) {
                    computed = "n/a (AC or EV = 0)";
                    interpretation = "CPI undefined → VAC cannot be derived.";
                } else {
                    BigDecimal cpi = ev.divide(ac, 6, RoundingMode.HALF_UP);
                    BigDecimal eac = bac.divide(cpi, 2, RoundingMode.HALF_UP);
                    BigDecimal vac = bac.subtract(eac);
                    inputs.put("CPI", cpi.setScale(4, RoundingMode.HALF_UP).toPlainString());
                    inputs.put("EAC", eac.toPlainString());
                    computed = vac.toPlainString();
                    interpretation = vac.signum() >= 0
                            ? "Positive → expected to finish under budget."
                            : "Negative → expected to overrun by " + vac.abs().toPlainString() + " OMR.";
                }
            }
            case TCPI -> {
                formula = "TCPI = (BAC − EV) / (BAC − AC)";
                BigDecimal denom = bac.subtract(ac);
                if (denom.signum() == 0) {
                    computed = "n/a (BAC − AC = 0)";
                    interpretation = "Budget already exhausted — TCPI undefined.";
                } else {
                    BigDecimal tcpi = bac.subtract(ev).divide(denom, 4, RoundingMode.HALF_UP);
                    computed = tcpi.toPlainString();
                    interpretation = tcpi.compareTo(BigDecimal.ONE) <= 0
                            ? "≤ 1.0 → achievable cost efficiency to finish on budget."
                            : "> 1.0 → cost efficiency must improve to finish on budget.";
                }
            }
            default -> {
                formula = metric.name();
                computed = "n/a";
                interpretation = "Unsupported.";
            }
        }

        out.put("formula", formula);
        out.set("inputs", inputs);
        out.put("computed", computed);
        out.put("interpretation", interpretation);

        ObjectNode source = objectMapper.createObjectNode();
        source.put("entity", "EvmCalculation");
        source.put("evmRowId", row.getId() == null ? null : row.getId().toString());
        // Scope tells the caller whether the EVM row matches the requested activity
        // (`activity`), was project-wide because no activity was asked (`project`),
        // or was a project-level fallback because no activity-level EVM exists for
        // the requested activity (`project_fallback`). The AI MUST treat the
        // `project_fallback` numbers as project-level, not activity-level.
        source.put("scope", evmScope);
        if (activityRequested) source.put("requestedActivityCode", activityCode);
        if (activityId != null) source.put("resolvedActivityId", activityId.toString());
        source.put("evmRowProjectId", row.getProjectId() == null ? null : row.getProjectId().toString());
        source.put("evmRowActivityId",
                row.getActivityId() == null ? null : row.getActivityId().toString());
        source.put("dataDate", row.getDataDate() == null ? null : row.getDataDate().toString());
        if (fallbackNote != null) source.put("note", fallbackNote);
        out.set("source", source);

        ObjectNode dateRange = objectMapper.createObjectNode();
        dateRange.put("from", fromDate == null ? null : fromDate.toString());
        dateRange.put("to", toDate == null ? null : toDate.toString());
        out.set("dateRange", dateRange);

        String summary = metric.name() + " = " + computed
                + " (" + formula + ") on data_date " + (row.getDataDate() == null ? "?" : row.getDataDate())
                + " · scope=" + evmScope
                + (fallbackNote != null ? " — NOTE: " + fallbackNote : "");
        return ToolResult.ok(summary, out);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utilization / productivity metrics
    // ──────────────────────────────────────────────────────────────────────

    private ToolResult evaluateManpowerUtil(UUID projectId, UUID supervisorUserId, String activityCode,
                                            LocalDate fromDate, LocalDate toDate) {
        List<DailyProgressReport> dprs = loadDprs(projectId, supervisorUserId, activityCode, fromDate, toDate);
        if (dprs.isEmpty()) {
            return emptyResult("MANPOWER_UTIL_PCT",
                    "MANPOWER_UTIL_PCT = Σ actual_hours / Σ budget_hours × 100",
                    fromDate, toDate);
        }
        List<UUID> dprIds = dprs.stream().map(DailyProgressReport::getId).toList();
        List<DprManpower> rows = batchManpower(dprIds);
        Map<LocalDate, Integer> dprsPerDate = new HashMap<>();
        for (DailyProgressReport d : dprs) {
            dprsPerDate.merge(d.getReportDate(), 1, Integer::sum);
        }
        int workingDays = dprsPerDate.size();

        BigDecimal actualHours = BigDecimal.ZERO;
        BigDecimal headcount = BigDecimal.ZERO;
        for (DprManpower r : rows) {
            actualHours = actualHours
                    .add(nz(r.getWorkingHours()).multiply(nz(toBd(r.getNos()))))
                    .add(nz(r.getOtHours()).multiply(nz(toBd(r.getNos()))));
            headcount = headcount.add(nz(toBd(r.getNos())));
        }
        BigDecimal budgetHours = headcount.multiply(SHIFT_HOURS); // headcount already a sum over (row × day)

        ObjectNode out = objectMapper.createObjectNode();
        out.put("metric", "MANPOWER_UTIL_PCT");
        out.put("formula", "MANPOWER_UTIL_PCT = Σ actual_hours / Σ budget_hours × 100");

        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.put("actual_hours", actualHours.setScale(2, RoundingMode.HALF_UP).toPlainString());
        inputs.put("budget_hours", budgetHours.setScale(2, RoundingMode.HALF_UP).toPlainString());
        inputs.put("budget_basis", "Σ nos × 11h (no explicit budget rows available — fallback)");
        inputs.put("working_days", workingDays);
        inputs.put("manpower_rows", rows.size());
        out.set("inputs", inputs);

        String computed;
        String interpretation;
        if (budgetHours.signum() == 0) {
            computed = "n/a (no manpower nos)";
            interpretation = "No manpower deployed in scope.";
        } else {
            BigDecimal util = actualHours.divide(budgetHours, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            computed = util.toPlainString();
            interpretation = util.compareTo(new BigDecimal("80")) >= 0
                    ? "Healthy utilization — crews are productive on shift."
                    : "Under-utilized — crews are idle for a meaningful share of the shift.";
        }
        out.put("computed", computed);
        out.put("interpretation", interpretation);

        ObjectNode source = objectMapper.createObjectNode();
        source.put("entity", "DprManpower");
        source.put("dprRowsUsed", dprs.size());
        source.put("manpowerRowsAggregated", rows.size());
        out.set("source", source);
        out.set("dateRange", renderRange(fromDate, toDate));
        return ToolResult.ok("MANPOWER_UTIL_PCT = " + computed + "% over "
                + dprs.size() + " DPR rows", out);
    }

    private ToolResult evaluateEquipmentUtil(UUID projectId, String activityCode,
                                             LocalDate fromDate, LocalDate toDate) {
        List<DailyProgressReport> dprs = loadDprs(projectId, null, activityCode, fromDate, toDate);
        if (dprs.isEmpty()) {
            return emptyResult("EQUIP_UTIL_PCT",
                    "EQUIP_UTIL_PCT = Σ working_hours / Σ (working + idle + breakdown) × 100",
                    fromDate, toDate);
        }
        List<UUID> dprIds = dprs.stream().map(DailyProgressReport::getId).toList();
        List<DprEquipment> rows = batchEquipment(dprIds);

        BigDecimal working = BigDecimal.ZERO;
        BigDecimal idle = BigDecimal.ZERO;
        BigDecimal breakdown = BigDecimal.ZERO;
        BigDecimal nosTotal = BigDecimal.ZERO;
        for (DprEquipment r : rows) {
            BigDecimal nos = nz(toBd(r.getNos()));
            working = working.add(nz(r.getWorkingHours()).multiply(nos));
            idle = idle.add(nz(r.getIdleHours()).multiply(nos));
            breakdown = breakdown.add(nz(r.getBreakdownHours()).multiply(nos));
            nosTotal = nosTotal.add(nos);
        }
        BigDecimal totalAvailable = working.add(idle).add(breakdown);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("metric", "EQUIP_UTIL_PCT");
        out.put("formula", "EQUIP_UTIL_PCT = Σ working_hours / Σ (working + idle + breakdown) × 100");

        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.put("working_hours", working.setScale(2, RoundingMode.HALF_UP).toPlainString());
        inputs.put("idle_hours", idle.setScale(2, RoundingMode.HALF_UP).toPlainString());
        inputs.put("breakdown_hours", breakdown.setScale(2, RoundingMode.HALF_UP).toPlainString());
        inputs.put("total_available_hours", totalAvailable.setScale(2, RoundingMode.HALF_UP).toPlainString());
        inputs.put("equipment_unit_days", nosTotal.toPlainString());
        inputs.put("equipment_rows", rows.size());
        out.set("inputs", inputs);

        String computed;
        String interpretation;
        if (totalAvailable.signum() == 0) {
            computed = "n/a";
            interpretation = "No equipment hours logged in scope.";
        } else {
            BigDecimal util = working.divide(totalAvailable, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            computed = util.toPlainString();
            interpretation = util.compareTo(new BigDecimal("70")) >= 0
                    ? "Healthy equipment utilization."
                    : "Under-utilized — significant idle or breakdown share.";
        }
        out.put("computed", computed);
        out.put("interpretation", interpretation);

        ObjectNode source = objectMapper.createObjectNode();
        source.put("entity", "DprEquipment");
        source.put("dprRowsUsed", dprs.size());
        source.put("equipmentRowsAggregated", rows.size());
        out.set("source", source);
        out.set("dateRange", renderRange(fromDate, toDate));
        return ToolResult.ok("EQUIP_UTIL_PCT = " + computed + "% over "
                + dprs.size() + " DPR rows", out);
    }

    private ToolResult evaluateProductivityRatio(UUID projectId, UUID supervisorUserId,
                                                 String activityCode, LocalDate fromDate, LocalDate toDate) {
        List<DailyProgressReport> dprs = loadDprs(projectId, supervisorUserId, activityCode, fromDate, toDate);
        if (dprs.isEmpty()) {
            return emptyResult("PRODUCTIVITY_RATIO",
                    "PRODUCTIVITY_RATIO = Σ qty_executed / Σ manpower_hours",
                    fromDate, toDate);
        }
        BigDecimal qty = BigDecimal.ZERO;
        for (DailyProgressReport d : dprs) qty = qty.add(nz(d.getQtyExecuted()));

        List<UUID> dprIds = dprs.stream().map(DailyProgressReport::getId).toList();
        List<DprManpower> rows = batchManpower(dprIds);
        BigDecimal manhours = BigDecimal.ZERO;
        for (DprManpower r : rows) {
            BigDecimal nos = nz(toBd(r.getNos()));
            manhours = manhours
                    .add(nz(r.getWorkingHours()).multiply(nos))
                    .add(nz(r.getOtHours()).multiply(nos));
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("metric", "PRODUCTIVITY_RATIO");
        out.put("formula", "PRODUCTIVITY_RATIO = Σ qty_executed / Σ manpower_hours (units per man-hour)");

        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.put("qty_executed", qty.setScale(3, RoundingMode.HALF_UP).toPlainString());
        inputs.put("manpower_hours", manhours.setScale(2, RoundingMode.HALF_UP).toPlainString());
        inputs.put("unit_note", "qty unit comes from DPR.unit (varies per activity — Cum / MT / Rm / Each).");
        out.set("inputs", inputs);

        String computed;
        String interpretation;
        if (manhours.signum() == 0) {
            computed = "n/a (no manpower hours)";
            interpretation = "Cannot compute productivity — no manpower hours in scope.";
        } else {
            BigDecimal ratio = qty.divide(manhours, 4, RoundingMode.HALF_UP);
            computed = ratio.toPlainString();
            interpretation = "Units of work delivered per man-hour. Compare against the ProductivityNorm for the activity to judge.";
        }
        out.put("computed", computed);
        out.put("interpretation", interpretation);

        ObjectNode source = objectMapper.createObjectNode();
        source.put("entity", "DailyProgressReport + DprManpower");
        source.put("dprRowsUsed", dprs.size());
        source.put("manpowerRowsAggregated", rows.size());
        out.set("source", source);
        out.set("dateRange", renderRange(fromDate, toDate));
        return ToolResult.ok("PRODUCTIVITY_RATIO = " + computed
                + " (qty/man-hour) over " + dprs.size() + " DPR rows", out);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private List<DailyProgressReport> loadDprs(UUID projectId, UUID supervisorUserId, String activityCode,
                                               LocalDate fromDate, LocalDate toDate) {
        List<DailyProgressReport> base;
        if (fromDate != null && toDate != null) {
            base = dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(
                    projectId, fromDate, toDate);
        } else {
            base = dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId);
        }
        List<DailyProgressReport> out = new ArrayList<>(base.size());
        for (DailyProgressReport d : base) {
            if (supervisorUserId != null
                    && (d.getSupervisorUserId() == null || !d.getSupervisorUserId().equals(supervisorUserId))) {
                continue;
            }
            if (activityCode != null) {
                String name = d.getActivityName();
                if (name == null || !name.equalsIgnoreCase(activityCode)) {
                    // DPR captures activity_name as the user-facing label, which usually equals code on
                    // SC-180-style projects. Also accept exact code match through activity_id below.
                    if (d.getActivityId() == null) continue;
                    Optional<Activity> a = activityRepository.findById(d.getActivityId());
                    if (a.isEmpty() || !activityCode.equalsIgnoreCase(a.get().getCode())) continue;
                }
            }
            out.add(d);
        }
        return out;
    }

    private List<DprManpower> batchManpower(Collection<UUID> dprIds) {
        if (dprIds.isEmpty()) return List.of();
        return manpowerRepository.findByDprIdIn(dprIds);
    }

    private List<DprEquipment> batchEquipment(Collection<UUID> dprIds) {
        if (dprIds.isEmpty()) return List.of();
        return equipmentRepository.findByDprIdIn(dprIds);
    }

    private ToolResult emptyResult(String metric, String formula, LocalDate fromDate, LocalDate toDate) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("metric", metric);
        out.put("formula", formula);
        out.set("inputs", objectMapper.createObjectNode());
        out.put("computed", "n/a");
        out.put("interpretation", "No DPR rows matched the supplied scope.");
        ObjectNode source = objectMapper.createObjectNode();
        source.put("dprRowsUsed", 0);
        out.set("source", source);
        out.set("dateRange", renderRange(fromDate, toDate));
        return ToolResult.ok(metric + " = n/a (no source rows)", out);
    }

    private ObjectNode renderRange(LocalDate from, LocalDate to) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("from", from == null ? null : from.toString());
        n.put("to", to == null ? null : to.toString());
        return n;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal toBd(Integer i) {
        return i == null ? BigDecimal.ZERO : BigDecimal.valueOf(i);
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

    private static String orNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private enum Metric {
        CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI,
        MANPOWER_UTIL_PCT, EQUIP_UTIL_PCT, PRODUCTIVITY_RATIO
    }
}
