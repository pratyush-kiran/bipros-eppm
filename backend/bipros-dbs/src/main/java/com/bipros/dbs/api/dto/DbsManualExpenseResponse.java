package com.bipros.dbs.api.dto;

import com.bipros.dbs.domain.model.DbsManualExpense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DbsManualExpenseResponse(
    UUID id,
    UUID projectId,
    LocalDate reportDate,
    UUID activityId,
    UUID categoryId,
    String categoryCode,
    String categoryName,
    String dbsSection,
    UUID supervisorUserId,
    String description,
    BigDecimal amount,
    String currency,
    String vendorName,
    String receiptNo,
    String attachmentUrl,
    BigDecimal taxAmount,
    BigDecimal taxRatePct,
    String notes,
    String approvalStatus,
    Boolean approvalThresholdBreached,
    UUID reversalOfId
) {
  public static DbsManualExpenseResponse from(DbsManualExpense e,
                                              String categoryCode,
                                              String categoryName,
                                              String dbsSection) {
    return new DbsManualExpenseResponse(
        e.getId(), e.getProjectId(), e.getReportDate(), e.getActivityId(),
        e.getCategoryId(), categoryCode, categoryName, dbsSection,
        e.getSupervisorUserId(),
        e.getDescription(), e.getAmount(), e.getCurrency(),
        e.getVendorName(), e.getReceiptNo(), e.getAttachmentUrl(),
        e.getTaxAmount(), e.getTaxRatePct(), e.getNotes(),
        e.getApprovalStatus().name(), e.getApprovalThresholdBreached(),
        e.getReversalOfId());
  }
}
