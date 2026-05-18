package com.bipros.reporting.materialconsumption;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One row of the Material Consumption Report. Either a raw consumption-log projection (when
 * the filter has no {@code groupBy}) or an aggregated bucket (per day, material, activity or
 * supervisor). Every monetary / quantity field is nullable so a grouped row that lacks a
 * dimension can leave it blank.
 */
public record MaterialConsumptionRow(
    UUID projectId,
    LocalDate fromDate,
    LocalDate toDate,
    UUID wbsNodeId,
    String wbsName,
    UUID activityId,
    String activityName,
    UUID supervisorUserId,
    String supervisorName,
    UUID storekeeperUserId,
    String storekeeperName,
    UUID materialRateMasterId,
    String materialName,
    String unit,
    BigDecimal plannedQty,
    BigDecimal issuedQty,
    BigDecimal consumedQty,
    BigDecimal balanceQty,
    BigDecimal wastagePercent,
    BigDecimal unitRate,
    BigDecimal plannedCost,
    BigDecimal actualCost,
    BigDecimal variance,
    BigDecimal variancePercent,
    List<String> alerts) {}
