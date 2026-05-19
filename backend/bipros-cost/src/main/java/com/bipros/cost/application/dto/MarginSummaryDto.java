package com.bipros.cost.application.dto;

import java.math.BigDecimal;

public record MarginSummaryDto(
        BigDecimal revenue,
        BigDecimal actualCost,
        BigDecimal margin,
        BigDecimal marginPct
) {}
