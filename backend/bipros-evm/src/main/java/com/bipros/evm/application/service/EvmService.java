package com.bipros.evm.application.service;

import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.common.event.EvmRecalculatedEvent;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.cost.domain.entity.CostAccount;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.cost.domain.repository.CostAccountRepository;
import com.bipros.evm.application.dto.ActivityEvmResponse;
import com.bipros.evm.application.dto.CalculateEvmRequest;
import com.bipros.evm.application.dto.CostAccountRollupResponse;
import com.bipros.evm.application.dto.EvmCalculationResponse;
import com.bipros.evm.application.dto.EvmSummaryResponse;
import com.bipros.evm.domain.algorithm.EvmTechniqueFactory;
import com.bipros.evm.domain.algorithm.EvmTechniqueStrategy;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmTechnique;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.application.service.DprActualCostLookup;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EvmService {

    private static final int SCALE = 4;
    private static final Logger log = LoggerFactory.getLogger(EvmService.class);

    private final EvmCalculationRepository evmCalculationRepository;
    private final ActivityRepository activityRepository;
    private final ActivityExpenseRepository activityExpenseRepository;
    private final ResourceAssignmentRepository resourceAssignmentRepository;
    private final ActivitySubContractorAssignmentRepository activitySubContractorAssignmentRepository;
    private final CostAccountRepository costAccountRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final ProjectRepository projectRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final FormulaEngine formulaEngine;
    private final DprActualCostLookup dprActualCostLookup;
    private final PercentCompleteCalculator percentCompleteCalculator;

    @Transactional(readOnly = true)
    public EvmCalculation computeEvmSnapshot(UUID projectId, EvmTechnique technique, EtcMethod etcMethod) {
        var project = projectRepository.findById(projectId).orElse(null);
        LocalDate dataDate = resolveDataDate(project, projectId);

        List<Activity> activities = activityRepository.findByProjectId(projectId);
        List<ActivityExpense> allExpenses = activityExpenseRepository.findByProjectId(projectId);
        List<ResourceAssignment> allAssignments = resourceAssignmentRepository.findByProjectId(projectId);
        List<ActivitySubContractorAssignment> allScAssignments =
                activitySubContractorAssignmentRepository.findByProjectId(projectId);

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

        BigDecimal totalPv = BigDecimal.ZERO;
        BigDecimal totalEv = BigDecimal.ZERO;
        BigDecimal totalAc = BigDecimal.ZERO;
        BigDecimal totalBac = BigDecimal.ZERO;

        for (Activity activity : activities) {
            // On-demand percent-complete refresh: covers the gap when DPR listener was a no-op
            // (e.g. fired before actualStartDate was persisted) and the nightly DurationPercentCompleteJob
            // hasn't run yet. Without this, the strategy reads a stale 0 and EV is 0 even though
            // the activity is partway through its planned duration.
            refreshPercentCompleteIfStale(activity, dataDate);
            BigDecimal activityBac = EvmRollupService.getActivityBac(activity, expensesByActivity,
                    assignmentsByActivity, scAssignmentsByActivity);
            BigDecimal activityPv = EvmRollupService.getActivityPv(activity, activityBac, dataDate);
            BigDecimal activityEv = strategy.calculateEarnedValue(activity, activityBac, activityPv);
            BigDecimal activityAc = EvmRollupService.getActivityAc(activity, expensesByActivity, assignmentsByActivity, dprAcByActivity);

            totalBac = totalBac.add(activityBac);
            totalPv = totalPv.add(activityPv);
            totalEv = totalEv.add(activityEv);
            totalAc = totalAc.add(activityAc);
        }

        // Project-level BAC source of truth: Project.currentBudget (approved, change-controlled)
        // when set. Activity rollup is the bottom-up forecast (still available via the
        // CostAccountRollup endpoint for variance reporting).
        //
        // DEFECT-11 unit fix: project.currentBudget is stored in the currency's "major-scale"
        // unit (crores for INR = 1e7, millions for every other currency = 1e6) — this matches
        // the Set-Budget UI ("Amount (crores)" / "Amount (millions OMR)"), the formatBudget
        // helper that renders it ("0.02 cr"), and the OmanRoadProjectSeeder. EV/AC/PV in this
        // method are all in raw currency units (rupees / baisa-less OMR), so we must convert
        // currentBudget to raw units before slotting it into totalBac. Without this, EVM
        // returns BAC ≈ 0.02 alongside EV ≈ 62 500, garbage CPI/ETC/EAC.
        BigDecimal projectBac = project != null
                ? (project.getCurrentBudget() != null ? project.getCurrentBudget() : project.getOriginalBudget())
                : null;
        if (projectBac != null && projectBac.signum() > 0) {
            String currency = project.getBudgetCurrency();
            BigDecimal majorUnitFactor = "INR".equalsIgnoreCase(currency)
                    ? new BigDecimal("10000000")   // 1 crore = 10^7
                    : new BigDecimal("1000000");   // 1 million = 10^6 (OMR and all others)
            totalBac = projectBac.multiply(majorUnitFactor);
        }

        var calculation = new EvmCalculation();
        calculation.setProjectId(projectId);
        calculation.setDataDate(dataDate);
        calculation.setEvmTechnique(technique);
        calculation.setEtcMethod(etcMethod);
        calculation.setBudgetAtCompletion(totalBac);
        calculation.setPlannedValue(totalPv);
        calculation.setEarnedValue(totalEv);
        calculation.setActualCost(totalAc);

        EvmServiceHelper.calculateIndices(calculation, formulaEngine);
        return calculation;
    }

    /** Default technique/ETC convenience overload used by read-only report surfaces. */
    @Transactional(readOnly = true)
    public EvmCalculation computeEvmSnapshot(UUID projectId) {
        return computeEvmSnapshot(projectId, EvmTechnique.ACTIVITY_PERCENT_COMPLETE, EtcMethod.CPI_BASED);
    }

    @Transactional
    public EvmCalculationResponse calculateEvm(UUID projectId, CalculateEvmRequest request) {
        EvmCalculation calculation = computeEvmSnapshot(projectId, request.technique(), request.etcMethod());
        var saved = evmCalculationRepository.save(calculation);
        auditService.logCreate("EvmCalculation", saved.getId(), EvmCalculationResponse.from(saved));
        eventPublisher.publishEvent(new EvmRecalculatedEvent(
            saved.getProjectId(), saved.getId(), saved.getDataDate()));
        return EvmCalculationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public EvmCalculationResponse getLatestEvm(UUID projectId) {
        return evmCalculationRepository.findTopByProjectIdOrderByDataDateDesc(projectId)
                .map(EvmCalculationResponse::from)
                .orElse(emptyEvmResponse(projectId));
    }

    private EvmCalculationResponse emptyEvmResponse(UUID projectId) {
        return new EvmCalculationResponse(
                null, projectId, null, null, null, LocalDate.now(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, 0.0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, 0.0);
    }

    @Transactional(readOnly = true)
    public List<EvmCalculationResponse> getEvmHistory(UUID projectId) {
        // Only project-level rows (wbsNodeId IS NULL, activityId IS NULL) belong in the S-curve.
        return evmCalculationRepository.findProjectLevelByProjectIdOrderByDataDateDesc(projectId)
                .stream()
                .map(EvmCalculationResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EvmCalculationResponse getEvmByWbs(UUID projectId, UUID wbsNodeId) {
        var entity = evmCalculationRepository.findTopByProjectIdAndWbsNodeIdOrderByDataDateDesc(projectId, wbsNodeId)
                .orElseThrow(() -> new ResourceNotFoundException("EvmCalculation", wbsNodeId));
        return EvmCalculationResponse.from(entity);
    }

    @Transactional(readOnly = true)
    public ActivityEvmResponse getActivityEvm(UUID projectId, UUID activityId) {
        var activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity", activityId));

        LocalDate dataDate = resolveDataDate(null, projectId);

        List<ActivityExpense> expenses = activityExpenseRepository.findByActivityId(activityId);
        List<ResourceAssignment> assignments = resourceAssignmentRepository.findByActivityId(activityId);
        List<ActivitySubContractorAssignment> scAssignments =
                activitySubContractorAssignmentRepository.findByProjectIdAndActivityId(projectId, activityId);

        Map<UUID, List<ActivityExpense>> expensesByActivity = expenses.stream()
                .collect(Collectors.groupingBy(e -> activityId));
        Map<UUID, List<ResourceAssignment>> assignmentsByActivity = assignments.stream()
                .collect(Collectors.groupingBy(r -> activityId));
        Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity =
                scAssignments.isEmpty() ? Map.of() : Map.of(activityId, scAssignments);

        BigDecimal bac = EvmRollupService.getActivityBac(activity, expensesByActivity,
                assignmentsByActivity, scAssignmentsByActivity);
        BigDecimal pv = EvmRollupService.getActivityPv(activity, bac, dataDate);

        EvmTechnique technique = resolveEvmTechnique(activity.getPercentCompleteType());
        EvmTechniqueStrategy strategy = EvmTechniqueFactory.getStrategy(technique);
        BigDecimal ev = strategy.calculateEarnedValue(activity, bac, pv);

        // Per-activity DPR AC fetch — one query for this activity only, since getActivityEvm is a
        // single-activity endpoint (not part of the project-wide rollup that uses sumByActivity()).
        BigDecimal dprAc = dprActualCostLookup.sumByActivity(projectId, activityId);
        BigDecimal ac = EvmRollupService.getActivityAc(activity, expensesByActivity, assignmentsByActivity,
                Map.of(activityId, dprAc));

        Map<String, BigDecimal> ctx = Map.of(
                "EV", nvl(ev),
                "AC", nvl(ac),
                "PV", nvl(pv)
        );

        BigDecimal cv = safeEvalBigDecimal("EVM_CV", projectId, ctx, ev.subtract(ac));
        BigDecimal sv = safeEvalBigDecimal("EVM_SV", projectId, ctx, ev.subtract(pv));

        Double cpi = safeEvalDouble("EVM_CPI", projectId, ctx,
                ac.compareTo(BigDecimal.ZERO) != 0
                        ? ev.divide(ac, 4, RoundingMode.HALF_UP).doubleValue()
                        : null);
        Double spi = safeEvalDouble("EVM_SPI", projectId, ctx,
                pv.compareTo(BigDecimal.ZERO) != 0
                        ? ev.divide(pv, 4, RoundingMode.HALF_UP).doubleValue()
                        : null);

        Double pct = activity.getPercentComplete();

        return new ActivityEvmResponse(
                activityId, projectId,
                bac, pv, ev, ac, cv, sv,
                cpi, spi,
                pct,
                technique.name()
        );
    }

    private BigDecimal safeEvalBigDecimal(String code, UUID projectId, Map<String, BigDecimal> ctx, BigDecimal fallback) {
        if (formulaEngine == null) return fallback;
        try {
            var result = formulaEngine.evaluate(code, projectId, ctx);
            return result.isError() ? fallback : result.getValue();
        } catch (Exception e) {
            return fallback;
        }
    }

    private Double safeEvalDouble(String code, UUID projectId, Map<String, BigDecimal> ctx, Double fallback) {
        if (formulaEngine == null) return fallback;
        try {
            var result = formulaEngine.evaluate(code, projectId, ctx);
            return result.isError() ? fallback : result.getValue().doubleValue();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Returns the project's {@code dataDate} when set; otherwise falls back to {@code LocalDate.now()}
     * and logs a warning so the operator knows the computation is anchored to the system clock.
     *
     * @param project    already-loaded project entity (may be null — triggers a DB fetch by projectId)
     * @param projectId  used for the DB fetch when {@code project} is null
     */
    private LocalDate resolveDataDate(com.bipros.project.domain.model.Project project, UUID projectId) {
        var p = (project != null) ? project : projectRepository.findById(projectId).orElse(null);
        if (p != null && p.getDataDate() != null) {
            return p.getDataDate();
        }
        log.info("EVM[project={}]: project.dataDate is null — defaulting dataDate to LocalDate.now(). "
                + "Set a dataDate on the project to anchor EVM computations.", projectId);
        return LocalDate.now();
    }

    /**
     * For IN_PROGRESS DURATION/UNITS activities whose stored percentComplete is null or 0
     * but whose actualStartDate + originalDuration imply progress against the given dataDate,
     * recompute via {@link PercentCompleteCalculator} so the strategy sees the correct value.
     * Non-mutating against the DB — only updates the in-memory entity for this calculation.
     */
    private void refreshPercentCompleteIfStale(Activity activity, LocalDate dataDate) {
        if (activity == null || dataDate == null) return;
        if (activity.getStatus() != ActivityStatus.IN_PROGRESS) return;
        PercentCompleteType type = activity.getPercentCompleteType();
        if (type != PercentCompleteType.DURATION) return; // UNITS needs unit sums we don't have here
        Double current = activity.getPercentComplete();
        if (current != null && current > 0.0) return; // already non-zero — trust it
        try {
            PercentCompleteCalculator.Result result =
                    percentCompleteCalculator.calculate(activity, null, null, dataDate);
            if (result == null || result.isKeepPrior() || result.percent() == null) return;
            if (result.percent() > 0.0) {
                activity.setPercentComplete(result.percent());
                if (activity.getDurationPercentComplete() == null) {
                    activity.setDurationPercentComplete(result.percent());
                }
            }
        } catch (Exception e) {
            log.debug("refreshPercentCompleteIfStale: activity={} skipped due to {}", activity.getId(), e.toString());
        }
    }

    private static EvmTechnique resolveEvmTechnique(PercentCompleteType type) {
        if (type == null) return EvmTechnique.ACTIVITY_PERCENT_COMPLETE;
        return switch (type) {
            case PHYSICAL -> EvmTechnique.WEIGHTED_STEPS;
            case DURATION, UNITS -> EvmTechnique.ACTIVITY_PERCENT_COMPLETE;
        };
    }

    /**
     * Rolls up EVM metrics (BAC, PV, EV, AC, CV, SV, CPI, SPI) per cost account for the given
     * project, using P6-style soft inheritance: activity.costAccountId wins; if null, falls back
     * to the WBS node's costAccountId; if still null, the activity lands in the "Unassigned"
     * bucket.
     *
     * <p>PV/SV/SPI are null for a bucket when any contributing activity has a null PV (partial
     * data). CPI is null when the bucket's AC is zero.
     *
     * <p>Sorting: assigned buckets by code ascending, "Unassigned" last.
     */
    @Transactional(readOnly = true)
    public List<CostAccountRollupResponse> getCostAccountRollup(UUID projectId) {
        LocalDate dataDate = resolveDataDate(null, projectId);

        List<Activity> activities = activityRepository.findByProjectId(projectId);
        List<ActivityExpense> allExpenses = activityExpenseRepository.findByProjectId(projectId);
        List<ResourceAssignment> allAssignments = resourceAssignmentRepository.findByProjectId(projectId);
        List<ActivitySubContractorAssignment> allScAssignments =
                activitySubContractorAssignmentRepository.findByProjectId(projectId);

        // Pre-load all WBS nodes for the project to support in-memory inheritance lookup (N+1 safe)
        Map<UUID, WbsNode> wbsById = wbsNodeRepository.findByProjectIdOrderBySortOrder(projectId)
                .stream()
                .collect(Collectors.toMap(w -> w.getId(), w -> w));

        // Group cost data by activity
        Map<UUID, List<ActivityExpense>> expensesByActivity = allExpenses.stream()
                .filter(e -> e.getActivityId() != null)
                .collect(Collectors.groupingBy(ActivityExpense::getActivityId));
        Map<UUID, List<ResourceAssignment>> assignmentsByActivity = allAssignments.stream()
                .collect(Collectors.groupingBy(ResourceAssignment::getActivityId));
        Map<UUID, List<ActivitySubContractorAssignment>> scAssignmentsByActivity = allScAssignments.stream()
                .filter(s -> s.getActivityId() != null)
                .collect(Collectors.groupingBy(ActivitySubContractorAssignment::getActivityId));
        Map<UUID, BigDecimal> dprAcByActivity = dprActualCostLookup.sumByActivity(projectId);

        // Accumulator per resolved cost account ID (null = unassigned)
        record Bucket(
                BigDecimal bac,
                BigDecimal pv,      // null means "at least one activity had null PV"
                BigDecimal ev,
                BigDecimal ac,
                int count
        ) {}

        Map<UUID, BigDecimal> bucketBac = new LinkedHashMap<>();
        Map<UUID, BigDecimal> bucketPv = new LinkedHashMap<>();   // null entry means "pv unknown"
        Map<UUID, BigDecimal> bucketEv = new LinkedHashMap<>();
        Map<UUID, BigDecimal> bucketAc = new LinkedHashMap<>();
        Map<UUID, Integer> bucketCount = new LinkedHashMap<>();
        Set<UUID> pvNull = new HashSet<>();  // buckets that have at least one null-PV activity

        for (Activity activity : activities) {
            // Resolve cost account: activity-level wins, then WBS node
            UUID caId = activity.getCostAccountId();
            if (caId == null && activity.getWbsNodeId() != null) {
                WbsNode wbs = wbsById.get(activity.getWbsNodeId());
                if (wbs != null) {
                    caId = wbs.getCostAccountId();
                }
            }
            // null caId → "Unassigned" bucket (represented by null key)

            EvmTechnique technique = resolveEvmTechnique(activity.getPercentCompleteType());
            EvmTechniqueStrategy strategy = EvmTechniqueFactory.getStrategy(technique);

            BigDecimal actBac = EvmRollupService.getActivityBac(activity, expensesByActivity,
                    assignmentsByActivity, scAssignmentsByActivity);
            BigDecimal actPv = EvmRollupService.getActivityPv(activity, actBac, dataDate);
            BigDecimal actEv = strategy.calculateEarnedValue(activity, actBac, actPv);
            BigDecimal actAc = EvmRollupService.getActivityAc(activity, expensesByActivity, assignmentsByActivity, dprAcByActivity);

            // Detect null PV: getActivityPv returns ZERO both for genuinely zero PV and for activities
            // whose dates make PV non-computable (no finish date, no start date, or dataDate before
            // plannedStartDate). All three cases must propagate null so the bucket's SV/SPI show null
            // rather than a falsely optimistic zero.
            boolean actPvNull = activity.getPlannedFinishDate() == null
                    || activity.getPlannedStartDate() == null
                    || dataDate.isBefore(activity.getPlannedStartDate());

            bucketBac.merge(caId, actBac, BigDecimal::add);
            bucketEv.merge(caId, actEv, BigDecimal::add);
            bucketAc.merge(caId, actAc, BigDecimal::add);
            bucketCount.merge(caId, 1, Integer::sum);

            if (actPvNull) {
                pvNull.add(caId);
                // Still accumulate zero so we can sum non-null contributors; the flag governs output
                bucketPv.merge(caId, BigDecimal.ZERO, BigDecimal::add);
            } else {
                bucketPv.merge(caId, actPv, BigDecimal::add);
            }
        }

        // Resolve cost account names/codes in one bulk call
        Set<UUID> assignedIds = bucketBac.keySet().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, CostAccount> caById = assignedIds.isEmpty()
                ? Collections.emptyMap()
                : costAccountRepository.findAllById(assignedIds).stream()
                        .collect(Collectors.toMap(c -> c.getId(), c -> c));

        // Build response rows
        List<CostAccountRollupResponse> rows = new ArrayList<>();

        for (UUID caId : bucketBac.keySet()) {
            BigDecimal bac = bucketBac.get(caId);
            BigDecimal pv = pvNull.contains(caId) ? null : bucketPv.get(caId);
            BigDecimal ev = bucketEv.get(caId);
            BigDecimal ac = bucketAc.get(caId);
            int count = bucketCount.get(caId);

            Map<String, BigDecimal> ctx = new HashMap<>();
            ctx.put("EV", nvl(ev));
            ctx.put("AC", nvl(ac));
            if (pv != null) ctx.put("PV", pv);

            BigDecimal cv = safeEvalBigDecimal("EVM_CV", projectId, ctx, ev.subtract(ac));
            BigDecimal sv = pv != null
                    ? safeEvalBigDecimal("EVM_SV", projectId, ctx, ev.subtract(pv))
                    : null;
            BigDecimal cpi = safeEvalBigDecimal("EVM_CPI", projectId, ctx,
                    ac.compareTo(BigDecimal.ZERO) != 0
                            ? ev.divide(ac, SCALE, RoundingMode.HALF_UP)
                            : null);
            BigDecimal spi = (pv != null && pv.compareTo(BigDecimal.ZERO) != 0)
                    ? safeEvalBigDecimal("EVM_SPI", projectId, ctx,
                            ev.divide(pv, SCALE, RoundingMode.HALF_UP))
                    : null;

            String code;
            String name;
            if (caId == null) {
                code = null;
                name = "Unassigned";
            } else {
                CostAccount ca = caById.get(caId);
                code = (ca != null) ? ca.getCode() : caId.toString();
                name = (ca != null) ? ca.getName() : caId.toString();
            }

            rows.add(new CostAccountRollupResponse(
                    caId, code, name, bac, pv, ev, ac, cv, sv, cpi, spi, count));
        }

        // Sort: assigned buckets by code ascending, "Unassigned" (null caId) last
        rows.sort((a, b) -> {
            boolean aUnassigned = a.costAccountId() == null;
            boolean bUnassigned = b.costAccountId() == null;
            if (aUnassigned && bUnassigned) return 0;
            if (aUnassigned) return 1;
            if (bUnassigned) return -1;
            String codeA = a.costAccountCode() != null ? a.costAccountCode() : "";
            String codeB = b.costAccountCode() != null ? b.costAccountCode() : "";
            return codeA.compareToIgnoreCase(codeB);
        });

        return rows;
    }

    @Transactional(readOnly = true)
    public EvmSummaryResponse getSummary(UUID projectId) {
        var optCalc = evmCalculationRepository.findTopByProjectIdOrderByDataDateDesc(projectId);
        if (optCalc.isEmpty()) {
            return new EvmSummaryResponse(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, 0.0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, 0.0);
        }
        var calculation = optCalc.get();

        return new EvmSummaryResponse(
                calculation.getBudgetAtCompletion(),
                calculation.getPlannedValue(),
                calculation.getEarnedValue(),
                calculation.getActualCost(),
                calculation.getScheduleVariance(),
                calculation.getCostVariance(),
                calculation.getSchedulePerformanceIndex(),
                calculation.getCostPerformanceIndex(),
                calculation.getToCompletePerformanceIndex(),
                calculation.getEstimateAtCompletion(),
                calculation.getEstimateToComplete(),
                calculation.getVarianceAtCompletion(),
                calculation.getEvmTechnique() != null ? calculation.getEvmTechnique().toString() : null,
                calculation.getEtcMethod() != null ? calculation.getEtcMethod().toString() : null,
                calculation.getPerformancePercentComplete()
        );
    }
}
