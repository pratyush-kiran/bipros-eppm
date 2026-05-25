package com.bipros.project.application.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One page of the day-cursored DPR list. {@code nextCursor} is the oldest report date in
 * this batch; the next request passes it as {@code before} (exclusive) to fetch older days.
 * Null when {@code hasMore} is false.
 */
public record DprPage(
    List<DprSummaryResponse> items,
    LocalDate nextCursor,
    boolean hasMore
) {}
