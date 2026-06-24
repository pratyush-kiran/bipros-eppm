package com.bipros.reporting.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CostOverrunRow(
    UUID projectId,
    String projectCode,
    String projectName,
    BigDecimal bacCrores,
    BigDecimal eacCrores,
    BigDecimal varianceCrores,
    double cpi,
    String budgetCurrency) {}
