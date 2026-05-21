package com.bipros.ai.tool.cost;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.cost.domain.entity.CashFlowForecast;
import com.bipros.cost.domain.entity.CostAccount;
import com.bipros.cost.domain.entity.FinancialPeriod;
import com.bipros.cost.domain.entity.ProjectFunding;
import com.bipros.cost.domain.entity.RaBill;
import com.bipros.cost.domain.entity.StorePeriodPerformance;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.cost.domain.repository.CashFlowForecastRepository;
import com.bipros.cost.domain.repository.CostAccountRepository;
import com.bipros.cost.domain.repository.FinancialPeriodRepository;
import com.bipros.cost.domain.repository.ProjectFundingRepository;
import com.bipros.cost.domain.repository.RaBillRepository;
import com.bipros.cost.domain.repository.StorePeriodPerformanceRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cost breakdown queries. Action-typed via {@code op}:
 * by_account / cash_flow / period_performance / ra_bills_summary / funding.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostBreakdownTool implements Tool {

    private final ActivityExpenseRepository expenseRepository;
    private final CostAccountRepository costAccountRepository;
    private final CashFlowForecastRepository cashFlowRepository;
    private final StorePeriodPerformanceRepository periodPerformanceRepository;
    private final FinancialPeriodRepository financialPeriodRepository;
    private final RaBillRepository raBillRepository;
    private final ProjectFundingRepository fundingRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "cost_breakdown";
    }

    @Override
    public String description() {
        return "Break project cost down by account, period, RA-bill status, funding source, or "
                + "cash flow profile. For the project's OVERALL totals (total budget, total actual, "
                + "CPI, BAC, variance) use `project_cost_summary` instead — this tool's "
                + "`by_account` rollup only covers ActivityExpense rows linked to a CostAccount, "
                + "so its totals will be 0 on projects that book cost purely via resource "
                + "assignments or DPRs. Operations via `op`: `by_account` (sum "
                + "budgeted/actual/remaining/at_completion of CostAccount-linked activity expenses, "
                + "joined with CostAccount code/name), `cash_flow` (CashFlowForecast rows by period "
                + "with cumulatives), `period_performance` (StorePeriodPerformance per financial "
                + "period — labour/material/expense actuals), `ra_bills_summary` (count + gross + "
                + "net amounts grouped by RaBillStatus), `funding` (ProjectFunding allocations and "
                + "totals). Examples: \"cost by account head\", \"cash-flow forecast curve\", "
                + "\"period actuals last quarter\", \"how many RA bills are pending payment\", "
                + "\"funding allocation\". Project-scoped.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        ArrayNode opEnum = objectMapper.createArrayNode();
        opEnum.add("by_account");
        opEnum.add("cash_flow");
        opEnum.add("period_performance");
        opEnum.add("ra_bills_summary");
        opEnum.add("funding");
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "string");
        op.set("enum", opEnum);
        op.put("default", "by_account");
        op.put("description", "Operation to run.");
        props.set("op", op);
        props.set("financial_period_id", objectMapper.createObjectNode().put("type", "string").put("format", "uuid")
                .put("description", "Optional filter for `period_performance`. If omitted, returns all periods."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("cost_breakdown needs a project in scope.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }
        String op = orDefault(input.path("op").asText(null), "by_account");
        return switch (op) {
            case "cash_flow" -> opCashFlow(projectId);
            case "period_performance" -> opPeriodPerformance(projectId, input);
            case "ra_bills_summary" -> opRaBillsSummary(projectId);
            case "funding" -> opFunding(projectId);
            default -> opByAccount(projectId);
        };
    }

    private ToolResult opByAccount(UUID projectId) {
        List<ActivityExpense> exp = expenseRepository.findByProjectId(projectId);
        Map<UUID, AccountRollup> byAcct = new LinkedHashMap<>();
        AccountRollup unassigned = new AccountRollup();
        for (ActivityExpense e : exp) {
            AccountRollup target = e.getCostAccountId() == null
                    ? unassigned
                    : byAcct.computeIfAbsent(e.getCostAccountId(), k -> new AccountRollup());
            target.budgeted = target.budgeted.add(nz(e.getBudgetedCost()));
            target.actual = target.actual.add(nz(e.getActualCost()));
            target.remaining = target.remaining.add(nz(e.getRemainingCost()));
            target.atCompletion = target.atCompletion.add(nz(e.getAtCompletionCost()));
            target.count++;
        }
        Set<UUID> ids = new HashSet<>(byAcct.keySet());
        Map<UUID, CostAccount> caById = new HashMap<>();
        if (!ids.isEmpty()) {
            costAccountRepository.findAllById(ids).forEach(c -> caById.put(c.getId(), c));
        }
        List<Map.Entry<UUID, AccountRollup>> ordered = new ArrayList<>(byAcct.entrySet());
        ordered.sort(Comparator.comparing((Map.Entry<UUID, AccountRollup> e) -> e.getValue().budgeted).reversed());

        ArrayNode rows = objectMapper.createArrayNode();
        BigDecimal totalBud = BigDecimal.ZERO, totalAct = BigDecimal.ZERO,
                totalRem = BigDecimal.ZERO, totalAtc = BigDecimal.ZERO;
        for (Map.Entry<UUID, AccountRollup> e : ordered) {
            CostAccount ca = caById.get(e.getKey());
            AccountRollup r = e.getValue();
            ObjectNode n = objectMapper.createObjectNode();
            n.put("cost_account_id", e.getKey().toString());
            n.put("account_code", ca == null ? null : ca.getCode());
            n.put("account_name", ca == null ? null : ca.getName());
            n.put("expense_count", r.count);
            n.put("budgeted", r.budgeted.doubleValue());
            n.put("actual", r.actual.doubleValue());
            n.put("remaining", r.remaining.doubleValue());
            n.put("at_completion", r.atCompletion.doubleValue());
            n.put("variance", r.actual.subtract(r.budgeted).doubleValue());
            rows.add(n);
            totalBud = totalBud.add(r.budgeted);
            totalAct = totalAct.add(r.actual);
            totalRem = totalRem.add(r.remaining);
            totalAtc = totalAtc.add(r.atCompletion);
        }
        if (unassigned.count > 0) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("cost_account_id", (String) null);
            n.put("account_code", "(unassigned)");
            n.put("account_name", "(no cost account)");
            n.put("expense_count", unassigned.count);
            n.put("budgeted", unassigned.budgeted.doubleValue());
            n.put("actual", unassigned.actual.doubleValue());
            n.put("remaining", unassigned.remaining.doubleValue());
            n.put("at_completion", unassigned.atCompletion.doubleValue());
            n.put("variance", unassigned.actual.subtract(unassigned.budgeted).doubleValue());
            rows.add(n);
            totalBud = totalBud.add(unassigned.budgeted);
            totalAct = totalAct.add(unassigned.actual);
            totalRem = totalRem.add(unassigned.remaining);
            totalAtc = totalAtc.add(unassigned.atCompletion);
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", rows);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("budgeted_total", totalBud.doubleValue());
        summary.put("actual_total", totalAct.doubleValue());
        summary.put("remaining_total", totalRem.doubleValue());
        summary.put("at_completion_total", totalAtc.doubleValue());
        summary.put("variance_total", totalAct.subtract(totalBud).doubleValue());
        summary.put("expense_count", exp.size());
        summary.put("account_count", byAcct.size());
        wrapper.set("summary", summary);

        // Data provenance: ActivityExpense.actualCost is reconciled from
        // ResourceAssignment.actualCost (which is override-aware), so these totals
        // already factor any ProjectResource.rateOverride values set on the project.
        ArrayNode provenance = objectMapper.createArrayNode();
        provenance.add("ResourceAssignment.actualCost");
        provenance.add("ActivityExpense.actualCost");
        wrapper.set("data_provenance", provenance);
        ArrayNode notes = objectMapper.createArrayNode();
        notes.add("totals_include_project_pool_overrides");
        wrapper.set("formula_overrides", notes);

        ToolResult.attachLinks(wrapper, Map.of("cost_account", new ArrayList<>(ids)));
        return ToolResult.ok(String.format("%d cost account%s; budgeted %.0f, actual %.0f.",
                byAcct.size(), byAcct.size() == 1 ? "" : "s",
                totalBud.doubleValue(), totalAct.doubleValue()), wrapper);
    }

    private ToolResult opCashFlow(UUID projectId) {
        List<CashFlowForecast> rows = cashFlowRepository.findByProjectIdOrderByPeriodAsc(projectId);
        ArrayNode arr = objectMapper.createArrayNode();
        BigDecimal pPlanned = BigDecimal.ZERO, pActual = BigDecimal.ZERO, pForecast = BigDecimal.ZERO;
        for (CashFlowForecast c : rows) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("cash_flow_id", c.getId() == null ? null : c.getId().toString());
            n.put("period", c.getPeriod());
            n.put("planned_amount", nz(c.getPlannedAmount()).doubleValue());
            n.put("actual_amount", nz(c.getActualAmount()).doubleValue());
            n.put("forecast_amount", nz(c.getForecastAmount()).doubleValue());
            n.put("cumulative_planned", c.getCumulativePlanned() == null ? null : c.getCumulativePlanned().doubleValue());
            n.put("cumulative_actual", c.getCumulativeActual() == null ? null : c.getCumulativeActual().doubleValue());
            n.put("cumulative_forecast", c.getCumulativeForecast() == null ? null : c.getCumulativeForecast().doubleValue());
            arr.add(n);
            pPlanned = pPlanned.add(nz(c.getPlannedAmount()));
            pActual = pActual.add(nz(c.getActualAmount()));
            pForecast = pForecast.add(nz(c.getForecastAmount()));
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", arr);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("period_count", rows.size());
        summary.put("planned_total", pPlanned.doubleValue());
        summary.put("actual_total", pActual.doubleValue());
        summary.put("forecast_total", pForecast.doubleValue());
        wrapper.set("summary", summary);
        return ToolResult.ok(String.format("%d cash-flow period%s (planned %.0f, actual %.0f).",
                rows.size(), rows.size() == 1 ? "" : "s",
                pPlanned.doubleValue(), pActual.doubleValue()), wrapper);
    }

    private ToolResult opPeriodPerformance(UUID projectId, JsonNode input) {
        String pidStr = orNull(input.path("financial_period_id").asText(null));
        UUID periodId = null;
        if (pidStr != null) {
            try { periodId = UUID.fromString(pidStr); } catch (IllegalArgumentException ignored) {}
        }
        List<StorePeriodPerformance> rows = periodId != null
                ? periodPerformanceRepository.findByProjectIdAndFinancialPeriodId(projectId, periodId)
                : periodPerformanceRepository.findByProjectId(projectId);
        // Resolve period names
        Map<UUID, FinancialPeriod> periods = new HashMap<>();
        for (FinancialPeriod fp : financialPeriodRepository.findAll()) periods.put(fp.getId(), fp);

        ArrayNode arr = objectMapper.createArrayNode();
        BigDecimal totLab = BigDecimal.ZERO, totMat = BigDecimal.ZERO,
                totNonLab = BigDecimal.ZERO, totExp = BigDecimal.ZERO,
                totEv = BigDecimal.ZERO, totPv = BigDecimal.ZERO;
        for (StorePeriodPerformance s : rows) {
            FinancialPeriod fp = periods.get(s.getFinancialPeriodId());
            ObjectNode n = objectMapper.createObjectNode();
            n.put("performance_id", s.getId() == null ? null : s.getId().toString());
            n.put("financial_period_id", s.getFinancialPeriodId() == null ? null : s.getFinancialPeriodId().toString());
            n.put("period_name", fp == null ? null : fp.getName());
            n.put("period_start", fp == null || fp.getStartDate() == null ? null : fp.getStartDate().toString());
            n.put("period_end", fp == null || fp.getEndDate() == null ? null : fp.getEndDate().toString());
            n.put("activity_id", s.getActivityId() == null ? null : s.getActivityId().toString());
            n.put("actual_labor_cost", nz(s.getActualLaborCost()).doubleValue());
            n.put("actual_nonlabor_cost", nz(s.getActualNonlaborCost()).doubleValue());
            n.put("actual_material_cost", nz(s.getActualMaterialCost()).doubleValue());
            n.put("actual_expense_cost", nz(s.getActualExpenseCost()).doubleValue());
            n.put("earned_value_cost", nz(s.getEarnedValueCost()).doubleValue());
            n.put("planned_value_cost", nz(s.getPlannedValueCost()).doubleValue());
            arr.add(n);
            totLab = totLab.add(nz(s.getActualLaborCost()));
            totNonLab = totNonLab.add(nz(s.getActualNonlaborCost()));
            totMat = totMat.add(nz(s.getActualMaterialCost()));
            totExp = totExp.add(nz(s.getActualExpenseCost()));
            totEv = totEv.add(nz(s.getEarnedValueCost()));
            totPv = totPv.add(nz(s.getPlannedValueCost()));
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", arr);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("row_count", rows.size());
        summary.put("actual_labor_total", totLab.doubleValue());
        summary.put("actual_nonlabor_total", totNonLab.doubleValue());
        summary.put("actual_material_total", totMat.doubleValue());
        summary.put("actual_expense_total", totExp.doubleValue());
        summary.put("earned_value_total", totEv.doubleValue());
        summary.put("planned_value_total", totPv.doubleValue());
        wrapper.set("summary", summary);
        return ToolResult.ok(String.format("%d period-performance row%s.", rows.size(),
                rows.size() == 1 ? "" : "s"), wrapper);
    }

    private ToolResult opRaBillsSummary(UUID projectId) {
        List<RaBill> bills = raBillRepository.findByProjectIdOrderByBillNumberDesc(projectId);
        Map<String, StatusRollup> byStatus = new LinkedHashMap<>();
        for (RaBill b : bills) {
            String key = b.getStatus() == null ? "UNKNOWN" : b.getStatus().name();
            StatusRollup sr = byStatus.computeIfAbsent(key, k -> new StatusRollup());
            sr.count++;
            sr.gross = sr.gross.add(nz(b.getGrossAmount()));
            sr.net = sr.net.add(nz(b.getNetAmount()));
            sr.deductions = sr.deductions.add(nz(b.getDeductions()));
        }
        ArrayNode rows = objectMapper.createArrayNode();
        BigDecimal grossTotal = BigDecimal.ZERO, netTotal = BigDecimal.ZERO;
        for (Map.Entry<String, StatusRollup> e : byStatus.entrySet()) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("status", e.getKey());
            n.put("count", e.getValue().count);
            n.put("gross_total", e.getValue().gross.doubleValue());
            n.put("net_total", e.getValue().net.doubleValue());
            n.put("deductions_total", e.getValue().deductions.doubleValue());
            rows.add(n);
            grossTotal = grossTotal.add(e.getValue().gross);
            netTotal = netTotal.add(e.getValue().net);
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", rows);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("bill_count", bills.size());
        summary.put("gross_total", grossTotal.doubleValue());
        summary.put("net_total", netTotal.doubleValue());
        wrapper.set("summary", summary);
        return ToolResult.ok(String.format("%d RA bill%s across %d status bucket%s; net %.0f.",
                bills.size(), bills.size() == 1 ? "" : "s",
                byStatus.size(), byStatus.size() == 1 ? "" : "s", netTotal.doubleValue()), wrapper);
    }

    private ToolResult opFunding(UUID projectId) {
        List<ProjectFunding> rows = fundingRepository.findByProjectId(projectId);
        ArrayNode arr = objectMapper.createArrayNode();
        BigDecimal total = BigDecimal.ZERO;
        Set<UUID> sources = new HashSet<>();
        for (ProjectFunding f : rows) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("funding_id", f.getId() == null ? null : f.getId().toString());
            n.put("funding_source_id", f.getFundingSourceId() == null ? null : f.getFundingSourceId().toString());
            n.put("wbs_node_id", f.getWbsNodeId() == null ? null : f.getWbsNodeId().toString());
            n.put("allocated_amount", nz(f.getAllocatedAmount()).doubleValue());
            arr.add(n);
            total = total.add(nz(f.getAllocatedAmount()));
            if (f.getFundingSourceId() != null) sources.add(f.getFundingSourceId());
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("rows", arr);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("allocation_count", rows.size());
        summary.put("source_count", sources.size());
        summary.put("total_allocated", total.doubleValue());
        wrapper.set("summary", summary);
        ToolResult.attachLinks(wrapper, Map.of("funding_source", new ArrayList<>(sources)));
        return ToolResult.ok(String.format("%d funding allocation%s across %d source%s; total %.0f.",
                rows.size(), rows.size() == 1 ? "" : "s",
                sources.size(), sources.size() == 1 ? "" : "s", total.doubleValue()), wrapper);
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static String orNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s.trim();
    }

    private static class AccountRollup {
        int count = 0;
        BigDecimal budgeted = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        BigDecimal atCompletion = BigDecimal.ZERO;
    }

    private static class StatusRollup {
        int count = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
    }
}
