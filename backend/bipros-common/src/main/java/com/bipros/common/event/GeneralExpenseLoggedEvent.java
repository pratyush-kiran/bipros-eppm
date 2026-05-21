package com.bipros.common.event;

import java.util.UUID;

/**
 * Published by {@code GeneralExpenseService} after a monthly Section G entry
 * is committed (CREATE, UPDATE, or DELETE). Triggers a DBS recompute over
 * every day inside the affected {@code yearMonth} so the per-day prorated
 * Section G value stays consistent with the monthly total.
 *
 * <p>{@code yearMonth} is encoded as {@code year * 100 + month} (e.g. 202605).
 */
public record GeneralExpenseLoggedEvent(
    UUID projectId,
    Integer yearMonth,
    UUID planItemId,
    DprMutationType mutationType
) {
}
