package com.bipros.ai.insights.project;

import com.bipros.ai.insights.InsightDataCollector;
import com.bipros.ai.insights.charts.EChartsOptions;
import com.bipros.ai.insights.dto.ChartSpec;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DprInsightsCollector implements InsightDataCollector {

    private final DailyProgressReportRepository dprRepository;
    private final ObjectMapper objectMapper;

    @Override
    public JsonNode collect(UUID projectId) {
        List<DailyProgressReport> rows = dprRepository
                .findByProjectIdAndApprovalStatusOrderByReportDateAscIdAsc(
                        projectId, DprApprovalStatus.APPROVED);

        Map<String, List<DailyProgressReport>> byActivity = rows.stream()
                .filter(r -> r.getActivityName() != null)
                .collect(Collectors.groupingBy(DailyProgressReport::getActivityName));

        ObjectNode root = objectMapper.createObjectNode();
        root.put("projectId", projectId.toString());
        root.put("totalRecords", rows.size());

        ArrayNode activities = root.putArray("activities");
        byActivity.entrySet().stream()
                .map(e -> {
                    String activityName = e.getKey();
                    List<DailyProgressReport> list = e.getValue();
                    BigDecimal totalQty = list.stream()
                            .map(DailyProgressReport::getQtyExecuted)
                            .filter(q -> q != null)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    double avgDailyQty = list.isEmpty() ? 0.0 : totalQty.doubleValue() / list.size();
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("activityName", activityName);
                    node.put("recordCount", list.size());
                    node.put("totalQtyExecuted", totalQty.doubleValue());
                    node.put("avgDailyQty", avgDailyQty);
                    return node;
                })
                .sorted(Comparator.comparingDouble((ObjectNode n) -> n.get("totalQtyExecuted").asDouble()).reversed())
                .limit(10)
                .forEach(activities::add);

        BigDecimal overallQty = rows.stream()
                .map(DailyProgressReport::getQtyExecuted)
                .filter(q -> q != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        root.put("overallTotalQtyExecuted", overallQty.doubleValue());

        if (!rows.isEmpty()) {
            LocalDate minDate = rows.stream()
                    .map(DailyProgressReport::getReportDate)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            LocalDate maxDate = rows.stream()
                    .map(DailyProgressReport::getReportDate)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (minDate != null) {
                root.put("dateRangeFrom", minDate.toString());
            }
            if (maxDate != null) {
                root.put("dateRangeTo", maxDate.toString());
            }
        }

        LocalDate today = LocalDate.now();
        LocalDate fourteenDaysAgo = today.minusDays(13);
        long reportedDaysInLast14 = rows.stream()
                .map(DailyProgressReport::getReportDate)
                .filter(d -> d != null && !d.isBefore(fourteenDaysAgo) && !d.isAfter(today))
                .distinct()
                .count();
        root.put("reportedDaysInLast14", reportedDaysInLast14);
        root.put("expectedDaysInLast14", 14);

        ArrayNode weatherBreakdown = objectMapper.createArrayNode();
        rows.stream()
                .filter(r -> r.getWeatherCondition() != null && !r.getWeatherCondition().isBlank())
                .collect(Collectors.groupingBy(DailyProgressReport::getWeatherCondition, Collectors.counting()))
                .forEach((weather, count) -> {
                    ObjectNode w = objectMapper.createObjectNode();
                    w.put("weather", weather);
                    w.put("count", count);
                    weatherBreakdown.add(w);
                });
        root.set("weatherBreakdown", weatherBreakdown);

        return root;
    }

    @Override
    public List<ChartSpec> charts(UUID projectId) {
        if (projectId == null) {
            return List.of(
                    new ChartSpec("dpr-top-activities", "Top Activities by Quantity", "bar", null, null),
                    new ChartSpec("dpr-weather", "Weather Conditions", "donut", null, null),
                    new ChartSpec("dpr-coverage", "Reporting Coverage (last 14 days)", "gauge", null, null)
            );
        }

        List<DailyProgressReport> rows = dprRepository
                .findByProjectIdAndApprovalStatusOrderByReportDateAscIdAsc(
                        projectId, DprApprovalStatus.APPROVED);
        List<ChartSpec> charts = new ArrayList<>();

        Map<String, BigDecimal> qtyByActivity = rows.stream()
                .filter(r -> r.getActivityName() != null && r.getQtyExecuted() != null)
                .collect(Collectors.groupingBy(
                        DailyProgressReport::getActivityName,
                        Collectors.reducing(BigDecimal.ZERO,
                                DailyProgressReport::getQtyExecuted,
                                BigDecimal::add)));
        List<Map.Entry<String, BigDecimal>> topActivities = qtyByActivity.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(8)
                .toList();
        charts.add(new ChartSpec("dpr-top-activities", "Top Activities by Quantity", "bar",
                EChartsOptions.bar(objectMapper,
                        topActivities.stream().map(Map.Entry::getKey).toList(),
                        "Total Qty Executed",
                        topActivities.stream().map(Map.Entry::getValue).toList()),
                "Top 8 activities by cumulative quantity executed"));

        Map<String, Long> weatherBreakdown = rows.stream()
                .filter(r -> r.getWeatherCondition() != null && !r.getWeatherCondition().isBlank())
                .collect(Collectors.groupingBy(DailyProgressReport::getWeatherCondition, Collectors.counting()));
        charts.add(new ChartSpec("dpr-weather", "Weather Conditions", "donut",
                EChartsOptions.donut(objectMapper, new LinkedHashMap<>(weatherBreakdown)),
                "Days reported by weather"));

        LocalDate today = LocalDate.now();
        LocalDate fourteenDaysAgo = today.minusDays(13);
        long reportedDaysInLast14 = rows.stream()
                .map(DailyProgressReport::getReportDate)
                .filter(d -> d != null && !d.isBefore(fourteenDaysAgo) && !d.isAfter(today))
                .distinct()
                .count();
        double coverageRatio = reportedDaysInLast14 / 14.0;
        charts.add(new ChartSpec("dpr-coverage", "Reporting Coverage (last 14 days)", "gauge",
                EChartsOptions.gauge(objectMapper, "Coverage", Math.round(coverageRatio * 100), 0, 100),
                "Days with at least one DPR record, last 14 days"));

        return charts;
    }

    @Override
    public String tabKey() {
        return "dpr";
    }

    @Override
    public String promptInstructions() {
        return "Produce insights to improve daily progress tracking and site productivity. Focus on activity coverage gaps, quantity execution trends, weather impact patterns, and reporting consistency.";
    }
}
