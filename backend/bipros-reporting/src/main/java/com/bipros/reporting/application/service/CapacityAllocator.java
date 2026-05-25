package com.bipros.reporting.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure allocator. Splits a DPR's workdone quantity across the roles on one side (manpower OR
 * equipment) of an activity, in proportion to each role's expected contribution
 * (= resolvedNorm × NOS). Untracked roles (no resolved norm) get null allocation and a
 * {@code normResolved=false} flag — they don't share in the qty split.
 *
 * <p>Whether the side is hidden (SERIES losing side, SUBSTITUTE redundant side) is decided here
 * given the other side's expected total. PARALLEL distributes proportionally and never hides.
 *
 * <p>The allocator is intentionally free of EntityManager / DB / DTO dependencies so it can be
 * unit-tested as a pure function. {@link CapacityUtilizationReportService} resolves norms,
 * groups DPR rows, calls this allocator once per (DPR, activity, side), and rolls the result
 * up into role-period buckets.
 */
public final class CapacityAllocator {

  private CapacityAllocator() {}

  /** Per-role input to allocation: identity + headcount + resolved norm (null if untracked). */
  public record RoleInput(UUID roleId, int nos, BigDecimal resolvedNorm) {
    public BigDecimal expectedContribution() {
      if (resolvedNorm == null || resolvedNorm.signum() <= 0 || nos <= 0) return BigDecimal.ZERO;
      return resolvedNorm.multiply(BigDecimal.valueOf(nos));
    }
  }

  /** Per-role allocation output: allocated qty + whether the role's norm resolved. */
  public record RoleAlloc(UUID roleId, BigDecimal allocatedQty, boolean normResolved) {}

  /**
   * Aggregate result. {@code hidden=true} means this whole side is suppressed for the activity
   * (SERIES losing side / SUBSTITUTE redundant side) — frontend renders a single "not applicable"
   * banner instead of role rows. {@code roleAllocations} preserves input order so callers can
   * zip it back against their input list by index.
   */
  public record AllocationResult(boolean hidden, List<RoleAlloc> roleAllocations) {}

  /**
   * Compute per-role allocations for one side (manpower OR equipment) of an activity.
   *
   * @param sideExpected      this side's expected output (Σ norm × NOS across its roles). Null or
   *                          ≤ 0 → side has nothing on it (returns hidden=true with empty allocs).
   * @param otherSideExpected the OTHER side's expected output. Null or ≤ 0 means this is a
   *                          single-side activity — this side gets the full {@code qtyDone}.
   * @param qtyDone           the DPR's workdone quantity for this activity. Null or ≤ 0 → all
   *                          allocations are ZERO (still visible).
   * @param normCombination   {@code "SERIES"}, {@code "PARALLEL"}, or {@code "SUBSTITUTE"}.
   *                          Null or unrecognised values fall back to SERIES so legacy / seeded
   *                          rows without an explicit combination still get sensible behaviour.
   * @param roles             one entry per role on this side (NOS pre-summed if multiple DPR
   *                          rows touched the same role). Order is preserved in the result.
   */
  public static AllocationResult allocate(
      BigDecimal sideExpected,
      BigDecimal otherSideExpected,
      BigDecimal qtyDone,
      String normCombination,
      List<RoleInput> roles) {

    BigDecimal sideShare = computeSideShare(sideExpected, otherSideExpected, qtyDone, normCombination);
    if (sideShare == null) {
      return new AllocationResult(true, emptyAllocs(roles));
    }
    return new AllocationResult(false, distributeByContribution(sideShare, roles));
  }

  /**
   * Decide how much of {@code qtyDone} belongs to this side. Returns null if the side is hidden.
   *
   * <ul>
   *   <li>Single-side activity (other side expected ≤ 0): this side gets the full qty.</li>
   *   <li>SERIES: smaller expected wins (gets full qty). Tie → both shown, each side gets full
   *       qty.</li>
   *   <li>PARALLEL: proportional to side's share of total expected.</li>
   *   <li>SUBSTITUTE: larger expected wins. Tie → both shown.</li>
   * </ul>
   */
  static BigDecimal computeSideShare(
      BigDecimal sideExpected, BigDecimal otherSideExpected,
      BigDecimal qtyDone, String normCombination) {
    if (qtyDone == null || qtyDone.signum() <= 0) return BigDecimal.ZERO;
    boolean hasSide = sideExpected != null && sideExpected.signum() > 0;
    boolean hasOther = otherSideExpected != null && otherSideExpected.signum() > 0;
    if (!hasSide) return null;
    if (!hasOther) return qtyDone;
    String combo = normCombination == null ? "SERIES" : normCombination.toUpperCase();
    switch (combo) {
      case "PARALLEL" -> {
        BigDecimal total = sideExpected.add(otherSideExpected);
        return qtyDone.multiply(sideExpected).divide(total, 4, RoundingMode.HALF_UP);
      }
      case "SUBSTITUTE" -> {
        int c = sideExpected.compareTo(otherSideExpected);
        return c >= 0 ? qtyDone : null;
      }
      default -> {
        int c = sideExpected.compareTo(otherSideExpected);
        return c <= 0 ? qtyDone : null;
      }
    }
  }

  /** Distribute {@code sideShare} across roles by expected-contribution share. */
  static List<RoleAlloc> distributeByContribution(BigDecimal sideShare, List<RoleInput> roles) {
    BigDecimal totalContrib = BigDecimal.ZERO;
    for (RoleInput r : roles) totalContrib = totalContrib.add(r.expectedContribution());

    List<RoleAlloc> out = new ArrayList<>(roles.size());
    if (totalContrib.signum() <= 0 || sideShare == null) {
      for (RoleInput r : roles) {
        out.add(new RoleAlloc(r.roleId(), null, false));
      }
      return out;
    }
    for (RoleInput r : roles) {
      BigDecimal contrib = r.expectedContribution();
      boolean tracked = contrib.signum() > 0;
      BigDecimal alloc;
      if (!tracked) {
        alloc = null;
      } else if (sideShare.signum() == 0) {
        alloc = BigDecimal.ZERO;
      } else {
        alloc = sideShare.multiply(contrib).divide(totalContrib, 4, RoundingMode.HALF_UP);
      }
      out.add(new RoleAlloc(r.roleId(), alloc, tracked));
    }
    return out;
  }

  /**
   * Allocations for a hidden side: every role gets a null qty (no share of work to claim), but
   * {@code normResolved} still reflects whether the role HAD a resolvable norm — the side lost,
   * the role's norm itself didn't disappear. Callers can use this to distinguish "tracked role
   * on a hidden side" from "untracked role on a visible side."
   */
  private static List<RoleAlloc> emptyAllocs(List<RoleInput> roles) {
    List<RoleAlloc> out = new ArrayList<>(roles.size());
    for (RoleInput r : roles) {
      out.add(new RoleAlloc(r.roleId(), null, r.expectedContribution().signum() > 0));
    }
    return out;
  }
}
