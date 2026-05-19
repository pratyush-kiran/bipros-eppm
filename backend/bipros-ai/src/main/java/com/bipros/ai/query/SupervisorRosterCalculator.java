package com.bipros.ai.query;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.ProjectResource;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.repository.ProjectResourceRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Roster-level supervisor rollup. Computes a per-supervisor summary for every
 * supervisor with at least one activity on a project, optionally appended by
 * the unassigned-but-eligible labor pool, so the AI can answer discovery
 * questions like "how many supervisors are there?" or "rank supervisors by
 * activity count" without first knowing who they are.
 *
 * <p>Diverges from {@link SupervisorPerformanceCalculator} in two ways:
 * <ul>
 *   <li>One-shot batch over all supervisors on the project (avoids N+1 across
 *       the discovery list). Per-row joins are coarser as a result.</li>
 *   <li>No DPR / team aggregation — that's the drill-down responsibility of
 *       {@code SupervisorTool}; the roster keeps payloads compact for the LLM.</li>
 * </ul>
 *
 * <p>Activity scope is enumerated via {@code Activity.responsibleResourceId} —
 * the cached supervisor pointer populated by the bulk-supervisor-assignment
 * flow. This mirrors how {@code SupervisorPerformanceCalculator} scopes work.
 *
 * <p>Per-row cost is summed from {@link ResourceAssignment#getPlannedCost()} /
 * {@link ResourceAssignment#getActualCost()} on the supervised activities; CPI
 * and SPI are computed from the latest {@link EvmCalculation} per activity
 * (mean across activities with EVM rows). When a metric isn't available it is
 * left as {@code null}; the AI tool surfaces nulls as "n/a".
 *
 * <p>Eligible-pool mode resolves the project's {@link ProjectResource} entries
 * filtered to LABOR-type resources, excludes IDs already in the assigned set,
 * and appends zero-activity rows flagged {@link SupervisorRow#isInPool()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupervisorRosterCalculator {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final String LABOR_TYPE_CODE = "MANPOWER";

  private final ActivityRepository activityRepository;
  private final ResourceRepository resourceRepository;
  private final ResourceAssignmentRepository assignmentRepository;
  private final EvmCalculationRepository evmRepository;
  private final ProjectRepository projectRepository;
  private final ProjectResourceRepository projectResourceRepository;

  /** Output of one {@link #compute} call. */
  public record SupervisorRoster(
      UUID projectId,
      String projectCode,
      String projectName,
      int totalSupervisors,
      List<SupervisorRow> rows) {}

  /**
   * Per-supervisor row. {@code cpi} / {@code spi} are nullable when no EVM
   * calculation rows exist for any of the supervised activities, or when the
   * row represents a pool member with zero activity coverage.
   */
  public record SupervisorRow(
      UUID supervisorResourceId,
      String code,
      String name,
      String roleName,
      int activityCount,
      StatusBreakdown statusBreakdown,
      BigDecimal avgPercentComplete,
      BigDecimal plannedCost,
      BigDecimal actualCost,
      BigDecimal costVariancePct,
      BigDecimal cpi,
      BigDecimal spi,
      boolean isInPool) {}

  /**
   * Activity-status counts. {@code onHold} is included for forward-compat with
   * the planned status model and is always zero today — {@link ActivityStatus}
   * only defines {@code NOT_STARTED}, {@code IN_PROGRESS}, {@code COMPLETED}.
   */
  public record StatusBreakdown(int notStarted, int inProgress, int completed, int onHold) {}

  /** Sort axis for the returned roster. */
  public enum RankBy {
    ACTIVITY_COUNT,
    PLANNED_COST,
    ACTUAL_COST,
    CPI,
    SPI,
    AVG_PERCENT_COMPLETE
  }

  /**
   * Compute a roster for one project.
   *
   * @param projectId           project to scope activities/pool to. Must be non-null.
   * @param includeEligiblePool when true, appends LABOR-type project pool resources
   *                            that are not already assigned as a supervisor to any
   *                            activity. Rows flagged {@link SupervisorRow#isInPool()}.
   * @param rankBy              sort axis. Higher-is-better for ACTIVITY_COUNT, COSTS,
   *                            CPI, SPI, AVG_PERCENT_COMPLETE — all descend; nulls last.
   * @param limit               max rows to return (clamped 1..200).
   */
  public SupervisorRoster compute(
      UUID projectId, boolean includeEligiblePool, RankBy rankBy, int limit) {
    Objects.requireNonNull(projectId, "projectId");
    if (rankBy == null) rankBy = RankBy.ACTIVITY_COUNT;
    int clampedLimit = clamp(limit, 1, 200);

    Project project = projectRepository.findById(projectId).orElse(null);
    String projectCode = project != null ? project.getCode() : null;
    String projectName = project != null ? project.getName() : null;

    // 1) Pull all activities for the project and group by responsibleResourceId.
    List<Activity> activities = activityRepository.findByProjectId(projectId);
    Map<UUID, List<Activity>> bySupervisor = new LinkedHashMap<>();
    List<UUID> allActivityIds = new ArrayList<>();
    for (Activity a : activities) {
      if (a.getResponsibleResourceId() == null) continue;
      bySupervisor.computeIfAbsent(a.getResponsibleResourceId(), k -> new ArrayList<>()).add(a);
      allActivityIds.add(a.getId());
    }

    // 2) Batch-load all supervised activity assignments → group by activityId.
    Map<UUID, List<ResourceAssignment>> assignmentsByActivity = new HashMap<>();
    if (!allActivityIds.isEmpty()) {
      List<ResourceAssignment> assignments = assignmentRepository.findByActivityIdIn(allActivityIds);
      for (ResourceAssignment ra : assignments) {
        if (ra.getActivityId() == null) continue;
        assignmentsByActivity
            .computeIfAbsent(ra.getActivityId(), k -> new ArrayList<>())
            .add(ra);
      }
    }

    // 3) Batch-load Resource records for every supervisor in one round-trip.
    Set<UUID> supervisorIds = bySupervisor.keySet();
    Map<UUID, Resource> resourceById = new HashMap<>();
    if (!supervisorIds.isEmpty()) {
      resourceRepository.findAllById(supervisorIds)
          .forEach(r -> resourceById.put(r.getId(), r));
    }

    // 4) Build assigned rows.
    List<SupervisorRow> rows = new ArrayList<>();
    for (Map.Entry<UUID, List<Activity>> entry : bySupervisor.entrySet()) {
      UUID supId = entry.getKey();
      List<Activity> supActs = entry.getValue();
      Resource res = resourceById.get(supId);
      rows.add(buildAssignedRow(projectId, supId, res, supActs, assignmentsByActivity));
    }

    // 5) Optionally append eligible-pool rows (zero-activity).
    if (includeEligiblePool) {
      rows.addAll(buildPoolRows(projectId, supervisorIds));
    }

    int total = rows.size();

    // 6) Sort + clamp.
    rows.sort(comparatorFor(rankBy));
    if (rows.size() > clampedLimit) rows = new ArrayList<>(rows.subList(0, clampedLimit));

    return new SupervisorRoster(projectId, projectCode, projectName, total, rows);
  }

  // ---- row builders ----

  private SupervisorRow buildAssignedRow(
      UUID projectId,
      UUID supId,
      Resource res,
      List<Activity> supActs,
      Map<UUID, List<ResourceAssignment>> assignmentsByActivity) {
    StatusBreakdown status = countStatuses(supActs);
    BigDecimal avgPct = avgPercentComplete(supActs);
    String code = res != null ? res.getCode() : null;
    String name = res != null ? res.getName() : null;
    String roleName = roleNameOf(res);

    BigDecimal planned = BigDecimal.ZERO;
    BigDecimal actual = BigDecimal.ZERO;
    boolean anyAssignment = false;
    for (Activity a : supActs) {
      List<ResourceAssignment> ras = assignmentsByActivity.get(a.getId());
      if (ras == null) continue;
      for (ResourceAssignment ra : ras) {
        anyAssignment = true;
        planned = planned.add(nvl(ra.getPlannedCost()));
        actual = actual.add(nvl(ra.getActualCost()));
      }
    }
    BigDecimal plannedOut = anyAssignment ? planned : null;
    BigDecimal actualOut = anyAssignment ? actual : null;
    BigDecimal variancePct = costVariancePct(plannedOut, actualOut);

    EvmAggregate evm = aggregateEvm(projectId, supActs);

    return new SupervisorRow(
        supId,
        code,
        name,
        roleName,
        supActs.size(),
        status,
        avgPct,
        plannedOut,
        actualOut,
        variancePct,
        evm.cpi,
        evm.spi,
        false);
  }

  private List<SupervisorRow> buildPoolRows(UUID projectId, Set<UUID> assignedIds) {
    List<ProjectResource> projectPool = projectResourceRepository.findByProjectId(projectId);
    if (projectPool.isEmpty()) return List.of();

    List<UUID> poolResourceIds = new ArrayList<>();
    for (ProjectResource pr : projectPool) {
      if (pr.getResourceId() == null) continue;
      if (assignedIds.contains(pr.getResourceId())) continue;
      poolResourceIds.add(pr.getResourceId());
    }
    if (poolResourceIds.isEmpty()) return List.of();

    List<Resource> resources = resourceRepository.findAllById(poolResourceIds);
    List<SupervisorRow> rows = new ArrayList<>();
    StatusBreakdown zero = new StatusBreakdown(0, 0, 0, 0);
    for (Resource r : resources) {
      if (r.getResourceType() == null) continue;
      if (!LABOR_TYPE_CODE.equalsIgnoreCase(r.getResourceType().getCode())) continue;
      rows.add(new SupervisorRow(
          r.getId(),
          r.getCode(),
          r.getName(),
          roleNameOf(r),
          0,
          zero,
          null,
          null,
          null,
          null,
          null,
          null,
          true));
    }
    return rows;
  }

  // ---- helpers ----

  private record EvmAggregate(BigDecimal cpi, BigDecimal spi) {}

  /**
   * Latest EVM row per activity → simple mean of CPI / SPI across activities
   * that actually have an EVM calculation. Activities without EVM contribute
   * neither to the mean nor to the divisor. {@code null} when no activity in
   * the supervised set has an EVM row.
   */
  private EvmAggregate aggregateEvm(UUID projectId, List<Activity> activities) {
    if (activities.isEmpty()) return new EvmAggregate(null, null);
    BigDecimal cpiSum = BigDecimal.ZERO;
    BigDecimal spiSum = BigDecimal.ZERO;
    int cpiCount = 0;
    int spiCount = 0;
    for (Activity a : activities) {
      EvmCalculation calc = evmRepository
          .findTopByProjectIdAndActivityIdOrderByDataDateDesc(projectId, a.getId())
          .orElse(null);
      if (calc == null) continue;
      if (calc.getCostPerformanceIndex() != null) {
        cpiSum = cpiSum.add(BigDecimal.valueOf(calc.getCostPerformanceIndex()));
        cpiCount++;
      }
      if (calc.getSchedulePerformanceIndex() != null) {
        spiSum = spiSum.add(BigDecimal.valueOf(calc.getSchedulePerformanceIndex()));
        spiCount++;
      }
    }
    BigDecimal cpi =
        cpiCount == 0 ? null : cpiSum.divide(BigDecimal.valueOf(cpiCount), 4, RoundingMode.HALF_UP);
    BigDecimal spi =
        spiCount == 0 ? null : spiSum.divide(BigDecimal.valueOf(spiCount), 4, RoundingMode.HALF_UP);
    return new EvmAggregate(cpi, spi);
  }

  private static StatusBreakdown countStatuses(List<Activity> activities) {
    int ns = 0, ip = 0, cp = 0;
    for (Activity a : activities) {
      ActivityStatus s = a.getStatus();
      if (s == ActivityStatus.NOT_STARTED) ns++;
      else if (s == ActivityStatus.IN_PROGRESS) ip++;
      else if (s == ActivityStatus.COMPLETED) cp++;
    }
    return new StatusBreakdown(ns, ip, cp, 0);
  }

  private static BigDecimal avgPercentComplete(List<Activity> activities) {
    double sum = 0;
    int count = 0;
    for (Activity a : activities) {
      if (a.getPercentComplete() == null) continue;
      sum += a.getPercentComplete();
      count++;
    }
    if (count == 0) return null;
    return BigDecimal.valueOf(sum / count).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal costVariancePct(BigDecimal planned, BigDecimal actual) {
    if (planned == null || actual == null) return null;
    if (planned.signum() == 0) return null;
    return actual.subtract(planned)
        .multiply(HUNDRED)
        .divide(planned, 4, RoundingMode.HALF_UP);
  }

  private static String roleNameOf(Resource r) {
    if (r == null) return null;
    ResourceRole role = r.getRole();
    return role == null ? null : role.getName();
  }

  private static BigDecimal nvl(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /**
   * All ranks sort descending by metric, with nulls placed last so unranked
   * supervisors don't poison the leaderboard. Ties are broken by name then code
   * for stable output across calls.
   */
  private static Comparator<SupervisorRow> comparatorFor(RankBy rankBy) {
    Comparator<SupervisorRow> primary = switch (rankBy) {
      case PLANNED_COST -> Comparator.comparing(
          SupervisorRow::plannedCost, Comparator.nullsLast(Comparator.reverseOrder()));
      case ACTUAL_COST -> Comparator.comparing(
          SupervisorRow::actualCost, Comparator.nullsLast(Comparator.reverseOrder()));
      case CPI -> Comparator.comparing(
          SupervisorRow::cpi, Comparator.nullsLast(Comparator.reverseOrder()));
      case SPI -> Comparator.comparing(
          SupervisorRow::spi, Comparator.nullsLast(Comparator.reverseOrder()));
      case AVG_PERCENT_COMPLETE -> Comparator.comparing(
          SupervisorRow::avgPercentComplete, Comparator.nullsLast(Comparator.reverseOrder()));
      case ACTIVITY_COUNT -> Comparator.comparingInt(SupervisorRow::activityCount).reversed();
    };
    Comparator<SupervisorRow> tieBreak = Comparator
        .comparing(SupervisorRow::name, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(SupervisorRow::code, Comparator.nullsLast(Comparator.naturalOrder()));
    return primary.thenComparing(tieBreak);
  }
}
