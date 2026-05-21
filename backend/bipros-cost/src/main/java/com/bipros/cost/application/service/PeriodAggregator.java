package com.bipros.cost.application.service;

import com.bipros.cost.domain.entity.FinancialPeriod;
import com.bipros.cost.domain.repository.FinancialPeriodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Resolves a {@code periodType ∈ {D, W, M}} request into the project's existing
 * {@link FinancialPeriod} rows (sorted by start date) and matches a given date into one
 * of those buckets. By project decision, weekly/monthly periods must exist explicitly in
 * the {@code financial_periods} table — no auto-bucketing into ISO weeks or calendar months.
 */
@Component
@RequiredArgsConstructor
public class PeriodAggregator {

    private final FinancialPeriodRepository financialPeriodRepository;

    public List<FinancialPeriod> findPeriods(UUID projectId, String periodType) {
        String normalized = normalize(periodType);
        return financialPeriodRepository.findByProjectIdOrderBySortOrderAsc(projectId).stream()
            .filter(p -> normalized.equals(normalize(p.getPeriodType())))
            .sorted(Comparator.comparing(FinancialPeriod::getStartDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    /**
     * @return the period whose {@code [startDate, endDate]} (inclusive) contains {@code date},
     *         or null if no explicit period covers it.
     */
    public FinancialPeriod bucketFor(List<FinancialPeriod> periods, LocalDate date) {
        if (date == null) return null;
        for (FinancialPeriod p : periods) {
            if (p.getStartDate() == null || p.getEndDate() == null) continue;
            if (!date.isBefore(p.getStartDate()) && !date.isAfter(p.getEndDate())) {
                return p;
            }
        }
        return null;
    }

    /**
     * Normalises the free-text {@code period_type} column so dashboards work regardless of
     * whether stored values are short codes ("D", "W", "M") or long names ("DAILY", etc.).
     * Unknown / null types resolve to the literal "?" so they cannot collide with a real bucket.
     */
    public static String normalize(String raw) {
        if (raw == null) return "?";
        String u = raw.trim().toUpperCase();
        if (u.isEmpty()) return "?";
        if (u.startsWith("D")) return "D";
        if (u.startsWith("W")) return "W";
        if (u.startsWith("M")) return "M";
        return u;
    }
}
