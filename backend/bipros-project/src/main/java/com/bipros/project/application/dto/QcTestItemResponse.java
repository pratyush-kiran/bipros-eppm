package com.bipros.project.application.dto;

import com.bipros.project.domain.model.QcOutcome;
import com.bipros.project.domain.model.QcTestItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QcTestItemResponse(
    UUID id,
    UUID testTypeId,
    String testTypeName,
    String sampleRefNo,
    BigDecimal testResult,
    BigDecimal requiredIrc,
    QcOutcome outcome,
    String labInspector,
    Instant createdAt
) {
    public static QcTestItemResponse from(QcTestItem i) {
        return new QcTestItemResponse(
            i.getId(),
            i.getTestTypeId(),
            i.getTestTypeName(),
            i.getSampleRefNo(),
            i.getTestResult(),
            i.getRequiredIrc(),
            i.getOutcome(),
            i.getLabInspector(),
            i.getCreatedAt()
        );
    }
}
