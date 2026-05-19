package com.bipros.resource.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * "What does productivity tracking look like for this Work Activity?"
 *
 * <p>One source of truth consumed by:
 * <ul>
 *   <li>The Activity edit page (under the Work Activity picker) — so the planner sees
 *       up-front whether picking this Work Activity will track Manpower, Equipment, both,
 *       or neither.</li>
 *   <li>The DPR form (banner above the resource tabs) — so the supervisor knows which
 *       side(s) drive the expected output for this DPR.</li>
 *   <li>The {@code DprProductivityPreviewService} — so warnings only fire for sides the
 *       Work Activity actually tracks (no more "missing manpower norm" on an
 *       Equipment-only activity).</li>
 * </ul>
 *
 * <p>{@code summary} ∈ {@code MANPOWER_ONLY}, {@code EQUIPMENT_ONLY}, {@code BOTH},
 * {@code NONE} — a convenience for UI label switching.
 */
public record ProductivityCoverageResponse(
    UUID workActivityId,
    String workActivityName,
    String defaultUnit,
    Side manpower,
    Side equipment,
    String summary,
    /** Echoed from the Work Activity master so the UI can name the rule in its banner copy:
     *  {@code SERIES} (min) | {@code PARALLEL} (sum) | {@code SUBSTITUTE} (max). */
    String normCombination) {

  public record Side(
      boolean configured,
      List<NormSummary> norms) {}

  public record NormSummary(
      String scope,            // VARIANT | ROLE | UNSCOPED
      String label,            // human-readable: e.g. "MASON / Skilled / A" or "Front End Loader"
      BigDecimal outputPerDay,
      BigDecimal outputPerManPerDay,
      Double workingHoursPerDay) {}
}
