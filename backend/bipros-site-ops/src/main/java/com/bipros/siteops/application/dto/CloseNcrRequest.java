package com.bipros.siteops.application.dto;

import jakarta.validation.constraints.NotBlank;

public record CloseNcrRequest(
        @NotBlank String rootCause,
        @NotBlank String correctiveAction
) {}
