package com.bipros.dbs.service.calculator;

import java.math.BigDecimal;
import java.util.List;

/** Aggregate output of a single DBS section calculator for one (project, supervisor, date). */
public record SectionResult(BigDecimal totalAmount, List<SectionLine> lines) {

    public static SectionResult empty() {
        return new SectionResult(BigDecimal.ZERO, List.of());
    }
}
