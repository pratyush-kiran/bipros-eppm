package com.bipros.api.dto;

import com.bipros.project.domain.model.WbsPhase;
import com.bipros.project.domain.model.WbsType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Flat per-work-package row used by the Work Packages list page. Each row corresponds to a single
 * leaf WBS node (typically {@code WbsType.WORK_PACKAGE}) and bundles together:
 *
 * <ul>
 *   <li>identity columns from {@link com.bipros.project.domain.model.WbsNode}</li>
 *   <li>activity-derived aggregates (counts, weighted % complete, derived planned dates, min float)</li>
 *   <li>EVM passthroughs from the latest {@link com.bipros.evm.application.dto.WbsEvmNode}</li>
 *   <li>a derived schedule/cost status the UI renders as a status pill</li>
 * </ul>
 *
 * <p>All aggregate fields are nullable so the UI can render "—" when the underlying activity or
 * EVM record is missing (typical for newly-created WBS nodes with no activities yet).
 */
public record WorkPackageRowResponse(
    UUID wbsNodeId,
    String code,
    String name,
    UUID parentId,
    String parentName,
    WbsType wbsType,
    Integer wbsLevel,
    WbsPhase phase,

    UUID contractorOrganisationId,
    String contractorName,

    LocalDate derivedPlannedStart,
    LocalDate derivedPlannedFinish,
    Long derivedDurationDays,

    Double weightedPercentComplete,
    Long daysBehindSchedule,

    BigDecimal budgetCrores,

    Long activityCountTotal,
    Long activityCountDone,
    Long activityCountInProgress,
    Long activityCountNotStarted,
    Long activityCountDelayed,

    BigDecimal bac,
    BigDecimal plannedValue,
    BigDecimal earnedValue,
    BigDecimal actualCost,
    BigDecimal scheduleVariance,
    BigDecimal costVariance,
    Double spi,
    Double cpi,
    BigDecimal eac,
    BigDecimal vac,

    Double minTotalFloat,
    Boolean onCriticalPath,

    String derivedStatus
) {
}
