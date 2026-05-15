package com.bipros.ai.tool.role.qc_manager;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists activities that are making progress (have at least one DPR) but are missing
 * the quality fields a QC reviewer would expect to be filled in.
 *
 * <p><strong>Gap detection:</strong> No explicit "QC required" flag exists on the DPR entity.
 * Instead, this tool infers gaps from the absence of standard quality-relevant DPR fields:
 * <ul>
 *   <li>{@code weatherCondition} — environmental context required for test validity</li>
 *   <li>{@code approvalStatus} — indicates whether a QC reviewer has signed off the entry</li>
 *   <li>{@code safetyObservation} — HSE/QC observation expected on every active work day</li>
 *   <li>{@code remarks} — field notes expected to document test status and issues</li>
 * </ul>
 *
 * <p>A DPR row is considered to have a gap when at least one of the above fields is
 * null or blank. Activities are grouped by {@code activityName}; the output row shows
 * how many DPRs exist, how many have at least one gap, and the union of all missing
 * field names across those DPRs. Rows are sorted by {@code gap_count} descending so
 * the most under-reported activities appear first.
 *
 * <p>If the project has no DPRs at all, the tool returns {@code data_unavailable} with
 * a clear reason rather than an empty table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeQualityDataGapsTool extends ProjectScopedTool {

    /**
     * Canonical set of DPR fields that a QC reviewer expects to be populated.
     * Adding a field here is the only change needed to extend gap detection.
     */
    static final Set<String> QC_FIELDS = Set.of(
            "weatherCondition",
            "approvalStatus",
            "safetyObservation",
            "remarks"
    );

    private final DailyProgressReportRepository dprRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyze_quality_data_gaps";
    }

    @Override
    public String description() {
        return "List activities making progress but missing the quality fields a QC reviewer "
                + "would expect (weather condition, approval status, safety observation, remarks). "
                + "When the data model has no explicit 'QC required' flag, infers the gap from "
                + "the absence of standard quality-relevant DPR fields. "
                + "Returns rows grouped by activity, sorted by gap_count desc. "
                + "Each row contains: activity_code, dpr_count, gap_count, missing_fields (array).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        return schema;
    }

    @Override
    public Set<String> allowedRoles() {
        return Set.of("QC_MANAGER", "QA_QC_ENGINEER", "BIM_DATA_COORDINATOR", "PROJECT_MANAGER");
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        if (ctx.projectId() == null) {
            return ToolResult.error("analyze_quality_data_gaps requires a project in scope.");
        }

        List<DailyProgressReport> all;
        try {
            all = dprRepository.findByProjectIdOrderByReportDateAscIdAsc(ctx.projectId());
        } catch (Exception e) {
            log.warn("analyze_quality_data_gaps: repository query failed for project {}: {}",
                    ctx.projectId(), e.getMessage());
            return dataUnavailable(
                    "DPR data is not accessible for this project.",
                    "audit_dpr_data_quality");
        }

        if (all.isEmpty()) {
            return dataUnavailable(
                    "Project has no DPRs yet — quality data gaps cannot be assessed.",
                    "audit_dpr_data_quality");
        }

        // Group by activityName, accumulating dpr_count, gap_count, and missing field names
        // Use LinkedHashMap to preserve insertion order before sorting
        Map<String, ActivityGapAccumulator> grouped = new LinkedHashMap<>();

        for (DailyProgressReport dpr : all) {
            String activityKey = dpr.getActivityName() != null ? dpr.getActivityName() : "(no activity)";
            grouped.computeIfAbsent(activityKey, ActivityGapAccumulator::new).add(dpr);
        }

        // Build result rows sorted by gap_count DESC
        record GapRow(String activityName, int dprCount, int gapCount, Set<String> missingFields) {}

        List<GapRow> rows = grouped.values().stream()
                .filter(acc -> acc.gapCount > 0)   // only activities with at least one gap
                .map(acc -> new GapRow(acc.activityName, acc.dprCount, acc.gapCount, acc.allMissingFields))
                .sorted(Comparator.comparingInt(GapRow::gapCount).reversed())
                .toList();

        if (rows.isEmpty()) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("status", "ok");
            payload.put("message", "No quality data gaps found — all DPRs have the expected QC fields populated.");
            return ToolResult.ok("No quality data gaps found for project " + ctx.projectId() + ".", payload);
        }

        ArrayNode arr = objectMapper.createArrayNode();
        for (GapRow row : rows) {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("activity_code", row.activityName());
            o.put("dpr_count", row.dprCount());
            o.put("gap_count", row.gapCount());
            ArrayNode missingArr = objectMapper.createArrayNode();
            // Sort for deterministic output
            row.missingFields().stream().sorted().forEach(missingArr::add);
            o.set("missing_fields", missingArr);
            arr.add(o);
        }

        String summary = "Quality data gaps for project " + ctx.projectId()
                + " — " + rows.size() + " activity group(s) with incomplete QC fields "
                + "(checked: weatherCondition, approvalStatus, safetyObservation, remarks).";

        return ToolResult.table(
                summary,
                arr,
                new String[]{"activity_code", "dpr_count", "gap_count", "missing_fields"}
        );
    }

    private ToolResult dataUnavailable(String reason, String closest) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "data_unavailable");
        payload.put("reason", reason);
        payload.put("closest_available", closest);
        return ToolResult.ok("Data not yet captured: " + reason, payload);
    }

    /**
     * Accumulates DPR gap statistics for one activity group.
     */
    private static final class ActivityGapAccumulator {
        final String activityName;
        int dprCount = 0;
        int gapCount = 0;
        // Union of all missing field names seen across DPRs in this group
        final Set<String> allMissingFields = new java.util.LinkedHashSet<>();

        ActivityGapAccumulator(String activityName) {
            this.activityName = activityName;
        }

        void add(DailyProgressReport dpr) {
            dprCount++;
            List<String> missing = missingQcFields(dpr);
            if (!missing.isEmpty()) {
                gapCount++;
                allMissingFields.addAll(missing);
            }
        }

        private static List<String> missingQcFields(DailyProgressReport dpr) {
            List<String> missing = new ArrayList<>();
            if (isBlank(dpr.getWeatherCondition()))  missing.add("weatherCondition");
            if (dpr.getApprovalStatus() == null)     missing.add("approvalStatus");
            if (isBlank(dpr.getSafetyObservation())) missing.add("safetyObservation");
            if (isBlank(dpr.getRemarks()))           missing.add("remarks");
            return missing;
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }
}
