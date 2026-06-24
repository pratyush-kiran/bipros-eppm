package com.bipros.resource.application.service;

import com.bipros.common.exception.BusinessRuleException;

import java.math.BigDecimal;

/**
 * Guards a planned resource row that already has actual deployment recorded against it (rolled
 * up from DPRs into {@code actualUnits}). Such a row may not be deleted, may not have its planned
 * units reduced below what's already deployed, and may not have its role/variant identity changed.
 *
 * <p>These reject exactly the cases the {@code remainingUnits = max(plannedUnits - actualUnits, 0)}
 * formula would otherwise silently clamp to zero. No calculation is changed — the same stored
 * {@code actualUnits} the screen already displays is read here and compared against the same
 * {@code plannedUnits} that {@code remainingUnits} already subtracts.
 */
public final class ResourceDeploymentGuard {

  /** Tolerance so floating-point dust doesn't false-trigger and equal-to-actual passes. */
  private static final double EPS = 1e-9;

  private ResourceDeploymentGuard() {}

  /** True when the row has any DPR-deployed actual units. */
  public static boolean isDeployed(Double actualUnits) {
    return actualUnits != null && actualUnits > EPS;
  }

  /** Blocks deleting a row that already has DPR-deployed units. */
  public static void assertDeletable(Double actualUnits) {
    if (isDeployed(actualUnits)) {
      throw new BusinessRuleException(
          "RESOURCE_DEPLOYED_DELETE",
          "Can't delete this resource — " + fmt(actualUnits)
              + " unit(s) already deployed via DPRs. Remove the DPR entries first.");
    }
  }

  /** Blocks setting planned units below what's already deployed. No-op when nothing is deployed. */
  public static void assertNotReducedBelowActual(double newPlannedUnits, Double actualUnits) {
    double actual = actualUnits == null ? 0.0 : actualUnits;
    if (actual > EPS && newPlannedUnits + EPS < actual) {
      throw new BusinessRuleException(
          "RESOURCE_DEPLOYED_REDUCE",
          "Can't set planned units to " + fmt(newPlannedUnits) + " — " + fmt(actual)
              + " unit(s) already deployed via DPRs."
              + " Planned units can't be below what's already deployed.");
    }
  }

  /** Blocks changing the role/variant of a row that already has DPR-deployed units. */
  public static void assertIdentityUnchangedWhenDeployed(boolean identityChanged, Double actualUnits) {
    if (identityChanged && isDeployed(actualUnits)) {
      throw new BusinessRuleException(
          "RESOURCE_DEPLOYED_IDENTITY",
          "Can't change this resource's role or variant — " + fmt(actualUnits)
              + " unit(s) already deployed via DPRs against the current one."
              + " Delete the DPR usage first.");
    }
  }

  /** Renders whole numbers without a trailing ".0" so messages read "6" not "6.0". */
  private static String fmt(double v) {
    if (v == Math.rint(v) && !Double.isInfinite(v)) {
      return Long.toString((long) v);
    }
    return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
  }
}
