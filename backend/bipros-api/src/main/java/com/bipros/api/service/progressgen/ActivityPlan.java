package com.bipros.api.service.progressgen;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ActivityPlan(
    UUID activityId, String activityCode, String activityName, String unit,
    UUID boqItemId, String boqItemNo, boolean boqFallback,
    UUID supervisorUserId, String supervisorName,    // first supervisor (for the report header)
    int targetPercent, BigDecimal qtyTotal,
    boolean needsRename, String newName, boolean needsLock,
    Set<UUID> scAssignmentIds, List<String> warnings,
    List<PlannedDpr> dprs) {}
