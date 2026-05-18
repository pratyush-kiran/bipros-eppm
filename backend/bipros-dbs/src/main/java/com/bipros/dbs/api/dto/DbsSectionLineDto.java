package com.bipros.dbs.api.dto;

import java.math.BigDecimal;

/**
 * Wire-shape mirror of {@code com.bipros.dbs.service.calculator.SectionLine}. Kept
 * decoupled from the calculator record so we can evolve the API contract independently
 * of the internal compute pipeline (e.g. add localisation, formatting hints).
 */
public record DbsSectionLineDto(
    String description,
    String unit,
    BigDecimal rate,
    BigDecimal quantity,
    BigDecimal totalAmount
) {}
