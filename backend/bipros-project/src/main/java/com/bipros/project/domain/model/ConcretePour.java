package com.bipros.project.domain.model;

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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Concrete pour ledger row: one entry per (project, date, site, structure, element) capturing
 * what was physically poured on a given day. Distinct from {@link DprMaterial} — concrete pours
 * carry their own metadata (grade code, slump, temperature, plant, chainage, structure element)
 * that doesn't fit cleanly on a generic DPR material row, and the customer dataset is large
 * enough (1,231 pours across Khasab + Lima at last count) that a dedicated aggregate keeps
 * reporting / analytics queries narrow.
 *
 * <p>{@code dprId} is a nullable soft FK to {@code project.daily_progress_reports.id} so a pour
 * can be linked back to the DPR that recorded the day's activity; pours imported from supplier
 * tickets or batch-plant exports often arrive ahead of the DPR and stay un-linked until the
 * supervisor reconciles. {@code supervisorUserId} is a nullable soft FK to {@code public.users.id}
 * mirroring the role-only model on {@link DailyProgressReport} (post-migration 091): the user is
 * the supervisor, not a Resource.
 *
 * <p>{@code slumpValue} and {@code temperatureC} are optional — the Lima plant logs them, Khasab
 * historically did not, so nullability lets both sites share one table without forcing synthetic
 * values. Aggregates ({@code totalsByGrade}, {@code totalsBySite}) are computed via repository
 * sum queries; nothing is stored as a running total.
 */
@Entity
@Table(
    name = "concrete_pour",
    schema = "project",
    indexes = {
        @Index(name = "idx_concrete_pour_project_date", columnList = "project_id, pour_date"),
        @Index(name = "idx_concrete_pour_project_site", columnList = "project_id, site"),
        @Index(name = "idx_concrete_pour_project_grade", columnList = "project_id, grade_code")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConcretePour extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "pour_date", nullable = false)
  private LocalDate pourDate;

  /** Site label, e.g. "Khasab" or "Lima". Free-text so new sites can be added without enum churn. */
  @Column(name = "site", nullable = false, length = 50)
  private String site;

  /** Batching plant name, e.g. "SCC Khasab", "SCC Lima", "SCC KM47". */
  @Column(name = "plant_name", length = 100)
  private String plantName;

  /** Chainage in meters. Nullable — element-pours (e.g. precast yard) have no chainage. */
  @Column(name = "chainage_m")
  private Long chainageM;

  /** Structure type, e.g. "Box Culvert", "Concrete Barrier", "Retaining Wall". */
  @Column(name = "structure", nullable = false, length = 150)
  private String structure;

  /** Sub-element description (e.g. "Wing wall LHS", "Top slab"). Optional. */
  @Column(name = "element", length = 255)
  private String element;

  /** Concrete grade code, e.g. "C15", "C25", "C30", "C35". */
  @Column(name = "grade_code", length = 20)
  private String gradeCode;

  @Column(name = "quantity_m3", nullable = false, precision = 18, scale = 3)
  private BigDecimal quantityM3;

  /** Slump value (mm). Lima-only — null for Khasab rows. */
  @Column(name = "slump_value", precision = 6, scale = 2)
  private BigDecimal slumpValue;

  /** Ambient / mix temperature in Celsius. Lima-only — null for Khasab rows. */
  @Column(name = "temperature_c", precision = 6, scale = 2)
  private BigDecimal temperatureC;

  /** Additional location label (e.g. block / span / pier id). Optional. */
  @Column(name = "section_label", length = 150)
  private String sectionLabel;

  /** Soft FK to {@code public.users.id}. Null for free-text / off-roster supervisors. */
  @Column(name = "supervisor_user_id")
  private UUID supervisorUserId;

  /** Soft FK to {@code project.daily_progress_reports.id}. Null when no DPR link is known yet. */
  @Column(name = "dpr_id")
  private UUID dprId;

  @Column(name = "remarks", length = 1000)
  private String remarks;
}
