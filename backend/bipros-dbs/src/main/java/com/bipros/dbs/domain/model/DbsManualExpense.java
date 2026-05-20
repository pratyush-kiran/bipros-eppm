package com.bipros.dbs.domain.model;

import com.bipros.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Manually-entered ad-hoc expense captured directly on the DBS page (not via DPR).
 * One row per expense. Optionally linked to an activity — when set, the expense
 * rolls into the activity's actual cost; when null, it's project-level overhead
 * (site office rent, internet, watchman salary).
 *
 * <p>Cross-module FKs ({@code activity_id}, {@code category_id}) are stored as UUIDs
 * without JPA relationships so DBS doesn't drag dependencies on bipros-resource /
 * bipros-activity entity graphs. Validation happens in {@code DbsManualExpenseService}.
 */
@Entity
@Table(
    name = "dbs_manual_expenses",
    schema = "dbs",
    indexes = {
        @Index(name = "idx_dme_project_date", columnList = "project_id, report_date"),
        @Index(name = "idx_dme_activity", columnList = "activity_id"),
        @Index(name = "idx_dme_supervisor_date", columnList = "supervisor_user_id, report_date"),
        @Index(name = "idx_dme_category", columnList = "category_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbsManualExpense extends BaseEntity {

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "report_date", nullable = false)
  private LocalDate reportDate;

  /** Nullable — project-level overhead expenses leave this blank. */
  @Column(name = "activity_id")
  private UUID activityId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "supervisor_user_id")
  private UUID supervisorUserId;

  /** Denormalised at write time so DBS rollups don't have to walk the team tree. */
  @Column(name = "engineer_user_id")
  private UUID engineerUserId;

  /** Denormalised at write time. */
  @Column(name = "cm_user_id")
  private UUID cmUserId;

  @Column(nullable = false, length = 500)
  private String description;

  @Column(nullable = false, precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 8)
  @Default
  private String currency = "INR";

  @Column(name = "vendor_name", length = 255)
  private String vendorName;

  @Column(name = "receipt_no", length = 100)
  private String receiptNo;

  @Column(name = "attachment_url", length = 500)
  private String attachmentUrl;

  @Column(name = "tax_amount", precision = 14, scale = 2)
  private BigDecimal taxAmount;

  @Column(name = "tax_rate_pct", precision = 5, scale = 2)
  private BigDecimal taxRatePct;

  @Column(length = 1000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(name = "approval_status", nullable = false, length = 16)
  @Default
  private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

  @Column(name = "approval_threshold_breached", nullable = false)
  @Default
  private Boolean approvalThresholdBreached = false;

  /** Set when this row reverses an earlier expense (negative-amount reversal). */
  @Column(name = "reversal_of_id")
  private UUID reversalOfId;

  public enum ApprovalStatus {
    PENDING, APPROVED, REJECTED
  }
}
