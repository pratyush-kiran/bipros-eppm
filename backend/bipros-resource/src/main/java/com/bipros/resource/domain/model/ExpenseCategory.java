package com.bipros.resource.domain.model;

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

import java.math.BigDecimal;

/**
 * Category of ad-hoc / unplanned expense captured directly in the DBS. Master data,
 * admin-configurable. Each category is mapped to a DBS section letter so that manual
 * expenses bucket into the correct A / B / C / D / E / F / G section at read time
 * (see {@code DbsAggregationService}).
 *
 * <p>Examples: {@code TRANSPORT → G}, {@code CATERING → B}, {@code LOCAL_PURCHASE → E}.
 *
 * <p>Why "resource" schema: ExpenseCategory is rate-book–adjacent master data, same shelf
 * as {@code ResourceRole} and the rate masters. Keeps the admin-data perimeter tight.
 */
@Entity
@Table(
    name = "expense_categories",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_expense_categories_code", columnNames = {"code"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpenseCategory extends BaseEntity {

  @Column(nullable = false, length = 50, unique = true)
  private String code;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 500)
  private String description;

  /** Single uppercase letter A–G mapping to the DBS section the expense buckets into. */
  @Column(name = "dbs_section", nullable = false, length = 1, columnDefinition = "char(1)")
  private String dbsSection;

  @Column(name = "default_taxable", nullable = false)
  @Default
  private Boolean defaultTaxable = false;

  @Column(name = "default_tax_rate_pct", precision = 5, scale = 2)
  private BigDecimal defaultTaxRatePct;

  @Column(name = "sort_order", nullable = false)
  @Default
  private Integer sortOrder = 0;

  @Column(nullable = false)
  @Default
  private Boolean active = true;
}
