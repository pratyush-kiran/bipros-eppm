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
 * <p>OT multiplier is a placeholder constant for phase 1. Phase 2 looks up an OVERTIME
 * {@link com.bipros.resource.domain.model.ResourceRate} row and applies it per-hour.
 */
public final class DprCostFormulas {

  /** OT cost premium for phase 1. Replace with a per-resource OVERTIME rate lookup in phase 2. */
  public static final BigDecimal DEFAULT_OT_MULTIPLIER = new BigDecimal("1.5");

  private DprCostFormulas() {}

  /**
   * Manpower row cost. {@code basis = "HOUR"} → {@code rate × nos × (workingHours + ot × OT_MULT)}.
   * Anything else (DAY / SHIFT / EACH / unknown) → {@code rate × nos}, with hours informational.
   *
   * <p>When basis is HOUR but the client omitted {@code workingHours} (and {@code otHours}), we
   * fall back to {@code rate × nos} rather than collapsing to zero — that lets a sparse DPR row
   * (rate × headcount only) still produce a sensible per-day cost instead of ₹0.
   */
  public static BigDecimal manpowerLineCost(DprManpower row, BigDecimal unitRate, String basis) {
    if (unitRate == null || row.getNos() == null || row.getNos() <= 0) return null;
    BigDecimal nos = BigDecimal.valueOf(row.getNos());
    if ("HOUR".equalsIgnoreCase(basis)) {
      BigDecimal hours = nz(row.getWorkingHours())
          .add(nz(row.getOtHours()).multiply(DEFAULT_OT_MULTIPLIER));
      if (hours.signum() == 0) {
        // No hours posted at all → fall back to per-day so we don't zero out the row.
        return unitRate.multiply(nos).setScale(2, RoundingMode.HALF_UP);
      }
      return unitRate.multiply(nos).multiply(hours).setScale(2, RoundingMode.HALF_UP);
    }
    return unitRate.multiply(nos).setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Manpower units rolled into the ledger (drives {@code actualUnits}). For HOUR-based resources
   * we report {@code nos × (workingHours + otHours)} — OT hours count toward effort but are not
   * inflated by the multiplier. For DAY-based resources we report {@code nos} (one day-equivalent
   * per person).
   */
  public static BigDecimal manpowerUnits(DprManpower row, String basis) {
    if (row.getNos() == null || row.getNos() <= 0) return BigDecimal.ZERO;
    BigDecimal nos = BigDecimal.valueOf(row.getNos());
    if ("HOUR".equalsIgnoreCase(basis)) {
      return nos.multiply(nz(row.getWorkingHours()).add(nz(row.getOtHours())));
    }
    return nos;
  }

  /**
   * Equipment row cost. Working hours are billed; idle / breakdown are not. Fuel cost is excluded
   * in phase 1 (no fuel-rate model yet) — fuel litres still persist on the row for analytics.
   */
  public static BigDecimal equipmentLineCost(DprEquipment row, BigDecimal unitRate, String basis) {
    if (unitRate == null || row.getNos() == null || row.getNos() <= 0) return null;
    BigDecimal nos = BigDecimal.valueOf(row.getNos());
    if ("HOUR".equalsIgnoreCase(basis)) {
      BigDecimal hours = nz(row.getWorkingHours());
      return unitRate.multiply(nos).multiply(hours).setScale(2, RoundingMode.HALF_UP);
    }
    return unitRate.multiply(nos).setScale(2, RoundingMode.HALF_UP);
  }

  /** Equipment units rolled into the ledger: {@code nos × workingHours} for HOUR basis, else nos. */
  public static BigDecimal equipmentUnits(DprEquipment row, String basis) {
    if (row.getNos() == null || row.getNos() <= 0) return BigDecimal.ZERO;
    BigDecimal nos = BigDecimal.valueOf(row.getNos());
    if ("HOUR".equalsIgnoreCase(basis)) {
      return nos.multiply(nz(row.getWorkingHours()));
    }
    return nos;
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

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
