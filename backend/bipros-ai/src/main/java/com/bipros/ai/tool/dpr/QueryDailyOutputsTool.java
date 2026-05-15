package com.bipros.ai.tool.dpr;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyActivityResourceOutput;
import com.bipros.project.domain.repository.DailyActivityResourceOutputRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.repository.ResourceRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Daily activity × resource output rows — the productivity table that pairs
 * an activity, a resource, a date, and the qty / hours / days actually worked.
 * Supports {@code group_by ∈ {date, activity, resource, none}} for rollups.
 *
 * <p>Use cases: "How many hours did the masonry crew put in last week?",
 * "Daily output for activity X over the past month", "Which resources logged
 * any work on April 10?".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryDailyOutputsTool implements Tool {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 1000;

  private final DailyActivityResourceOutputRepository outputRepository;
  private final ActivityRepository activityRepository;
  private final ResourceRepository resourceRepository;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "query_daily_outputs";
  }

  @Override
  public String description() {
    return "Query the daily (activity × resource) output table — the productivity "
        + "ground truth (qty done, hours worked, days-equivalent) per resource per activity per "
        + "day. Filter by date range, activity (code OR id), or resource (code OR id). "
        + "Group results by date, activity, resource, or none (raw rows). Use this when the "
        + "user asks about productivity, hours logged, daily resource output, or wants to "
        + "compare what crews / equipment actually delivered vs the plan. For productivity "
        + "vs the budgeted norm, use compare_actual_vs_norm instead. Project-scoped.";
  }

  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = objectMapper.createObjectNode();
    props.set(
        "date_from",
        objectMapper.createObjectNode().put("type", "string").put("format", "date").put("description", "ISO date. Default: 30 days before date_to."));
    props.set(
        "date_to",
        objectMapper.createObjectNode().put("type", "string").put("format", "date").put("description", "ISO date. Default: today."));
    props.set(
        "activity_code",
        objectMapper.createObjectNode().put("type", "string").put("description", "Activity short code."));
    props.set(
        "activity_id",
        objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
    props.set(
        "resource_code",
        objectMapper.createObjectNode().put("type", "string").put("description", "Resource code."));
    props.set(
        "resource_id",
        objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
    ArrayNode groupEnum = objectMapper.createArrayNode();
    groupEnum.add("date");
    groupEnum.add("activity");
    groupEnum.add("resource");
    groupEnum.add("none");
    ObjectNode gNode = objectMapper.createObjectNode();
    gNode.put("type", "string");
    gNode.set("enum", groupEnum);
    gNode.put("default", "none");
    gNode.put(
        "description",
        "How to roll up the rows. \"none\" returns raw rows. \"date\"/\"activity\"/\"resource\" "
            + "return aggregates with totals.");
    props.set("group_by", gNode);
    props.set(
        "limit",
        objectMapper.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT).put("default", DEFAULT_LIMIT));
    schema.set("properties", props);
    return schema;
  }

  @Override
  @Transactional(readOnly = true)
  public ToolResult execute(JsonNode input, AiContext ctx) {
    UUID projectId = ctx.projectId();
    if (projectId == null) {
      return ToolResult.error("query_daily_outputs needs a project in scope.");
    }
    if (!"ADMIN".equals(ctx.role())
        && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
      throw new AccessDeniedException("project not in user scope");
    }

    LocalDate dateTo = parseDate(input.path("date_to").asText(null), LocalDate.now());
    LocalDate dateFrom = parseDate(input.path("date_from").asText(null), dateTo.minusDays(30));
    if (dateFrom.isAfter(dateTo)) {
      LocalDate tmp = dateFrom;
      dateFrom = dateTo;
      dateTo = tmp;
    }
    int limit = Math.max(1, Math.min(MAX_LIMIT, input.path("limit").asInt(DEFAULT_LIMIT)));
    String groupBy = input.path("group_by").asText("none").toLowerCase();

    UUID activityFilter = resolveActivity(input, projectId);
    UUID resourceFilter = resolveResource(input);

    List<DailyActivityResourceOutput> base =
        outputRepository.findByProjectIdAndOutputDateBetweenOrderByOutputDateDescIdAsc(
            projectId, dateFrom, dateTo);

    List<DailyActivityResourceOutput> filtered = new ArrayList<>();
    Set<UUID> activityIds = new HashSet<>();
    Set<UUID> resourceIds = new HashSet<>();
    for (DailyActivityResourceOutput o : base) {
      if (activityFilter != null && !activityFilter.equals(o.getActivityId())) continue;
      if (resourceFilter != null && !resourceFilter.equals(o.getResourceId())) continue;
      filtered.add(o);
      if (o.getActivityId() != null) activityIds.add(o.getActivityId());
      if (o.getResourceId() != null) resourceIds.add(o.getResourceId());
    }

    Map<UUID, Activity> activityById = new HashMap<>();
    activityRepository.findAllById(activityIds).forEach(a -> activityById.put(a.getId(), a));
    Map<UUID, Resource> resourceById = new HashMap<>();
    resourceRepository.findAllById(resourceIds).forEach(r -> resourceById.put(r.getId(), r));

    BigDecimal totalQty = BigDecimal.ZERO;
    double totalHours = 0;
    double totalDays = 0;
    for (DailyActivityResourceOutput o : filtered) {
      if (o.getQtyExecuted() != null) totalQty = totalQty.add(o.getQtyExecuted());
      if (o.getHoursWorked() != null) totalHours += o.getHoursWorked();
      if (o.getDaysWorked() != null) totalDays += o.getDaysWorked();
    }

    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.put("date_from", dateFrom.toString());
    wrapper.put("date_to", dateTo.toString());
    wrapper.put("matched", filtered.size());
    wrapper.put("group_by", groupBy);
    wrapper.put("total_qty_executed", totalQty.doubleValue());
    wrapper.put("total_hours_worked", totalHours);
    wrapper.put("total_days_worked", totalDays);

    ArrayNode rows = objectMapper.createArrayNode();
    if ("none".equals(groupBy)) {
      List<DailyActivityResourceOutput> capped =
          filtered.size() > limit ? filtered.subList(0, limit) : filtered;
      for (DailyActivityResourceOutput o : capped) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("output_id", o.getId() == null ? null : o.getId().toString());
        n.put("output_date", o.getOutputDate() == null ? null : o.getOutputDate().toString());
        Activity a = activityById.get(o.getActivityId());
        n.put("activity_id", o.getActivityId() == null ? null : o.getActivityId().toString());
        n.put("activity_code", a != null ? a.getCode() : null);
        n.put("activity_name", a != null ? a.getName() : null);
        Resource r = resourceById.get(o.getResourceId());
        n.put("resource_id", o.getResourceId() == null ? null : o.getResourceId().toString());
        n.put("resource_code", r != null ? r.getCode() : null);
        n.put("resource_name", r != null ? r.getName() : null);
        n.put("qty_executed", o.getQtyExecuted() == null ? null : o.getQtyExecuted().doubleValue());
        n.put("unit", o.getUnit());
        n.put("hours_worked", o.getHoursWorked());
        n.put("days_worked", o.getDaysWorked());
        n.put("actual_per_day",
            o.getQtyExecuted() != null && o.getDaysWorked() != null && o.getDaysWorked() > 0
                ? o.getQtyExecuted().doubleValue() / o.getDaysWorked()
                : null);
        n.put("remarks", o.getRemarks());
        rows.add(n);
      }
      wrapper.put("returned", rows.size());
    } else {
      Map<String, Bucket> buckets = new LinkedHashMap<>();
      for (DailyActivityResourceOutput o : filtered) {
        String key;
        switch (groupBy) {
          case "date" -> key = o.getOutputDate() == null ? "?" : o.getOutputDate().toString();
          case "activity" -> {
            Activity a = activityById.get(o.getActivityId());
            key = a != null ? a.getCode() + " — " + a.getName() : "?";
          }
          case "resource" -> {
            Resource r = resourceById.get(o.getResourceId());
            key = r != null ? r.getCode() + " — " + r.getName() : "?";
          }
          default -> key = "?";
        }
        buckets.computeIfAbsent(key, k -> new Bucket(k)).add(o);
      }
      List<Bucket> sorted = new ArrayList<>(buckets.values());
      sorted.sort(Comparator.comparingDouble(Bucket::qty).reversed());
      for (Bucket b : sorted.subList(0, Math.min(limit, sorted.size()))) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put(groupBy, b.key);
        n.put("rows", b.count);
        n.put("qty_executed", b.qty);
        n.put("hours_worked", b.hours);
        n.put("days_worked", b.days);
        rows.add(n);
      }
      wrapper.put("buckets", buckets.size());
    }
    wrapper.set("rows", rows);

    Map<String, List<UUID>> links = new HashMap<>();
    if (activityFilter != null) links.put("activity", List.of(activityFilter));
    if (resourceFilter != null) links.put("resource", List.of(resourceFilter));
    ToolResult.attachLinks(wrapper, links);

    String summary =
        String.format(
            "%d output rows between %s and %s — total qty %s, total %.1f hours / %.1f days",
            filtered.size(), dateFrom, dateTo, totalQty, totalHours, totalDays);
    return ToolResult.ok(summary, wrapper);
  }

  private UUID resolveActivity(JsonNode input, UUID projectId) {
    String idStr = orNull(input.path("activity_id").asText(null));
    if (idStr != null) {
      try {
        UUID id = UUID.fromString(idStr);
        return activityRepository.findById(id).filter(a -> projectId.equals(a.getProjectId())).map(Activity::getId).orElse(null);
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    String code = orNull(input.path("activity_code").asText(null));
    if (code != null) {
      return activityRepository.findByProjectIdAndCode(projectId, code).map(Activity::getId).orElse(null);
    }
    return null;
  }

  private UUID resolveResource(JsonNode input) {
    String idStr = orNull(input.path("resource_id").asText(null));
    if (idStr != null) {
      try {
        return UUID.fromString(idStr);
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    String code = orNull(input.path("resource_code").asText(null));
    if (code != null) {
      Optional<Resource> r = resourceRepository.findByCode(code);
      if (r.isPresent()) return r.get().getId();
    }
    return null;
  }

  private static LocalDate parseDate(String raw, LocalDate fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  private static String orNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static class Bucket {
    final String key;
    int count = 0;
    double qty = 0;
    double hours = 0;
    double days = 0;

    Bucket(String key) {
      this.key = key;
    }

    void add(DailyActivityResourceOutput o) {
      count++;
      if (o.getQtyExecuted() != null) qty += o.getQtyExecuted().doubleValue();
      if (o.getHoursWorked() != null) hours += o.getHoursWorked();
      if (o.getDaysWorked() != null) days += o.getDaysWorked();
    }

    double qty() {
      return qty;
    }
  }

  @Override
  public java.util.Set<String> allowedRoles() {
    return java.util.Set.of(
            "PROJECT_MANAGER", "PORTFOLIO_MANAGER",
            "SITE_MANAGER", "PROJECT_ENGINEER", "QC_MANAGER", "QA_QC_ENGINEER",
            "BIM_DATA_COORDINATOR",
            "SITE_ENGINEER", "RESOURCE_MANAGER", "SCHEDULER",
            "EXECUTIVE_VIEWER"
    );
  }
}
