package com.bipros.project.application.dto;

import com.bipros.contract.domain.model.ContractType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateProjectRequest(
    @NotBlank(message = "Code is required")
    @Size(max = 20, message = "Code must not exceed 20 characters")
    @Pattern(regexp = "^[A-Za-z0-9_\\-./]+$",
        message = "Code may contain only letters, digits, hyphen, underscore, dot, or slash")
    String code,

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    String name,

    String description,

    @NotNull(message = "EPS node ID is required")
    UUID epsNodeId,

    UUID obsNodeId,

    LocalDate plannedStartDate,

    LocalDate plannedFinishDate,

    @Min(value = 1, message = "Priority must be between 1 and 100")
    @Max(value = 100, message = "Priority must be between 1 and 100")
    Integer priority,

    /** Road-construction category code — references project_category_master. */
    String category,

    /** MoRTH category code linked to {@link #category}. */
    @Size(max = 20)
    String morthCode,

    /** Start chainage in metres (e.g. km 145+000 → 145000). */
    @Min(value = 0)
    Long fromChainageM,

    /** End chainage in metres. Must be >= fromChainageM when both supplied. */
    @Min(value = 0)
    Long toChainageM,

    @Size(max = 120) String fromLocation,
    @Size(max = 120) String toLocation,

    /** Optional override for the auto-derived corridor length in km. */
    BigDecimal totalLengthKm,

    /** Default calendar for the project. Activities inherit this when no explicit calendar is set. */
    UUID calendarId,

    /** ISO 4217 currency code for budget fields (e.g. "USD", "OMR"). Defaults to "INR" when omitted. */
    @Size(max = 10) String budgetCurrency,

    /** Primary contract summary. When supplied the server upserts the project's primary
     *  Contract row with these values. */
    @Valid ContractSummaryInput contract
) {

    /** Flat write-side form of the primary Contract attached to this project. */
    public record ContractSummaryInput(
        @Size(max = 50) String contractNumber,
        ContractType contractType,
        BigDecimal contractValue,
        BigDecimal revisedValue,
        LocalDate startDate,
        LocalDate completionDate,
        @Min(0) @Max(240) Integer dlpMonths,
        @Size(max = 200) String contractorName
    ) {}
}
