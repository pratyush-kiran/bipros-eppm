package com.bipros.ai.tool.dbs;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.dbs.api.dto.CumulativeDaysResponse;
import com.bipros.dbs.api.dto.EquipmentRegisterResponse;
import com.bipros.dbs.api.dto.EquipmentRegisterTypeRow;
import com.bipros.dbs.service.RegisterAggregationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Equipment deployment register for a project's DBS — per equipment type with
 * day/night/total counts, optionally sliced by Construction Manager. Use
 * {@code mode=TODAY} for a single-day snapshot, {@code mode=CUMULATIVE} for total
 * deployment-days from project start to {@code asOf}. Answers questions like
 * "how many Excavator-days have been deployed", "today's equipment shift breakdown
 * for CM X".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbsEquipmentRegisterTool extends ProjectScopedTool {

    private final RegisterAggregationService registerService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "dbs_equipment_register";
    }

    @Override
    public String description() {
        return "Equipment deployment register for a project's DBS — per equipment type with "
                + "day/night/total counts, optionally sliced by Construction Manager. Use "
                + "mode=TODAY for a single-day snapshot, mode=CUMULATIVE for total deployment-days "
                + "from project start to asOf date. Answers questions like 'how many Excavator-days "
                + "have been deployed', 'today's equipment shift breakdown for CM X'.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        props.set("projectId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Optional — falls back to the project in scope."));

        ObjectNode mode = objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "TODAY = single-day snapshot (per-type day/night/total). CUMULATIVE = total deployment-days summed from project start to asOf.");
        mode.putArray("enum").add("TODAY").add("CUMULATIVE");
        mode.put("default", "TODAY");
        props.set("mode", mode);

        props.set("date", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "ISO date for the snapshot — used when mode=TODAY. Defaults to today."));
        props.set("asOf", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "ISO as-of date — used when mode=CUMULATIVE. Defaults to today."));
        props.set("cmUserId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Optional Construction Manager UUID — restricts the register to that CM's slice."));

        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("dbs_equipment_register needs a projectId (or a project in scope).");
        }

        String mode = normaliseEnum(input.path("mode").asText(null), "TODAY");
        UUID cmUserId = parseUuid(input.path("cmUserId").asText(null));

        if ("CUMULATIVE".equals(mode)) {
            LocalDate asOf = parseDate(input.path("asOf").asText(null));
            if (asOf == null) asOf = LocalDate.now();
            CumulativeDaysResponse r = registerService.cumulative(projectId, asOf, cmUserId);
            // Project to equipment-only payload — manpower belongs to the sister tool.
            Map<String, Object> payload = Map.of(
                "asOfDate", r.asOfDate(),
                "equipment", r.equipment()
            );
            long totalDays = r.equipment().stream().mapToLong(CumulativeDaysResponse.CumulativeEquipmentDays::days).sum();
            String summary = String.format(Locale.ROOT,
                "Cumulative equipment-days as of %s: %d types, %d total deployment-days",
                r.asOfDate(), r.equipment().size(), totalDays);
            return ToolResult.ok(summary, objectMapper.valueToTree(payload));
        }

        // TODAY (default)
        LocalDate date = parseDate(input.path("date").asText(null));
        if (date == null) date = LocalDate.now();
        EquipmentRegisterResponse r = registerService.getEquipmentRegister(projectId, date, cmUserId);
        int totalDay = r.equipment().stream().mapToInt(EquipmentRegisterTypeRow::totalDay).sum();
        int totalNight = r.equipment().stream().mapToInt(EquipmentRegisterTypeRow::totalNight).sum();
        String summary = String.format(Locale.ROOT,
            "Equipment register for %s: %d equipment types, total %d day-shifts + %d night-shifts (%d total deployments)",
            r.date(), r.equipment().size(), totalDay, totalNight, totalDay + totalNight);
        return ToolResult.ok(summary, objectMapper.valueToTree(r));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
