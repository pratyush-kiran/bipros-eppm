package com.bipros.resource.application.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ExpenseCategoryRequest(
    @NotBlank(message = "Code is required")
    @Size(max = 50, message = "Code must be at most 50 characters")
    @Pattern(regexp = "^[A-Z0-9_]+$", message = "Code must use uppercase letters, digits, and underscores only")
    String code,

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    String name,

    @Size(max = 500, message = "Description must be at most 500 characters")
    String description,

    @NotBlank(message = "DBS section is required")
    @Pattern(regexp = "^[A-G]$", message = "DBS section must be a single letter A-G")
    String dbsSection,

    Boolean defaultTaxable,

    @DecimalMin(value = "0.00", message = "Tax rate cannot be negative")
    @DecimalMax(value = "100.00", message = "Tax rate cannot exceed 100%")
    BigDecimal defaultTaxRatePct,

    Integer sortOrder,

    Boolean active
) {}
