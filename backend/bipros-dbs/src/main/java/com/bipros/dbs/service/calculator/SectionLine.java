package com.bipros.dbs.service.calculator;

import java.math.BigDecimal;

/**
 * One line inside a DBS section accordion. All numeric fields may be null when the
 * source table didn't carry the value — the UI renders blanks for nulls rather than
 * fabricating zeros.
 */
public record SectionLine(
    String description,
    String unit,
    BigDecimal rate,
    BigDecimal quantity,
    BigDecimal totalAmount
) {}
