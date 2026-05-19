package com.bipros.dbs.api.dto;

import java.util.List;

/**
 * Pivoted equipment register row for one {@code equipment_type} — gives the per-CM
 * day/night split + totals across all CMs. {@code byCm} entries with null
 * {@code cmUserId} are the unattached bucket.
 */
public record EquipmentRegisterTypeRow(
    String type,
    List<CmShiftCount> byCm,
    int totalDay,
    int totalNight,
    int total
) {}
