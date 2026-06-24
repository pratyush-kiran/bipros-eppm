package com.bipros.reporting.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortfolioScorecardDto(
    long totalProjects,
    Map<String, Long> byStatus,
    BigDecimal totalBudgetCrores,
    BigDecimal totalCommittedCrores,
    BigDecimal totalSpentCrores,
    RagCounts rag,
    long activeProjectsWithCriticalActivities,
    long openRisksCritical,
    List<CurrencyBudget> budgetByCurrency,
    Double avgPercentComplete,
    List<CurrencyBudget> spentByCurrency,
    List<CurrencyBudget> committedByCurrency) {

  public record RagCounts(long green, long amber, long red, long grey) {}
}
