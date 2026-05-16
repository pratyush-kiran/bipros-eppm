package com.bipros.ai.tool.resource;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceStatus;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the project-agnostic resource <b>catalogue</b> — the priced master list of
 * Manpower / Equipment / Material rows in {@code resource.resources} (exposed at
 * {@code /v1/resources}). Distinct from {@code find_resource_deployment} and
 * {@code summarize_activity_resources}, which only see resources that have been
 * <i>assigned</i> to an activity. A project may have hundreds of priced catalogue
 * rows yet zero assignments — and answering "what's the daily rate of a 20T
 * Excavator?" or "which is our most expensive equipment?" requires the catalogue,
 * not the assignment view.
 *
 * <p>Type-code aliases accepted (in addition to the canonical seeded codes):
 * EQUIPMENT → MACHINE, LABOR / LABOUR → MANPOWER. The model can pass either form;
 * the tool normalises before querying.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryResourceCatalogueTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final ResourceRepository resourceRepository;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "query_resource_catalogue";
    }

    @Override
    public String description() {
        return "Query the project's resource CATALOGUE — the priced master list of "
                + "Manpower / Equipment / Material rows in resource.resources (the same "
                + "list /v1/resources returns). Use this for ANY question about rates, "
                + "unit costs, daily / hourly / per-MT prices, the size of the priced "
                + "catalogue, or the most/least expensive items of a kind. Examples: "
                + "\"how many resources of each type are in the catalogue\", \"most "
                + "expensive equipment\", \"daily rate for a 20T excavator\", "
                + "\"top 3 labor categories by rate\", \"per-MT rate for TMT Rebar "
                + "Fe500D\". "
                + "Inputs: type_code (MANPOWER | EQUIPMENT | MATERIAL — EQUIPMENT and "
                + "LABOR/LABOUR are accepted aliases for MACHINE and MANPOWER), code "
                + "(exact resource code), name_filter (case-insensitive substring on "
                + "name OR code), order_by (name | cost_desc | cost_asc), limit. "
                + "Returns: rows with resource_id, code, name, type_code, role_code, "
                + "role_name, unit, cost_per_unit, status; PLUS a counts_by_type rollup "
                + "and a top_by_rate rollup (top 5 most expensive of the matched set). "
                + "DO NOT use find_resource_deployment / summarize_activity_resources "
                + "for catalogue/rate questions — those tools only see resources that "
                + "are CURRENTLY ASSIGNED to an activity, and a fully-priced catalogue "
                + "with zero assignments will look empty through them.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("type_code", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Filter by resource type. Canonical codes: MANPOWER, "
                        + "MATERIAL, MACHINE. Aliases: EQUIPMENT → MACHINE, LABOR / LABOUR "
                        + "→ MANPOWER. Omit for all types."));
        props.set("code", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Exact resource code (e.g. 'EXC-20T'). Case-insensitive."));
        props.set("name_filter", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Case-insensitive substring matched against the resource "
                        + "name AND code (e.g. 'excavator', 'fe500d', 'piling rig')."));
        props.set("status", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "ACTIVE (default) | INACTIVE | RETIRED. Pass 'ALL' to "
                        + "include every status."));
        props.set("order_by", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "name (default), cost_desc (most expensive first), "
                        + "cost_asc (cheapest first)."));
        props.set("include_zero_cost", mapper.createObjectNode()
                .put("type", "boolean")
                .put("description", "Default true. When false, drops rows with null or zero "
                        + "cost_per_unit (useful for rate questions)."));
        props.set("limit", mapper.createObjectNode()
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", MAX_LIMIT)
                .put("default", DEFAULT_LIMIT)
                .put("description", "Max rows to return. Default 100, max 500."));
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode());
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        String typeCodeRaw = text(input, "type_code");
        String typeCode = canonicaliseTypeCode(typeCodeRaw);
        String codeFilter = text(input, "code");
        String nameFilter = text(input, "name_filter");
        String statusRaw = text(input, "status");
        String orderBy = text(input, "order_by");
        boolean includeZeroCost = !input.path("include_zero_cost").isMissingNode()
                && !input.path("include_zero_cost").isNull()
                ? input.path("include_zero_cost").asBoolean(true)
                : true;
        int limit = Math.max(1, Math.min(MAX_LIMIT,
                input.path("limit").asInt(DEFAULT_LIMIT)));

        ResourceStatus statusEnum = parseStatus(statusRaw); // null means "all statuses"

        // Catalogue is bounded (typical project ~100-300 rows). Load the relevant
        // subset and filter / sort in memory — keeps the SQL trivial and lets us
        // compose multi-field substring matches without a custom JPQL.
        List<Resource> base;
        if (typeCode != null) {
            if (statusEnum != null) {
                base = resourceRepository.findByResourceType_CodeAndStatus(typeCode, statusEnum);
            } else {
                base = resourceRepository.findByResourceType_Code(typeCode);
            }
        } else {
            base = resourceRepository.findAll();
            if (statusEnum != null) {
                base = base.stream().filter(r -> r.getStatus() == statusEnum).toList();
            }
        }

        List<Resource> filtered = new ArrayList<>();
        String nameLower = nameFilter == null ? null : nameFilter.toLowerCase(Locale.ROOT);
        String codeLower = codeFilter == null ? null : codeFilter.toLowerCase(Locale.ROOT);
        for (Resource r : base) {
            if (codeLower != null) {
                if (r.getCode() == null
                        || !r.getCode().toLowerCase(Locale.ROOT).equals(codeLower)) continue;
            }
            if (nameLower != null) {
                String name = r.getName() == null ? "" : r.getName().toLowerCase(Locale.ROOT);
                String code = r.getCode() == null ? "" : r.getCode().toLowerCase(Locale.ROOT);
                if (!name.contains(nameLower) && !code.contains(nameLower)) continue;
            }
            if (!includeZeroCost) {
                BigDecimal c = r.getCostPerUnit();
                if (c == null || c.signum() <= 0) continue;
            }
            filtered.add(r);
        }

        // Counts-by-type rollup runs over the FULL filtered set, before limit /
        // sort — so "show me the 10 most expensive" still tells the model how
        // many catalogue rows of each type exist. Honest, useful, cheap.
        Map<String, Long> countsByType = new LinkedHashMap<>();
        for (Resource r : filtered) {
            String tc = r.getResourceType() == null ? "UNKNOWN" : r.getResourceType().getCode();
            countsByType.merge(tc, 1L, Long::sum);
        }

        // Sort + cap.
        Comparator<Resource> cmp = comparatorFor(orderBy);
        filtered.sort(cmp);
        List<Resource> capped = filtered.size() > limit
                ? filtered.subList(0, limit) : filtered;

        // Top-by-rate independently of the user's order_by choice — gives the
        // model the answer to "most expensive" without an extra round.
        List<Resource> topByRate = new ArrayList<>(filtered);
        topByRate.sort(comparatorFor("cost_desc"));
        if (topByRate.size() > 5) topByRate = topByRate.subList(0, 5);

        ArrayNode rows = mapper.createArrayNode();
        for (Resource r : capped) rows.add(toJson(r));

        ArrayNode top = mapper.createArrayNode();
        for (Resource r : topByRate) top.add(toJson(r));

        ArrayNode countsArr = mapper.createArrayNode();
        countsByType.forEach((k, v) -> {
            ObjectNode n = mapper.createObjectNode();
            n.put("type_code", k);
            n.put("count", v);
            countsArr.add(n);
        });

        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.set("rows", rows);
        wrapper.set("counts_by_type", countsArr);
        wrapper.set("top_by_rate", top);
        wrapper.put("matched", filtered.size());
        wrapper.put("returned", capped.size());
        wrapper.put("source", "resource.resources (catalogue, project-agnostic)");
        if (typeCodeRaw != null) wrapper.put("type_code_input", typeCodeRaw);
        if (typeCode != null) wrapper.put("type_code_resolved", typeCode);
        if (codeFilter != null) wrapper.put("code", codeFilter);
        if (nameFilter != null) wrapper.put("name_filter", nameFilter);
        if (orderBy != null) wrapper.put("order_by", orderBy);

        String summary = filtered.isEmpty()
                ? buildEmptySummary(typeCode, codeFilter, nameFilter)
                : buildSummary(filtered.size(), countsByType, topByRate);
        return ToolResult.ok(summary, wrapper);
    }

    private ObjectNode toJson(Resource r) {
        ObjectNode n = mapper.createObjectNode();
        n.put("resource_id", r.getId() == null ? null : r.getId().toString());
        n.put("code", r.getCode());
        n.put("name", r.getName());
        n.put("type_code", r.getResourceType() == null ? null : r.getResourceType().getCode());
        n.put("type_name", r.getResourceType() == null ? null : r.getResourceType().getName());
        n.put("role_code", r.getRole() == null ? null : r.getRole().getCode());
        n.put("role_name", r.getRole() == null ? null : r.getRole().getName());
        n.put("unit", r.getUnit());
        n.put("cost_per_unit",
                r.getCostPerUnit() == null ? null : r.getCostPerUnit().toPlainString());
        n.put("status", r.getStatus() == null ? null : r.getStatus().name());
        return n;
    }

    private static Comparator<Resource> comparatorFor(String orderBy) {
        if (orderBy == null) return byName();
        return switch (orderBy.trim().toLowerCase(Locale.ROOT)) {
            case "cost_desc" -> byCostDesc();
            case "cost_asc" -> byCostAsc();
            default -> byName();
        };
    }

    private static Comparator<Resource> byName() {
        return Comparator.comparing(
                (Resource r) -> r.getName() == null ? "" : r.getName().toLowerCase(Locale.ROOT));
    }

    private static Comparator<Resource> byCostDesc() {
        return (a, b) -> {
            BigDecimal ca = a.getCostPerUnit() == null ? BigDecimal.ZERO : a.getCostPerUnit();
            BigDecimal cb = b.getCostPerUnit() == null ? BigDecimal.ZERO : b.getCostPerUnit();
            return cb.compareTo(ca);
        };
    }

    private static Comparator<Resource> byCostAsc() {
        return (a, b) -> {
            BigDecimal ca = a.getCostPerUnit() == null ? BigDecimal.ZERO : a.getCostPerUnit();
            BigDecimal cb = b.getCostPerUnit() == null ? BigDecimal.ZERO : b.getCostPerUnit();
            return ca.compareTo(cb);
        };
    }

    /**
     * Map common aliases the model is likely to emit ("EQUIPMENT", "LABOR") to
     * the canonical seeded type codes (MACHINE, MANPOWER). Returns null when no
     * input was given so the caller knows to load every type.
     */
    static String canonicaliseTypeCode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "EQUIPMENT", "MACHINE", "PLANT" -> "MACHINE";
            case "LABOR", "LABOUR", "MANPOWER", "MAN_POWER" -> "MANPOWER";
            case "MATERIAL", "MATERIALS" -> "MATERIAL";
            default -> upper;
        };
    }

    private static ResourceStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return ResourceStatus.ACTIVE;
        if ("ALL".equalsIgnoreCase(raw.trim())) return null;
        try {
            return ResourceStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResourceStatus.ACTIVE;
        }
    }

    private static String text(JsonNode in, String field) {
        JsonNode n = in == null ? null : in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String buildSummary(int matched, Map<String, Long> countsByType,
                                       List<Resource> topByRate) {
        StringBuilder sb = new StringBuilder();
        sb.append(matched).append(" catalogue row").append(matched == 1 ? "" : "s");
        if (!countsByType.isEmpty()) {
            sb.append(" (");
            boolean first = true;
            for (Map.Entry<String, Long> e : countsByType.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getValue()).append(" ").append(e.getKey());
                first = false;
            }
            sb.append(")");
        }
        Optional<Resource> top = topByRate.stream().findFirst();
        top.ifPresent(r -> {
            sb.append(". Most expensive: ");
            sb.append(r.getName()).append(" — ");
            sb.append(r.getCostPerUnit() == null ? "no rate" : r.getCostPerUnit().toPlainString());
            if (r.getUnit() != null) sb.append("/").append(r.getUnit());
            sb.append(".");
        });
        return sb.toString();
    }

    private static String buildEmptySummary(String typeCode, String codeFilter, String nameFilter) {
        StringBuilder sb = new StringBuilder("No catalogue rows match");
        if (typeCode != null) sb.append(" type=").append(typeCode);
        if (codeFilter != null) sb.append(" code=").append(codeFilter);
        if (nameFilter != null) sb.append(" name~").append(nameFilter);
        sb.append(".");
        return sb.toString();
    }
}
