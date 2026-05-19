package com.bipros.project.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Output of the productivity-preview computation. {@code expectedBottleneck} is the
 * {@code min(manpower, equipment)} of the two non-null sums — the realistic baseline for what
 * the activity can produce today given the rows logged so far.
 *
 * <p>{@code source} tells the UI which side(s) actually contributed a number to the bottleneck:
 * {@code BOTH} / {@code MANPOWER_ONLY} / {@code EQUIPMENT_ONLY} / {@code NONE}. {@code coverage}
 * is the orthogonal "what does this Work Activity track at all" signal: the form uses it to
 * decide whether to render the Manpower or Equipment side at all (avoids the misleading
 * "Manpower: —" on an equipment-only activity).
 *
 * <p>{@code warnings} only fires for sides the Work Activity actually tracks — a row whose role
 * has no matching norm on a side the WA does track is a real config gap worth flagging.
 */
public record ProductivityPreviewResponse(
    BigDecimal expectedFromManpower,
    BigDecimal expectedFromEquipment,
    BigDecimal expectedBottleneck,
    String source,
    /** Coverage summary echoed from the Work Activity: MANPOWER_ONLY | EQUIPMENT_ONLY | BOTH | NONE | NO_WORK_ACTIVITY. */
    String coverage,
    /** How MP + EQ were combined: SERIES (min) | PARALLEL (sum) | SUBSTITUTE (max). Echoed from
     *  the Work Activity so the UI banner can phrase the explanation correctly. */
    String normCombination,
    List<String> warnings) {}
