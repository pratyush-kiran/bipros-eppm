package com.bipros.api.controller;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.dto.ApiResponse;
import com.bipros.cost.application.service.ActivityCostCalculator;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-activity cost rollup endpoint used by the activities grid (Phase 1.3 of the
 * baseline-progress roadmap). The grid needs an actual-cost column for every visible
 * activity, and looping the existing single-activity expenses endpoint N times for an
 * N-row grid is wasteful. This endpoint returns one map entry per activity that has
 * any cost contribution from {@link ActivityExpense} or {@link ResourceAssignment}.
 *
 * <p>The math itself lives in {@link ActivityCostCalculator} — the same calculator that
 * {@code BaselineService.createBaseline} uses, so live values and snapshot values stay
 * comparable.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/activities")
@RequiredArgsConstructor
public class ActivityCostSummaryController {

  private final ActivityRepository activityRepository;
  private final ActivityExpenseRepository activityExpenseRepository;
  private final ResourceAssignmentRepository resourceAssignmentRepository;

  public record ActivityCostSummaryRow(
      UUID activityId,
      BigDecimal budgetedCost,
      BigDecimal plannedCost,
      BigDecimal actualCost,
      BigDecimal remainingCost,
      BigDecimal atCompletionCost
  ) {}

  @GetMapping("/cost-summary")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<List<ActivityCostSummaryRow>>> getCostSummary(
      @PathVariable UUID projectId) {

    List<Activity> activities = activityRepository.findByProjectId(projectId);

    List<ActivityExpense> allExpenses = activityExpenseRepository.findByProjectId(projectId);
    Map<UUID, List<ActivityExpense>> expensesByActivity = allExpenses.stream()
        .filter(e -> e.getActivityId() != null)
        .collect(Collectors.groupingBy(ActivityExpense::getActivityId));

    List<ResourceAssignment> allAssignments =
        resourceAssignmentRepository.findByProjectId(projectId);
    Map<UUID, List<ResourceAssignment>> assignmentsByActivity = allAssignments.stream()
        .collect(Collectors.groupingBy(ResourceAssignment::getActivityId));

    // Iterate every activity (not only those with cost rows) so the grid can render an
    // explicit ₹0 row instead of a blank cell for activities that simply have no costs yet.
    List<ActivityCostSummaryRow> rows = new java.util.ArrayList<>(activities.size());
    Map<UUID, ActivityCostSummaryRow> byActivity = new HashMap<>(activities.size());
    for (Activity activity : activities) {
      ActivityCostCalculator.ActivityCostSummary summary = ActivityCostCalculator.summarize(
          activity.getId(), expensesByActivity, assignmentsByActivity);
      ActivityCostSummaryRow row = new ActivityCostSummaryRow(
          activity.getId(),
          summary.budgetedCost(),
          summary.plannedCost(),
          summary.actualCost(),
          summary.remainingCost(),
          summary.atCompletionCost());
      rows.add(row);
      byActivity.put(activity.getId(), row);
    }

    // Defensive: include rows for any activity_id that exists in expenses/assignments
    // but no longer in the activities table — otherwise the rollup silently drops cost
    // associated with deleted activities, which would understate project totals.
    for (UUID orphanActivityId : expensesByActivity.keySet()) {
      if (!byActivity.containsKey(orphanActivityId)) {
        ActivityCostCalculator.ActivityCostSummary summary = ActivityCostCalculator.summarize(
            orphanActivityId, expensesByActivity, assignmentsByActivity);
        rows.add(new ActivityCostSummaryRow(
            orphanActivityId,
            summary.budgetedCost(),
            summary.plannedCost(),
            summary.actualCost(),
            summary.remainingCost(),
            summary.atCompletionCost()));
      }
    }
    for (UUID orphanActivityId : assignmentsByActivity.keySet()) {
      if (!byActivity.containsKey(orphanActivityId)
          && !expensesByActivity.containsKey(orphanActivityId)) {
        ActivityCostCalculator.ActivityCostSummary summary = ActivityCostCalculator.summarize(
            orphanActivityId, expensesByActivity, assignmentsByActivity);
        rows.add(new ActivityCostSummaryRow(
            orphanActivityId,
            summary.budgetedCost(),
            summary.plannedCost(),
            summary.actualCost(),
            summary.remainingCost(),
            summary.atCompletionCost()));
      }
    }

    return ResponseEntity.ok(ApiResponse.ok(rows));
  }
}
