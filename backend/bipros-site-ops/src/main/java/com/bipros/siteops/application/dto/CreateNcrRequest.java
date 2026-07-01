package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.NcrCategory;
import com.bipros.siteops.domain.model.NcrSeverity;
import com.bipros.siteops.domain.model.NcrSourceType;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateNcrRequest(
        @NotBlank String title,
        String description,
        NcrCategory category,
        NcrSeverity severity,
        UUID assignedTo,
        NcrSourceType sourceType,
        UUID sourceRefId,
        UUID activityId
) {}
