package com.bipros.dbs.api.dto;

import java.math.BigDecimal;

/**
 * Wire-shape mirror of {@code com.bipros.dbs.service.calculator.SubContractLine}.
 * One row inside the PM tab's F. Sub-Contractor accordion, keyed by
 * (sub-contractor master, work-type) per day.
 *
 * <p>{@code scImputedIncome = qty × boqRate} (null boqRate → 0) — the BOQ revenue
 * the project would have invoiced for the SC's portion of workdone. PM Total Income
 * is sourced from the project-scope BOQ section (which already includes this);
 * this field exists so the UI can show margin per sub-contractor.
 */
public record DbsSubContractLineDto(
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
