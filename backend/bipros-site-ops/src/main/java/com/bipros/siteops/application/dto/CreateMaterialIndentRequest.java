package com.bipros.siteops.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record CreateMaterialIndentRequest(
        @NotNull LocalDate requiredBy,
        String notes,
        @Valid List<MaterialIndentItemDto> items
) {}
