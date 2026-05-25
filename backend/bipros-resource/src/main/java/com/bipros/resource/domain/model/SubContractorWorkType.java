package com.bipros.resource.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lightweight master of work types usable by sub-contractors.
 * Independent of the global {@link WorkActivity} table.
 */
@Entity
@Table(
    name = "subcontractor_work_types",
    schema = "resource",
    indexes = {
        @Index(name = "idx_scwt_name", columnList = "name"),
        @Index(name = "idx_scwt_active", columnList = "active"),
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubContractorWorkType extends BaseEntity {

  @Column(nullable = false, length = 150, unique = true)
  private String name;

  @Column(name = "default_unit", length = 30)
  private String defaultUnit;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
