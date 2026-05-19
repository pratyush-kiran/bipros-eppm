package com.bipros.ai.tool.dbs;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.dbs.service.DbsQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight DBS alert codes (NEGATIVE_CONTRIBUTION, RUNAWAY_FUEL, …) for a project on a
 * date. Cheap on purpose — lets the LLM scan many days without dragging the full financial
 * payload each time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbsAlertsTool extends ProjectScopedTool {

    private final DbsQueryService dbsQueryService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "dbs_alerts";
    }

    @Override
    public String description() {
        return "Daily Balance Sheet alert codes for a project on a date — flags health issues "
                + "like NEGATIVE_CONTRIBUTION, RUNAWAY_FUEL, etc. Use to triage which days need a "
                + "deeper `dbs_financial` look. Returns an empty array if the day has no DBS row.";
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
                .put("description", "ISO date to evaluate. Required."));
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
            return ToolResult.error("dbs_alerts needs a projectId (or a project in scope).");
        }
        LocalDate date = parseDate(input.path("date").asText(null));
        if (date == null) {
            return ToolResult.error("dbs_alerts needs an ISO `date` parameter.");
        }

        List<String> alerts = dbsQueryService.getAlertsForProjectDay(projectId, date);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("projectId", projectId.toString());
        out.put("date", date.toString());
        ArrayNode arr = objectMapper.createArrayNode();
        for (String a : alerts) arr.add(a);
        out.set("alerts", arr);

        String summary = alerts.isEmpty()
                ? "DBS alerts " + date + ": none"
                : "DBS alerts " + date + ": " + String.join(", ", alerts);
        return ToolResult.ok(summary, out);
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
