package com.bipros.cost.application.dto;

import java.math.BigDecimal;

public record MarginActivityDto(
        String activity,
        BigDecimal revenue,
        BigDecimal actualCost,
        BigDecimal margin,
        BigDecimal marginPct
) {}
