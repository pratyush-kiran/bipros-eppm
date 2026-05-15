package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.Shift;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record CreateShiftHandoverRequest(
        @NotNull LocalDate shiftDate,
        @NotNull Shift shift,
        @NotNull UUID toUserId,
        @NotBlank String summary,
        String pendingItems
) {
}
