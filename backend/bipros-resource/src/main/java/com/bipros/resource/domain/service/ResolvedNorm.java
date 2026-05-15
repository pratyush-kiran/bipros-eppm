package com.bipros.resource.domain.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of resolving the productivity norm that applies to a given (work activity, resource)
 * pair. Records the source of the value so callers can show provenance and audit logic.
 */
public record ResolvedNorm(
    BigDecimal outputPerDay,
    String unit,
    Source source,
    UUID productivityNormId,
    UUID workActivityId,
    UUID resourceId
) {
  public enum Source {
    /** Role-keyed variant tier: (work activity, role, skill/grade or make/model). */
    VARIANT,
    /** Role-keyed role-only tier: (work activity, role). */
    ROLE,
    /** Unscoped tier: (work activity) — the 102 seeded rows fall here. */
    UNSCOPED,
    /** A {@code ProductivityNorm} row scoped to the specific resource. Legacy. */
    SPECIFIC_RESOURCE,
    /** A {@code ProductivityNorm} row scoped to the resource type. Legacy. */
    RESOURCE_TYPE,
    /** Fell back to {@code Resource.standardOutputPerDay}. */
    RESOURCE_LEGACY,
    /** No norm could be resolved for the inputs. */
    NONE
  }

  public static ResolvedNorm none(UUID workActivityId, UUID resourceId) {
    return new ResolvedNorm(null, null, Source.NONE, null, workActivityId, resourceId);
  }
}
