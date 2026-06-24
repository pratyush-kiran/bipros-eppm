package com.bipros.evm.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.resource.domain.model.ResourceAssignment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link EvmRollupService}'s static helper methods.
 * No Spring context required — these are pure-logic tests.
 */
class EvmRollupServiceTest {

    // -------------------------------------------------------------------------
    // getActivityAc — de-duplication proof
    // -------------------------------------------------------------------------

    /**
     * DB-proven scenario (activity 20e5ae36-dd96-4eda-aaaa-7b9173b4eb9a):
     *   activity_expenses.actual_cost = 0
     *   resource_assignments.actual_cost = 125.56  (same money as DPR manpower+equipment)
     *   DPR line_cost (sumByActivity) = 3_725.56
     *
     * Expected AC = 0 + 3_725.56 = 3_725.56
     * Old (buggy) AC = 0 + 125.56 + 3_725.56 = 3_851.12  (double-counted RA)
     */
    @Test
    void getActivityAc_excludesResourceAssignmentActualCost() {
        UUID actId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(actId);

        // activity_expenses.actual_cost = 0 (no expense rows)
        Map<UUID, List<ActivityExpense>> expensesByActivity = Map.of();

        // resource_assignments.actual_cost = 125.56
        ResourceAssignment ra = new ResourceAssignment();
        ra.setActualCost(new BigDecimal("125.56"));
        Map<UUID, List<ResourceAssignment>> assignmentsByActivity = Map.of(actId, List.of(ra));

        // DPR line_cost = 3_725.56
        Map<UUID, BigDecimal> dprAcByActivity = Map.of(actId, new BigDecimal("3725.56"));

        BigDecimal result = EvmRollupService.getActivityAc(
                activity, expensesByActivity, assignmentsByActivity, dprAcByActivity);

        assertEquals(new BigDecimal("3725.56"), result,
                "AC must equal activity_expenses + DPR only; RA.actual_cost is excluded "
                + "because ResourceAssignmentCostRollupListener already mirrors it into the DPR ledger");
    }

    /**
     * When there is no DPR data yet and expenses exist, AC = expenses only (no RA contribution).
     */
    @Test
    void getActivityAc_expensesOnly_noDpr() {
        UUID actId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(actId);

        ActivityExpense expense = new ActivityExpense();
        expense.setActivityId(actId);
        expense.setActualCost(new BigDecimal("500.00"));
        Map<UUID, List<ActivityExpense>> expensesByActivity = Map.of(actId, List.of(expense));

        ResourceAssignment ra = new ResourceAssignment();
        ra.setActualCost(new BigDecimal("200.00"));
        Map<UUID, List<ResourceAssignment>> assignmentsByActivity = Map.of(actId, List.of(ra));

        // No DPR rows yet
        Map<UUID, BigDecimal> dprAcByActivity = Map.of();

        BigDecimal result = EvmRollupService.getActivityAc(
                activity, expensesByActivity, assignmentsByActivity, dprAcByActivity);

        assertEquals(new BigDecimal("500.00"), result,
                "With no DPR rows, AC = activity_expenses only; RA.actual_cost still excluded");
    }

    /**
     * Both expenses and DPR contribute; RA must still be excluded.
     */
    @Test
    void getActivityAc_expensesPlusDpr_raStillExcluded() {
        UUID actId = UUID.randomUUID();

        Activity activity = new Activity();
        activity.setId(actId);

        ActivityExpense expense = new ActivityExpense();
        expense.setActivityId(actId);
        expense.setActualCost(new BigDecimal("1000.00"));
        Map<UUID, List<ActivityExpense>> expensesByActivity = Map.of(actId, List.of(expense));

        ResourceAssignment ra = new ResourceAssignment();
        ra.setActualCost(new BigDecimal("300.00"));
        Map<UUID, List<ResourceAssignment>> assignmentsByActivity = Map.of(actId, List.of(ra));

        Map<UUID, BigDecimal> dprAcByActivity = Map.of(actId, new BigDecimal("200.00"));

        BigDecimal result = EvmRollupService.getActivityAc(
                activity, expensesByActivity, assignmentsByActivity, dprAcByActivity);

        assertEquals(new BigDecimal("1200.00"), result,
                "AC = 1000 (expense) + 200 (DPR); RA 300 is excluded to prevent double-count");
    }
}
