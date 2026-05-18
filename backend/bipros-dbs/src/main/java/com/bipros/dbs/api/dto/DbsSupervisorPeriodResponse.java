package com.bipros.dbs.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Period rollup envelope for one supervisor. {@code totals} is the SUM of {@code dailyRows};
 * {@code dailyRows} is zero-filled across the full {@code [from, to]} inclusive range so the
 * UI can render a sparse calendar without gaps.
 */
public record DbsSupervisorPeriodResponse(
    String periodType,
    LocalDate from,
    LocalDate to,
    DbsSupervisorDayResponse totals,
    List<DbsSupervisorDayResponse> dailyRows
) {}
