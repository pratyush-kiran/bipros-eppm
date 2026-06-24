package com.bipros.reporting.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FundingUtilizationRow(
    UUID projectId,
    String projectName,
    BigDecimal totalSanctionedCrores,
    BigDecimal totalReleasedCrores,
    BigDecimal totalUtilizedCrores,
    BigDecimal pendingWithTreasuryCrores,
    double releasePct,
    double utilizationPct,
    String fundingStatus,
    String currency) {}
