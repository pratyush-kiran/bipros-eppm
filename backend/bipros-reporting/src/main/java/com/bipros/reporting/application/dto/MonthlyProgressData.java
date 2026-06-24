package com.bipros.reporting.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonthlyProgressData(
    String projectName,
    String projectCode,
    String period,
    int totalActivities,
    int completedActivities,
    int inProgressActivities,
    double overallPercentComplete,
    BigDecimal budgetAmount,
    BigDecimal actualCost,
    BigDecimal forecastCost,
    int totalMilestones,
    int achievedMilestones,
    int openRisks,
    int highRisks,
    List<ActivitySummaryRow> topDelayedActivities) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ActivitySummaryRow(
      String code,
      String name,
      String status,
      int delayDays,
      LocalDate plannedFinish) {}
}
