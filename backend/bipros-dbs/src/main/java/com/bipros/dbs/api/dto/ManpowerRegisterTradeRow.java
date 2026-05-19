package com.bipros.dbs.api.dto;

import java.util.List;

/**
 * Pivoted manpower register row for one {@code trade} — gives the per-CM day/night
 * split + totals across all CMs.
 */
public record ManpowerRegisterTradeRow(
    String trade,
    List<CmShiftCount> byCm,
    int totalDay,
    int totalNight,
    int total
) {}
