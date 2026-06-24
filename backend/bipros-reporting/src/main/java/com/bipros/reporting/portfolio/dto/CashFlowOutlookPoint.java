package com.bipros.reporting.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CashFlowOutlookPoint(
    String yearMonth,
    BigDecimal plannedOutflowCrores,
    BigDecimal plannedInflowCrores,
    BigDecimal netCrores,
    BigDecimal cumulativeCrores,
    String currency) {}
