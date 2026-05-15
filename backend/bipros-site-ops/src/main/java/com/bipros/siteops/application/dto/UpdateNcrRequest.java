package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.NcrCategory;
import com.bipros.siteops.domain.model.NcrSeverity;

import java.util.UUID;

public record UpdateNcrRequest(
        String title,
        String description,
        NcrCategory category,
        NcrSeverity severity,
        UUID assignedTo,
        String rootCause,
        String correctiveAction
) {}
