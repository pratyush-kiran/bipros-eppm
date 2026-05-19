package com.bipros.dbs.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 6 — cumulative equipment-days / manpower-days response for the
 * {@code /register/cumulative} endpoint. Matches the Excel template's
 * "Eqpmnt & MP Days" sheet shape: one running total per equipment type or
 * trade, summed over all dates {@code <= asOfDate} (optionally filtered by CM).
 */
public record CumulativeDaysResponse(
    LocalDate asOfDate,
    List<CumulativeEquipmentDays> equipment,
    List<CumulativeManpowerDays> manpower
) {
    public record CumulativeEquipmentDays(String type, long days) {}
    public record CumulativeManpowerDays(String trade, long days) {}
}
