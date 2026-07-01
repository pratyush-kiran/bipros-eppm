package com.bipros.reporting.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Source model for the "Daily Activity Costing" workbook (one sheet per month). Each {@link Block}
 * is one APPROVED DPR row; its Manpower / PmV / Material / Subcontract line-items stack in parallel
 * columns under the activity. All money/qty values are computed from our own DB — the sample
 * template's external {@code VLOOKUP}s into rate-master / BOQ workbooks are replaced by
 * {@code line_cost}, {@code nos × unit_rate}, and {@code boq_items.boq_qty}.
 */
public record DprCostingReport(
    String projectName,
    List<Block> blocks
) {
  /** One activity block (= one APPROVED DPR row), ordered by date then BOQ item no. */
  public record Block(
      LocalDate date,
      String site,
      String location,
      Long chainageFrom,
      Long chainageTo,
      String side,
      String activityCode,
      String unit,
      BigDecimal executedQty,
      String remarks,
      String supervisorName,
      /**
       * Total Qty for this row — the BOQ item's {@code qty_executed_to_date}, matched by Activity
       * Code ({@code item_no}); null when no matching BOQ item. Drives Progress % / Progress Length.
       */
      BigDecimal totalQty,
      List<Manpower> manpower,
      List<Pmv> pmv,
      List<Material> material,
      List<SubContract> subContract
  ) {}

  /** Cost = nr × rate (equals the stored line cost). */
  public record Manpower(String category, BigDecimal nr, BigDecimal rate, BigDecimal cost) {}

  /** PmV = Plant, machinery & Vehicles (our Equipment). Cost = nr × rate. */
  public record Pmv(String detail, BigDecimal nr, BigDecimal rate, BigDecimal cost) {}

  /** Cost is the stored line cost (= quantity × rate). */
  public record Material(
      String description, String unit, BigDecimal quantity, BigDecimal rate, BigDecimal cost) {}

  /** Cost = quantity × rate (rate from the assignment snapshot). */
  public record SubContract(
      String name, String workDescription, String unit,
      BigDecimal quantity, BigDecimal rate, BigDecimal cost) {}
}
