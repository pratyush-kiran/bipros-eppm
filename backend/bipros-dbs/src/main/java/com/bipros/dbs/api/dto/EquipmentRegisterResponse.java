package com.bipros.dbs.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Pivoted Equipment Register response — one row per equipment type, each broken down
 * by CM × shift. Powers the PM tab's Equipment Register panel.
 */
public record EquipmentRegisterResponse(
    LocalDate date,
    List<EquipmentRegisterTypeRow> equipment
) {}
