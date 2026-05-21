package com.bipros.cost.application.service;

import com.bipros.cost.application.dto.CashFlowForecastDto;
import com.bipros.cost.domain.entity.FinancialPeriod;
import com.bipros.cost.domain.entity.StorePeriodPerformance;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class CashFlowForecastEngine {

    public enum ForecastMethod {
        LINEAR,
        CPI_BASED,
        SPI_CPI_COMPOSITE
    }

    public List<CashFlowForecastDto> generateForecast(
            UUID projectId,
            List<FinancialPeriod> periods,
            Map<UUID, BigDecimal> periodBudgets,
            Map<UUID, BigDecimal> periodActuals,
            List<StorePeriodPerformance> performances,
            ForecastMethod method) {

        var sortedPeriods = periods.stream()
                .sorted(Comparator.comparing(FinancialPeriod::getStartDate))
                .toList();

        if (sortedPeriods.isEmpty()) {
            return List.of();
        }

        BigDecimal totalBudget = periodBudgets.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalActual = periodActuals.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRemaining = totalBudget.subtract(totalActual).max(BigDecimal.ZERO);

        // Compute CPI and SPI from StorePeriodPerformance
        BigDecimal cumulativeEV = BigDecimal.ZERO;
        BigDecimal cumulativePV = BigDecimal.ZERO;
        BigDecimal cumulativeAC = BigDecimal.ZERO;
        for (var perf : performances) {
            cumulativeEV = cumulativeEV.add(perf.getEarnedValueCost() != null ? perf.getEarnedValueCost() : BigDecimal.ZERO);
            cumulativePV = cumulativePV.add(perf.getPlannedValueCost() != null ? perf.getPlannedValueCost() : BigDecimal.ZERO);
            var ac = BigDecimal.ZERO;
            if (perf.getActualLaborCost() != null) ac = ac.add(perf.getActualLaborCost());
            if (perf.getActualNonlaborCost() != null) ac = ac.add(perf.getActualNonlaborCost());
            if (perf.getActualMaterialCost() != null) ac = ac.add(perf.getActualMaterialCost());
            if (perf.getActualExpenseCost() != null) ac = ac.add(perf.getActualExpenseCost());
            cumulativeAC = cumulativeAC.add(ac);
        }

        BigDecimal cpi = cumulativeAC.compareTo(BigDecimal.ZERO) > 0
                ? cumulativeEV.divide(cumulativeAC, 4, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        BigDecimal spi = cumulativePV.compareTo(BigDecimal.ZERO) > 0
                ? cumulativeEV.divide(cumulativePV, 4, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        // periodBudgets / periodActuals are pre-computed by CostService — they already combine
        // the manual ActivityExpense ledger with the operational DPR / ResourceAssignment ledger.
        LocalDate today = LocalDate.now();

        // Build forecast rows
        List<CashFlowForecastDto> results = new ArrayList<>();
        BigDecimal cumPlanned = BigDecimal.ZERO;
        BigDecimal cumActual = BigDecimal.ZERO;
        BigDecimal cumForecast = BigDecimal.ZERO;

        // Compute total remaining budget for future periods
        BigDecimal futureRemainingBudget = BigDecimal.ZERO;
        for (var period : sortedPeriods) {
            if (!period.getEndDate().isBefore(today)) {
                futureRemainingBudget = futureRemainingBudget.add(
                        periodBudgets.getOrDefault(period.getId(), BigDecimal.ZERO));
            }
        }

        for (var period : sortedPeriods) {
            BigDecimal planned = periodBudgets.getOrDefault(period.getId(), BigDecimal.ZERO);
            BigDecimal actual = periodActuals.getOrDefault(period.getId(), BigDecimal.ZERO);
            BigDecimal forecast;

            boolean isPast = period.getEndDate().isBefore(today);

            if (isPast) {
                // For past periods, forecast = actual
                forecast = actual;
            } else {
                forecast = switch (method) {
                    case LINEAR -> computeLinearForecast(planned);
                    case CPI_BASED -> computeCpiForecast(planned, totalRemaining, futureRemainingBudget, cpi);
                    case SPI_CPI_COMPOSITE -> computeSpiCpiForecast(planned, totalRemaining, futureRemainingBudget, cpi, spi);
                };
            }

            cumPlanned = cumPlanned.add(planned);
            cumActual = cumActual.add(actual);
            cumForecast = cumForecast.add(forecast);

            results.add(CashFlowForecastDto.builder()
                    .id(null)
                    .projectId(projectId)
                    .period(period.getName())
                    .plannedAmount(planned)
                    .actualAmount(actual)
                    .forecastAmount(forecast)
                    .cumulativePlanned(cumPlanned)
                    .cumulativeActual(cumActual)
                    .cumulativeForecast(cumForecast)
                    .build());
        }

        return results;
    }

    /**
     * LINEAR: forecast = planned budget for that period (no adjustment)
     */
    private BigDecimal computeLinearForecast(BigDecimal periodBudget) {
        return periodBudget;
    }

    /**
     * CPI-BASED: forecastCost[p] = remainingBudget * (periodBudget / totalFutureBudget) / CPI
     */
    private BigDecimal computeCpiForecast(BigDecimal periodBudget, BigDecimal remaining,
                                           BigDecimal totalFutureBudget, BigDecimal cpi) {
        if (totalFutureBudget.compareTo(BigDecimal.ZERO) == 0 || cpi.compareTo(BigDecimal.ZERO) == 0) {
            return periodBudget;
        }
        BigDecimal proportion = periodBudget.divide(totalFutureBudget, 6, RoundingMode.HALF_UP);
        return remaining.multiply(proportion).divide(cpi, 2, RoundingMode.HALF_UP);
    }

    /**
     * SPI*CPI COMPOSITE: forecastCost[p] = remainingBudget * (periodBudget / totalFutureBudget) / (CPI * SPI)
     */
    private BigDecimal computeSpiCpiForecast(BigDecimal periodBudget, BigDecimal remaining,
                                              BigDecimal totalFutureBudget, BigDecimal cpi, BigDecimal spi) {
        if (totalFutureBudget.compareTo(BigDecimal.ZERO) == 0) {
            return periodBudget;
        }
        BigDecimal composite = cpi.multiply(spi);
        if (composite.compareTo(BigDecimal.ZERO) == 0) {
            return periodBudget;
        }
        BigDecimal proportion = periodBudget.divide(totalFutureBudget, 6, RoundingMode.HALF_UP);
        return remaining.multiply(proportion).divide(composite, 2, RoundingMode.HALF_UP);
    }

}
