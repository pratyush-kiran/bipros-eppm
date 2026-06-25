package com.bipros.project.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure, deterministic math for rescaling DPR resource quantities so capacity
 * efficiency (= (qty / norm) / nos) lands in the healthy band [0.85, 1.05].
 * No Spring, no I/O — unit-testable in isolation.
 */
public final class DprRescaleCalculator {

  private static final double EFF_LOW = 0.85;
  private static final double EFF_HIGH = 1.05;

  private DprRescaleCalculator() {}

  /** Deployed nos so that (qty/norm)/nos is in [EFF_LOW, EFF_HIGH]. 0 when no work or no norm. */
  public static int targetNos(BigDecimal qtyExecuted, BigDecimal normOutputPerDay, UUID dprId) {
    if (qtyExecuted == null || normOutputPerDay == null) return 0;
    if (qtyExecuted.signum() <= 0 || normOutputPerDay.signum() <= 0) return 0;
    double budgetDays = qtyExecuted.divide(normOutputPerDay, 6, RoundingMode.HALF_UP).doubleValue();
    double eff = EFF_LOW + jitter01(dprId) * (EFF_HIGH - EFF_LOW); // deterministic eff in band
    int nos = (int) Math.round(budgetDays / eff);
    return Math.max(nos, 1);
  }

  /** Split targetTotal across rows preserving the current ratio; even split if all zero. */
  public static List<Integer> distribute(int targetTotal, List<Integer> currentNos) {
    int n = currentNos.size();
    List<Integer> out = new ArrayList<>(n);
    if (n == 0) return out;
    if (targetTotal <= 0) { for (int i = 0; i < n; i++) out.add(0); return out; }
    int weightSum = currentNos.stream().mapToInt(v -> Math.max(v, 0)).sum();
    int assigned = 0;
    for (int i = 0; i < n; i++) {
      int share = weightSum == 0
          ? targetTotal / n
          : (int) Math.floor((double) targetTotal * Math.max(currentNos.get(i), 0) / weightSum);
      out.add(share);
      assigned += share;
    }
    for (int i = 0; assigned < targetTotal; i = (i + 1) % n) { out.set(i, out.get(i) + 1); assigned++; }
    return out;
  }

  /** Stable [0,1) value derived from the DPR id — same id always yields the same jitter. */
  private static double jitter01(UUID dprId) {
    long h = dprId == null ? 0L : (dprId.getMostSignificantBits() ^ dprId.getLeastSignificantBits());
    return (Math.floorMod(h, 1000)) / 1000.0;
  }
}
