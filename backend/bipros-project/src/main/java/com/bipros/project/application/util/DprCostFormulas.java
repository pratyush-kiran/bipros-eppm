package com.bipros.project.application.util;

import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-row cost + units formulas applied at DPR save time. Outputs feed two snapshots:
 * <ul>
 *   <li>{@code line_cost} on the DPR child row (persisted, used by reports without recompute).</li>
 *   <li>{@code units} aggregated into {@code daily_activity_resource_outputs} per
 *       (activity, resource), which the existing rollup chain uses to update
 *       {@code ResourceAssignment.actualUnits} and AC, and through the rolled-up event,
 *       {@code Activity.unitsPercentComplete}.</li>
 * </ul>
 *
 * <p><b>Cost rule:</b> {@code cost = nos × rate}; {@code units = nos}. The DPR's
 * {@code working_hours} and {@code ot_hours} columns are logging fields only — never
 * multiplied into cost or units. The {@code basis} string is preserved on the call so
 * existing call sites compile, but is intentionally not consulted.
 *
 * <p>This mirrors the canonical Resource Plan formula in
 * {@code ResourceAssignmentCostRollupListener}: {@code actualCost = rate × actualUnits}.
 */
public final class DprCostFormulas {

  private DprCostFormulas() {}

  /** Manpower row cost: {@code rate × nos}. Hours and OT hours are informational only. */
  public static BigDecimal manpowerLineCost(DprManpower row, BigDecimal unitRate, String basis) {
    if (unitRate == null || row.getNos() == null || row.getNos() <= 0) return null;
    BigDecimal nos = BigDecimal.valueOf(row.getNos());
    return unitRate.multiply(nos).setScale(2, RoundingMode.HALF_UP);
  }

  /** Manpower units rolled into the ledger: {@code nos}. Hours never enter unit counts. */
  public static BigDecimal manpowerUnits(DprManpower row, String basis) {
    if (row.getNos() == null || row.getNos() <= 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf(row.getNos());
  }

  /** Equipment row cost: {@code rate × nos}. Working hours are logging-only. */
  public static BigDecimal equipmentLineCost(DprEquipment row, BigDecimal unitRate, String basis) {
    if (unitRate == null || row.getNos() == null || row.getNos() <= 0) return null;
    BigDecimal nos = BigDecimal.valueOf(row.getNos());
    return unitRate.multiply(nos).setScale(2, RoundingMode.HALF_UP);
  }

  /** Equipment units rolled into the ledger: {@code nos}. */
  public static BigDecimal equipmentUnits(DprEquipment row, String basis) {
    if (row.getNos() == null || row.getNos() <= 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf(row.getNos());
  }

  /** Material row cost: {@code rate × quantity}. */
  public static BigDecimal materialLineCost(DprMaterial row, BigDecimal unitRate) {
    if (unitRate == null || row.getQuantity() == null) return null;
    return unitRate.multiply(row.getQuantity()).setScale(2, RoundingMode.HALF_UP);
  }

  /** Material units rolled into the ledger: {@code quantity}. */
  public static BigDecimal materialUnits(DprMaterial row) {
    return row.getQuantity() == null ? BigDecimal.ZERO : row.getQuantity();
  }
}
