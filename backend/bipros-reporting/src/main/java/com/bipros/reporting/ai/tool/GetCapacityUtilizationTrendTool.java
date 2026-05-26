package com.bipros.reporting.ai.tool;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.reporting.application.dto.CapacityUtilizationAggregateReport;
import com.bipros.reporting.application.dto.CapacityUtilizationAggregateReport.Bucket;
import com.bipros.reporting.application.service.CapacityUtilizationReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Multi-period trend wrapper for {@link CapacityUtilizationReportService#aggregate}. Slices a
 * [from_date, to_date] window into WEEKLY (ISO weeks, Mon-Sun) or MONTHLY (calendar month)
 * buckets and returns the Manpower + Equipment Sections per bucket — so the AI can answer
 * "show me utilization week-by-week" or "compare June vs July productivity" without making N
 * separate get_capacity_utilization calls.
 *
 * <p>Each bucket carries the SAME shape as {@code get_capacity_utilization} sections (per-role
 * rows, hidden_side_notes, etc.), but cumulative-over-bucket only (no Day/Month/Cumulative
 * triple — that's per-row and would explode the payload).
 *
 * <p>Window length is capped to keep the JSON payload tractable: WEEKLY ≤ 90 days
 * (~13 buckets), MONTHLY ≤ 24 months. Exceeding the cap returns a clean error so the LLM can
 * narrow the request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetCapacityUtilizationTrendTool implements Tool {

    private static final int MAX_WEEKLY_DAYS = 90;
    private static final int MAX_MONTHLY_MONTHS = 24;

    private final CapacityUtilizationReportService service;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "get_capacity_utilization_trend";
    }

    @Override
    public String description() {
        return "Multi-period capacity-utilization TREND — week-by-week or month-by-month "
                + "buckets across a [from_date, to_date] window. Use this for: "
                + "'show me the weekly utilization trend for May', 'compare June vs July "
                + "productivity for the project', 'week-by-week Helper allocated qty', "
                + "'monthly equipment efficiency over the last 6 months'. "
                + "Returns buckets[] — one entry per WEEKLY (ISO weeks, Mon-Sun) or MONTHLY "
                + "(calendar month) slice, each carrying {from, to, label, manpower, equipment} "
                + "Sections with the same per-role rows + hidden_side_notes shape as "
                + "get_capacity_utilization (per-DPR allocator + sub-contractor netting already "
                + "applied). Bucket values are cumulative-over-bucket. "
                + "DIFFERENT FROM get_capacity_utilization: that tool returns Day / Month / "
                + "Cumulative buckets for ONE window; this tool returns N buckets ACROSS the "
                + "window so the user can see a time-series. Use this for trend questions, "
                + "get_capacity_utilization for snapshot questions. "
                + "Inputs: period_type ∈ {WEEKLY, MONTHLY} (default MONTHLY), from_date "
                + "(default = year start), to_date (default = today), supervisor_user_id "
                + "(optional User UUID — call list_project_supervisors first to resolve names), "
                + "group_by ∈ {ROLE, RESOURCE_TYPE} (default ROLE). "
                + "WINDOW LIMITS to keep the payload tractable: WEEKLY ≤ 90 days (about 13 "
                + "buckets), MONTHLY ≤ 24 months. Exceeding the cap returns an error so the "
                + "LLM can narrow the request. "
                + "RESPONSE FORMAT: lead with the trend shape ('Helper allocated qty grew from "
                + "X in W18 to Y in W22'), call out hidden_side_notes per bucket where present, "
                + "and never quote a single bucket as if it's the project total. "
                + "Requires a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();

        ObjectNode periodType = mapper.createObjectNode();
        periodType.put("type", "string");
        ArrayNode periodEnum = mapper.createArrayNode();
        periodEnum.add("WEEKLY");
        periodEnum.add("MONTHLY");
        periodType.set("enum", periodEnum);
        periodType.put("default", "MONTHLY");
        periodType.put("description",
                "WEEKLY (ISO weeks Mon-Sun) or MONTHLY (calendar month) buckets.");
        props.set("period_type", periodType);

        props.set("from_date", str("ISO date (yyyy-MM-dd). Default: year start."));
        props.set("to_date", str("ISO date (yyyy-MM-dd). Default: today."));
        props.set("supervisor_user_id",
                str("Optional User UUID — restricts every bucket to DPRs filed by this "
                        + "supervisor. Call list_project_supervisors first to resolve a name."));

        ObjectNode groupBy = mapper.createObjectNode();
        groupBy.put("type", "string");
        ArrayNode gbEnum = mapper.createArrayNode();
        gbEnum.add("ROLE");
        gbEnum.add("RESOURCE_TYPE");
        groupBy.set("enum", gbEnum);
        groupBy.put("default", "ROLE");
        props.set("group_by", groupBy);

        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error(
                    "get_capacity_utilization_trend requires a project in scope.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null
                        || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        LocalDate today = LocalDate.now();
        LocalDate to = parseDate(input, "to_date", today);
        LocalDate from = parseDate(input, "from_date", to.withDayOfYear(1));
        if (from.isAfter(to)) {
            LocalDate t = from; from = to; to = t;
        }
        String periodType = orDefault(text(input, "period_type"), "MONTHLY").toUpperCase();
        if (!"WEEKLY".equals(periodType) && !"MONTHLY".equals(periodType)) {
            periodType = "MONTHLY";
        }
        String groupBy = orDefault(text(input, "group_by"), "ROLE").toUpperCase();
        UUID supervisorUserId = parseUuid(text(input, "supervisor_user_id"));

        // Window-length guardrail.
        if ("WEEKLY".equals(periodType)) {
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            if (days > MAX_WEEKLY_DAYS) {
                return ToolResult.error(
                        "WEEKLY period_type requires a window of at most " + MAX_WEEKLY_DAYS
                                + " days (about 13 buckets). Requested window is " + days
                                + " days. Narrow the from_date / to_date or switch to MONTHLY.");
            }
        } else {
            long months = ChronoUnit.MONTHS.between(
                    from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1;
            if (months > MAX_MONTHLY_MONTHS) {
                return ToolResult.error(
                        "MONTHLY period_type requires a window of at most "
                                + MAX_MONTHLY_MONTHS + " months. Requested window is "
                                + months + " months. Narrow the from_date / to_date.");
            }
        }

        try {
            CapacityUtilizationAggregateReport report = service.aggregate(
                    projectId, periodType, from, to, groupBy, supervisorUserId);
            ObjectNode out = mapper.createObjectNode();
            out.put("project_id", projectId.toString());
            out.put("period_type", report.periodType());
            out.put("group_by", report.groupBy());
            out.put("from_date", report.fromDate().toString());
            out.put("to_date", report.toDate().toString());
            if (supervisorUserId != null) {
                out.put("supervisor_user_id", supervisorUserId.toString());
            }
            ArrayNode bucketsJson = mapper.createArrayNode();
            int totalBuckets = 0;
            if (report.buckets() != null) {
                for (Bucket b : report.buckets()) {
                    bucketsJson.add(renderBucket(b));
                    totalBuckets++;
                }
            }
            out.set("buckets", bucketsJson);
            out.put("bucket_count", totalBuckets);

            String summary = String.format(
                    "Capacity utilization trend %s..%s: %d %s bucket%s%s",
                    from, to, totalBuckets, report.periodType(),
                    totalBuckets == 1 ? "" : "s",
                    supervisorUserId != null
                            ? " (supervisor-scoped)" : " (project-wide)");
            return ToolResult.ok(summary, out);
        } catch (Exception e) {
            log.warn("get_capacity_utilization_trend failed", e);
            return ToolResult.error(
                    "Failed to compute capacity utilization trend: " + e.getMessage());
        }
    }

    private ObjectNode renderBucket(Bucket b) {
        ObjectNode n = mapper.createObjectNode();
        if (b.from() != null) n.put("from", b.from().toString());
        if (b.to() != null) n.put("to", b.to().toString());
        if (b.label() != null) n.put("label", b.label());
        if (b.manpower() != null) n.set("manpower", mapper.valueToTree(b.manpower()));
        if (b.equipment() != null) n.set("equipment", mapper.valueToTree(b.equipment()));
        return n;
    }

    // ──────────────────────────────────────────────────────── helpers

    private ObjectNode str(String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }

    private static String text(JsonNode in, String field) {
        if (in == null) return null;
        JsonNode n = in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    private static LocalDate parseDate(JsonNode in, String field, LocalDate fallback) {
        String s = text(in, field);
        if (s == null) return fallback;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return fallback;
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
