package com.bipros.dbs.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.dbs.api.dto.DbsManualExpenseRequest;
import com.bipros.dbs.api.dto.DbsManualExpenseResponse;
import com.bipros.dbs.domain.event.DbsManualExpenseSavedEvent;
import com.bipros.dbs.domain.model.DbsManualExpense;
import com.bipros.dbs.domain.model.DbsManualExpense.ApprovalStatus;
import com.bipros.dbs.domain.repository.DbsManualExpenseRepository;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.ExpenseCategory;
import com.bipros.resource.domain.repository.ExpenseCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD + approval workflow for manual DBS expenses. Every mutation fires a
 * {@link DbsManualExpenseSavedEvent} which a downstream listener uses to recompute
 * the day's DBS rollup so the new total reflects immediately.
 *
 * <p>Approval threshold (v1): hardcoded to ₹10,000 by default, overridable via
 * {@code bipros.dbs.expense.approval-threshold} config. Entries at or above the
 * threshold land as PENDING and are excluded from rollups until APPROVED.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class DbsManualExpenseService {

  private final DbsManualExpenseRepository expenseRepo;
  private final ExpenseCategoryRepository categoryRepo;
  private final ActivityRepository activityRepo;
  private final ProjectRepository projectRepo;
  private final ProjectTeamService projectTeamService;
  private final AuditService auditService;
  private final ApplicationEventPublisher eventPublisher;

  /** Fallback when the project has no override. Per-project value takes precedence. */
  @Value("${bipros.dbs.expense.approval-threshold:10000.00}")
  private BigDecimal defaultApprovalThreshold;

  /**
   * Resolves the approval threshold for a project: project-level override if set, else
   * the global {@link #defaultApprovalThreshold}. Returns a positive {@link BigDecimal}.
   */
  private BigDecimal thresholdFor(UUID projectId) {
    return projectRepo.findById(projectId)
        .map(Project::getExpenseApprovalThreshold)
        .filter(t -> t != null && t.signum() > 0)
        .orElse(defaultApprovalThreshold);
  }

  // ── reads ────────────────────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<DbsManualExpenseResponse> listForDate(UUID projectId, LocalDate date) {
    List<DbsManualExpense> rows = expenseRepo.findByProjectIdAndReportDate(projectId, date);
    return toResponses(rows);
  }

  @Transactional(readOnly = true)
  public List<DbsManualExpenseResponse> listForActivity(UUID activityId) {
    List<DbsManualExpense> rows = expenseRepo.findByActivityId(activityId);
    return toResponses(rows);
  }

  /** PM inbox: all PENDING entries for the project, newest first. */
  @Transactional(readOnly = true)
  public List<DbsManualExpenseResponse> listPending(UUID projectId) {
    List<DbsManualExpense> rows = expenseRepo
        .findByProjectIdAndApprovalStatusOrderByCreatedAtDesc(projectId, ApprovalStatus.PENDING);
    return toResponses(rows);
  }

  @Transactional(readOnly = true)
  public BigDecimal getApprovalThreshold(UUID projectId) {
    return thresholdFor(projectId);
  }

  /**
   * Sets the per-project override. Pass {@code null} or a non-positive value to clear
   * the override and fall back to the global default.
   */
  public BigDecimal setApprovalThreshold(UUID projectId, BigDecimal newThreshold) {
    Project project = projectRepo.findById(projectId)
        .orElseThrow(() -> new com.bipros.common.exception.ResourceNotFoundException("Project", projectId));
    BigDecimal previous = project.getExpenseApprovalThreshold();
    BigDecimal effective = (newThreshold != null && newThreshold.signum() > 0) ? newThreshold : null;
    project.setExpenseApprovalThreshold(effective);
    projectRepo.save(project);
    auditService.logUpdate("Project", projectId, "expenseApprovalThreshold", previous, effective);
    log.info("Project {} expense approval threshold set to {}", projectId, effective);
    return thresholdFor(projectId);
  }

  // ── mutations ────────────────────────────────────────────────────────────────

  public DbsManualExpenseResponse create(UUID projectId, DbsManualExpenseRequest req) {
    ExpenseCategory category = categoryRepo.findById(req.categoryId())
        .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory", req.categoryId()));
    if (!Boolean.TRUE.equals(category.getActive())) {
      throw new BusinessRuleException("EXPENSE_CATEGORY_INACTIVE",
          "Expense category '" + category.getCode() + "' is inactive");
    }
    if (req.activityId() != null) {
      Activity activity = activityRepo.findById(req.activityId())
          .orElseThrow(() -> new ResourceNotFoundException("Activity", req.activityId()));
      if (!activity.getProjectId().equals(projectId)) {
        throw new BusinessRuleException("ACTIVITY_PROJECT_MISMATCH",
            "Activity does not belong to project " + projectId);
      }
    }

    // Denormalise the engineer / CM from the supervisor if supplied — keeps DBS rollup
    // fans-out simple (no extra team-chain walks at read time).
    UUID engineerId = null;
    UUID cmId = null;
    if (req.supervisorUserId() != null) {
      engineerId = projectTeamService.resolveEngineerFor(projectId, req.supervisorUserId()).orElse(null);
      cmId = projectTeamService.resolveCmFor(projectId, req.supervisorUserId()).orElse(null);
    }

    BigDecimal absAmount = req.amount() == null ? BigDecimal.ZERO : req.amount().abs();
    BigDecimal threshold = thresholdFor(projectId);
    boolean breached = absAmount.compareTo(threshold) >= 0;

    DbsManualExpense e = DbsManualExpense.builder()
        .projectId(projectId)
        .reportDate(req.reportDate())
        .activityId(req.activityId())
        .categoryId(req.categoryId())
        .supervisorUserId(req.supervisorUserId())
        .engineerUserId(engineerId)
        .cmUserId(cmId)
        .description(req.description())
        .amount(req.amount())
        .currency(req.currency() == null || req.currency().isBlank() ? "INR" : req.currency())
        .vendorName(req.vendorName())
        .receiptNo(req.receiptNo())
        .attachmentUrl(req.attachmentUrl())
        .taxAmount(req.taxAmount())
        .taxRatePct(req.taxRatePct())
        .notes(req.notes())
        .approvalStatus(breached ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED)
        .approvalThresholdBreached(breached)
        .reversalOfId(req.reversalOfId())
        .build();

    DbsManualExpense saved = expenseRepo.save(e);
    auditService.logCreate("DbsManualExpense", saved.getId(),
        DbsManualExpenseResponse.from(saved, category.getCode(), category.getName(), category.getDbsSection()));
    eventPublisher.publishEvent(new DbsManualExpenseSavedEvent(
        projectId, req.reportDate(), req.activityId()));
    log.info("DbsManualExpense created id={} project={} date={} amount={} status={}",
        saved.getId(), projectId, req.reportDate(), req.amount(), saved.getApprovalStatus());
    return DbsManualExpenseResponse.from(saved, category.getCode(), category.getName(), category.getDbsSection());
  }

  public DbsManualExpenseResponse update(UUID id, DbsManualExpenseRequest req) {
    DbsManualExpense e = expenseRepo.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("DbsManualExpense", id));
    ExpenseCategory category = categoryRepo.findById(req.categoryId())
        .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory", req.categoryId()));

    BigDecimal absAmount = req.amount() == null ? BigDecimal.ZERO : req.amount().abs();
    BigDecimal threshold = thresholdFor(e.getProjectId());
    boolean breached = absAmount.compareTo(threshold) >= 0;

    e.setReportDate(req.reportDate());
    e.setActivityId(req.activityId());
    e.setCategoryId(req.categoryId());
    e.setSupervisorUserId(req.supervisorUserId());
    e.setDescription(req.description());
    e.setAmount(req.amount());
    if (req.currency() != null && !req.currency().isBlank()) e.setCurrency(req.currency());
    e.setVendorName(req.vendorName());
    e.setReceiptNo(req.receiptNo());
    e.setAttachmentUrl(req.attachmentUrl());
    e.setTaxAmount(req.taxAmount());
    e.setTaxRatePct(req.taxRatePct());
    e.setNotes(req.notes());
    e.setApprovalThresholdBreached(breached);
    // Editing a PENDING entry keeps it PENDING; an APPROVED entry stays APPROVED unless
    // the amount now breaches threshold for the first time (in which case it goes back
    // to PENDING for re-approval).
    if (breached && e.getApprovalStatus() == ApprovalStatus.APPROVED) {
      e.setApprovalStatus(ApprovalStatus.PENDING);
    }

    DbsManualExpense saved = expenseRepo.save(e);
    auditService.logUpdate("DbsManualExpense", id, "dbsManualExpense", null,
        DbsManualExpenseResponse.from(saved, category.getCode(), category.getName(), category.getDbsSection()));
    eventPublisher.publishEvent(new DbsManualExpenseSavedEvent(
        e.getProjectId(), e.getReportDate(), e.getActivityId()));
    return DbsManualExpenseResponse.from(saved, category.getCode(), category.getName(), category.getDbsSection());
  }

  public void delete(UUID id) {
    DbsManualExpense e = expenseRepo.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("DbsManualExpense", id));
    expenseRepo.delete(e);
    auditService.logDelete("DbsManualExpense", id);
    eventPublisher.publishEvent(new DbsManualExpenseSavedEvent(
        e.getProjectId(), e.getReportDate(), e.getActivityId()));
  }

  public DbsManualExpenseResponse approve(UUID id) {
    DbsManualExpense e = expenseRepo.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("DbsManualExpense", id));
    e.setApprovalStatus(ApprovalStatus.APPROVED);
    DbsManualExpense saved = expenseRepo.save(e);
    auditService.logUpdate("DbsManualExpense", id, "approvalStatus", "PENDING", "APPROVED");
    eventPublisher.publishEvent(new DbsManualExpenseSavedEvent(
        e.getProjectId(), e.getReportDate(), e.getActivityId()));
    ExpenseCategory c = categoryRepo.findById(saved.getCategoryId()).orElse(null);
    return DbsManualExpenseResponse.from(saved,
        c == null ? null : c.getCode(),
        c == null ? null : c.getName(),
        c == null ? null : c.getDbsSection());
  }

  public DbsManualExpenseResponse reject(UUID id) {
    DbsManualExpense e = expenseRepo.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("DbsManualExpense", id));
    e.setApprovalStatus(ApprovalStatus.REJECTED);
    DbsManualExpense saved = expenseRepo.save(e);
    auditService.logUpdate("DbsManualExpense", id, "approvalStatus", null, "REJECTED");
    eventPublisher.publishEvent(new DbsManualExpenseSavedEvent(
        e.getProjectId(), e.getReportDate(), e.getActivityId()));
    ExpenseCategory c = categoryRepo.findById(saved.getCategoryId()).orElse(null);
    return DbsManualExpenseResponse.from(saved,
        c == null ? null : c.getCode(),
        c == null ? null : c.getName(),
        c == null ? null : c.getDbsSection());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private List<DbsManualExpenseResponse> toResponses(List<DbsManualExpense> rows) {
    if (rows.isEmpty()) return List.of();
    List<UUID> catIds = rows.stream().map(DbsManualExpense::getCategoryId).distinct().toList();
    Map<UUID, ExpenseCategory> cats = categoryRepo.findAllById(catIds).stream()
        .collect(Collectors.toMap(ExpenseCategory::getId, c -> c));
    return rows.stream()
        .map(e -> {
          ExpenseCategory c = cats.get(e.getCategoryId());
          return DbsManualExpenseResponse.from(e,
              c == null ? null : c.getCode(),
              c == null ? null : c.getName(),
              c == null ? null : c.getDbsSection());
        })
        .toList();
  }
}
