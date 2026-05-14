package com.bipros.project.application.dto;

import com.bipros.project.domain.model.QcSession;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record QcSessionResponse(
    UUID id,
    UUID projectId,
    UUID activityId,
    String activityName,
    LocalDate testDate,
    String chainageFrom,
    String chainageTo,
    List<QcTestItemResponse> items,
    Instant createdAt,
    Instant updatedAt
) {
    public static QcSessionResponse from(QcSession s) {
        return new QcSessionResponse(
            s.getId(),
            s.getProjectId(),
            s.getActivityId(),
            s.getActivityName(),
            s.getTestDate(),
            s.getChainageFrom(),
            s.getChainageTo(),
            s.getItems().stream().map(QcTestItemResponse::from).toList(),
            s.getCreatedAt(),
            s.getUpdatedAt()
        );
    }
}
