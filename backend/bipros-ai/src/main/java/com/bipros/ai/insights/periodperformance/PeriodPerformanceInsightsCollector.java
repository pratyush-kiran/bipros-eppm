package com.bipros.ai.insights.periodperformance;

import com.bipros.ai.insights.InsightDataCollector;
import com.bipros.ai.insights.dto.ChartSpec;
import com.bipros.cost.application.dto.StorePeriodPerformanceDto;
import com.bipros.cost.application.service.CostService;
import com.bipros.cost.domain.entity.FinancialPeriod;
import com.bipros.cost.domain.repository.FinancialPeriodRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PeriodPerformanceInsightsCollector implements InsightDataCollector {

    private final CostService costService;
    private final FinancialPeriodRepository financialPeriodRepository;
    private final ObjectMapper objectMapper;

    @Override
    public JsonNode collect(UUID projectId) {
        List<StorePeriodPerformanceDto> records = costService.getProjectPeriodPerformance(projectId);

        Map<UUID, FinancialPeriod> periodMap = financialPeriodRepository.findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .collect(Collectors.toMap(FinancialPeriod::getId, p -> p));

        Map<UUID, List<StorePeriodPerformanceDto>> byPeriod = records.stream()
                .collect(Collectors.groupingBy(StorePeriodPerformanceDto::financialPeriodId));

        List<Map<String, Object>> periodList = byPeriod.entrySet().stream()
                .sorted((e1, e2) -> {
                    FinancialPeriod p1 = periodMap.get(e1.getKey());
                    FinancialPeriod p2 = periodMap.get(e2.getKey());
                    int s1 = p1 != null && p1.getSortOrder() != null ? p1.getSortOrder() : 0;
                    int s2 = p2 != null && p2.getSortOrder() != null ? p2.getSortOrder() : 0;
                    return Integer.compare(s2, s1);
                })
                .limit(5)
                .map(e -> {
                    UUID periodId = e.getKey();
                    List<StorePeriodPerformanceDto> list = e.getValue();
                    FinancialPeriod period = periodMap.get(periodId);
                    String periodName = period != null ? period.getName() : periodId.toString();

                    BigDecimal actualCost = list.stream()
                            .map(r -> r.actualLaborCost() != null ? r.actualLaborCost() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .add(list.stream()
                                    .map(r -> r.actualNonlaborCost() != null ? r.actualNonlaborCost() : BigDecimal.ZERO)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                            .add(list.stream()
                                    .map(r -> r.actualMaterialCost() != null ? r.actualMaterialCost() : BigDecimal.ZERO)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                            .add(list.stream()
                                    .map(r -> r.actualExpenseCost() != null ? r.actualExpenseCost() : BigDecimal.ZERO)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add));

                    BigDecimal ev = list.stream()
                            .map(r -> r.earnedValueCost() != null ? r.earnedValueCost() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal pv = list.stream()
                            .map(r -> r.plannedValueCost() != null ? r.plannedValueCost() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    double laborUnits = list.stream()
                            .mapToDouble(r -> r.actualLaborUnits() != null ? r.actualLaborUnits() : 0.0)
                            .sum();

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("periodName", periodName);
                    m.put("actualCost", actualCost);
                    m.put("earnedValue", ev);
                    m.put("plannedValue", pv);
                    m.put("laborUnits", laborUnits);
                    return m;
                })
                .collect(Collectors.toList());

        BigDecimal totalActualCost = records.stream()
                .map(r -> r.actualLaborCost() != null ? r.actualLaborCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(records.stream()
                        .map(r -> r.actualNonlaborCost() != null ? r.actualNonlaborCost() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .add(records.stream()
                        .map(r -> r.actualMaterialCost() != null ? r.actualMaterialCost() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .add(records.stream()
                        .map(r -> r.actualExpenseCost() != null ? r.actualExpenseCost() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal totalEv = records.stream()
                .map(r -> r.earnedValueCost() != null ? r.earnedValueCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPv = records.stream()
                .map(r -> r.plannedValueCost() != null ? r.plannedValueCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double totalLaborUnits = records.stream()
                .mapToDouble(r -> r.actualLaborUnits() != null ? r.actualLaborUnits() : 0.0)
                .sum();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalActualCost", totalActualCost);
        snapshot.put("totalEarnedValue", totalEv);
        snapshot.put("totalPlannedValue", totalPv);
        snapshot.put("totalLaborUnits", totalLaborUnits);
        snapshot.put("topPeriods", periodList);

        return objectMapper.valueToTree(snapshot);
    }

    @Override
    public List<ChartSpec> charts(UUID projectId) {
        if (projectId == null) {
            return List.of(
                    new ChartSpec("pp-planned-vs-actual", "Planned vs Actual Cost", "line", null, null),
                    new ChartSpec("pp-cum-variance", "Cumulative Variance", "area", null, null)
            );
        }

        List<StorePeriodPerformanceDto> records = costService.getProjectPeriodPerformance(projectId);
        Map<UUID, FinancialPeriod> periodMap = financialPeriodRepository.findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .collect(Collectors.toMap(FinancialPeriod::getId, p -> p));

        Map<UUID, List<StorePeriodPerformanceDto>> byPeriod = records.stream()
                .collect(Collectors.groupingBy(StorePeriodPerformanceDto::financialPeriodId));

        List<Map.Entry<UUID, List<StorePeriodPerformanceDto>>> sortedPeriods = byPeriod.entrySet().stream()
                .sorted((e1, e2) -> {
                    FinancialPeriod p1 = periodMap.get(e1.getKey());
                    FinancialPeriod p2 = periodMap.get(e2.getKey());
                    int s1 = p1 != null && p1.getSortOrder() != null ? p1.getSortOrder() : 0;
                    int s2 = p2 != null && p2.getSortOrder() != null ? p2.getSortOrder() : 0;
                    return Integer.compare(s1, s2);
                })
                .limit(5)
                .toList();

        List<ChartSpec> charts = new ArrayList<>();

        // Planned vs Actual line
        ObjectNode lineOption = objectMapper.createObjectNode();
        ObjectNode lineTooltip = objectMapper.createObjectNode();
        lineTooltip.put("trigger", "axis");
        lineOption.set("tooltip", lineTooltip);
        lineOption.set("legend", objectMapper.createObjectNode());
        ObjectNode lineXAxis = objectMapper.createObjectNode();
        lineXAxis.put("type", "category");
        ArrayNode lineCats = objectMapper.createArrayNode();
        sortedPeriods.forEach(e -> {
            FinancialPeriod p = periodMap.get(e.getKey());
            lineCats.add(p != null ? p.getName() : "");
        });
        lineXAxis.set("data", lineCats);
        lineOption.set("xAxis", lineXAxis);
        ObjectNode lineYAxis = objectMapper.createObjectNode();
        lineYAxis.put("type", "value");
        lineOption.set("yAxis", lineYAxis);
        ArrayNode lineSeries = objectMapper.createArrayNode();
        ObjectNode pvSeries = objectMapper.createObjectNode();
        pvSeries.put("name", "Planned Value");
        pvSeries.put("type", "line");
        ArrayNode pvData = objectMapper.createArrayNode();
        sortedPeriods.forEach(e -> {
            BigDecimal pv = e.getValue().stream()
                    .map(r -> r.plannedValueCost() != null ? r.plannedValueCost() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            pvData.add(pv);
        });
        pvSeries.set("data", pvData);
        lineSeries.add(pvSeries);
        ObjectNode avSeries = objectMapper.createObjectNode();
        avSeries.put("name", "Actual Cost");
        avSeries.put("type", "line");
        ArrayNode avData = objectMapper.createArrayNode();
        sortedPeriods.forEach(e -> {
            BigDecimal ac = e.getValue().stream()
                    .map(r -> {
                        BigDecimal sum = BigDecimal.ZERO;
                        sum = sum.add(r.actualLaborCost() != null ? r.actualLaborCost() : BigDecimal.ZERO);
                        sum = sum.add(r.actualNonlaborCost() != null ? r.actualNonlaborCost() : BigDecimal.ZERO);
                        sum = sum.add(r.actualMaterialCost() != null ? r.actualMaterialCost() : BigDecimal.ZERO);
                        sum = sum.add(r.actualExpenseCost() != null ? r.actualExpenseCost() : BigDecimal.ZERO);
                        return sum;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            avData.add(ac);
        });
        avSeries.set("data", avData);
        lineSeries.add(avSeries);
        lineOption.set("series", lineSeries);
        charts.add(new ChartSpec("pp-planned-vs-actual", "Planned vs Actual Cost", "line", lineOption,
                "Period-over-period planned value vs actual cost"));

        // Cumulative variance area
        ObjectNode areaOption = objectMapper.createObjectNode();
        ObjectNode areaTooltip = objectMapper.createObjectNode();
        areaTooltip.put("trigger", "axis");
        areaOption.set("tooltip", areaTooltip);
        ObjectNode areaXAxis = objectMapper.createObjectNode();
        areaXAxis.put("type", "category");
        ArrayNode areaCats = objectMapper.createArrayNode();
        sortedPeriods.forEach(e -> {
            FinancialPeriod p = periodMap.get(e.getKey());
            areaCats.add(p != null ? p.getName() : "");
        });
        areaXAxis.set("data", areaCats);
        areaOption.set("xAxis", areaXAxis);
        ObjectNode areaYAxis = objectMapper.createObjectNode();
        areaYAxis.put("type", "value");
        areaOption.set("yAxis", areaYAxis);
        ArrayNode areaSeries = objectMapper.createArrayNode();
        ObjectNode varArea = objectMapper.createObjectNode();
        varArea.put("name", "Cumulative Variance");
        varArea.put("type", "line");
        varArea.put("areaStyle", true);
        ArrayNode varData = objectMapper.createArrayNode();
        BigDecimal cumulativeVar = BigDecimal.ZERO;
        for (var e : sortedPeriods) {
            BigDecimal pv = e.getValue().stream()
                    .map(r -> r.plannedValueCost() != null ? r.plannedValueCost() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ac = e.getValue().stream()
                    .map(r -> {
                        BigDecimal s = BigDecimal.ZERO;
                        s = s.add(r.actualLaborCost() != null ? r.actualLaborCost() : BigDecimal.ZERO);
                        s = s.add(r.actualNonlaborCost() != null ? r.actualNonlaborCost() : BigDecimal.ZERO);
                        s = s.add(r.actualMaterialCost() != null ? r.actualMaterialCost() : BigDecimal.ZERO);
                        s = s.add(r.actualExpenseCost() != null ? r.actualExpenseCost() : BigDecimal.ZERO);
                        return s;
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            cumulativeVar = cumulativeVar.add(pv.subtract(ac));
            varData.add(cumulativeVar);
        }
        varArea.set("data", varData);
        areaSeries.add(varArea);
        areaOption.set("series", areaSeries);
        charts.add(new ChartSpec("pp-cum-variance", "Cumulative Variance", "area", areaOption,
                "Running sum of PV minus AC across periods"));

        return charts;
    }

    @Override
    public String tabKey() {
        return "period-performance";
    }

    @Override
    public String promptInstructions() {
        return "Produce insights to improve period performance and earned value tracking. Focus on labor/material/expense cost breakdowns, EV vs PV variance by period, and under-performing periods.";
    }
}
