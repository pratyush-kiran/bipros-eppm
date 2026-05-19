package com.bipros.cost.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One period row in the Performance dashboard's D/W/M rollup. Sums
 * {@code StorePeriodPerformance} entries whose parent {@link com.bipros.cost.domain.entity.FinancialPeriod}
 * matches the requested {@code periodType}.
 *
 * @param cpi {@code earnedValue / actualCost}, null when actualCost = 0
 * @param spi {@code earnedValue / plannedValue}, null when plannedValue = 0
 */
public record PeriodPerformanceRollupDto(
        UUID periodId,
        String periodName,
        String periodType,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal actualCost,
        BigDecimal plannedValue,
        BigDecimal earnedValue,
        BigDecimal cv,
        BigDecimal sv,
        BigDecimal cpi,
        BigDecimal spi
) {}
