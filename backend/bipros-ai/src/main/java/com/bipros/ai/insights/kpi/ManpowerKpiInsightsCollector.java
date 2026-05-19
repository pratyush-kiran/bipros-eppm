package com.bipros.ai.insights.kpi;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.insights.InsightDataCollector;
import com.bipros.ai.insights.charts.EChartsOptions;
import com.bipros.ai.insights.dto.ChartSpec;
import com.bipros.project.domain.model.DailyActivityResourceOutput;
import com.bipros.project.domain.repository.DailyActivityResourceOutputRepository;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Collects manpower KPI signals for the LLM. Reads raw DAR + ProductivityNorm + Resource
 * directly (rather than calling {@code ManpowerKpiService} in {@code bipros-api}, which
 * isn't on this module's classpath) — same data shape, lighter aggregation.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManpowerKpiInsightsCollector implements InsightDataCollector {

  private static final String LABOR_TYPE_CODE = "MANPOWER";
  private static final int DEFAULT_LOOKBACK_DAYS = 30;

  private final DailyActivityResourceOutputRepository darRepository;
  private final ResourceRepository resourceRepository;
  private final ActivityRepository activityRepository;
  private final ProductivityNormRepository productivityNormRepository;
  private final ObjectMapper objectMapper;

  @Override
  public JsonNode collect(UUID projectId) {
    LocalDate today = LocalDate.now();
    LocalDate from = today.minusDays(DEFAULT_LOOKBACK_DAYS);

    List<DailyActivityResourceOutput> dar = darRepository
        .findByProjectIdAndOutputDateBetweenOrderByOutputDateDescIdAsc(projectId, from, today);

    Map<UUID, Resource> resourcesById = resourceRepository
        .findAllById(dar.stream().map(DailyActivityResourceOutput::getResourceId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
        .stream().collect(Collectors.toMap(Resource::getId, r -> r, (a, b) -> a));

    List<DailyActivityResourceOutput> labour = dar.stream()
        .filter(d -> d.getResourceId() != null
            && resourcesById.containsKey(d.getResourceId())
            && resourcesById.get(d.getResourceId()).getResourceType() != null
            && LABOR_TYPE_CODE.equalsIgnoreCase(
                resourcesById.get(d.getResourceId()).getResourceType().getCode()))
        .toList();

    ObjectNode root = objectMapper.createObjectNode();
    root.put("projectId", projectId.toString());
    root.put("from", from.toString());
    root.put("to", today.toString());
    root.put("darRows", labour.size());

    double totalHours = labour.stream()
        .map(DailyActivityResourceOutput::getHoursWorked)
        .filter(java.util.Objects::nonNull)
        .mapToDouble(Double::doubleValue)
        .sum();
    double totalQty = labour.stream()
        .map(DailyActivityResourceOutput::getQtyExecuted)
        .filter(java.util.Objects::nonNull)
        .map(BigDecimal::doubleValue)
        .reduce(0d, Double::sum);
    root.put("totalLabourHours", totalHours);
    root.put("totalQtyExecuted", totalQty);

    Map<UUID, double[]> byActivity = new HashMap<>(); // [hours, qty]
    for (DailyActivityResourceOutput d : labour) {
      if (d.getActivityId() == null) continue;
      double[] acc = byActivity.computeIfAbsent(d.getActivityId(), k -> new double[2]);
      if (d.getHoursWorked() != null) acc[0] += d.getHoursWorked();
      if (d.getQtyExecuted() != null) acc[1] += d.getQtyExecuted().doubleValue();
    }
    Map<UUID, Activity> activitiesById = activityRepository.findAllById(byActivity.keySet()).stream()
        .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));

    ArrayNode activities = root.putArray("activities");
    byActivity.entrySet().stream()
        .sorted((x, y) -> Double.compare(y.getValue()[1], x.getValue()[1]))
        .limit(10)
        .forEach(e -> {
          Activity a = activitiesById.get(e.getKey());
          double hours = e.getValue()[0];
          double qty = e.getValue()[1];
          double daysEq = hours > 0 ? hours / 8d : 0d;
          double actualPerManPerDay = daysEq > 0 ? qty / daysEq : 0d;

          double norm = 0d;
          if (a != null && a.getName() != null) {
            norm = productivityNormRepository.findByActivityNameIgnoreCase(a.getName()).stream()
                .map(ProductivityNorm::getOutputPerManPerDay)
                .filter(java.util.Objects::nonNull)
                .map(BigDecimal::doubleValue)
                .findFirst()
                .orElse(0d);
          }
          double factor = norm > 0 ? actualPerManPerDay / norm : 0d;

          ObjectNode node = objectMapper.createObjectNode();
          node.put("activityName", a != null ? a.getName() : "Unknown");
          node.put("totalHours", hours);
          node.put("totalQty", qty);
          node.put("actualOutputPerManPerDay", actualPerManPerDay);
          node.put("normOutputPerManPerDay", norm);
          node.put("productivityFactor", factor);
          activities.add(node);
        });

    return root;
  }

  @Override
  public String tabKey() {
    return "manpower-kpi";
  }

  @Override
  public String promptInstructions() {
    return "Produce role-specific manpower insights. Identify activities running below their "
        + "ProductivityNorm (factor < 0.8), trends in labour-hour utilisation, and crews with "
        + "consistently low or high output. Recommend gang reallocation or norm review where "
        + "actual diverges materially from the norm. Keep recommendations concrete and tied to "
        + "specific activity names.";
  }

  @Override
  public List<ChartSpec> charts(UUID projectId) {
    if (projectId == null) {
      return List.of(
          new ChartSpec("manpower-top-activities", "Top Activities by Labour Hours", "bar", null, null),
          new ChartSpec("manpower-productivity-factor", "Productivity Factor by Activity", "bar", null, null)
      );
    }

    JsonNode root = collect(projectId);
    JsonNode activities = root.get("activities");
    if (activities == null || !activities.isArray()) return List.of();

    Map<String, BigDecimal> hoursByActivity = new LinkedHashMap<>();
    Map<String, BigDecimal> factorByActivity = new LinkedHashMap<>();
    activities.forEach(node -> {
      String name = node.get("activityName").asText();
      hoursByActivity.put(name, BigDecimal.valueOf(node.get("totalHours").asDouble()));
      factorByActivity.put(name, BigDecimal.valueOf(node.get("productivityFactor").asDouble()));
    });

    return List.of(
        new ChartSpec("manpower-top-activities", "Top Activities by Labour Hours", "bar",
            EChartsOptions.bar(objectMapper,
                new java.util.ArrayList<>(hoursByActivity.keySet()),
                "Hours",
                new java.util.ArrayList<>(hoursByActivity.values())),
            "Top 10 activities by total labour hours in the last 30 days"),
        new ChartSpec("manpower-productivity-factor", "Productivity Factor (actual / norm)", "bar",
            EChartsOptions.bar(objectMapper,
                new java.util.ArrayList<>(factorByActivity.keySet()),
                "Factor",
                new java.util.ArrayList<>(factorByActivity.values())),
            "1.0 = on norm; < 0.8 = under-performing")
    );
  }
}
