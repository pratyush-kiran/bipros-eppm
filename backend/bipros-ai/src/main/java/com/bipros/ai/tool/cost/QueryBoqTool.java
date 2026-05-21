package com.bipros.ai.tool.cost;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Project Bill of Quantities query. Returns BOQ master rows with contract qty/rate/amount,
 * project-team budgeted rate/amount, and the listener-maintained actuals
 * (qtyExecutedToDate, actualRate, actualAmount, percentComplete, costVariance).
 *
 * <p>Use for questions like 'what is item RB-09.02', 'list all concrete BOQ items',
 * 'BOQ progress for chapter 4', 'BOQ planned vs actual'. This is the only AI tool that
 * reads {@code project.boq_items} directly — other tools (query_dpr, get_dpr_details)
 * only see {@code boq_item_no} as a shadow field on DPR rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryBoqTool extends ProjectScopedTool {

    private final BoqItemRepository boqItemRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "query_boq";
    }

    @Override
    public String description() {
        return "Project Bill of Quantities — contract qty/rate/amount, budgeted rate/amount, "
                + "and rolled-up actuals (qty executed to date, actual rate, actual amount, "
                + "percent complete, cost variance). Pass `itemNo` for a single line; otherwise "
                + "returns the full BOQ master with grand totals. Optional filters: `chapter` "
                + "(MoRTH chapter grouping, substring match, case-insensitive), `status` "
                + "(ACTIVE, etc.), `minPercentComplete` / `maxPercentComplete` (0..1, e.g. "
                + "0.5 = 50%). Default limit 100 rows. Use this for BOQ planned-vs-actual, "
                + "BOQ rollup, BOQ status, and item-level cost variance questions.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        props.set("projectId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Optional — falls back to the project in scope."));
        props.set("itemNo", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "BOQ item number (e.g. 'RB-09.02'). When present, returns a single item."));
        props.set("chapter", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Substring filter (case-insensitive) on the MoRTH chapter, e.g. 'Earthwork' or '3 - Bituminous'."));
        props.set("status", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Exact status filter, e.g. 'ACTIVE'. Case-insensitive."));
        props.set("minPercentComplete", objectMapper.createObjectNode()
                .put("type", "number")
                .put("description", "Lower bound on percent_complete (0..1). 0.5 = 50% complete."));
        props.set("maxPercentComplete", objectMapper.createObjectNode()
                .put("type", "number")
                .put("description", "Upper bound on percent_complete (0..1)."));
        props.set("limit", objectMapper.createObjectNode()
                .put("type", "integer")
                .put("description", "Max rows to return. Default 100.")
                .put("default", 100));

        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("query_boq needs a projectId (or a project in scope).");
        }

        final UUID pid = projectId;
        String itemNo = text(input, "itemNo");

        // Single-item path.
        if (itemNo != null) {
            final String itemNoFinal = itemNo;
            return boqItemRepository.findByProjectIdAndItemNo(pid, itemNoFinal)
                    .map(item -> {
                        ObjectNode wrapper = objectMapper.createObjectNode();
                        wrapper.put("project_id", pid.toString());
                        wrapper.set("item", itemToJson(item));
                        return ToolResult.ok(summariseOne(item), wrapper);
                    })
                    .orElseGet(() -> ToolResult.error(
                            "No BOQ item '" + itemNoFinal + "' on project " + pid + "."));
        }

        // List path with filters.
        String chapterFilter = text(input, "chapter");
        String chapterLower = chapterFilter == null ? null : chapterFilter.toLowerCase(Locale.ROOT);
        String statusFilter = text(input, "status");
        String statusUpper = statusFilter == null ? null : statusFilter.toUpperCase(Locale.ROOT);
        BigDecimal minPct = parseDecimal(input, "minPercentComplete");
        BigDecimal maxPct = parseDecimal(input, "maxPercentComplete");
        int limit = input.path("limit").asInt(100);
        if (limit <= 0 || limit > 500) limit = 100;

        List<BoqItem> all = boqItemRepository.findByProjectIdOrderByItemNoAsc(pid);

        ArrayNode items = objectMapper.createArrayNode();
        BigDecimal sumBoq = BigDecimal.ZERO;
        BigDecimal sumBudgeted = BigDecimal.ZERO;
        BigDecimal sumActual = BigDecimal.ZERO;
        BigDecimal sumVariance = BigDecimal.ZERO;
        int filteredCount = 0;

        for (BoqItem item : all) {
            if (chapterLower != null) {
                String ch = item.getChapter();
                if (ch == null || !ch.toLowerCase(Locale.ROOT).contains(chapterLower)) continue;
            }
            if (statusUpper != null) {
                if (item.getStatus() == null
                        || !item.getStatus().name().equalsIgnoreCase(statusUpper)) continue;
            }
            if (minPct != null) {
                BigDecimal pc = item.getPercentComplete();
                if (pc == null || pc.compareTo(minPct) < 0) continue;
            }
            if (maxPct != null) {
                BigDecimal pc = item.getPercentComplete();
                if (pc == null || pc.compareTo(maxPct) > 0) continue;
            }

            filteredCount++;
            sumBoq = sumBoq.add(nz(item.getBoqAmount()));
            sumBudgeted = sumBudgeted.add(nz(item.getBudgetedAmount()));
            sumActual = sumActual.add(nz(item.getActualAmount()));
            sumVariance = sumVariance.add(nz(item.getCostVariance()));

            if (items.size() < limit) {
                items.add(itemToJson(item));
            }
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("project_id", pid.toString());
        wrapper.set("items", items);
        wrapper.put("count", filteredCount);
        wrapper.put("returned", items.size());
        wrapper.put("truncated", filteredCount > items.size());

        ObjectNode totals = objectMapper.createObjectNode();
        totals.put("boq_amount", fmt2(sumBoq));
        totals.put("budgeted_amount", fmt2(sumBudgeted));
        totals.put("actual_amount", fmt2(sumActual));
        totals.put("cost_variance", fmt2(sumVariance));
        wrapper.set("totals", totals);

        String summary = String.format(Locale.ROOT,
                "%d BOQ item%s%s. Contract=%s, Budgeted=%s, Actual=%s, Variance=%s.",
                filteredCount, filteredCount == 1 ? "" : "s",
                filteredCount > items.size() ? " (showing first " + items.size() + ")" : "",
                fmt2(sumBoq), fmt2(sumBudgeted), fmt2(sumActual), fmt2(sumVariance));
        return ToolResult.ok(summary, wrapper);
    }

    private ObjectNode itemToJson(BoqItem item) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", item.getId() != null ? item.getId().toString() : null);
        n.put("item_no", item.getItemNo());
        n.put("description", item.getDescription());
        n.put("unit", item.getUnit());
        n.put("chapter", item.getChapter());
        n.put("status", item.getStatus() != null ? item.getStatus().name() : null);
        n.put("wbs_node_id", item.getWbsNodeId() != null ? item.getWbsNodeId().toString() : null);
        n.put("boq_qty", item.getBoqQty());
        n.put("boq_rate", item.getBoqRate());
        n.put("boq_amount", item.getBoqAmount());
        n.put("budgeted_rate", item.getBudgetedRate());
        n.put("budgeted_amount", item.getBudgetedAmount());
        n.put("qty_executed_to_date", item.getQtyExecutedToDate());
        n.put("actual_rate", item.getActualRate());
        n.put("actual_amount", item.getActualAmount());
        n.put("percent_complete", item.getPercentComplete());
        n.put("cost_variance", item.getCostVariance());
        n.put("cost_variance_percent", item.getCostVariancePercent());
        n.put("manual_override", Boolean.TRUE.equals(item.getManualOverride()));
        return n;
    }

    private static String summariseOne(BoqItem item) {
        BigDecimal pct = item.getPercentComplete();
        String pctStr = pct == null
                ? "—"
                : pct.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        return String.format(Locale.ROOT,
                "BOQ %s (%s): qty=%s %s @ %s = %s; executed %s (%s); actual rate %s, variance %s.",
                item.getItemNo(),
                item.getDescription(),
                item.getBoqQty(),
                item.getUnit(),
                item.getBoqRate(),
                item.getBoqAmount(),
                item.getQtyExecutedToDate(),
                pctStr,
                item.getActualRate(),
                item.getCostVariance());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String fmt2(BigDecimal v) {
        return v == null
                ? "0.00"
                : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in == null ? null : in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal parseDecimal(JsonNode in, String field) {
        String s = text(in, field);
        if (s == null) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
