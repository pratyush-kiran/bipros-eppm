package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.ExpenseCategory;

import java.math.BigDecimal;
import java.util.UUID;

public record ExpenseCategoryResponse(
    UUID id,
    String code,
    String name,
    String description,
    String dbsSection,
    Boolean defaultTaxable,
    BigDecimal defaultTaxRatePct,
    Integer sortOrder,
    Boolean active
) {
  public static ExpenseCategoryResponse from(ExpenseCategory e) {
    return new ExpenseCategoryResponse(
        e.getId(), e.getCode(), e.getName(), e.getDescription(),
        e.getDbsSection(), e.getDefaultTaxable(), e.getDefaultTaxRatePct(),
        e.getSortOrder(), e.getActive());
  }
}
