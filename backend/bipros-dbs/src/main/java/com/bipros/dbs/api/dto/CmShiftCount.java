package com.bipros.dbs.api.dto;

import java.util.UUID;

/**
 * Per-CM, per-shift count of equipment/manpower units deployed on one day. Null
 * {@code cmUserId} carries the unattached bucket (supervisors with no CM in chain).
 */
public record CmShiftCount(
    UUID cmUserId,
    String cmName,
    int day,
    int night,
    int total
) {}
