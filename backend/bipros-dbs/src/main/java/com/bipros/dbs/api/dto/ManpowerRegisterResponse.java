package com.bipros.dbs.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Pivoted Manpower Register response — one row per trade, each broken down
 * by CM × shift. Powers the PM tab's Manpower Register panel.
 */
public record ManpowerRegisterResponse(
    LocalDate date,
    List<ManpowerRegisterTradeRow> manpower
) {}
