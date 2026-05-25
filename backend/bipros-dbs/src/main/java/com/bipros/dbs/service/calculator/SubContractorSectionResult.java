package com.bipros.dbs.service.calculator;

import java.math.BigDecimal;
import java.util.List;

/**
 * Project-scope output of {@link SectionFSubContractorCalculator}. Carries the
 * total SC expense for the day plus the per-(sub-contractor, work-type) lines
 * for the PM tab's F. Sub-Contractor accordion.
 *
 * <p>{@code totalImputedIncome} is informational only — used by the UI to
 * display margin per sub-contractor. PM Total Income is sourced from
 * {@link SectionFBoqCalculator} at project scope, which already includes the
 * SC portion of qty × boq_rate.
 */
public record SubContractorSectionResult(
    BigDecimal totalExpense,
    BigDecimal totalImputedIncome,
    List<SubContractLine> lines
) {
    public static SubContractorSectionResult empty() {
        return new SubContractorSectionResult(BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
