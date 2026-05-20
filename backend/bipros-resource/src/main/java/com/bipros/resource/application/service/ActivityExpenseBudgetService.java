package com.bipros.resource.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.ActivityExpenseBudgetRequest;
import com.bipros.resource.application.dto.ActivityExpenseBudgetResponse;
import com.bipros.resource.domain.model.ActivityExpenseBudget;
import com.bipros.resource.domain.model.ExpenseCategory;
import com.bipros.resource.domain.repository.ActivityExpenseBudgetRepository;
import com.bipros.resource.domain.repository.ExpenseCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD for activity-level ad-hoc-expense budgets. Enforces the activity LOCKED gate
 * (mirroring {@code RoleAssignmentService} — once locked, the resource plan / budget
 * shape is frozen).
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ActivityExpenseBudgetService {

  private final ActivityExpenseBudgetRepository budgetRepo;
  private final ExpenseCategoryRepository categoryRepo;
  private final ActivityRepository activityRepo;
  private final AuditService auditService;

  @Transactional(readOnly = true)
  public List<ActivityExpenseBudgetResponse> listForActivity(UUID activityId) {
    List<ActivityExpenseBudget> rows = budgetRepo.findByActivityId(activityId);
    if (rows.isEmpty()) return List.of();

    Map<UUID, ExpenseCategory> cats = loadCategoriesForRows(rows);
    return rows.stream()
        .map(b -> ActivityExpenseBudgetResponse.from(b, cats.get(b.getCategoryId())))
        .sorted(Comparator.comparing(r -> {
          ExpenseCategory c = cats.get(r.categoryId());
          return c == null ? Integer.MAX_VALUE : c.getSortOrder();
        }))
        .toList();
  }

  public ActivityExpenseBudgetResponse upsert(UUID projectId, UUID activityId,
                                              ActivityExpenseBudgetRequest req) {
    Activity activity = activityRepo.findById(activityId)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", activityId));
    if (!activity.getProjectId().equals(projectId)) {
      throw new BusinessRuleException("ACTIVITY_PROJECT_MISMATCH",
          "Activity does not belong to project " + projectId);
    }
    if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
      throw new BusinessRuleException("ACTIVITY_LOCKED",
          "Activity '" + activity.getCode() + "' is locked. Unlock it before editing budgets.");
    }

    ExpenseCategory category = categoryRepo.findById(req.categoryId())
        .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory", req.categoryId()));
    if (!Boolean.TRUE.equals(category.getActive())) {
      throw new BusinessRuleException("EXPENSE_CATEGORY_INACTIVE",
          "Expense category '" + category.getCode() + "' is inactive");
    }

    ActivityExpenseBudget b = budgetRepo
        .findByActivityIdAndCategoryId(activityId, req.categoryId())
        .orElseGet(() -> ActivityExpenseBudget.builder()
            .projectId(projectId)
            .activityId(activityId)
            .categoryId(req.categoryId())
            .build());
    b.setPlannedAmount(req.plannedAmount());
    b.setNotes(req.notes());

    ActivityExpenseBudget saved = budgetRepo.save(b);
    auditService.logUpdate("ActivityExpenseBudget", saved.getId(), "activityExpenseBudget",
        null, ActivityExpenseBudgetResponse.from(saved, category));
    log.info("ActivityExpenseBudget upsert activity={} category={} planned={}",
        activityId, category.getCode(), req.plannedAmount());
    return ActivityExpenseBudgetResponse.from(saved, category);
  }

  public void delete(UUID activityId, UUID budgetId) {
    ActivityExpenseBudget b = budgetRepo.findById(budgetId)
        .orElseThrow(() -> new ResourceNotFoundException("ActivityExpenseBudget", budgetId));
    if (!b.getActivityId().equals(activityId)) {
      throw new BusinessRuleException("BUDGET_ACTIVITY_MISMATCH",
          "Budget does not belong to activity " + activityId);
    }
    Activity activity = activityRepo.findById(activityId)
        .orElseThrow(() -> new ResourceNotFoundException("Activity", activityId));
    if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
      throw new BusinessRuleException("ACTIVITY_LOCKED",
          "Activity '" + activity.getCode() + "' is locked. Unlock it before deleting budgets.");
    }
    budgetRepo.delete(b);
    auditService.logDelete("ActivityExpenseBudget", budgetId);
  }

  private Map<UUID, ExpenseCategory> loadCategoriesForRows(List<ActivityExpenseBudget> rows) {
    if (rows.isEmpty()) return Map.of();
    List<UUID> ids = rows.stream().map(ActivityExpenseBudget::getCategoryId).distinct().toList();
    return categoryRepo.findAllById(ids).stream()
        .collect(Collectors.toMap(ExpenseCategory::getId, c -> c));
  }
}
