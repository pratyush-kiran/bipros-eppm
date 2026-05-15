package com.bipros.siteops.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StartChecklistRequest(
        @NotNull UUID templateId,
        UUID activityId
) {}
