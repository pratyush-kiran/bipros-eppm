package com.bipros.project.application.dto;

import com.bipros.project.domain.model.ProjectStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateProjectRequest(
    @Size(max = 100, message = "Name must not exceed 100 characters")
    String name,

    String description,

    UUID obsNodeId,

    LocalDate plannedStartDate,

    LocalDate plannedFinishDate,

    LocalDate mustFinishByDate,

    ProjectStatus status,

    @Min(value = 1, message = "Priority must be between 1 and 100")
    @Max(value = 100, message = "Priority must be between 1 and 100")
    Integer priority,

    LocalDate dataDate,

    String category,
    @Size(max = 20) String morthCode,
    @Min(0) Long fromChainageM,
    @Min(0) Long toChainageM,
    @Size(max = 120) String fromLocation,
    @Size(max = 120) String toLocation,
    BigDecimal totalLengthKm,

    UUID calendarId,

    /** ISO 4217 currency code for budget fields (e.g. "USD", "OMR"). Leave null to keep existing value. */
    @Size(max = 10) String budgetCurrency,

    @Valid CreateProjectRequest.ContractSummaryInput contract
) {
}
