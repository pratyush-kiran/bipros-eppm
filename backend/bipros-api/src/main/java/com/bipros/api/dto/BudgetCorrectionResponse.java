package com.bipros.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response returned after a successful budget correction.
 *
 * @param projectId            the project whose budget was corrected
 * @param currency             the project's budget currency (ISO code)
 * @param originalBudget       the corrected originalBudget (major-unit scale)
 * @param currentBudget        originalBudget + approvedNet (major-unit scale)
 * @param rawCurrencyEquivalent currentBudget × majorUnitFactor (raw currency units)
 * @param approvedNet          net of APPROVED budget-change logs (ADDITION − REDUCTION)
 * @param evmRecomputed        true if an EVM snapshot was recomputed and persisted
 */
public record BudgetCorrectionResponse(
        UUID projectId,
        String currency,
        BigDecimal originalBudget,
        BigDecimal currentBudget,
        BigDecimal rawCurrencyEquivalent,
        BigDecimal approvedNet,
        boolean evmRecomputed
) {
}
