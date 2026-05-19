package com.bipros.ai.tool.dbs;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.dbs.api.dto.DbsSupervisorSummaryDto;
import com.bipros.dbs.service.DbsQueryService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Compact list of supervisors with DBS rows on a given day — supervisor UUID + name plus the
 * key financial totals. Drives drill-down navigation (find the worst margin → fetch full
 * supervisor DBS with {@code dbs_financial level=SUPERVISOR}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbsListSupervisorsTool extends ProjectScopedTool {

    private final DbsQueryService dbsQueryService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "dbs_list_supervisors";
    }

    @Override
    public String description() {
        return "Lists supervisors with a DBS row on a given day for a project — supervisor UUID, "
                + "name, total expense, total income, contribution, contribution %. Use to find "
                + "the right supervisorUserId for a follow-up `dbs_financial` call, or to rank "
                + "supervisors by margin on a day.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("projectId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Optional — falls back to the project in scope."));
        props.set("date", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "ISO date. Required."));
        schema.set("properties", props);
        schema.putArray("required").add("date");
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("dbs_list_supervisors needs a projectId (or a project in scope).");
        }
        LocalDate date = parseDate(input.path("date").asText(null));
        if (date == null) {
            return ToolResult.error("dbs_list_supervisors needs an ISO `date` parameter.");
        }

        List<DbsSupervisorSummaryDto> rows = dbsQueryService.listSupervisorsForDay(projectId, date);

        ArrayNode arr = objectMapper.createArrayNode();
        for (DbsSupervisorSummaryDto r : rows) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("supervisorUserId", r.supervisorUserId() == null ? null : r.supervisorUserId().toString());
            n.put("supervisorName", r.supervisorName());
            n.put("totalExpense", fmt(r.totalExpense()));
            n.put("totalIncome", fmt(r.totalIncome()));
            n.put("contribution", fmt(r.contribution()));
            n.put("contributionPct", fmt(scalePct(r.contributionPct())));
            n.put("dprCount", r.dprCount() == null ? 0 : r.dprCount());
            arr.add(n);
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("projectId", projectId.toString());
        out.put("date", date.toString());
        out.put("count", rows.size());
        out.set("supervisors", arr);

        String summary = "DBS supervisors " + date + ": " + rows.size() + " with rows";
        return ToolResult.ok(summary, out);
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "0";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal scalePct(BigDecimal pct) {
        if (pct == null) return BigDecimal.ZERO;
        return pct.multiply(new BigDecimal("100"));
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
