package com.bipros.dbs.domain.event;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Fired after a manual expense is created / updated / deleted. Listener triggers a
 * DBS recompute for the (project, date) pair so the merged Section totals refresh.
 */
public record DbsManualExpenseSavedEvent(
    UUID projectId,
    LocalDate reportDate,
    UUID activityId
) {}
