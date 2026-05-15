package com.bipros.resource.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Slim base table for resources of every type. Per-type details (equipment / material / manpower)
 * live in dedicated 1:1 tables keyed by {@code resource_id}, with {@code ON DELETE CASCADE} so
 * removing a Resource cascades to its detail row(s).
 */
@Entity
@Table(
    name = "resources",
    schema = "resource",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_resource_code", columnNames = {"code"})
    },
    indexes = {
        @Index(name = "idx_resource_role", columnList = "role_id"),
        @Index(name = "idx_resource_type", columnList = "resource_type_id"),
        @Index(name = "idx_resource_status", columnList = "status"),
        @Index(name = "idx_resource_parent", columnList = "parent_id"),
        @Index(name = "idx_resource_calendar", columnList = "calendar_id"),
        @Index(name = "idx_resource_rate_master", columnList = "rate_master_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resource extends BaseEntity {

  @Column(nullable = false, length = 50, unique = true)
  private String code;

  @Column(nullable = false, length = 150)
  private String name;

  @Column(length = 1000)
  private String description;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "role_id", nullable = false)
  private ResourceRole role;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "resource_type_id", nullable = false)
  private ResourceType resourceType;

  @Column(precision = 5, scale = 2)
  private BigDecimal availability;

  @Column(name = "cost_per_unit", precision = 19, scale = 4)
  private BigDecimal costPerUnit;

  @Column(length = 30)
  private String unit;

  /**
   * FK to the type-appropriate rate master row this resource is linked to. Semantics depend on
   * {@link #resourceType}: LABOR → {@code manpower_rate_masters}, EQUIPMENT →
   * {@code equipment_rate_masters}, MATERIAL → {@code material_rate_masters}. Nullable: legacy
   * resources and custom resource types may not have a rate master link, in which case
   * {@link #unit} and {@link #costPerUnit} are entered directly.
   *
   * <p>When set, {@link #unit} and {@link #costPerUnit} are snapshotted from the rate master row
   * at create/update time and re-synced when the rate master row is edited.
   */
  @Column(name = "rate_master_id")
  private UUID rateMasterId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Default
  private ResourceStatus status = ResourceStatus.ACTIVE;

  @Column(name = "calendar_id")
  private UUID calendarId;

  @Column(name = "parent_id")
  private UUID parentId;

  @Column(name = "sort_order", nullable = false)
  @Default
  private Integer sortOrder = 0;
}
