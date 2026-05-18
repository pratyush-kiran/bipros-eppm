package com.bipros.reporting.materialconsumption;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-function alert evaluator for the Material Consumption Report. Codes:
 * <ul>
 *   <li>{@code EXCESS_CONSUMPTION} — consumed &gt; 1.10 × planned (only when planned &gt; 0).</li>
 *   <li>{@code NEGATIVE_BALANCE} — balanceQty &lt; 0.</li>
 *   <li>{@code BUDGET_OVERCONSUMPTION} — actualCost &gt; plannedCost (only when plannedCost &gt; 0).</li>
 *   <li>{@code MISSING_UNIT_RATE} — consumedQty &gt; 0 AND (unitRate is null or ≤ 0).</li>
 * </ul>
 */
public final class MaterialConsumptionAlertEvaluator {

  public static final String EXCESS_CONSUMPTION = "EXCESS_CONSUMPTION";
  public static final String NEGATIVE_BALANCE = "NEGATIVE_BALANCE";
  public static final String BUDGET_OVERCONSUMPTION = "BUDGET_OVERCONSUMPTION";
  public static final String MISSING_UNIT_RATE = "MISSING_UNIT_RATE";

  private static final BigDecimal EXCESS_THRESHOLD = new BigDecimal("1.10");

  private MaterialConsumptionAlertEvaluator() {}

  public static List<String> evaluate(
      BigDecimal plannedQty,
      BigDecimal consumedQty,
      BigDecimal balanceQty,
      BigDecimal plannedCost,
      BigDecimal actualCost,
      BigDecimal unitRate) {
    List<String> alerts = new ArrayList<>(2);
    BigDecimal consumed = nz(consumedQty);
    BigDecimal planned = nz(plannedQty);
    if (planned.signum() > 0 && consumed.compareTo(planned.multiply(EXCESS_THRESHOLD)) > 0) {
      alerts.add(EXCESS_CONSUMPTION);
    }
    if (balanceQty != null && balanceQty.signum() < 0) {
      alerts.add(NEGATIVE_BALANCE);
    }
    if (plannedCost != null
        && plannedCost.signum() > 0
        && actualCost != null
        && actualCost.compareTo(plannedCost) > 0) {
      alerts.add(BUDGET_OVERCONSUMPTION);
    }
    if (consumed.signum() > 0 && (unitRate == null || unitRate.signum() <= 0)) {
      alerts.add(MISSING_UNIT_RATE);
    }
    return alerts;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
