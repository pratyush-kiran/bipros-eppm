package com.bipros.dbs.service.calculator;

import java.math.BigDecimal;

/**
 * One grouped line inside the PM tab's F. Sub-Contractor accordion. Rows are
 * keyed by {@code (sub-contractor master, work-type)} per day so multiple DPRs
 * with the same SC + work-type collapse into one displayed row.
 *
 * <p>{@code scImputedIncome = qty × boq_rate} (NULL boq_rate → 0) — what the
 * project would have invoiced for the SC's portion of workdone at the BOQ rate.
 * {@code scMargin = scImputedIncome − scExpense}.
 */
public record SubContractLine(
    String subContractorCode,
    String subContractorName,
    String workTypeName,
    String unit,
    BigDecimal qty,
    BigDecimal scRate,
    BigDecimal scExpense,
    BigDecimal boqRate,
    BigDecimal scImputedIncome,
    BigDecimal scMargin
) {}
