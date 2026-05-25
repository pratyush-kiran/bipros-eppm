package com.bipros.api.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.admin.domain.model.Organisation;
import com.bipros.admin.domain.repository.OrganisationRepository;
import com.bipros.api.dto.WorkPackageRowResponse;
import com.bipros.evm.application.dto.WbsEvmNode;
import com.bipros.evm.application.service.EvmRollupService;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmTechnique;
import com.bipros.project.application.dto.WbsNodeResponse;
import com.bipros.project.application.service.WbsService;
import com.bipros.project.domain.model.WbsType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregator that turns each leaf WBS node into a {@link WorkPackageRowResponse} for the Work
 * Packages list page. Rollups (activity counts, weighted progress, days-behind, EVM passthroughs,
 * critical-path indicator, derived status) are computed on-the-fly per request — there is no
 * persisted recompute. For a typical project this is a few hundred millis of in-memory math after
 * three repository reads (WBS tree, all activities, EVM tree).
 *
 * <p>Lives in {@code bipros-api} because it spans {@code bipros-project}, {@code bipros-activity},
 * {@code bipros-evm}, and {@code bipros-admin} — none of which may depend on each other.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class WorkPackageRollupService {

    /** Threshold below which SPI flags the row as AT_RISK. P6's default warning band. */
    private static final double SPI_AT_RISK_THRESHOLD = 0.9;

    /** Days-behind / planned-duration ratio above which the row flags AT_RISK. */
    private static final double DAYS_BEHIND_AT_RISK_RATIO = 0.1;

    private final WbsService wbsService;
    private final ActivityRepository activityRepository;
    private final EvmRollupService evmRollupService;
    private final OrganisationRepository organisationRepository;
    private final Clock clock;

    public List<WorkPackageRowResponse> listWorkPackages(UUID projectId) {
        LocalDate today = LocalDate.now(clock);

        List<WbsNodeResponse> tree = wbsService.getTree(projectId);
        List<WbsNodeResponse> flatNodes = flatten(tree);
        Map<UUID, WbsNodeResponse> nodeById = flatNodes.stream()
            .collect(Collectors.toMap(WbsNodeResponse::id, n -> n, (a, b) -> a));

        List<WbsNodeResponse> workPackages = filterToWorkPackages(flatNodes);
        if (workPackages.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<Activity>> activitiesByWbs = activityRepository.findByProjectId(projectId).stream()
            .filter(a -> a.getWbsNodeId() != null)
            .collect(Collectors.groupingBy(Activity::getWbsNodeId));

        Map<UUID, WbsEvmNode> evmByWbs = loadEvmRollup(projectId);

        Map<UUID, String> orgNameById = loadOrganisationNames(workPackages);

        return workPackages.stream()
            .map(node -> buildRow(node, nodeById, activitiesByWbs.getOrDefault(node.id(), List.of()),
                evmByWbs.get(node.id()), orgNameById, today))
            .sorted(Comparator.comparing(WorkPackageRowResponse::code,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    /** Depth-first flattening of the WBS tree into a plain list. */
    private List<WbsNodeResponse> flatten(List<WbsNodeResponse> roots) {
        List<WbsNodeResponse> out = new ArrayList<>();
        for (WbsNodeResponse root : roots) {
            walk(root, out);
        }
        return out;
    }

    private void walk(WbsNodeResponse node, List<WbsNodeResponse> out) {
        out.add(node);
        if (node.children() != null) {
            for (WbsNodeResponse c : node.children()) {
                walk(c, out);
            }
        }
    }

    /**
     * Prefer explicit {@code WORK_PACKAGE}-typed nodes when present (the canonical case for
     * IC-PMS projects). Fall back to leaf nodes (no children) when a project doesn't tag work
     * packages — that way the page still has rows even on legacy data.
     */
    private List<WbsNodeResponse> filterToWorkPackages(List<WbsNodeResponse> flat) {
        List<WbsNodeResponse> typed = flat.stream()
            .filter(n -> n.wbsType() == WbsType.WORK_PACKAGE)
            .toList();
        if (!typed.isEmpty()) {
            return typed;
        }
        return flat.stream()
            .filter(n -> n.children() == null || n.children().isEmpty())
            .toList();
    }

    private Map<UUID, WbsEvmNode> loadEvmRollup(UUID projectId) {
        try {
            List<WbsEvmNode> roots = evmRollupService.calculateWbsTree(
                projectId, EvmTechnique.ACTIVITY_PERCENT_COMPLETE, EtcMethod.CPI_BASED);
            Map<UUID, WbsEvmNode> out = new HashMap<>();
            for (WbsEvmNode root : roots) {
                collectEvm(root, out);
            }
            return out;
        } catch (RuntimeException ex) {
            log.warn("EVM rollup failed for project {} — Work Packages page will render without EVM columns",
                projectId, ex);
            return Map.of();
        }
    }

    private void collectEvm(WbsEvmNode node, Map<UUID, WbsEvmNode> out) {
        if (node.wbsNodeId() != null) {
            out.put(node.wbsNodeId(), node);
        }
        if (node.children() != null) {
            for (WbsEvmNode c : node.children()) {
                collectEvm(c, out);
            }
        }
    }

    private Map<UUID, String> loadOrganisationNames(List<WbsNodeResponse> workPackages) {
        Set<UUID> orgIds = workPackages.stream()
            .map(WbsNodeResponse::responsibleOrganisationId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        if (orgIds.isEmpty()) {
            return Map.of();
        }
        return organisationRepository.findAllById(orgIds).stream()
            .collect(Collectors.toMap(o -> o.getId(), Organisation::getName, (a, b) -> a));
    }

    private WorkPackageRowResponse buildRow(
        WbsNodeResponse node,
        Map<UUID, WbsNodeResponse> nodeById,
        List<Activity> activities,
        WbsEvmNode evm,
        Map<UUID, String> orgNameById,
        LocalDate today
    ) {
        String parentName = node.parentId() == null
            ? null
            : (nodeById.get(node.parentId()) != null ? nodeById.get(node.parentId()).name() : null);

        String contractorName = node.responsibleOrganisationId() == null
            ? null
            : orgNameById.get(node.responsibleOrganisationId());

        ActivityAggregates agg = aggregate(activities, node, today);

        Double spi = evm != null ? evm.schedulePerformanceIndex() : null;
        String status = deriveStatus(agg, spi, today);

        return new WorkPackageRowResponse(
            node.id(),
            node.code(),
            node.name(),
            node.parentId(),
            parentName,
            node.wbsType(),
            node.wbsLevel(),
            node.phase(),
            node.responsibleOrganisationId(),
            contractorName,
            agg.derivedPlannedStart,
            agg.derivedPlannedFinish,
            agg.derivedDurationDays,
            agg.weightedPercentComplete,
            agg.daysBehindSchedule,
            node.budgetCrores(),
            agg.total,
            agg.done,
            agg.inProgress,
            agg.notStarted,
            agg.delayed,
            evm != null ? evm.budgetAtCompletion() : null,
            evm != null ? evm.plannedValue() : null,
            evm != null ? evm.earnedValue() : null,
            evm != null ? evm.actualCost() : null,
            evm != null ? evm.scheduleVariance() : null,
            evm != null ? evm.costVariance() : null,
            spi,
            evm != null ? evm.costPerformanceIndex() : null,
            evm != null ? evm.estimateAtCompletion() : null,
            evm != null ? evm.varianceAtCompletion() : null,
            agg.minTotalFloat,
            agg.minTotalFloat != null && agg.minTotalFloat <= 0,
            status
        );
    }

    private ActivityAggregates aggregate(List<Activity> activities, WbsNodeResponse node, LocalDate today) {
        ActivityAggregates agg = new ActivityAggregates();
        agg.total = (long) activities.size();

        if (activities.isEmpty()) {
            // Fall back to whatever the WBS itself carries.
            agg.derivedPlannedStart = node.plannedStart();
            agg.derivedPlannedFinish = node.plannedFinish();
            agg.derivedDurationDays = (agg.derivedPlannedStart != null && agg.derivedPlannedFinish != null)
                ? ChronoUnit.DAYS.between(agg.derivedPlannedStart, agg.derivedPlannedFinish)
                : null;
            agg.weightedPercentComplete = node.summaryPercentComplete();
            agg.daysBehindSchedule = null;
            agg.minTotalFloat = null;
            return agg;
        }

        double weightedNumerator = 0d;
        double weightedDenominator = 0d;
        double simpleSum = 0d;
        Double minFloat = null;
        LocalDate minStart = null;
        LocalDate maxFinish = null;

        for (Activity a : activities) {
            double pct = a.getPercentComplete() == null ? 0d : a.getPercentComplete();
            double dur = a.getOriginalDuration() == null ? 0d : a.getOriginalDuration();
            weightedNumerator += dur * pct;
            weightedDenominator += dur;
            simpleSum += pct;

            ActivityStatus s = a.getStatus();
            if (s == ActivityStatus.COMPLETED) agg.done++;
            else if (s == ActivityStatus.IN_PROGRESS) agg.inProgress++;
            else agg.notStarted++;

            // "Delayed" = past planned finish and not complete. Independent of the count buckets
            // above so the UI can show e.g. "3 in-progress, 2 of which delayed".
            if (s != ActivityStatus.COMPLETED
                && a.getPlannedFinishDate() != null
                && today.isAfter(a.getPlannedFinishDate())) {
                agg.delayed++;
            }

            Double f = a.getTotalFloat();
            if (f != null && (minFloat == null || f < minFloat)) {
                minFloat = f;
            }
            if (a.getPlannedStartDate() != null && (minStart == null || a.getPlannedStartDate().isBefore(minStart))) {
                minStart = a.getPlannedStartDate();
            }
            if (a.getPlannedFinishDate() != null && (maxFinish == null || a.getPlannedFinishDate().isAfter(maxFinish))) {
                maxFinish = a.getPlannedFinishDate();
            }
        }

        agg.weightedPercentComplete = weightedDenominator > 0
            ? weightedNumerator / weightedDenominator
            : (activities.isEmpty() ? null : simpleSum / activities.size());

        agg.derivedPlannedStart = minStart != null ? minStart : node.plannedStart();
        agg.derivedPlannedFinish = maxFinish != null ? maxFinish : node.plannedFinish();
        agg.derivedDurationDays = (agg.derivedPlannedStart != null && agg.derivedPlannedFinish != null)
            ? ChronoUnit.DAYS.between(agg.derivedPlannedStart, agg.derivedPlannedFinish)
            : null;
        agg.minTotalFloat = minFloat;
        agg.daysBehindSchedule = computeDaysBehind(agg, today);
        return agg;
    }

    /**
     * Three-branch derivation:
     * <ol>
     *   <li>Fully complete → 0 (on time).</li>
     *   <li>Past planned finish and incomplete → calendar days late.</li>
     *   <li>In flight → planned-vs-actual progress gap translated to days via duration.</li>
     * </ol>
     * Returns {@code null} when the row carries no usable planned dates so the UI can render "—".
     */
    private Long computeDaysBehind(ActivityAggregates agg, LocalDate today) {
        if (agg.derivedPlannedStart == null || agg.derivedPlannedFinish == null) {
            return null;
        }
        Double pct = agg.weightedPercentComplete;
        if (pct != null && pct >= 100d) {
            return 0L;
        }
        if (today.isAfter(agg.derivedPlannedFinish)) {
            return ChronoUnit.DAYS.between(agg.derivedPlannedFinish, today);
        }
        if (agg.derivedDurationDays == null || agg.derivedDurationDays <= 0) {
            return 0L;
        }
        long elapsedDays = Math.max(0L, ChronoUnit.DAYS.between(agg.derivedPlannedStart, today));
        double plannedPct = Math.min(100d, (elapsedDays * 100d) / agg.derivedDurationDays);
        double actualPct = pct == null ? 0d : pct;
        double gapPct = plannedPct - actualPct;
        if (gapPct <= 0d) {
            return 0L;
        }
        return Math.round((gapPct / 100d) * agg.derivedDurationDays);
    }

    /**
     * Status mapping — first matching rule wins. Mirrors the badge buckets the UI's
     * {@code StatusBadge[gantt]} variant supports.
     */
    private String deriveStatus(ActivityAggregates agg, Double spi, LocalDate today) {
        Double pct = agg.weightedPercentComplete;
        if (pct != null && pct >= 100d) return "DONE";
        if (agg.total == 0L) return "PLANNED";
        if (agg.derivedPlannedFinish != null && today.isAfter(agg.derivedPlannedFinish)
            && (pct == null || pct < 100d)) {
            return "DELAYED";
        }
        if (spi != null && spi < SPI_AT_RISK_THRESHOLD) return "AT_RISK";
        if (agg.daysBehindSchedule != null && agg.derivedDurationDays != null
            && agg.derivedDurationDays > 0
            && agg.daysBehindSchedule > DAYS_BEHIND_AT_RISK_RATIO * agg.derivedDurationDays) {
            return "AT_RISK";
        }
        if ((pct == null || pct <= 0d)
            && agg.derivedPlannedStart != null && today.isBefore(agg.derivedPlannedStart)) {
            return "NOT_STARTED";
        }
        return "IN_PROGRESS";
    }

    /** Mutable bag carried between {@link #aggregate}, {@link #computeDaysBehind}, {@link #deriveStatus}. */
    private static final class ActivityAggregates {
        long total;
        long done;
        long inProgress;
        long notStarted;
        long delayed;
        Double weightedPercentComplete;
        LocalDate derivedPlannedStart;
        LocalDate derivedPlannedFinish;
        Long derivedDurationDays;
        Long daysBehindSchedule;
        Double minTotalFloat;
    }
}
