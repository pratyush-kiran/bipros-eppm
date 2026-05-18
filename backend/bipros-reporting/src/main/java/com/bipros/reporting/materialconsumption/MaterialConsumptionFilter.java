package com.bipros.reporting.materialconsumption;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Filter inputs for the Material Consumption Report. All fields are optional except
 * {@code projectId}, {@code from} and {@code to}. {@code groupBy} accepts
 * {@code DAY | MATERIAL | ACTIVITY | SUPERVISOR} or {@code null} (no grouping — one
 * row per consumption-log entry).
 */
public record MaterialConsumptionFilter(
    UUID projectId,
    LocalDate from,
    LocalDate to,
    UUID wbsNodeId,
    UUID activityId,
    UUID supervisorUserId,
    UUID storekeeperUserId,
    UUID materialRateMasterId,
    String groupBy) {}
