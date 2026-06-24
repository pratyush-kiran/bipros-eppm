package com.bipros.evm.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.evm.application.dto.EvmCalculationResponse;
import com.bipros.evm.application.dto.WbsEvmNode;
import com.bipros.evm.domain.algorithm.EvmTechniqueFactory;
import com.bipros.evm.domain.algorithm.EvmTechniqueStrategy;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.entity.EvmTechnique;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.application.service.DprActualCostLookup;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.udf.application.service.FormulaEngine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EvmRollupService {

    private static final Logger log = LoggerFactory.getLogger(EvmRollupService.class);
    private static final int SCALE = 4;

    private final ActivityRepository activityRepository;
    private final ActivityExpenseRepository activityExpenseRepository;
    private final ResourceAssignmentRepository resourceAssignmentRepository;
    private final ActivitySubContractorAssignmentRepository activitySubContractorAssignmentRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final EvmCalculationRepository evmCalculationRepository;
    private final FormulaEngine formulaEngine;
    private final DprActualCostLookup dprActualCostLookup;
    private final ProjectRepository projectRepository;

    @Transactional
    public List<WbsEvmNode> calculateWbsTree(UUID projectId, EvmTechnique technique, EtcMethod etcMethod) {
        LocalDate dataDate = resolveDataDate(projectId);
        List<WbsNode> allWbs = wbsNodeRepository.findByProjectIdOrderBySortOrder(projectId);
        List<Activity> allActivities = activityRepository.findByProjectId(projectId);
        List<ActivityExpense> allExpenses = activityExpenseRepository.findByProjectId(projectId);
        List<ResourceAssignment> allAssignments = resourceAssignmentRepository.findByProjectId(projectId);
        List<ActivitySubContractorAssignment> allScAssignments =
                activitySubContractorAssignmentRepository.findByProjectId(projectId);

        // Group activities by WBS node
        Map<UUID, List<Activity>> activitiesByWbs = allActivities.stream()
                .filter(a -> a.getWbsNodeId() != null)
                .collect(Collectors.groupingBy(Activity::getWbsNodeId));

        // Group expenses by activity
        Map<UUID, List<ActivityExpense>> expensesByActivity = allExpenses.stream()
                .filter(e -> e.getActivityId() != null)
                .collect(Collectors.groupingBy(ActivityExpense::getActivityId));

        // Group assignments by activity
        Map<UUID, List<ResourceAssignment>> assignmentsByActivity = allAssignments.stream()
                .collect(Collectors.groupingBy(ResourceAssignment::getActivityId));

        Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity = allScAssignments.stream()
                .filter(s -> s.getActivityId() != null)
                .collect(Collectors.groupingBy(ActivitySubContractorAssignment::getActivityId));

        // Pre-load DPR persisted line_cost per activity for the whole project so the leaf walk
        // doesn't issue an N+1 query per activity. Empty map when there are no DPRs yet.
        Map<UUID, BigDecimal> dprAcByActivity = dprActualCostLookup.sumByActivity(projectId);

        EvmTechniqueStrategy strategy = EvmTechniqueFactory.getStrategy(technique);

        // Build WBS hierarchy map
        Map<UUID, List<WbsNode>> childrenMap = allWbs.stream()
                .filter(w -> w.getParentId() != null)
                .collect(Collectors.groupingBy(WbsNode::getParentId));

        List<WbsNode> roots = allWbs.stream()
                .filter(w -> w.getParentId() == null)
                .toList();

        // Calculate leaf-level EVM and roll up
        List<WbsEvmNode> result = new ArrayList<>();
        for (WbsNode root : roots) {
            result.add(buildWbsEvmTree(root, childrenMap, activitiesByWbs,
                    expensesByActivity, assignmentsByActivity, scAssignmentsByActivity, dprAcByActivity,
                    strategy, dataDate, etcMethod,
                    projectId));
        }
        return result;
    }

    private WbsEvmNode buildWbsEvmTree(
            WbsNode node,
            Map<UUID, List<WbsNode>> childrenMap,
            Map<UUID, List<Activity>> activitiesByWbs,
            Map<UUID, List<ActivityExpense>> expensesByActivity,
            Map<UUID, List<ResourceAssignment>> assignmentsByActivity,
            Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity,
            Map<UUID, BigDecimal> dprAcByActivity,
            EvmTechniqueStrategy strategy,
            LocalDate dataDate,
            EtcMethod etcMethod,
            UUID projectId) {

        List<WbsNode> children = childrenMap.getOrDefault(node.getId(), List.of());

        if (children.isEmpty()) {
            // Leaf node — calculate from activities
            return calculateLeafEvm(node, activitiesByWbs, expensesByActivity,
                    assignmentsByActivity, scAssignmentsByActivity, dprAcByActivity, strategy, dataDate, etcMethod, projectId);
        }

        // Parent node — aggregate children
        List<WbsEvmNode> childResults = new ArrayList<>();
        BigDecimal totalPv = BigDecimal.ZERO;
        BigDecimal totalEv = BigDecimal.ZERO;
        BigDecimal totalAc = BigDecimal.ZERO;
        BigDecimal totalBac = BigDecimal.ZERO;

        for (WbsNode child : children) {
            WbsEvmNode childResult = buildWbsEvmTree(child, childrenMap, activitiesByWbs,
                    expensesByActivity, assignmentsByActivity, scAssignmentsByActivity, dprAcByActivity,
                    strategy, dataDate, etcMethod, projectId);
            childResults.add(childResult);
            totalPv = totalPv.add(childResult.plannedValue());
            totalEv = totalEv.add(childResult.earnedValue());
            totalAc = totalAc.add(childResult.actualCost());
            totalBac = totalBac.add(childResult.budgetAtCompletion());
        }

        // Also include activities directly under this WBS node
        WbsEvmNode directActivities = calculateLeafEvm(node, activitiesByWbs, expensesByActivity,
                assignmentsByActivity, scAssignmentsByActivity, dprAcByActivity, strategy, dataDate, etcMethod, projectId);
        totalPv = totalPv.add(directActivities.plannedValue());
        totalEv = totalEv.add(directActivities.earnedValue());
        totalAc = totalAc.add(directActivities.actualCost());
        totalBac = totalBac.add(directActivities.budgetAtCompletion());

        // Save WBS-level calculation
        EvmCalculation calc = createCalculation(projectId, node.getId(), dataDate,
                totalPv, totalEv, totalAc, totalBac, etcMethod,
                EvmTechniqueFactory.getStrategy(EvmTechnique.ACTIVITY_PERCENT_COMPLETE) == strategy
                        ? EvmTechnique.ACTIVITY_PERCENT_COMPLETE : null);

        return new WbsEvmNode(
                node.getId(),
                node.getName(),
                node.getCode(),
                totalBac, totalPv, totalEv, totalAc,
                calc.getScheduleVariance(), calc.getCostVariance(),
                calc.getSchedulePerformanceIndex(), calc.getCostPerformanceIndex(),
                calc.getEstimateAtCompletion(), calc.getEstimateToComplete(),
                calc.getVarianceAtCompletion(),
                childResults
        );
    }

    private WbsEvmNode calculateLeafEvm(
            WbsNode node,
            Map<UUID, List<Activity>> activitiesByWbs,
            Map<UUID, List<ActivityExpense>> expensesByActivity,
            Map<UUID, List<ResourceAssignment>> assignmentsByActivity,
            Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity,
            Map<UUID, BigDecimal> dprAcByActivity,
            EvmTechniqueStrategy strategy,
            LocalDate dataDate,
            EtcMethod etcMethod,
            UUID projectId) {

        List<Activity> activities = activitiesByWbs.getOrDefault(node.getId(), List.of());

        BigDecimal totalPv = BigDecimal.ZERO;
        BigDecimal totalEv = BigDecimal.ZERO;
        BigDecimal totalAc = BigDecimal.ZERO;
        BigDecimal totalBac = BigDecimal.ZERO;

        for (Activity activity : activities) {
            BigDecimal activityBac = getActivityBac(activity, expensesByActivity,
                    assignmentsByActivity, scAssignmentsByActivity);
            BigDecimal activityPv = getActivityPv(activity, activityBac, dataDate);
            BigDecimal activityEv = strategy.calculateEarnedValue(activity, activityBac, activityPv);
            BigDecimal activityAc = getActivityAc(activity, expensesByActivity, assignmentsByActivity,
                    dprAcByActivity);

            totalBac = totalBac.add(activityBac);
            totalPv = totalPv.add(activityPv);
            totalEv = totalEv.add(activityEv);
            totalAc = totalAc.add(activityAc);
        }

        EvmCalculation calc = createCalculation(projectId, node.getId(), dataDate,
                totalPv, totalEv, totalAc, totalBac, etcMethod, null);

        return new WbsEvmNode(
                node.getId(),
                node.getName(),
                node.getCode(),
                totalBac, totalPv, totalEv, totalAc,
                calc.getScheduleVariance(), calc.getCostVariance(),
                calc.getSchedulePerformanceIndex(), calc.getCostPerformanceIndex(),
                calc.getEstimateAtCompletion(), calc.getEstimateToComplete(),
                calc.getVarianceAtCompletion(),
                List.of()
        );
    }

    static BigDecimal getActivityBac(Activity activity,
                                      Map<UUID, List<ActivityExpense>> expensesByActivity,
                                      Map<UUID, List<ResourceAssignment>> assignmentsByActivity,
                                      Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity) {
        BigDecimal bac = BigDecimal.ZERO;
        List<ActivityExpense> expenses = expensesByActivity.getOrDefault(activity.getId(), List.of());
        for (ActivityExpense expense : expenses) {
            if (expense.getBudgetedCost() != null) {
                bac = bac.add(expense.getBudgetedCost());
            }
        }
        List<ResourceAssignment> assignments = assignmentsByActivity.getOrDefault(activity.getId(), List.of());
        for (ResourceAssignment assignment : assignments) {
            if (assignment.getPlannedCost() != null) {
                bac = bac.add(assignment.getPlannedCost());
            }
        }
        List<ActivitySubContractorAssignment> scAssignments =
                scAssignmentsByActivity.getOrDefault(activity.getId(), List.of());
        for (ActivitySubContractorAssignment sa : scAssignments) {
            if (sa.getPlannedCost() != null) {
                bac = bac.add(sa.getPlannedCost());
            }
        }
        return bac;
    }

    static BigDecimal getActivityPv(Activity activity, BigDecimal activityBac, LocalDate dataDate) {
        if (activity.getPlannedFinishDate() == null) {
            return BigDecimal.ZERO;
        }
        // If planned finish <= data date, full BAC is planned value
        if (!activity.getPlannedFinishDate().isAfter(dataDate)) {
            return activityBac;
        }
        // If planned start <= data date but finish > data date, time-phase proportionally
        if (activity.getPlannedStartDate() != null && !activity.getPlannedStartDate().isAfter(dataDate)) {
            long totalDays = java.time.temporal.ChronoUnit.DAYS.between(
                    activity.getPlannedStartDate(), activity.getPlannedFinishDate());
            if (totalDays <= 0) return activityBac;
            long elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(
                    activity.getPlannedStartDate(), dataDate);
            return activityBac.multiply(BigDecimal.valueOf(elapsedDays))
                    .divide(BigDecimal.valueOf(totalDays), SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    /**
     * Back-compat overload that omits the DPR sums. Kept for callers without a preloaded DPR map;
     * prefer the 4-arg overload — the DPR contribution is the dominant source on projects where
     * supervisors file daily reports but no one writes ActivityExpense rows.
     */
    static BigDecimal getActivityAc(Activity activity,
                                     Map<UUID, List<ActivityExpense>> expensesByActivity,
                                     Map<UUID, List<ResourceAssignment>> assignmentsByActivity) {
        return getActivityAc(activity, expensesByActivity, assignmentsByActivity, Map.of());
    }

    /**
     * Sum the Actual Cost sources for an activity: {@link ActivityExpense#getActualCost()} and
     * the DPR persisted {@code line_cost} carried in {@code dprAcByActivity}.
     *
     * <p>NOTE: {@code resource_assignments.actual_cost} is intentionally excluded.
     * {@code ResourceAssignmentCostRollupListener} keeps that column in lock-step with the DPR
     * manpower+equipment ledger — including both would double-count the same money.
     * The DPR sum is the single source of actuals (mirrors {@code CostService.getCostSummary}).
     */
    static BigDecimal getActivityAc(Activity activity,
                                     Map<UUID, List<ActivityExpense>> expensesByActivity,
                                     Map<UUID, List<ResourceAssignment>> assignmentsByActivity,
                                     Map<UUID, BigDecimal> dprAcByActivity) {
        BigDecimal ac = BigDecimal.ZERO;
        List<ActivityExpense> expenses = expensesByActivity.getOrDefault(activity.getId(), List.of());
        for (ActivityExpense expense : expenses) {
            if (expense.getActualCost() != null) {
                ac = ac.add(expense.getActualCost());
            }
        }
        BigDecimal dprAc = dprAcByActivity.get(activity.getId());
        if (dprAc != null) {
            ac = ac.add(dprAc);
        }
        return ac;
    }

    private EvmCalculation createCalculation(UUID projectId, UUID wbsNodeId, LocalDate dataDate,
                                              BigDecimal pv, BigDecimal ev, BigDecimal ac, BigDecimal bac,
                                              EtcMethod etcMethod, EvmTechnique technique) {
        var calc = new EvmCalculation();
        calc.setProjectId(projectId);
        calc.setWbsNodeId(wbsNodeId);
        calc.setDataDate(dataDate);
        calc.setBudgetAtCompletion(bac);
        calc.setPlannedValue(pv);
        calc.setEarnedValue(ev);
        calc.setActualCost(ac);
        if (technique != null) calc.setEvmTechnique(technique);
        if (etcMethod != null) calc.setEtcMethod(etcMethod);

        EvmServiceHelper.calculateIndices(calc, formulaEngine);

        evmCalculationRepository.save(calc);
        return calc;
    }

    /** Transient index calc — same math as createCalculation but NO repository.save. */
    private EvmCalculation computeCalculationTransient(UUID projectId, UUID wbsNodeId, LocalDate dataDate,
                                                       BigDecimal pv, BigDecimal ev, BigDecimal ac, BigDecimal bac,
                                                       EtcMethod etcMethod, EvmTechnique technique) {
        var calc = new EvmCalculation();
        calc.setProjectId(projectId);
        calc.setWbsNodeId(wbsNodeId);
        calc.setDataDate(dataDate);
        calc.setBudgetAtCompletion(bac);
        calc.setPlannedValue(pv);
        calc.setEarnedValue(ev);
        calc.setActualCost(ac);
        if (technique != null) calc.setEvmTechnique(technique);
        if (etcMethod != null) calc.setEtcMethod(etcMethod);
        EvmServiceHelper.calculateIndices(calc, formulaEngine);
        return calc;
    }

    @Transactional(readOnly = true)
    public List<WbsEvmNode> computeWbsTree(UUID projectId, EvmTechnique technique, EtcMethod etcMethod) {
        LocalDate dataDate = resolveDataDate(projectId);
        List<WbsNode> allWbs = wbsNodeRepository.findByProjectIdOrderBySortOrder(projectId);
        List<Activity> allActivities = activityRepository.findByProjectId(projectId);
        List<ActivityExpense> allExpenses = activityExpenseRepository.findByProjectId(projectId);
        List<ResourceAssignment> allAssignments = resourceAssignmentRepository.findByProjectId(projectId);
        List<ActivitySubContractorAssignment> allScAssignments =
                activitySubContractorAssignmentRepository.findByProjectId(projectId);

        Map<UUID, List<Activity>> activitiesByWbs = allActivities.stream()
                .filter(a -> a.getWbsNodeId() != null)
                .collect(Collectors.groupingBy(Activity::getWbsNodeId));
        Map<UUID, List<ActivityExpense>> expensesByActivity = allExpenses.stream()
                .filter(e -> e.getActivityId() != null)
                .collect(Collectors.groupingBy(ActivityExpense::getActivityId));
        Map<UUID, List<ResourceAssignment>> assignmentsByActivity = allAssignments.stream()
                .collect(Collectors.groupingBy(ResourceAssignment::getActivityId));
        Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity = allScAssignments.stream()
                .filter(s -> s.getActivityId() != null)
                .collect(Collectors.groupingBy(ActivitySubContractorAssignment::getActivityId));
        Map<UUID, BigDecimal> dprAcByActivity = dprActualCostLookup.sumByActivity(projectId);

        EvmTechniqueStrategy strategy = EvmTechniqueFactory.getStrategy(technique);
        Map<UUID, List<WbsNode>> childrenMap = allWbs.stream()
                .filter(w -> w.getParentId() != null)
                .collect(Collectors.groupingBy(WbsNode::getParentId));
        List<WbsNode> roots = allWbs.stream().filter(w -> w.getParentId() == null).toList();

        List<WbsEvmNode> result = new ArrayList<>();
        for (WbsNode root : roots) {
            result.add(buildWbsEvmTreeTransient(root, childrenMap, activitiesByWbs,
                    expensesByActivity, assignmentsByActivity, scAssignmentsByActivity, dprAcByActivity,
                    strategy, dataDate, etcMethod, projectId));
        }
        return result;
    }

    private WbsEvmNode buildWbsEvmTreeTransient(
            WbsNode node, Map<UUID, List<WbsNode>> childrenMap,
            Map<UUID, List<Activity>> activitiesByWbs,
            Map<UUID, List<ActivityExpense>> expensesByActivity,
            Map<UUID, List<ResourceAssignment>> assignmentsByActivity,
            Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity,
            Map<UUID, BigDecimal> dprAcByActivity,
            EvmTechniqueStrategy strategy, LocalDate dataDate, EtcMethod etcMethod, UUID projectId) {

        List<WbsNode> children = childrenMap.getOrDefault(node.getId(), List.of());
        BigDecimal totalPv = BigDecimal.ZERO, totalEv = BigDecimal.ZERO,
                   totalAc = BigDecimal.ZERO, totalBac = BigDecimal.ZERO;
        List<WbsEvmNode> childResults = new ArrayList<>();
        for (WbsNode child : children) {
            WbsEvmNode cr = buildWbsEvmTreeTransient(child, childrenMap, activitiesByWbs,
                    expensesByActivity, assignmentsByActivity, scAssignmentsByActivity, dprAcByActivity,
                    strategy, dataDate, etcMethod, projectId);
            childResults.add(cr);
            totalPv = totalPv.add(cr.plannedValue());
            totalEv = totalEv.add(cr.earnedValue());
            totalAc = totalAc.add(cr.actualCost());
            totalBac = totalBac.add(cr.budgetAtCompletion());
        }
        for (Activity activity : activitiesByWbs.getOrDefault(node.getId(), List.of())) {
            BigDecimal aBac = getActivityBac(activity, expensesByActivity, assignmentsByActivity, scAssignmentsByActivity);
            BigDecimal aPv = getActivityPv(activity, aBac, dataDate);
            BigDecimal aEv = strategy.calculateEarnedValue(activity, aBac, aPv);
            BigDecimal aAc = getActivityAc(activity, expensesByActivity, assignmentsByActivity, dprAcByActivity);
            totalBac = totalBac.add(aBac);
            totalPv = totalPv.add(aPv);
            totalEv = totalEv.add(aEv);
            totalAc = totalAc.add(aAc);
        }
        EvmCalculation calc = computeCalculationTransient(projectId, node.getId(), dataDate,
                totalPv, totalEv, totalAc, totalBac, etcMethod, EvmTechnique.ACTIVITY_PERCENT_COMPLETE);
        return new WbsEvmNode(node.getId(), node.getName(), node.getCode(),
                totalBac, totalPv, totalEv, totalAc,
                calc.getScheduleVariance(), calc.getCostVariance(),
                calc.getSchedulePerformanceIndex(), calc.getCostPerformanceIndex(),
                calc.getEstimateAtCompletion(), calc.getEstimateToComplete(),
                calc.getVarianceAtCompletion(), childResults);
    }

    /**
     * Returns the project's {@code dataDate} when set; otherwise falls back to {@code LocalDate.now()}
     * and logs an INFO so the operator knows the computation is anchored to the system clock.
     */
    private LocalDate resolveDataDate(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project != null && project.getDataDate() != null) {
            return project.getDataDate();
        }
        log.info("EvmRollup[project={}]: project.dataDate is null — defaulting dataDate to LocalDate.now(). "
                + "Set a dataDate on the project to anchor EVM computations.", projectId);
        return LocalDate.now();
    }
}
