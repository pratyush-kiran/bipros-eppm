package com.bipros.resource.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Links an activity to a sub-contractor master row with work-activity mapping snapshot.
 * Stored independently of {@code ResourceAssignment} — appears as the 4th section in
 * the Resource Demand panel.
 */
@Entity
@Table(
    name = "activity_sub_contractor_assignments",
    schema = "resource",
    indexes = {
        @Index(name = "idx_act_subcon_activity_id", columnList = "activity_id"),
        @Index(name = "idx_act_subcon_project_id", columnList = "project_id"),
        @Index(name = "idx_act_subcon_sub_contractor_master_id", columnList = "sub_contractor_master_id"),
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivitySubContractorAssignment extends BaseEntity {

  @Column(name = "activity_id", nullable = false)
  private UUID activityId;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "sub_contractor_master_id", nullable = false)
  private UUID subContractorMasterId;

  @Column(name = "sc_work_type_id", nullable = false)
  private UUID scWorkTypeId;

  @Column(name = "work_type_name", length = 150)
  private String workTypeName;

  @Column(name = "unit", length = 30)
  private String unit;

  @Column(name = "planned_units", precision = 19, scale = 4)
  private BigDecimal plannedUnits;

  @Column(name = "rate_per_unit", precision = 19, scale = 4)
  private BigDecimal ratePerUnit;

  @Column(name = "planned_cost", precision = 19, scale = 4)
  private BigDecimal plannedCost;

  @Column(name = "actual_units", precision = 19, scale = 4,
          columnDefinition = "NUMERIC(19,4) DEFAULT 0")
  @Builder.Default
  private BigDecimal actualUnits = BigDecimal.ZERO;

  @Column(name = "actual_cost", precision = 19, scale = 4,
          columnDefinition = "NUMERIC(19,4) DEFAULT 0")
  @Builder.Default
  private BigDecimal actualCost = BigDecimal.ZERO;
}
