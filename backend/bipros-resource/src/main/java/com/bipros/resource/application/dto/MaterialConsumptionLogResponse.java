package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.MaterialConsumptionLog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MaterialConsumptionLogResponse(
    UUID id,
    UUID projectId,
    LocalDate logDate,
    UUID resourceId,
    String materialName,
    String unit,
    BigDecimal openingStock,
    BigDecimal received,
    BigDecimal consumed,
    BigDecimal closingStock,
    BigDecimal wastagePercent,
    String issuedBy,
    String receivedBy,
    UUID issuedByUserId,
    UUID receivedByUserId,
    UUID wbsNodeId,
    UUID activityId,
    BigDecimal unitRate,
    BigDecimal lineCost,
    UUID materialRateMasterId,
    String enteredByRole,
    String remarks,
    Instant createdAt,
    String createdBy
) {
  public static MaterialConsumptionLogResponse from(MaterialConsumptionLog entity) {
    return new MaterialConsumptionLogResponse(
        entity.getId(),
        entity.getProjectId(),
        entity.getLogDate(),
        entity.getResourceId(),
        entity.getMaterialName(),
        entity.getUnit(),
        entity.getOpeningStock(),
        entity.getReceived(),
        entity.getConsumed(),
        entity.getClosingStock(),
        entity.getWastagePercent(),
        entity.getIssuedBy(),
        entity.getReceivedBy(),
        entity.getIssuedByUserId(),
        entity.getReceivedByUserId(),
        entity.getWbsNodeId(),
        entity.getActivityId(),
        entity.getUnitRate(),
        entity.getLineCost(),
        entity.getMaterialRateMasterId(),
        entity.getEnteredByRole(),
        entity.getRemarks(),
        entity.getCreatedAt(),
        entity.getCreatedBy());
  }
}
