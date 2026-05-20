package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.ActivityExpenseBudget;
import com.bipros.resource.domain.model.ExpenseCategory;

import java.math.BigDecimal;
import java.util.UUID;

public record ActivityExpenseBudgetResponse(
    UUID id,
    UUID projectId,
    UUID activityId,
    UUID categoryId,
    String categoryCode,
    String categoryName,
    String dbsSection,
    BigDecimal plannedAmount,
    String notes
) {
  public static ActivityExpenseBudgetResponse from(ActivityExpenseBudget b, ExpenseCategory c) {
    return new ActivityExpenseBudgetResponse(
        b.getId(), b.getProjectId(), b.getActivityId(), b.getCategoryId(),
        c == null ? null : c.getCode(),
        c == null ? null : c.getName(),
        c == null ? null : c.getDbsSection(),
        b.getPlannedAmount(), b.getNotes());
  }
}
