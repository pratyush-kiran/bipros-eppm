package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.Side;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Slim DPR list row for the paginated DPR tab. Carries only the parent fields the
 * Day -> Activity -> Work-front grouping and the collapsed row need, plus precomputed
 * child aggregates (counts / sums) so the frontend does not hydrate full child arrays
 * just to render count chips. Full child detail comes from GET /dpr/{id} on expand.
 *
 * <p>cumulativeQty is deliberately absent: it is a project-to-date running figure that
 * cannot be computed correctly from a paginated subset; the detail GET computes it.
 */
public record DprSummaryResponse(
    UUID id,
    UUID projectId,
    LocalDate reportDate,
    UUID supervisorUserId,
    String supervisorName,
    Long chainageFromM,
    Long chainageToM,
    UUID activityId,
    String activityName,
    String boqItemNo,
    String unit,
    BigDecimal qtyExecuted,
    Side side,
    DprApprovalStatus approvalStatus,
    String weatherCondition,
    long manpowerNos,
    long equipmentNos,
    int materialCount,
    int photoCount,
    int issueCount,
    int openIssueCount,
    boolean hasCriticalOpen
) {}
