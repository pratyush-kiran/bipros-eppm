package com.bipros.ai.tool.cost;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.cost.application.dto.CostSummaryDto;
import com.bipros.cost.application.service.CostService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Project-level cost KPIs (total budget, total actual, CPI, BAC, material ledger).
 * Delegates to {@link CostService#getCostSummary(UUID)} so the AI returns the same
 * numbers the Cost tab renders — independent of whether the project defines any
 * {@code cost_account} rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectCostSummaryTool implements Tool {

    private final CostService costService;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "project_cost_summary";
    }

    @Override
    public String description() {
        return "Use this for any question about the project's overall cost KPIs — total "
                + "budgeted cost, total actual cost, total remaining, BAC, CPI, cost variance, "
                + "material procurement / open stock / issued. Sources include ActivityExpense "
                + "rows, ResourceAssignment planned cost, DPR-sourced actuals, and the material "
                + "ledger. Does NOT require any cost_account to exist — prefer this over "
                + "`cost_breakdown` whenever the user asks about the project's overall "
                + "budget / actual / CPI / variance. Project-scoped (no input).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("project_cost_summary needs a project in scope.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        CostSummaryDto s = costService.getCostSummary(projectId);
        String currency = projectRepository.findById(projectId)
                .map(Project::getBudgetCurrency)
                .orElse("INR");

        ObjectNode out = objectMapper.createObjectNode();
        out.put("project_id", projectId.toString());
        out.put("currency", currency);
        putAmount(out, "total_budget", s.totalBudget());
        putAmount(out, "total_actual", s.totalActual());
        putAmount(out, "total_remaining", s.totalRemaining());
        putAmount(out, "at_completion", s.atCompletion());
        putAmount(out, "cost_variance", s.costVariance());
        out.put("cpi", s.costPerformanceIndex() == null ? null : s.costPerformanceIndex().doubleValue());
        out.put("expense_count", s.expenseCount());
        putAmount(out, "material_procurement_cost", s.materialProcurementCost());
        putAmount(out, "open_stock_value", s.openStockValue());
        putAmount(out, "material_issued_cost", s.materialIssuedCost());
        putAmount(out, "project_original_budget", s.projectOriginalBudget());
        putAmount(out, "project_current_budget", s.projectCurrentBudget());

        String cpiTxt = s.costPerformanceIndex() == null ? "N/A"
                : String.format("%.4f", s.costPerformanceIndex().doubleValue());
        String summary = String.format(
                "Total budget %,.0f %s; total actual %,.0f %s; CPI %s.",
                nz(s.totalBudget()).doubleValue(), currency,
                nz(s.totalActual()).doubleValue(), currency,
                cpiTxt);
        return ToolResult.ok(summary, out);
    }

    private void putAmount(ObjectNode node, String key, BigDecimal value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value.doubleValue());
        }
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
