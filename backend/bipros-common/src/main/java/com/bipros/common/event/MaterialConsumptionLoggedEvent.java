package com.bipros.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by MaterialConsumptionLogService on CREATE/UPDATE/DELETE. Consumed by the
 * DBS rollup listener.
 */
public record MaterialConsumptionLoggedEvent(
    UUID projectId,
    UUID logId,
    LocalDate logDate,
    UUID activityId,
    UUID wbsNodeId,
    UUID materialRateMasterId,
    UUID issuedByUserId,
    UUID receivedByUserId,
    BigDecimal lineCost,
    EventType eventType
) {
    public enum EventType { CREATED, UPDATED, DELETED }
}
