package com.bipros.project.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Project-level Bill of Quantities line item. Stores the raw inputs from the contract BOQ
 * (boqQty, boqRate) and the project-team's budgeted & actual unit rates; the derived amounts
 * and variance are persisted alongside for query speed but are always recomputed on every write
 * by {@link com.bipros.project.application.service.BoqCalculator}.
 */
@Entity
@Table(
    name = "boq_items",
    schema = "project",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_boq_project_item_no", columnNames = {"project_id", "item_no"})
    },
    indexes = {
        @Index(name = "idx_boq_project", columnList = "project_id"),
        @Index(name = "idx_boq_wbs_node", columnList = "wbs_node_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoqItem extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "item_no", nullable = false, length = 20)
  private String itemNo;

  @Column(name = "description", nullable = false, length = 500)
  private String description;

  @Column(name = "unit", nullable = false, length = 20)
  private String unit;

  @Column(name = "wbs_node_id")
  private UUID wbsNodeId;

  /** Contract BOQ quantity. */
  @Column(name = "boq_qty", precision = 18, scale = 3)
  private BigDecimal boqQty;

  /** Contract BOQ unit rate. */
  @Column(name = "boq_rate", precision = 18, scale = 4)
  private BigDecimal boqRate;

  /** Derived: boqQty × boqRate. */
  @Column(name = "boq_amount", precision = 19, scale = 2)
  private BigDecimal boqAmount;

  /** Internal budgeted unit rate (project team's planned rate). */
  @Column(name = "budgeted_rate", precision = 18, scale = 4)
  private BigDecimal budgetedRate;

  /** Derived: boqQty × budgetedRate. */
  @Column(name = "budgeted_amount", precision = 19, scale = 2)
  private BigDecimal budgetedAmount;

  /** Physical quantity executed to date. */
  @Column(name = "qty_executed_to_date", precision = 18, scale = 3)
  private BigDecimal qtyExecutedToDate;

  /** Actual unit rate being incurred (from site cost reports). */
  @Column(name = "actual_rate", precision = 18, scale = 4)
  private BigDecimal actualRate;

  /** Derived: qtyExecutedToDate × actualRate. */
  @Column(name = "actual_amount", precision = 19, scale = 2)
  private BigDecimal actualAmount;

  /** Derived: qtyExecutedToDate / boqQty (0..1+). */
  @Column(name = "percent_complete", precision = 9, scale = 6)
  private BigDecimal percentComplete;

  /** Derived: actualAmount − (qtyExecutedToDate × budgetedRate). Positive = over-budget. */
  @Column(name = "cost_variance", precision = 19, scale = 2)
  private BigDecimal costVariance;

  /** Derived: costVariance / earnedBudget (qtyExecutedToDate × budgetedRate). Nullable when earnedBudget = 0. */
  @Column(name = "cost_variance_percent", precision = 9, scale = 6)
  private BigDecimal costVariancePercent;

  /**
   * MoRTH chapter grouping (e.g. "1 - Earthwork", "3 - Bituminous"). Free text so contracts with
   * non-standard chapter numbering still fit; the UI's Chapter dropdown is populated from
   * distinct values across the project.
   */
  @Column(name = "chapter", length = 80)
  private String chapter;

  /** Lifecycle status per PMS MasterData Screen 03. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20)
  private BoqStatus status;

  /**
   * When TRUE, the {@code actualRate} on this item was set explicitly via PATCH and must be
   * preserved by the {@code BoqActualRateRecalcListener} (Workstream B2). Null/FALSE means the
   * listener is free to overwrite {@code actualRate} from rolled-up DPR contributions. Nullable
   * for ddl-auto:update compatibility with pre-Workstream-B rows.
   */
  @Column(name = "manual_override")
  private Boolean manualOverride;
}
