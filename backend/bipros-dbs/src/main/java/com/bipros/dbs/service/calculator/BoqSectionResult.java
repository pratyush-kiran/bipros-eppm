package com.bipros.dbs.service.calculator;

import java.math.BigDecimal;
import java.util.List;

/**
 * BOQ section needs three independent rollups (for-the-day, planned-to-date, achieved-to-date)
 * so the aggregation service can populate the corresponding columns separately. Lines mirror
 * the "BOQ Work" rows from the client workbook.
 *
 * <p>Phase 7: the {@code forTheDayAmount} is additionally split into
 * {@code directBoqAmount} (non-preliminary activities) and {@code prelimBoqAmount}
 * (preliminary activities, i.e. mobilisation / site-setup / diversions). The sum of
 * the two equals {@code forTheDayAmount} (modulo rounding) and lets the aggregation
 * service populate the new {@code direct_cost} / {@code prelim_cost} columns on the
 * DBS rollup tables without re-reading the source DPRs.
 */
public record BoqSectionResult(
    BigDecimal forTheDayAmount,
    BigDecimal plannedAmount,
    BigDecimal achievedAmount,
    BigDecimal directBoqAmount,
    BigDecimal prelimBoqAmount,
    List<SectionLine> lines
) {
    public static BoqSectionResult empty() {
        return new BoqSectionResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
