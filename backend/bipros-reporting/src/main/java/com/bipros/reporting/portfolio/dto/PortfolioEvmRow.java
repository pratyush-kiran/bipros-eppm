package com.bipros.reporting.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortfolioEvmRow(
    UUID projectId,
    String projectCode,
    String projectName,
    BigDecimal pv,
    BigDecimal ev,
    BigDecimal ac,
    Double cpi,
    Double spi,
    BigDecimal cv,
    BigDecimal sv,
    BigDecimal eac,
    BigDecimal bac,
    String budgetCurrency,
    Double percentComplete) {}
