package com.bipros.resource.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProjectSubContractorSummaryResponse(
    UUID subContractorMasterId,
    String code,
    String name,
    String location,
    String primaryContactName,
    String primaryContactNumber,
    int assignmentCount,
    BigDecimal plannedCost,
    BigDecimal actualCost,
    BigDecimal costVariance,
    BigDecimal percentComplete,
    List<SubContractorAssignmentLine> lines
) {}
