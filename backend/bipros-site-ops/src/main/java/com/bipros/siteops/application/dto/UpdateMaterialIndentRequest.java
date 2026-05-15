package com.bipros.siteops.application.dto;

import jakarta.validation.Valid;

import java.time.LocalDate;
import java.util.List;

public record UpdateMaterialIndentRequest(
        LocalDate requiredBy,
        String notes,
        @Valid List<MaterialIndentItemDto> items
) {}
