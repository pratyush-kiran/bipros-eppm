package com.bipros.cost.application.service;

import com.bipros.cost.application.dto.PeriodPerformanceRollupDto;
import com.bipros.cost.domain.entity.FinancialPeriod;
import com.bipros.cost.domain.entity.StorePeriodPerformance;
import com.bipros.cost.domain.repository.StorePeriodPerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates {@link StorePeriodPerformance} rows into a per-{@code FinancialPeriod} rollup for
 * the Performance (D/W/M) dashboard. Activity-level and project-level rows are summed together;
 * callers wanting only project-level rows can use {@link CostService#getProjectLevelPerformance}.
 */
@Service
@RequiredArgsConstructor
public class PerformanceRollupService {

    private static final int AMOUNT_SCALE = 2;
    private static final int RATIO_SCALE = 6;

    private final StorePeriodPerformanceRepository sppRepository;
    private final PeriodAggregator periodAggregator;

    @Transactional(readOnly = true)
    public List<PeriodPerformanceRollupDto> rollup(UUID projectId, String periodType) {
        List<FinancialPeriod> periods = periodAggregator.findPeriods(periodType);
        if (periods.isEmpty()) return List.of();

        Map<UUID, List<StorePeriodPerformance>> rowsByPeriod = new HashMap<>();
        for (StorePeriodPerformance row : sppRepository.findByProjectId(projectId)) {
            rowsByPeriod.computeIfAbsent(row.getFinancialPeriodId(), k -> new ArrayList<>()).add(row);
        }

        List<PeriodPerformanceRollupDto> out = new ArrayList<>(periods.size());
        for (FinancialPeriod period : periods) {
            List<StorePeriodPerformance> rows = rowsByPeriod.getOrDefault(period.getId(), List.of());
            BigDecimal ac = BigDecimal.ZERO;
            BigDecimal ev = BigDecimal.ZERO;
            BigDecimal pv = BigDecimal.ZERO;
            for (StorePeriodPerformance row : rows) {
                ac = ac.add(sumCosts(row));
                ev = ev.add(nz(row.getEarnedValueCost()));
                pv = pv.add(nz(row.getPlannedValueCost()));
            }
            ac = ac.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            ev = ev.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            pv = pv.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            BigDecimal cv = ev.subtract(ac);
            BigDecimal sv = ev.subtract(pv);
            BigDecimal cpi = ac.signum() == 0 ? null : ev.divide(ac, RATIO_SCALE, RoundingMode.HALF_UP);
            BigDecimal spi = pv.signum() == 0 ? null : ev.divide(pv, RATIO_SCALE, RoundingMode.HALF_UP);
            out.add(new PeriodPerformanceRollupDto(
                period.getId(),
                period.getName(),
                period.getPeriodType(),
                period.getStartDate(),
                period.getEndDate(),
                ac, pv, ev, cv, sv, cpi, spi
            ));
        }
        return out;
    }

    private static BigDecimal sumCosts(StorePeriodPerformance row) {
        return nz(row.getActualLaborCost())
            .add(nz(row.getActualNonlaborCost()))
            .add(nz(row.getActualMaterialCost()))
            .add(nz(row.getActualExpenseCost()));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
