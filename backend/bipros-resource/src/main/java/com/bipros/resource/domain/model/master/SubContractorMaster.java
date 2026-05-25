package com.bipros.resource.domain.model.master;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Master for Sub-Contractor — code, name, location, contact. Admin-managed list referenced by
 * {@code ActivitySubContractorAssignment}.
 */
@Entity
@Table(
    name = "sub_contractor_master",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_sub_contractor_master_code", columnNames = {"code"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubContractorMaster extends BaseEntity {

  @Column(nullable = false, length = 50, unique = true)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(length = 300)
  private String location;

  @Column(name = "primary_contact_name", length = 150)
  private String primaryContactName;

  @Column(name = "primary_contact_number", length = 30)
  private String primaryContactNumber;

  @Column(length = 1000)
  private String remarks;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
