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
 * Master-level mapping of a sub-contractor to a work activity with rate and optional
 * productivity norm. Separate from {@code ActivitySubContractorAssignment} which is
 * project-scoped planning.
 */
@Entity
@Table(
    name = "sub_contractor_work_activity_mappings",
    schema = "resource",
    indexes = {
        @Index(name = "idx_scwam_sub_contractor", columnList = "sub_contractor_master_id"),
        @Index(name = "idx_scwam_sc_work_type", columnList = "sc_work_type_id"),
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubContractorWorkActivityMapping extends BaseEntity {

  @Column(name = "sub_contractor_master_id", nullable = false)
  private UUID subContractorMasterId;

  @Column(name = "sc_work_type_id", nullable = false)
  private UUID scWorkTypeId;

  /** Denormalized snapshot for display without cross-module joins. */
  @Column(name = "work_type_name", length = 150)
  private String workTypeName;

  @Column(name = "unit", length = 30)
  private String unit;

  @Column(name = "rate_per_unit", precision = 19, scale = 4)
  private BigDecimal ratePerUnit;

  /** Daily output for this sub-contractor on the mapped activity. */
  @Column(name = "output_per_day", precision = 12, scale = 4)
  private java.math.BigDecimal outputPerDay;
}
