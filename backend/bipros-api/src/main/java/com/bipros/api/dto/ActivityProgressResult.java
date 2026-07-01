package com.bipros.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ActivityProgressResult(
    UUID activityId, String activityCode, String activityName,
    String status,                 // GENERATED | DRY_RUN | SKIPPED_NO_SUPERVISOR | SKIPPED_EXISTING | FAILED
    UUID supervisorUserId, String supervisorName,
    UUID boqItemId, String boqItemNo, boolean boqFallback,
    Integer targetPercent, BigDecimal qtyTotal,
    List<LocalDate> datesUsed, List<UUID> dprIds,
    boolean renamed, boolean autoLocked,
    List<String> warnings) {}
