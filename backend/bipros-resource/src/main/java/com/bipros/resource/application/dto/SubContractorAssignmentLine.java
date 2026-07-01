package com.bipros.resource.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SubContractorAssignmentLine(
    UUID activityId,
    String workTypeName,
    String unit,
    BigDecimal plannedUnits,
    BigDecimal ratePerUnit,
    BigDecimal plannedCost,
    BigDecimal actualUnits,
    BigDecimal actualCost
) {}
