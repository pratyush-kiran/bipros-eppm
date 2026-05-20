package com.bipros.dbs.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DbsManualExpenseRequest(
    @NotNull(message = "reportDate is required") LocalDate reportDate,
    UUID activityId,
    @NotNull(message = "categoryId is required") UUID categoryId,
    UUID supervisorUserId,
    @NotBlank(message = "description is required")
    @Size(max = 500, message = "description must be at most 500 characters")
    String description,
    @NotNull(message = "amount is required") BigDecimal amount,
    String currency,
    String vendorName,
    String receiptNo,
    String attachmentUrl,
    BigDecimal taxAmount,
    BigDecimal taxRatePct,
    @Size(max = 1000, message = "notes must be at most 1000 characters") String notes,
    UUID reversalOfId
) {}
