package com.bipros.api.service;

import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.application.service.ActivityService;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.api.dto.ActivityProgressGenerationRequest;
import com.bipros.api.dto.ActivityProgressGenerationResponse;
import com.bipros.api.dto.ActivityProgressResult;
import com.bipros.api.service.progressgen.ActivityPlan;
import com.bipros.api.service.progressgen.BoqLinkResolver;
import com.bipros.api.service.progressgen.PlannedDpr;
import com.bipros.api.service.progressgen.ResourceRowBuilder;
import com.bipros.api.service.progressgen.ScheduleSpreader;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.DailyProgressReportResponse;
import com.bipros.project.application.dto.DprApprovalActionRequest;
import com.bipros.project.application.dto.DprSubContractorRow;
import com.bipros.project.application.service.DailyProgressReportService;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.Shift;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin orchestrator that backfills real progress onto 0%-complete activities by generating and
 * auto-approving DPRs — exactly the path the UI submit+approve takes. {@link #plan} is pure (no
 * writes): it selects targets, caps qty per BOQ item, and lays out per-day DPR slots. {@link
 * #generate} executes the plan via the genuine {@link DailyProgressReportService} so every
 * downstream cascade (BOQ qty, resource-plan actuals, DBS, capacity utilization, activity
 * %-complete) populates consistently.
 *
 * <p>No DPR/BOQ/DBS/activity business logic is changed here — existing services/repos only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityProgressGenerationService {

  private final ActivityRepository activityRepo;
  private final ResourceAssignmentRepository resourceAssignmentRepo;
  private final ActivitySupervisorRepository activitySupervisorRepo;
  private final ActivitySubContractorAssignmentRepository scAssignmentRepo;
  private final WorkActivityRepository workActivityRepo;
  private final BoqLinkResolver boqLinkResolver;
  private final ResourceRowBuilder resourceRowBuilder;
  private final ScheduleSpreader scheduleSpreader;
  private final Clock clock;
  private final ActivityService activityService;
  private final DailyProgressReportService dprService;

  // ─── Planning (pure, no writes) ──────────────────────────────────────────────────────

  public List<ActivityPlan> plan(UUID projectId, ActivityProgressGenerationRequest req) {
    LocalDate today = LocalDate.now(clock);
    List<Activity> all = req.getWbsNodeId() != null
        ? activityRepo.findByWbsNodeId(req.getWbsNodeId())
        : activityRepo.findByProjectId(projectId);
    List<Activity> targets = all.stream()
        .filter(a -> req.getActivityIds() == null || req.getActivityIds().contains(a.getId()))
        .filter(a -> a.getPercentComplete() == null || a.getPercentComplete() == 0.0)
        .toList();

    // Duplicate-name groups for the rename pre-pass.
    Map<String, Long> nameCounts = targets.stream()
        .collect(Collectors.groupingBy(a -> lc(a.getName()), Collectors.counting()));

    // Per-BOQ remaining budget so cumulative approved qty never exceeds boqQty.
    Map<UUID, BigDecimal> boqBudget = new HashMap<>();
    List<ActivityPlan> plans = new ArrayList<>();
    int idx = 0;
    for (Activity a : targets) {
      List<String> warnings = new ArrayList<>();
      List<ActivitySupervisor> sups = activitySupervisorRepo.findByActivityId(a.getId());
      if (sups.isEmpty()) {
        plans.add(emptyPlan(a, "SKIPPED_NO_SUPERVISOR"));
        continue;
      }
      var boq = boqLinkResolver.resolve(projectId, a.getId(), a.getWbsNodeId());
      if (boq == null) {
        plans.add(emptyPlan(a, "SKIPPED_NO_BOQ"));
        continue;
      }
      if (boq.fallback()) warnings.add("BOQ link is a fallback (no name match): " + boq.itemNo());

      int pct = percentForIndex(idx++, req.getTargetPercentMin(), req.getTargetPercentMax());
      BigDecimal budget = boqBudget.computeIfAbsent(boq.boqItemId(),
          k -> nz(boq.boqQty()).subtract(nz(boq.qtyExecutedToDate())).max(BigDecimal.ZERO));
      BigDecimal desired = nz(boq.boqQty())
          .multiply(BigDecimal.valueOf(pct))
          .divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
      BigDecimal qtyTotal = desired.min(budget);
      boqBudget.put(boq.boqItemId(), budget.subtract(qtyTotal));
      if (qtyTotal.signum() <= 0) {
        plans.add(emptyPlan(a, "SKIPPED_BOQ_FULL"));
        continue;
      }

      String unit = a.getWorkActivityId() == null ? "Cum"
          : workActivityRepo.findById(a.getWorkActivityId()).map(w -> w.getDefaultUnit()).filter(Objects::nonNull).orElse("Cum");

      List<ResourceAssignment> raw = req.isIncludeResources()
          ? resourceAssignmentRepo.findByActivityId(a.getId()) : List.of();
      var rows = resourceRowBuilder.build(raw, pct / 100.0, req.getWorkingHoursPerDay());

      // Sub-contractor rows are intentionally NOT auto-generated in v1. The DPR write enforces
      // "Σ SC qty <= that DPR's qtyExecuted" (SC_EXCEEDS_WORKDONE), but each generated DPR only
      // carries a per-slot fraction of qtyTotal, so safely fitting SC rows under the per-DPR cap
      // requires per-slot SC splitting that isn't worth the risk for a first cut. We surface a
      // warning so the operator knows SC actuals weren't touched; scAssignmentIds stays empty so
      // generate() skips the SC reconcile. (Sanctioned by the plan's Task 5 design note.)
      Set<UUID> scIds = new LinkedHashSet<>();
      List<DprSubContractorRow> scRows = List.of();
      if (req.isIncludeSubContractors()) {
        var scAssignments = scAssignmentRepo.findByProjectIdAndActivityId(projectId, a.getId());
        if (!scAssignments.isEmpty()) {
          warnings.add("Sub-contractor rows not auto-generated (v1): "
              + scAssignments.size() + " assignment(s) skipped.");
        }
      }

      List<LocalDate> dates =
          scheduleSpreader.spread(a.getPlannedStartDate(), today, req.getDatesPerActivity());
      List<PlannedDpr> dprs = splitAcrossSlots(sups, dates, qtyTotal, rows, scRows);

      boolean needsRename = req.isRenameDuplicates()
          && nameCounts.getOrDefault(lc(a.getName()), 0L) > 1
          && !a.getName().contains("— " + a.getCode());
      String newName = needsRename ? a.getName() + " — " + a.getCode() : null;
      boolean needsLock = req.isAutoLockDraft() && a.getEditStatus() == ActivityEditStatus.DRAFT;

      plans.add(new ActivityPlan(
          a.getId(), a.getCode(), a.getName(), unit,
          boq.boqItemId(), boq.itemNo(), boq.fallback(),
          sups.get(0).getUserId(), sups.get(0).getUserNameSnapshot(),
          pct, qtyTotal, needsRename, newName, needsLock, scIds, warnings, dprs));
    }
    return plans;
  }

  // ─── Execution (side-effecting) ──────────────────────────────────────────────────────

  /**
   * Generate + approve DPRs for the planned activities. {@link Propagation#NOT_SUPPORTED} so each
   * create+approve commits on its own transaction and its AFTER_COMMIT cascade (DBS, ledger,
   * activity %) fires per row — exactly like the UI. A failure on one activity never rolls back
   * the others.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ActivityProgressGenerationResponse generate(
      UUID projectId, ActivityProgressGenerationRequest req) {
    List<ActivityPlan> plans = plan(projectId, req);
    List<ActivityProgressResult> results = new ArrayList<>();
    int generated = 0;
    int skipped = 0;
    int dprs = 0;
    for (ActivityPlan p : plans) {
      if (p.dprs().isEmpty()) { // SKIPPED_* from planning
        String status = p.warnings().isEmpty() ? "SKIPPED" : p.warnings().get(0);
        results.add(result(p, status, List.of(), false, false));
        skipped++;
        continue;
      }
      if (req.isDryRun()) {
        results.add(result(p, "DRY_RUN", List.of(), p.needsRename(), p.needsLock()));
        continue;
      }
      List<String> warnings = new ArrayList<>(p.warnings());
      boolean renamed = false;
      boolean locked = false;
      try {
        if (p.needsRename()) {
          renameActivity(p);
          renamed = true;
        }
        if (p.needsLock()) {
          activityService.lockActivity(p.activityId());
          locked = true;
        }
        List<UUID> dprIds = new ArrayList<>();
        for (PlannedDpr d : p.dprs()) {
          UUID id = createAndApprove(projectId, p, d, warnings);
          if (id != null) {
            dprIds.add(id);
            dprs++;
          }
        }
        // Post-approval reconcile — closes the resource / SC actuals gaps deterministically.
        dprService.recomputeActivityResourceActuals(p.activityId());
        if (!p.scAssignmentIds().isEmpty()) {
          dprService.recomputeScActualsForAssignments(p.scAssignmentIds());
        }
        String status = dprIds.isEmpty() ? "SKIPPED_EXISTING" : "GENERATED";
        results.add(result(p, status, dprIds, renamed, locked, warnings));
        if (!dprIds.isEmpty()) {
          generated++;
        } else {
          skipped++;
        }
      } catch (RuntimeException e) {
        log.warn("progressgen failed for activity {}: {}", p.activityCode(), e.toString());
        warnings.add("FAILED: " + e.getMessage());
        results.add(result(p, "FAILED", List.of(), renamed, locked, warnings));
      }
    }
    return new ActivityProgressGenerationResponse(
        req.isDryRun(), plans.size(), generated, skipped, dprs, results);
  }

  private UUID createAndApprove(UUID projectId, ActivityPlan p, PlannedDpr d, List<String> warnings) {
    try {
      DailyProgressReportResponse created = dprService.create(projectId, buildDprRequest(p, d));
      List<String> dprWarnings = created.warnings();
      if (dprWarnings != null) warnings.addAll(dprWarnings);
      dprService.approve(projectId, created.id(),
          new DprApprovalActionRequest("Auto-generated progress backfill"));
      return created.id();
    } catch (BusinessRuleException e) {
      if ("DPR_ALREADY_EXISTS_FOR_ACTIVITY".equals(e.getRuleCode())) {
        return null; // idempotent skip — a DPR already exists for (project, date, activity, supervisor)
      }
      throw e;
    }
  }

  private CreateDailyProgressReportRequest buildDprRequest(ActivityPlan p, PlannedDpr d) {
    return new CreateDailyProgressReportRequest(
        d.reportDate(), d.supervisorUserId(), d.supervisorName(), null, null,
        p.activityId(), p.activityName(), null, p.boqItemId(), null, p.unit(), d.qtyExecuted(),
        null, "Auto-generated progress backfill", null, null, null, null,
        Shift.DAY, DprApprovalStatus.SUBMITTED, null, null, null, null,
        d.manpower(), d.equipment(), d.materials(), d.subContractors(), List.of());
  }

  private void renameActivity(ActivityPlan p) {
    // UpdateActivityRequest mutates only on non-null components; name set, everything else null.
    // 28 components total: name + 27 nulls (verified against the record's declaration order).
    UpdateActivityRequest request = new UpdateActivityRequest(
        p.newName(),                                              // name (1)
        null, null, null, null, null, null, null, null, null,     // (2-10)
        null, null, null, null, null, null, null, null, null,     // (11-19)
        null, null, null, null, null, null, null, null, null);    // (20-28)
    activityService.updateActivity(p.activityId(), request);
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────────────

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** Deterministic percent within [min,max] so generated progress varies but is reproducible. */
  private int percentForIndex(int i, int min, int max) {
    int span = max - min;
    if (span <= 0) return min;
    return min + (i % (span + 1));
  }

  private static String lc(String s) { return s == null ? "" : s.toLowerCase(); }

  private ActivityPlan emptyPlan(Activity a, String reasonWarning) {
    return new ActivityPlan(a.getId(), a.getCode(), a.getName(), "Cum", null, null, false,
        null, null, 0, BigDecimal.ZERO, false, null, false, Set.of(), List.of(reasonWarning),
        List.of());
  }

  /**
   * Split {@code qtyTotal} across the report dates, last slot carrying the rounding remainder, and
   * attach the full scaled resource set to the FIRST emitted DPR only (the resource-plan rollup
   * sums all DPR rows for the activity, so this keeps actuals == scaled plan with no per-slot
   * rounding drift). Zero/negative-qty slots are never emitted (DPR qtyExecuted is @Positive).
   */
  private List<PlannedDpr> splitAcrossSlots(List<ActivitySupervisor> sups, List<LocalDate> dates,
      BigDecimal qtyTotal, ResourceRowBuilder.Rows rows, List<DprSubContractorRow> scRows) {
    int slots = dates.size();
    BigDecimal per = qtyTotal.divide(BigDecimal.valueOf(slots), 3, RoundingMode.DOWN);
    List<PlannedDpr> out = new ArrayList<>();
    BigDecimal acc = BigDecimal.ZERO;
    boolean resourcesAttached = false;
    for (int i = 0; i < slots; i++) {
      BigDecimal qty = (i == slots - 1) ? qtyTotal.subtract(acc) : per;
      acc = acc.add(qty);
      if (qty.signum() <= 0) continue;
      ActivitySupervisor sup = sups.get(i % sups.size());
      boolean first = !resourcesAttached;
      resourcesAttached = true;
      out.add(new PlannedDpr(dates.get(i), sup.getUserId(), sup.getUserNameSnapshot(), qty,
          first ? rows.manpower() : List.of(),
          first ? rows.equipment() : List.of(),
          first ? rows.materials() : List.of(),
          first ? scRows : List.of()));
    }
    return out;
  }

  private ActivityProgressResult result(ActivityPlan p, String status, List<UUID> dprIds,
      boolean renamed, boolean locked) {
    return result(p, status, dprIds, renamed, locked, p.warnings());
  }

  private ActivityProgressResult result(ActivityPlan p, String status, List<UUID> dprIds,
      boolean renamed, boolean locked, List<String> warnings) {
    List<LocalDate> datesUsed = p.dprs().stream().map(PlannedDpr::reportDate).toList();
    Integer targetPercent = p.targetPercent() == 0 ? null : p.targetPercent();
    return new ActivityProgressResult(
        p.activityId(), p.activityCode(), p.activityName(), status,
        p.supervisorUserId(), p.supervisorName(),
        p.boqItemId(), p.boqItemNo(), p.boqFallback(),
        targetPercent, p.qtyTotal(),
        datesUsed, dprIds, renamed, locked, warnings);
  }
}
