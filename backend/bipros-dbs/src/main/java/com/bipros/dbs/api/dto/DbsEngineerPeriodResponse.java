package com.bipros.dbs.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Period rollup envelope for one engineer. See {@link DbsSupervisorPeriodResponse} for
 * range / zero-fill semantics.
 */
public record DbsEngineerPeriodResponse(
    String periodType,
    LocalDate from,
    LocalDate to,
    DbsEngineerDayResponse totals,
    List<DbsEngineerDayResponse> dailyRows
) {}
