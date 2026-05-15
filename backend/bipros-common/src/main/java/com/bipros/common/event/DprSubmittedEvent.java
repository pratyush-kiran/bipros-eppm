package com.bipros.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by {@code DailyProgressReportService} after a DPR row is committed (CREATED,
 * UPDATED, or DELETED). Triggers BOQ qty sync (via {@code DprBoqSyncListener}) and analytics
 * ETL into {@code fact_dpr_logs} / {@code fact_activity_progress_daily} (and the per-resource
 * fact tables when {@code resourceCounts} is non-zero).
 *
 * <p>For UPDATED, {@code qtyExecuted} / {@code boqItemNo} are the new values; {@code oldQty}
 * / {@code oldBoqItemNo} hold the prior values so a listener can apply a delta. For DELETED,
 * the new values mirror the deleted row (so the listener can subtract its qty from BOQ); the
 * {@code dprId} still resolves to a (now-removed) row and the event carries enough state to
 * skip a follow-up read.
 *
 * <p>{@code manpowerCount}/{@code equipmentCount}/{@code materialCount}/{@code issueCount} let
 * the analytics listener know whether to fetch and ETL the new per-resource child rows or issue
 * rows (avoids a needless extra query when there are none).
 */
public record DprSubmittedEvent(
    UUID projectId,
    UUID dprId,
    LocalDate reportDate,
    String activityName,
    String boqItemNo,
    BigDecimal qtyExecuted,
    String oldBoqItemNo,
    BigDecimal oldQty,
    DprMutationType eventType,

    int manpowerCount,
    int equipmentCount,
    int materialCount,
    int issueCount,
    BigDecimal totalManpowerHours,
    BigDecimal totalEquipmentHours,
    BigDecimal totalFuelLitres,

    /**
     * Soft FK to {@code public.users.id}. The supervisor (an application user, not a Resource)
     * who oversees this DPR. Replaces the legacy {@code supervisorResourceId} on the analytics
     * feed; the OLTP DPR column it mirrors was renamed in Liquibase 087 (added supervisor_user_id)
     * and Liquibase 091 (drops supervisor_resource_id). Null when the DPR has free-text "Other".
     */
    UUID supervisorUserId
) {
    /** Back-compat helper for callers that don't have child counts on hand (e.g. DELETE). */
    public static DprSubmittedEvent withoutChildren(
            UUID projectId, UUID dprId, LocalDate reportDate, String activityName,
            String boqItemNo, BigDecimal qtyExecuted, String oldBoqItemNo, BigDecimal oldQty,
            DprMutationType eventType) {
        return new DprSubmittedEvent(
                projectId, dprId, reportDate, activityName, boqItemNo, qtyExecuted,
                oldBoqItemNo, oldQty, eventType,
                0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null);
    }
}
