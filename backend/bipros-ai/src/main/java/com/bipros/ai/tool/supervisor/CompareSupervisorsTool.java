package com.bipros.ai.tool.supervisor;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.query.ResourceContextFacade;
import com.bipros.ai.query.SupervisorPerformance;
import com.bipros.ai.query.SupervisorPerformanceCalculator;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Side-by-side comparison of multiple supervisors on cost, schedule, EVM
 * (CPI / SPI), and DPR cadence. Reuses {@link SupervisorPerformanceCalculator}
 * so the metrics match the single-supervisor {@code supervisor} tool exactly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompareSupervisorsTool implements Tool {

  private static final int MAX_SUPERVISORS = 6;

  private final ResourceContextFacade facade;
  private final SupervisorPerformanceCalculator calculator;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "compare_supervisors";
  }

  @Override
  public String description() {
    return "**COST / EVM / DPR-cadence comparison ONLY — uses LEGACY Resource UUIDs, "
        + "not User UUIDs.** Compare two to six supervisors side-by-side for one project over "
        + "a date window. Returns one ranked row per supervisor with: supervised activity "
        + "count + status (delayed / in-progress / completed), planned & actual cost + "
        + "variance percent, EVM (BAC/PV/EV/AC, CPI, SPI), DPR cadence, qty executed, team "
        + "hours/days. Identify supervisors by supervisor_resource_ids (legacy Resource UUIDs "
        + "— NOT User UUIDs) or supervisor_names (resolved fuzzily). rank_by: cpi "
        + "(default, higher better), spi (higher), cost_variance_pct (lower better), "
        + "qty_executed (higher), dpr_count (higher). "
        + "**DO NOT USE THIS TOOL FOR:** capacity utilization, manpower / equipment "
        + "efficiency, per-role allocated qty, productivity vs norm, activity drill-down, "
        + "best-supervisor-per-trade, suppressed / hidden-side counts, or any post-allocator "
        + "metric. For all of those, call `get_supervisor_performance` instead — that "
        + "tool uses canonical RBAC User UUIDs (resolve names via list_project_supervisors), "
        + "applies the per-DPR allocator, nets sub-contractor qty, and returns server-computed "
        + "best-per-trade rankings plus full activity drill-down. "
        + "For a single supervisor on cost/EVM only, use the `supervisor` tool instead. "
        + "Project-scoped.";
  }

  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = objectMapper.createObjectNode();

    ObjectNode ids = objectMapper.createObjectNode();
    ids.put("type", "array");
    ids.put("description",
        "UUIDs of supervisor Resource records (2–" + MAX_SUPERVISORS + "). Preferred over names.");
    ids.set("items",
        objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
    ids.put("minItems", 2);
    ids.put("maxItems", MAX_SUPERVISORS);
    props.set("supervisor_resource_ids", ids);

    ObjectNode names = objectMapper.createObjectNode();
    names.put("type", "array");
    names.put("description",
        "Free-text names; each is resolved via fuzzy match. Used when supervisor_resource_ids is empty.");
    names.set("items", objectMapper.createObjectNode().put("type", "string"));
    props.set("supervisor_names", names);

    props.set("date_from",
        objectMapper.createObjectNode().put("type", "string").put("format", "date")
            .put("description", "Default: 30 days before date_to."));
    props.set("date_to",
        objectMapper.createObjectNode().put("type", "string").put("format", "date")
            .put("description", "Default: today."));

    ArrayNode rankEnum = objectMapper.createArrayNode();
    rankEnum.add("cpi");
    rankEnum.add("spi");
    rankEnum.add("cost_variance_pct");
    rankEnum.add("qty_executed");
    rankEnum.add("dpr_count");
    ObjectNode rank = objectMapper.createObjectNode();
    rank.put("type", "string");
    rank.set("enum", rankEnum);
    rank.put("default", "cpi");
    rank.put("description",
        "Metric to sort rows by. cpi/spi/qty/dpr → higher is better; cost_variance_pct → lower is better (less over-budget).");
    props.set("rank_by", rank);

    schema.set("properties", props);
    return schema;
  }

  @Override
  @Transactional(readOnly = true)
  public ToolResult execute(JsonNode input, AiContext ctx) {
    UUID projectId = ctx.projectId();
    if (projectId == null) {
      return ToolResult.error("compare_supervisors needs a project in scope.");
    }
    if (!"ADMIN".equals(ctx.role())
        && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
      throw new AccessDeniedException("project not in user scope");
    }

    LocalDate dateTo = parseDate(input.path("date_to").asText(null), LocalDate.now());
    LocalDate dateFrom = parseDate(input.path("date_from").asText(null), dateTo.minusDays(30));
    if (dateFrom.isAfter(dateTo)) {
      LocalDate t = dateFrom;
      dateFrom = dateTo;
      dateTo = t;
    }
    String rankBy = input.path("rank_by").asText("cpi").toLowerCase();

    LinkedHashSet<UUID> resolved = new LinkedHashSet<>();
    JsonNode idsNode = input.path("supervisor_resource_ids");
    if (idsNode.isArray()) {
      for (JsonNode n : idsNode) {
        try {
          resolved.add(UUID.fromString(n.asText().trim()));
        } catch (Exception ignored) {
          // skip invalid
        }
      }
    }
    JsonNode namesNode = input.path("supervisor_names");
    if (namesNode.isArray()) {
      for (JsonNode n : namesNode) {
        String raw = n.asText(null);
        if (raw == null || raw.isBlank()) continue;
        Optional<UUID> id = facade.resolveResourceId(raw.trim());
        id.ifPresent(resolved::add);
      }
    }
    if (resolved.size() < 2) {
      return ToolResult.error(
          "Provide at least 2 supervisor_resource_ids or supervisor_names that resolve to distinct supervisors.");
    }
    if (resolved.size() > MAX_SUPERVISORS) {
      return ToolResult.error(
          "compare_supervisors accepts at most " + MAX_SUPERVISORS + " supervisors per call.");
    }

    List<SupervisorPerformance> rows = new ArrayList<>();
    for (UUID id : resolved) {
      rows.add(calculator.compute(projectId, id, dateFrom, dateTo));
    }

    rows.sort(comparatorFor(rankBy));

    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.put("project_id", projectId.toString());
    wrapper.put("date_from", dateFrom.toString());
    wrapper.put("date_to", dateTo.toString());
    wrapper.put("ranked_by", rankBy);
    wrapper.put("supervisor_count", rows.size());

    ArrayNode rowsNode = objectMapper.createArrayNode();
    for (SupervisorPerformance p : rows) {
      rowsNode.add(renderRow(p));
    }
    wrapper.set("rows", rowsNode);

    String summary = buildSummary(rows, rankBy);
    wrapper.put("summary", summary);

    java.util.Map<String, List<UUID>> links = new java.util.HashMap<>();
    List<UUID> ids = new ArrayList<>();
    for (SupervisorPerformance p : rows) {
      if (p.supervisorResourceId() != null) ids.add(p.supervisorResourceId());
    }
    if (!ids.isEmpty()) links.put("supervisors", ids);
    ToolResult.attachLinks(wrapper, links);

    return ToolResult.ok(summary, wrapper);
  }

  private ObjectNode renderRow(SupervisorPerformance p) {
    ObjectNode n = objectMapper.createObjectNode();
    if (p.supervisorResourceId() != null) n.put("supervisor_resource_id", p.supervisorResourceId().toString());
    n.put("supervisor_code", p.supervisorCode());
    n.put("supervisor_name", p.supervisorName());
    n.put("team_size", p.teamSize());

    n.put("supervised_activity_count", p.activityScope().total());
    n.put("not_started", p.activityScope().notStarted());
    n.put("in_progress", p.activityScope().inProgress());
    n.put("completed", p.activityScope().completed());
    n.put("delayed", p.activityScope().delayed());
    if (p.activityScope().avgPctComplete() != null) n.put("avg_pct_complete", p.activityScope().avgPctComplete());

    n.put("planned_cost", p.costRollup().planned().toPlainString());
    n.put("actual_cost", p.costRollup().actual().toPlainString());
    n.put("cost_variance", p.costRollup().variance().toPlainString());
    if (p.costRollup().variancePct() != null) n.put("cost_variance_pct", p.costRollup().variancePct());
    if (!p.costRollup().overriddenFormulaCodes().isEmpty()) {
      ArrayNode overrides = objectMapper.createArrayNode();
      for (String code : p.costRollup().overriddenFormulaCodes()) overrides.add(code);
      n.set("formula_overrides", overrides);
    }

    n.put("bac", p.evmRollup().bac().toPlainString());
    n.put("pv", p.evmRollup().pv().toPlainString());
    n.put("ev", p.evmRollup().ev().toPlainString());
    n.put("ac", p.evmRollup().ac().toPlainString());
    if (p.evmRollup().cpi() != null) n.put("cpi", p.evmRollup().cpi());
    if (p.evmRollup().spi() != null) n.put("spi", p.evmRollup().spi());
    n.put("ev_source", p.evmRollup().evSource());

    n.put("dpr_count", p.dprRollup().dprCount());
    n.put("qty_executed", p.dprRollup().totalQtyExecuted().toPlainString());
    n.put("hours_worked", p.dprRollup().totalHoursWorkedByTeam().toPlainString());
    n.put("days_worked", p.dprRollup().totalDaysWorkedByTeam().toPlainString());
    return n;
  }

  /**
   * Higher-is-better metrics sort descending; cost_variance_pct sorts ascending
   * (smaller variance % = better). Nulls sink to the bottom so supervisors with
   * no data don't poison the leaderboard.
   */
  private Comparator<SupervisorPerformance> comparatorFor(String rankBy) {
    return switch (rankBy) {
      case "spi" -> nullsLast(Comparator.comparing((SupervisorPerformance p) -> p.evmRollup().spi()).reversed());
      case "cost_variance_pct" -> nullsLast(Comparator.comparing(p -> p.costRollup().variancePct()));
      case "qty_executed" -> Comparator.comparing(
              (SupervisorPerformance p) -> p.dprRollup().totalQtyExecuted())
          .reversed();
      case "dpr_count" -> Comparator.comparingInt(
              (SupervisorPerformance p) -> p.dprRollup().dprCount())
          .reversed();
      default -> nullsLast(Comparator.comparing((SupervisorPerformance p) -> p.evmRollup().cpi()).reversed());
    };
  }

  private static <T, U extends Comparable<? super U>> Comparator<T> nullsLast(Comparator<T> base) {
    return Comparator.nullsLast(base);
  }

  private String buildSummary(List<SupervisorPerformance> rows, String rankBy) {
    if (rows.isEmpty()) return "no rows";
    SupervisorPerformance best = rows.get(0);
    SupervisorPerformance worst = rows.get(rows.size() - 1);
    String metric = metricLabel(rankBy);
    String bestVal = metricValue(best, rankBy);
    String worstVal = metricValue(worst, rankBy);
    return String.format(
        "Compared %d supervisors over %s..%s on %s — leader: %s (%s), trailing: %s (%s).",
        rows.size(),
        best.dateFrom(),
        best.dateTo(),
        metric,
        nameOf(best),
        bestVal,
        nameOf(worst),
        worstVal);
  }

  private static String metricLabel(String rankBy) {
    return switch (rankBy) {
      case "spi" -> "SPI";
      case "cost_variance_pct" -> "cost variance %";
      case "qty_executed" -> "qty executed";
      case "dpr_count" -> "DPR count";
      default -> "CPI";
    };
  }

  private static String metricValue(SupervisorPerformance p, String rankBy) {
    Double d;
    BigDecimal bd;
    Integer i;
    switch (rankBy) {
      case "spi":
        d = p.evmRollup().spi();
        return d == null ? "n/a" : String.format("%.2f", d);
      case "cost_variance_pct":
        d = p.costRollup().variancePct();
        return d == null ? "n/a" : String.format("%.1f%%", d);
      case "qty_executed":
        bd = p.dprRollup().totalQtyExecuted();
        return bd == null ? "n/a" : bd.toPlainString();
      case "dpr_count":
        i = p.dprRollup().dprCount();
        return String.valueOf(i);
      default:
        d = p.evmRollup().cpi();
        return d == null ? "n/a" : String.format("%.2f", d);
    }
  }

  private static String nameOf(SupervisorPerformance p) {
    if (p.supervisorName() != null) return p.supervisorName();
    if (p.supervisorCode() != null) return p.supervisorCode();
    return p.supervisorResourceId() == null ? "unknown" : p.supervisorResourceId().toString();
  }

  private static LocalDate parseDate(String raw, LocalDate fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception e) {
      return fallback;
    }
  }
}
