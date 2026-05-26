package com.bipros.ai.tool.formula;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Given an EVM metric (CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI) and a scope (project /
 * activity / date range), returns the formula in human-readable form, the named numeric
 * inputs that were used, the computed value, and a small source-rows envelope so callers
 * can audit the math. Anchored on the most recent {@link EvmCalculation} matching the
 * scope (activity if supplied, else project).
 *
 * <p><b>Utilization / productivity metrics are NOT handled here.</b> The previous
 * MANPOWER_UTIL_PCT / EQUIP_UTIL_PCT / PRODUCTIVITY_RATIO branches used HRS-based math
 * (Σ actual_hours ÷ Σ budget_hours) that ignored the per-DPR allocator, sub-contractor
 * netting, and SERIES/PARALLEL/SUBSTITUTE side handling. Those questions must go to
 * {@code get_capacity_utilization} (canonical per-role allocator) or
 * {@code get_subcontractor_kpis} (per-SC × work-type).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormulaValidatorTool implements Tool {

    private final EvmCalculationRepository evmRepository;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "formula_validate";
    }

    @Override
    public String description() {
        return "Given an EVM metric (CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI) and scope "
                + "(project / activity / date range), return the formula, the named numeric "
                + "inputs, the computed value, and the source rows so callers can verify the "
                + "math. Use this any time the user asks 'how did you compute CPI', 'show the "
                + "formula for SPI', 'why is variance Y', or any EVM audit question. Numbers "
                + "are returned exactly as stored — no rounding, no recomputation in prose. "
                + "EVM SCOPE — IMPORTANT: the response carries `source.scope` ∈ "
                + "{activity | project | project_fallback}. "
                + "'activity' = we found an activity-level EvmCalculation row matching activityCode. "
                + "'project' = no activityCode was asked, so the latest project-level row was used. "
                + "'project_fallback' = activityCode WAS asked but no activity-level EVM row "
                + "exists for it, so the tool returned the project-level snapshot as a fallback. "
                + "When scope='project_fallback' you MUST NOT report the numbers as activity-specific. "
                + "Either skip the EVM block in your answer, or explicitly say 'no activity-level "
                + "earned value yet for <activityCode>; the project-level CPI/SPI/CV is X'. The "
                + "`source.note` field will spell out the fallback condition — disclose it. "
                + "DO NOT USE THIS TOOL FOR: manpower utilization, equipment utilization, "
                + "capacity utilization, productivity factor, role efficiency, per-role "
                + "allocated qty, or any question about hours vs budget. Those questions go to "
                + "`get_capacity_utilization` (canonical per-role allocator with sub-contractor "
                + "netting and SERIES/PARALLEL/SUBSTITUTE handling) or `get_subcontractor_kpis` "
                + "(sub-contractor productivity / cost). This tool only validates EVM math.";
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
        props.set("activityCode", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Activity code (e.g. ACT-1.3.5). Narrows EVM lookup to one activity."));
        props.set("fromDate", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "ISO date — inclusive lower bound (reserved; EVM lookup uses latest snapshot)."));
        props.set("toDate", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "ISO date — inclusive upper bound (reserved; EVM lookup uses latest snapshot)."));

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
            String redirect = "";
            String u = metricRaw.trim().toUpperCase();
            if (u.equals("MANPOWER_UTIL_PCT") || u.equals("EQUIP_UTIL_PCT")
                    || u.equals("EQUIPMENT_UTIL_PCT")) {
                redirect = " For capacity / utilization metrics call get_capacity_utilization "
                        + "(canonical per-role allocator — applies SC netting + SERIES/PARALLEL/"
                        + "SUBSTITUTE hiding) instead.";
            } else if (u.equals("PRODUCTIVITY_RATIO")) {
                redirect = " For productivity / role-level efficiency call "
                        + "get_capacity_utilization. For sub-contractor productivity factor "
                        + "call get_subcontractor_kpis.";
            }
            return ToolResult.error("Unknown metric: " + metricRaw
                    + ". This tool only validates EVM math. Allowed metrics: "
                    + "CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI." + redirect);
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

        String activityCode = orNull(input.path("activityCode").asText(null));
        LocalDate fromDate = parseDate(input.path("fromDate").asText(null));
        LocalDate toDate = parseDate(input.path("toDate").asText(null));
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            LocalDate t = fromDate;
            fromDate = toDate;
            toDate = t;
        }

        return evaluateEvm(metric, projectId, activityCode, fromDate, toDate);
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
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private ObjectNode renderRange(LocalDate from, LocalDate to) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("from", from == null ? null : from.toString());
        n.put("to", to == null ? null : to.toString());
        return n;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
        CPI, SPI, CV, SV, EAC, ETC, VAC, TCPI
    }
}
