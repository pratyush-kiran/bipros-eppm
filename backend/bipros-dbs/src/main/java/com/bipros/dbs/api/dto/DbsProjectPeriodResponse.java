package com.bipros.dbs.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Period rollup envelope at project (PM) level. See {@link DbsSupervisorPeriodResponse}
 * for range / zero-fill semantics. {@code totals.cumulative*} reflects cumulative through
 * {@code to}, not through each daily row.
 */
public record DbsProjectPeriodResponse(
    String periodType,
    LocalDate from,
    LocalDate to,
    DbsProjectDayResponse totals,
    List<DbsProjectDayResponse> dailyRows
) {}
