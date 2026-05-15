package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.AnswerValue;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ChecklistAnswerDto(
        @NotNull UUID itemId,
        AnswerValue value,
        String note,
        String photoUrl
) {}
