package com.bipros.dbs.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lightweight snapshot of a {@link com.bipros.dbs.service.recompute.DbsRecomputeJob}
 * returned to the client. The frontend polls this until {@code status} reaches a
 * terminal state ({@code SUCCEEDED} or {@code FAILED}).
 */
public record DbsRecomputeJobDto(
    UUID jobId,
    String kind,
    String status,
    LocalDate fromDate,
    LocalDate toDate,
    int totalDays,
    int processedDays,
    Instant startedAt,
    Instant finishedAt,
    String errorMessage
) {}
