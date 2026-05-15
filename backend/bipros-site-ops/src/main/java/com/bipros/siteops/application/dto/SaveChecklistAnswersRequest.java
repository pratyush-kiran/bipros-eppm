package com.bipros.siteops.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SaveChecklistAnswersRequest(
        @NotNull @Valid List<ChecklistAnswerDto> answers
) {}
