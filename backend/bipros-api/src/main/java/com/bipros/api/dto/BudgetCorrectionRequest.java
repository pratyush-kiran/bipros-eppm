package com.bipros.api.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BudgetCorrectionRequest {

    /** Corrected BAC in major-unit scale (crores for INR, millions for all other currencies). */
    private BigDecimal correctedBudget;

    /** Human-readable reason for the correction — stored in the audit log. */
    private String reason;

    /** When true (default), triggers an EVM snapshot recompute after saving the corrected budget. */
    private boolean recomputeEvm = true;
}
