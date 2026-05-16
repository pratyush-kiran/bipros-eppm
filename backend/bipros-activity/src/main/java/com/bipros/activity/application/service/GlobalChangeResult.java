package com.bipros.activity.application.service;

import java.util.List;

/**
 * Outcome of {@link GlobalChangeService#applyGlobalChange}. {@code updatedCount} is the
 * number of activities the change was applied to; {@code skippedLocked} is the number
 * skipped because they were in {@code LOCKED} edit-status. {@code skippedLockedCodes}
 * lists the codes so the UI can surface them in a toast.
 */
public record GlobalChangeResult(int updatedCount, int skippedLocked, List<String> skippedLockedCodes) {
}
