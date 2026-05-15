package com.bipros.ai.tool.role.qc_manager;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Audits the traceability of operators and material lots for a single DPR,
 * flagging each manpower and material line as {@code traceable=true/false}.
 *
 * <p>A <strong>manpower row</strong> is considered traceable when its {@code trade} field is
 * populated (the operator/worker identity is recorded).
 *
 * <p>A <strong>material row</strong> is considered traceable when both {@code batch_no}
 * (the lot/batch identifier) AND {@code quantity} are present (lot identity + measurement).
 *
 * <p>Lookup: by {@code dpr_id} (UUID) OR by {@code activity_code} + {@code report_date}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTraceabilityTool extends ProjectScopedTool {

    private final DailyProgressReportRepository dprRepository;
    private final DprManpowerRepository manpowerRepository;
    private final DprMaterialRepository materialRepository;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "audit_traceability";
    }

    @Override
    public String description() {
        return "For a single DPR (by id, OR by report_date + activity_code), audit the "
                + "traceability of operators and material lots back to a location/activity/DPR. "
                + "Flags each manpower line as traceable when the operator/trade is recorded, "
                + "and each material line as traceable when both the lot/batch identifier "
                + "and the quantity are present. Returns one output row per child line with "
                + "fields: dpr_id, report_date, kind (manpower|material), identifier, "
                + "traceable (bool), gap (description of missing fields). "
                + "Use to identify QC traceability gaps before milestone sign-off.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("dpr_id", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "uuid")
                .put("description",
                        "DPR UUID. Either this OR (activity_code + report_date) is required."));
        props.set("activity_code", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description",
                        "Activity short code (e.g. ACT-1.3.5). Pair with report_date."));
        props.set("report_date", objectMapper.createObjectNode()
                .put("type", "string")
                .put("format", "date")
                .put("description",
                        "ISO date (YYYY-MM-DD). Pair with activity_code."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    public Set<String> allowedRoles() {
        return Set.of("QC_MANAGER", "QA_QC_ENGINEER", "PROJECT_MANAGER", "BIM_DATA_COORDINATOR");
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        if (ctx.projectId() == null) {
            return ToolResult.error("audit_traceability requires a project in scope.");
        }

        // --- resolve which DPR to audit ---
        List<DailyProgressReport> matches = resolveDpr(input, ctx.projectId());

        if (matches.isEmpty()) {
            String dprIdStr = orNull(input.path("dpr_id").asText(null));
            if (dprIdStr != null) {
                return dataUnavailable(
                        "No DPR found with id " + dprIdStr + " in this project.",
                        "Verify the dpr_id is correct and belongs to the current project.");
            }
            return ToolResult.error(
                    "Provide either dpr_id or both activity_code and report_date.");
        }

        // Audit the first matched DPR (one at a time for traceability detail)
        DailyProgressReport dpr = matches.get(0);
        UUID dprId = dpr.getId();
        String reportDate = dpr.getReportDate() != null ? dpr.getReportDate().toString() : "";

        List<DprManpower> manpowerLines = manpowerRepository.findByDprIdOrderByTradeAsc(dprId);
        List<DprMaterial> materialLines = materialRepository.findByDprIdOrderByMaterialNameAsc(dprId);

        if (manpowerLines.isEmpty() && materialLines.isEmpty()) {
            return dataUnavailable(
                    "DPR has no manpower or material entries to trace.",
                    "Ensure manpower and material consumption lines are recorded on the DPR "
                            + "before running a traceability audit.");
        }

        ArrayNode rows = objectMapper.createArrayNode();
        int traceableCount = 0;
        int totalCount = 0;

        // --- Manpower lines ---
        for (DprManpower mp : manpowerLines) {
            totalCount++;
            List<String> gaps = new ArrayList<>();

            boolean tradePresent = mp.getTrade() != null && !mp.getTrade().isBlank();
            if (!tradePresent) gaps.add("trade/operator name missing");

            boolean traceable = tradePresent;
            if (traceable) traceableCount++;

            String identifier = tradePresent ? mp.getTrade()
                    : (mp.getContractorName() != null ? mp.getContractorName() : "(unknown trade)");

            ObjectNode row = objectMapper.createObjectNode();
            row.put("dpr_id", dprId.toString());
            row.put("report_date", reportDate);
            row.put("kind", "manpower");
            row.put("identifier", identifier);
            row.put("traceable", traceable);
            row.put("gap", String.join("; ", gaps));
            rows.add(row);
        }

        // --- Material lines ---
        for (DprMaterial mat : materialLines) {
            totalCount++;
            List<String> gaps = new ArrayList<>();

            boolean batchPresent = mat.getBatchNo() != null && !mat.getBatchNo().isBlank();
            boolean quantityPresent = mat.getQuantity() != null;

            if (!batchPresent) gaps.add("batch/lot number missing");
            if (!quantityPresent) gaps.add("quantity missing");

            boolean traceable = batchPresent && quantityPresent;
            if (traceable) traceableCount++;

            String identifier = mat.getMaterialName() != null ? mat.getMaterialName() : "(unknown material)";

            ObjectNode row = objectMapper.createObjectNode();
            row.put("dpr_id", dprId.toString());
            row.put("report_date", reportDate);
            row.put("kind", "material");
            row.put("identifier", identifier);
            row.put("traceable", traceable);
            row.put("gap", String.join("; ", gaps));
            rows.add(row);
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("dpr_id", dprId.toString());
        wrapper.put("report_date", reportDate);
        wrapper.put("activity_name", dpr.getActivityName());
        wrapper.put("total_lines", totalCount);
        wrapper.put("traceable_count", traceableCount);
        wrapper.put("untraceable_count", totalCount - traceableCount);
        wrapper.set("rows", rows);

        String summary = String.format(
                "audit_traceability: DPR %s (%s) — %d/%d lines traceable (%d gap(s)).",
                dprId, reportDate, traceableCount, totalCount, totalCount - traceableCount);

        return ToolResult.ok(summary, wrapper);
    }

    // --- resolution helpers ---

    private List<DailyProgressReport> resolveDpr(JsonNode input, UUID projectId) {
        String idStr = orNull(input.path("dpr_id").asText(null));
        if (idStr != null) {
            try {
                UUID id = UUID.fromString(idStr);
                Optional<DailyProgressReport> opt = dprRepository.findById(id);
                if (opt.isPresent() && projectId.equals(opt.get().getProjectId())) {
                    return List.of(opt.get());
                }
                return List.of();
            } catch (IllegalArgumentException ignored) {
                // fall through to date+code path
            }
        }

        String dateStr = orNull(input.path("report_date").asText(null));
        String activityCode = orNull(input.path("activity_code").asText(null));
        if (dateStr == null || activityCode == null) {
            return List.of();
        }

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            return List.of();
        }

        Optional<Activity> activity = activityRepository.findByProjectIdAndCode(projectId, activityCode);
        if (activity.isEmpty()) {
            log.debug("audit_traceability: activity code '{}' not found in project {}", activityCode, projectId);
            return List.of();
        }

        List<DailyProgressReport> sameName =
                dprRepository.findByProjectIdAndActivityNameIgnoreCaseOrderByReportDateAsc(
                        projectId, activity.get().getName());

        List<DailyProgressReport> out = new ArrayList<>();
        for (DailyProgressReport d : sameName) {
            if (date.equals(d.getReportDate())) out.add(d);
        }
        return out;
    }

    private ToolResult dataUnavailable(String reason, String whatNeeded) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "data_unavailable");
        payload.put("reason", reason);
        payload.put("what_would_be_needed", whatNeeded);
        return ToolResult.ok("Data not available: " + reason, payload);
    }

    private static String orNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
