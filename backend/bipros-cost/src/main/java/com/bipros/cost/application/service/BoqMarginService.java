package com.bipros.cost.application.service;

import com.bipros.cost.application.dto.MarginActivityDto;
import com.bipros.cost.application.dto.MarginItemDto;
import com.bipros.cost.application.dto.MarginPeriodDto;
import com.bipros.cost.application.dto.MarginSummaryDto;
import com.bipros.cost.application.service.MarginCalculator.MarginResult;
import com.bipros.cost.domain.entity.FinancialPeriod;
import com.bipros.dbs.domain.model.DbsDailyProject;
import com.bipros.dbs.domain.repository.DbsDailyProjectRepository;
import com.bipros.project.application.dto.DailyCostReportResponse;
import com.bipros.project.application.dto.DailyCostReportRow;
import com.bipros.project.application.service.DailyCostReportService;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P&L vs BOQ (contract) rates. Revenue is {@code boqRate × qtyExecuted} — the rate the client
 * pays. For period rollups we reuse {@link DbsDailyProject#getBoqForTheDayAmount} (income) and
 * {@link DbsDailyProject#getTotalExpense} (cost) since the DBS module already computes these
 * daily; activity drilldown joins DPR rows to {@link BoqItem#getBoqRate}.
 */
@Service
@RequiredArgsConstructor
public class BoqMarginService {

    private final BoqItemRepository boqItemRepository;
    private final DailyCostReportService dailyCostReportService;
    private final DbsDailyProjectRepository dbsDailyProjectRepository;
    private final PeriodAggregator periodAggregator;

    @Transactional(readOnly = true)
    public List<MarginItemDto> marginByBoqItem(UUID projectId) {
        List<BoqItem> items = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
        List<MarginItemDto> out = new ArrayList<>(items.size());
        for (BoqItem item : items) {
            MarginResult m = MarginCalculator.compute(
                item.getBoqRate(), item.getQtyExecutedToDate(), item.getActualAmount());
            out.add(new MarginItemDto(
                item.getId(), item.getItemNo(), item.getDescription(), item.getUnit(),
                item.getQtyExecutedToDate(), item.getBoqRate(),
                m.revenue(), m.actualCost(), m.margin(), m.marginPct()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<MarginActivityDto> marginByActivity(UUID projectId) {
        Map<UUID, BigDecimal> boqRateById = new HashMap<>();
        for (BoqItem item : boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId)) {
            boqRateById.put(item.getId(), item.getBoqRate());
        }
        DailyCostReportResponse report = dailyCostReportService.generate(projectId, null, null);
        Map<String, BigDecimal[]> byActivity = new LinkedHashMap<>();
        for (DailyCostReportRow row : report.rows()) {
            BigDecimal rate = row.boqItemId() != null ? boqRateById.get(row.boqItemId()) : null;
            BigDecimal revenue = nz(row.qtyExecuted()).multiply(nz(rate));
            BigDecimal cost = nz(row.actualCost());
            BigDecimal[] acc = byActivity.computeIfAbsent(
                row.activity() == null ? "—" : row.activity(),
                k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            acc[0] = acc[0].add(revenue);
            acc[1] = acc[1].add(cost);
        }
        List<MarginActivityDto> out = new ArrayList<>(byActivity.size());
        byActivity.forEach((activity, acc) -> {
            MarginResult m = MarginCalculator.fromRevenueAndCost(acc[0], acc[1]);
            out.add(new MarginActivityDto(activity, m.revenue(), m.actualCost(), m.margin(), m.marginPct()));
        });
        return out;
    }

    @Transactional(readOnly = true)
    public List<MarginPeriodDto> marginByPeriod(UUID projectId, String periodType) {
        List<FinancialPeriod> periods = periodAggregator.findPeriods(periodType);
        if (periods.isEmpty()) return List.of();

        Map<UUID, BigDecimal[]> byPeriod = new HashMap<>();
        // DBS rollup is per project per day — pull the union of all D rows that cover any of the
        // requested periods. Simpler: pull every day in the earliest..latest window.
        var minStart = periods.stream().map(FinancialPeriod::getStartDate)
            .filter(d -> d != null).min(java.time.LocalDate::compareTo).orElse(null);
        var maxEnd = periods.stream().map(FinancialPeriod::getEndDate)
            .filter(d -> d != null).max(java.time.LocalDate::compareTo).orElse(null);
        if (minStart == null || maxEnd == null) return List.of();
        for (DbsDailyProject day : dbsDailyProjectRepository
                .findByProjectIdAndReportDateBetween(projectId, minStart, maxEnd)) {
            FinancialPeriod fp = periodAggregator.bucketFor(periods, day.getReportDate());
            if (fp == null) continue;
            BigDecimal[] acc = byPeriod.computeIfAbsent(fp.getId(),
                k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            acc[0] = acc[0].add(nz(day.getBoqForTheDayAmount()));
            acc[1] = acc[1].add(nz(day.getTotalExpense()));
        }
        List<MarginPeriodDto> out = new ArrayList<>(periods.size());
        for (FinancialPeriod p : periods) {
            BigDecimal[] acc = byPeriod.getOrDefault(p.getId(), new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            MarginResult m = MarginCalculator.fromRevenueAndCost(acc[0], acc[1]);
            out.add(new MarginPeriodDto(p.getId(), p.getName(), p.getPeriodType(),
                p.getStartDate(), p.getEndDate(),
                m.revenue(), m.actualCost(), m.margin(), m.marginPct()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public MarginSummaryDto summary(UUID projectId) {
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (BoqItem item : boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId)) {
            revenue = revenue.add(nz(item.getBoqRate()).multiply(nz(item.getQtyExecutedToDate())));
            cost = cost.add(nz(item.getActualAmount()));
        }
        MarginResult m = MarginCalculator.fromRevenueAndCost(revenue, cost);
        return new MarginSummaryDto(m.revenue(), m.actualCost(), m.margin(), m.marginPct());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
