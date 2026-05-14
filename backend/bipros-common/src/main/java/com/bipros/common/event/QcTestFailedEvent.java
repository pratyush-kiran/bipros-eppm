package com.bipros.common.event;

import java.util.UUID;

/**
 * Published when a QC test record is created or updated with outcome {@code FAIL}.
 * Serves as a hook point for future notification/email infrastructure.
 */
public record QcTestFailedEvent(
    UUID projectId,
    UUID activityId,
    UUID testRecordId,
    String testTypeName,
    String chainage,
    String sampleRefNo
) {
}
