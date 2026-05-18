package com.bipros.dbs.service.calculator;

import java.math.BigDecimal;
import java.util.List;

/**
 * BOQ section needs three independent rollups (for-the-day, planned-to-date, achieved-to-date)
 * so the aggregation service can populate the corresponding columns separately. Lines mirror
 * the "BOQ Work" rows from the client workbook.
 */
public record BoqSectionResult(
    BigDecimal forTheDayAmount,
    BigDecimal plannedAmount,
    BigDecimal achievedAmount,
    List<SectionLine> lines
) {
    public static BoqSectionResult empty() {
        return new BoqSectionResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
