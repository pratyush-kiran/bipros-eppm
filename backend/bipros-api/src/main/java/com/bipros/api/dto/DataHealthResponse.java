package com.bipros.api.dto;

import java.time.LocalDate;
import java.util.UUID;

public record DataHealthResponse(
    UUID projectId,
    int dprTotal,
    int supervisorIssues,
    int nullCategoryRows,
    int unitMismatches,
    int resourceLessDprs,
    int boqItems,
    int boqWithStaleRate,
    LocalDate minDate,
    LocalDate maxDate) {}
