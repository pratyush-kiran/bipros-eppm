package com.bipros.cost.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record MarginPeriodDto(
        UUID periodId,
        String periodName,
        String periodType,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal revenue,
        BigDecimal actualCost,
        BigDecimal margin,
        BigDecimal marginPct
) {}
