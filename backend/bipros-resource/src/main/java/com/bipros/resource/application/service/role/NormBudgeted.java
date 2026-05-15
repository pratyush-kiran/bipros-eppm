package com.bipros.resource.application.service.role;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reusable, kind-agnostic projection of a productivity norm. Used by every caller that needs a
 * "what is the standard output for this norm" answer: capacity-utilization report, supervisor
 * performance report, DPR productivity preview, and the activity-planning {@code suggestUnits}
 * flow.
 *
 * <p>Consumers pick {@link #outputPerManPerDay()} for MANPOWER rollups and {@link #outputPerDay()}
 * for EQUIPMENT rollups. {@link #source} carries the tier that produced the match
 * ({@code VARIANT}, {@code ROLE}, or {@code UNSCOPED}) so the report can show provenance.
 */
public record NormBudgeted(
    BigDecimal outputPerDay,
    BigDecimal outputPerManPerDay,
    BigDecimal outputPerHour,
    Double workingHoursPerDay,
    UUID normId,
    String source,
    String normType) {

  public static NormBudgeted none() {
    return new NormBudgeted(null, null, null, null, null, "NONE", null);
  }

  public boolean isPresent() {
    return normId != null;
  }
}
