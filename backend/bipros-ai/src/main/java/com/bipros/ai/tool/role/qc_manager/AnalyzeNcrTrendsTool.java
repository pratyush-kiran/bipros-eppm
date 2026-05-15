package com.bipros.ai.tool.role.qc_manager;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.ProjectScopedTool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.risk.domain.model.Risk;
import com.bipros.risk.domain.model.RiskCategoryType;
import com.bipros.risk.domain.repository.RiskCategoryTypeRepository;
import com.bipros.risk.domain.repository.RiskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Analyses non-conformance (NCR) trends by category source for a project.
 *
 * <p><strong>Proxy note:</strong> No dedicated NCR entity exists in the system.
 * This tool falls back to Quality-category entries in the Risk Register
 * ({@code RiskCategoryType.code = "CONSTRUCTION_QUALITY"}). Each matched risk
 * represents a quality non-conformance event tracked by the project team.
 *
 * <p>Output rows are grouped by {@code category_name} (the risk category, i.e. the
 * crew/source of the non-conformance) and sorted by {@code ncr_count} descending.
 * The {@code latest_event_date} reflects the most recent {@code identifiedDate}
 * within that category group.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeNcrTrendsTool extends ProjectScopedTool {

    /** RiskCategoryType code used as the Quality / NCR proxy filter. */
    private static final String QUALITY_TYPE_CODE = "CONSTRUCTION_QUALITY";

    private final RiskRepository riskRepository;
    private final RiskCategoryTypeRepository categoryTypeRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "analyze_ncr_trends";
    }

    @Override
    public String description() {
        return "Analyse non-conformance trends by crew/source. "
                + "No dedicated NCR entity exists — falls back to Quality-category entries "
                + "in the Risk Register (RiskCategoryType = CONSTRUCTION_QUALITY). "
                + "Groups by risk category (crew/source) and returns ncr_count + latest_event_date per group.";
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
        return Set.of("QC_MANAGER", "QA_QC_ENGINEER", "PROJECT_MANAGER");
    }

    @Override
    protected ToolResult doExecute(JsonNode input, AiContext ctx) {
        if (ctx.projectId() == null) {
            return ToolResult.error("Pick a project first — NCR trend analysis is per-project.");
        }

        // Resolve the CONSTRUCTION_QUALITY category type
        Optional<RiskCategoryType> qualityTypeOpt;
        try {
            qualityTypeOpt = categoryTypeRepository.findByCode(QUALITY_TYPE_CODE);
        } catch (Exception e) {
            log.warn("analyze_ncr_trends: failed to resolve quality category type: {}", e.getMessage());
            return dataUnavailable(
                    "Could not resolve the CONSTRUCTION_QUALITY risk category type.",
                    "Ensure risk category master data has been seeded.",
                    "analyze_risk");
        }

        if (qualityTypeOpt.isEmpty()) {
            return dataUnavailable(
                    "CONSTRUCTION_QUALITY risk category type is not configured in this deployment.",
                    "Seed the risk category master data (RiskCategorySeeder).",
                    "analyze_risk");
        }

        RiskCategoryType qualityType = qualityTypeOpt.get();

        // Fetch all project risks
        List<Risk> allRisks;
        try {
            allRisks = riskRepository.findByProjectId(ctx.projectId());
        } catch (Exception e) {
            log.warn("analyze_ncr_trends: failed to fetch risks for project {}: {}",
                    ctx.projectId(), e.getMessage());
            return dataUnavailable(
                    "Risk Register data is not accessible for this project.",
                    "Ensure risks are being logged in the Risk Register.",
                    "analyze_risk");
        }

        // Filter to Quality-category risks only
        List<Risk> qualityRisks = allRisks.stream()
                .filter(r -> r.getCategory() != null
                        && r.getCategory().getType() != null
                        && qualityType.getCode().equals(r.getCategory().getType().getCode()))
                .toList();

        if (qualityRisks.isEmpty()) {
            return dataUnavailable(
                    "Dedicated NCR tracking is not yet captured. "
                            + "The closest signal in the system is the Risk Register filtered to "
                            + "Quality-category risks, but no such entries exist for this project.",
                    "Log quality non-conformances as risks under the CONSTRUCTION_QUALITY category.",
                    "analyze_risk");
        }

        // Group by category name (crew/source of NCR)
        record GroupKey(String categoryName) {}

        Map<GroupKey, long[]> countMap = new HashMap<>();
        Map<GroupKey, LocalDate> latestDateMap = new HashMap<>();

        for (Risk r : qualityRisks) {
            String catName = r.getCategory().getName();
            GroupKey key = new GroupKey(catName);
            countMap.merge(key, new long[]{1}, (existing, one) -> {
                existing[0]++;
                return existing;
            });
            LocalDate identified = r.getIdentifiedDate();
            if (identified != null) {
                latestDateMap.merge(key, identified,
                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        // Build result rows ordered by ncr_count desc
        record NcrRow(String categoryName, long ncrCount, LocalDate latestEventDate) {}

        List<NcrRow> rows = countMap.entrySet().stream()
                .map(e -> new NcrRow(
                        e.getKey().categoryName(),
                        e.getValue()[0],
                        latestDateMap.getOrDefault(e.getKey(), null)))
                .sorted(Comparator.comparingLong(NcrRow::ncrCount).reversed())
                .toList();

        ArrayNode arr = objectMapper.createArrayNode();
        for (NcrRow row : rows) {
            ObjectNode o = objectMapper.createObjectNode();
            o.put("crew_or_source", row.categoryName());
            o.put("ncr_count", row.ncrCount());
            o.put("latest_event_date",
                    row.latestEventDate() != null ? row.latestEventDate().toString() : "");
            arr.add(o);
        }

        String summary = "NCR trends for project " + ctx.projectId() + " — "
                + qualityRisks.size() + " quality risk(s) across " + rows.size() + " category group(s). "
                + "(Based on Quality-category entries in the Risk Register — "
                + "dedicated NCR tracking is not yet captured.)";

        return ToolResult.table(
                summary,
                arr,
                new String[]{"crew_or_source", "ncr_count", "latest_event_date"}
        );
    }

    private ToolResult dataUnavailable(String reason, String whatNeeded, String closest) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("status", "data_unavailable");
        payload.put("reason", reason);
        payload.put("what_would_be_needed", whatNeeded);
        payload.put("closest_available", closest);
        return ToolResult.ok("Data not yet captured: " + reason, payload);
    }
}
