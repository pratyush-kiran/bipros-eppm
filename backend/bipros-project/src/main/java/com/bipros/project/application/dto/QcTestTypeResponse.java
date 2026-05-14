package com.bipros.project.application.dto;

import com.bipros.project.domain.model.QcTestType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QcTestTypeResponse(
    UUID id,
    UUID projectId,
    String name,
    String unit,
    BigDecimal ircThreshold,
    Boolean active,
    Instant createdAt,
    Instant updatedAt
) {
    public static QcTestTypeResponse from(QcTestType t) {
        return new QcTestTypeResponse(
            t.getId(),
            t.getProjectId(),
            t.getName(),
            t.getUnit(),
            t.getIrcThreshold(),
            t.getActive(),
            t.getCreatedAt(),
            t.getUpdatedAt()
        );
    }
}
