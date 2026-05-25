package com.bipros.reporting.application.service;

import com.bipros.reporting.application.dto.CapacityUtilizationAggregateReport;
import com.bipros.reporting.application.dto.CapacityUtilizationAggregateReport.Bucket;
import com.bipros.reporting.application.dto.CapacityUtilizationReport;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.RolePeriod;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.RoleRow;
import com.bipros.reporting.application.dto.CapacityUtilizationReport.Section;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SC180-style Capacity Utilization. Reads directly from {@code project.dpr_manpower} and
 * {@code project.dpr_equipment} (skipping the legacy {@code daily_activity_resource_outputs}
 * ledger which doesn't get populated for role-only DPRs). Groups by Role within Manpower /
 * Equipment sections; produces three time buckets per row (Day · Month · Cumulative).
 *
 * <p>For each {@code (role, activity, period)} we compute role-days from {@code nos × hours / norm_hours}
 * and use the activity's productivity norm (resolved through the role chain) to derive budget
 * days from the activity's executed quantity. Per-role rollups sum across all activities.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapacityUtilizationReportService {

  private static final double DEFAULT_HOURS_PER_DAY = 8.0;
  private static final BigDecimal DEFAULT_HOURS_PER_DAY_BD = BigDecimal.valueOf(DEFAULT_HOURS_PER_DAY);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  @PersistenceContext private EntityManager em;

  @Transactional(readOnly = true)
  public CapacityUtilizationReport build(
      UUID projectId, LocalDate fromDate, LocalDate toDate, String groupBy, String normType) {
    return build(projectId, fromDate, toDate, groupBy, normType, null, defaultWorkDays());
  }

  /**
   * Supervisor-aware overload. When {@code supervisorUserId} is non-null, only DPRs filed by
   * that user are counted.
   *
   * <p>RBAC Phase 4.4 — pivots off {@code daily_progress_reports.supervisor_user_id}; the
   * legacy {@code supervisor_resource_id} column was dropped by migration 091, so the dual-id
   * source-agnostic path from feat/capacity-utilization has been collapsed to user-only here.
   */
  @Transactional(readOnly = true)
  public CapacityUtilizationReport build(
      UUID projectId, LocalDate fromDate, LocalDate toDate,
      String groupBy, String normType, UUID supervisorUserId) {
    return build(projectId, fromDate, toDate, groupBy, normType, supervisorUserId, defaultWorkDays());
  }

  /** Workdays-aware overload (controls Nos derivation in the SC180 sections). */
  @Transactional(readOnly = true)
  public CapacityUtilizationReport build(
      UUID projectId, LocalDate fromDate, LocalDate toDate,
      String groupBy, String normType,
      UUID supervisorUserId, int workDays) {

    LocalDate today = LocalDate.now();
    LocalDate effectiveTo = toDate == null ? today : toDate;
    LocalDate effectiveFrom = fromDate == null ? effectiveTo.withDayOfYear(1) : fromDate;
    // Day/Month buckets anchor on today when today falls inside the window; otherwise the last
    // day of the window. Cumulative covers the full [from, to] range.
    LocalDate referenceDate = effectiveTo.isBefore(today) ? effectiveTo : today;
    YearMonth referenceMonth = YearMonth.from(referenceDate);
    int effectiveWorkDays = workDays > 0 ? workDays : 26;
    String requestedNormType = normType == null ? null : normType.toUpperCase();

    boolean wantManpower = requestedNormType == null || "MANPOWER".equals(requestedNormType);
    boolean wantEquipment = requestedNormType == null || "EQUIPMENT".equals(requestedNormType);

    Section manpowerSection = wantManpower
        ? buildSection(projectId, "MANPOWER", effectiveFrom, effectiveTo,
            referenceDate, referenceMonth, supervisorUserId, effectiveWorkDays)
        : null;
    Section equipmentSection = wantEquipment
        ? buildSection(projectId, "EQUIPMENT", effectiveFrom, effectiveTo,
            referenceDate, referenceMonth, supervisorUserId, effectiveWorkDays)
        : null;

    // Synthesise the legacy flat-row + groupBy/normType fields so existing consumers
    // (Excel writer, Insights collector) keep working until they're migrated.
    String resolvedGroupBy = groupBy == null ? "ROLE" : groupBy.toUpperCase();
    List<CapacityUtilizationReport.Row> legacyRows =
        synthesiseLegacyRows(manpowerSection, equipmentSection);
    return new CapacityUtilizationReport(
        projectId, effectiveFrom, effectiveTo, effectiveWorkDays,
        manpowerSection, equipmentSection,
        resolvedGroupBy, requestedNormType, legacyRows);
  }

  /**
   * Multi-period aggregate. Slices the {@code [fromDate, toDate]} window into weekly or monthly
   * buckets and, for each bucket, returns the Manpower / Equipment {@link Section} computed
   * over just that bucket's window. The frontend renders each bucket as a column with the
   * per-role rows beneath. Reuses {@link #buildSection} per bucket so accumulator logic stays
   * single-sourced — the Day/Month/Cumulative triple internally collapses to the bucket window
   * (we only surface the {@code cumulative} field per RoleRow downstream).
   */
  @Transactional(readOnly = true)
  public CapacityUtilizationAggregateReport aggregate(
      UUID projectId, String periodType, LocalDate fromDate, LocalDate toDate,
      String groupBy) {
    LocalDate today = LocalDate.now();
    LocalDate effectiveTo = toDate == null ? today : toDate;
    LocalDate effectiveFrom = fromDate == null ? effectiveTo.withDayOfYear(1) : fromDate;
    String pt = periodType == null ? "MONTHLY" : periodType.toUpperCase();
    if (!pt.equals("WEEKLY") && !pt.equals("MONTHLY")) pt = "MONTHLY";
    String gb = groupBy == null ? "ROLE" : groupBy.toUpperCase();

    List<Bucket> buckets = new ArrayList<>();
    LocalDate cursor = effectiveFrom;
    while (!cursor.isAfter(effectiveTo)) {
      LocalDate bucketEnd;
      String label;
      if ("WEEKLY".equals(pt)) {
        // ISO weeks: start anchored to Monday of the week the cursor falls in.
        java.time.temporal.WeekFields wf = java.time.temporal.WeekFields.ISO;
        int weekOfYear = cursor.get(wf.weekOfWeekBasedYear());
        int weekYear = cursor.get(wf.weekBasedYear());
        bucketEnd = cursor.with(java.time.DayOfWeek.SUNDAY);
        if (bucketEnd.isAfter(effectiveTo)) bucketEnd = effectiveTo;
        label = String.format("%d-W%02d", weekYear, weekOfYear);
      } else {
        YearMonth ym = YearMonth.from(cursor);
        bucketEnd = ym.atEndOfMonth();
        if (bucketEnd.isAfter(effectiveTo)) bucketEnd = effectiveTo;
        label = ym.toString();
      }

      LocalDate bucketStart = cursor;
      Section manpowerSection = buildSection(projectId, "MANPOWER",
          bucketStart, bucketEnd,
          bucketEnd, YearMonth.from(bucketEnd),
          null, defaultWorkDays());
      Section equipmentSection = buildSection(projectId, "EQUIPMENT",
          bucketStart, bucketEnd,
          bucketEnd, YearMonth.from(bucketEnd),
          null, defaultWorkDays());

      // Per bucket we only want the cumulative-over-bucket view — strip the Day/Month
      // fields so the wire payload is tight and the frontend doesn't accidentally render
      // mid-bucket reference-date data.
      buckets.add(new Bucket(bucketStart, bucketEnd, label,
          stripToCumulative(manpowerSection),
          stripToCumulative(equipmentSection)));

      cursor = bucketEnd.plusDays(1);
    }
    return new CapacityUtilizationAggregateReport(
        projectId, pt, gb, effectiveFrom, effectiveTo, buckets);
  }

  private Section stripToCumulative(Section src) {
    if (src == null) return null;
    List<RoleRow> stripped = new ArrayList<>(src.rows().size());
    for (RoleRow r : src.rows()) {
      stripped.add(new RoleRow(
          r.roleId(), r.roleCode(), r.roleName(), r.ratePerDay(),
          null, null, r.cumulative(), r.normSource()));
    }
    return new Section(stripped, null, null, src.totalCumulative());
  }

  /**
   * Project the new section/role shape back into the legacy flat-row shape so existing
   * downstream consumers (Excel writer, Insights collector) can keep rendering until they're
   * migrated. One legacy row per (role, period); group key carries the role id.
   */
  private List<CapacityUtilizationReport.Row> synthesiseLegacyRows(
      Section manpower, Section equipment) {
    List<CapacityUtilizationReport.Row> out = new ArrayList<>();
    if (manpower != null) {
      for (RoleRow rr : manpower.rows()) {
        out.add(toLegacyRow(rr));
      }
    }
    if (equipment != null) {
      for (RoleRow rr : equipment.rows()) {
        out.add(toLegacyRow(rr));
      }
    }
    return out;
  }

  private CapacityUtilizationReport.Row toLegacyRow(RoleRow rr) {
    CapacityUtilizationReport.GroupKey gk = new CapacityUtilizationReport.GroupKey(
        null, rr.roleId(),
        rr.roleCode() != null ? rr.roleCode() + " — " + (rr.roleName() == null ? "" : rr.roleName())
            : (rr.roleName() == null ? "(role)" : rr.roleName()));
    CapacityUtilizationReport.Budgeted bd = new CapacityUtilizationReport.Budgeted(null, rr.normSource());
    return new CapacityUtilizationReport.Row(
        gk, null, bd,
        toLegacyPeriod(rr.forTheDay()),
        toLegacyPeriod(rr.forTheMonth()),
        toLegacyPeriod(rr.cumulative()));
  }

  private CapacityUtilizationReport.Period toLegacyPeriod(RolePeriod p) {
    if (p == null) return null;
    return new CapacityUtilizationReport.Period(
        p.qty(), p.budgetDays(), p.actualDays(), null, p.utilizationPct(),
        p.actualDaysUntracked());
  }

  // ─── Per-section build ─────────────────────────────────────────────────────────────────────

  private Section buildSection(
      UUID projectId, String normType,
      LocalDate fromDate, LocalDate toDate,
      LocalDate referenceDate, YearMonth referenceMonth,
      UUID supervisorUserId,
      int workDays) {
    List<Contribution> contributions = "MANPOWER".equals(normType)
        ? loadManpowerContributions(projectId, fromDate, toDate, supervisorUserId)
        : loadEquipmentContributions(projectId, fromDate, toDate, supervisorUserId);

    // Pass 1 — group contributions by (DPR, activity). Within each group, build the list of
    // (role, NOS, resolvedNorm) on THIS side. Same DPR can have multiple rows for one role; sum
    // NOS first so allocation sees one entry per role.
    record GroupKey(UUID dprId, UUID activityId) {}
    record NosNormPair(int nos, BigDecimal norm) {}
    Map<GroupKey, Map<UUID, NosNormPair>> bySideGroup = new LinkedHashMap<>();
    Map<GroupKey, List<Contribution>> contribsByGroup = new LinkedHashMap<>();
    Map<UUID, String> nameByActivity = new HashMap<>();
    Map<WorkActivityRoleKey, NormLookup> normCache = new HashMap<>();

    for (Contribution c : contributions) {
      if (c.dprId == null || c.activityId == null) continue;
      GroupKey gk = new GroupKey(c.dprId, c.activityId);
      contribsByGroup.computeIfAbsent(gk, k -> new ArrayList<>()).add(c);
      nameByActivity.putIfAbsent(c.activityId, c.workActivityName);

      NormLookup nl = normCache.computeIfAbsent(
          new WorkActivityRoleKey(c.workActivityId, c.roleId),
          k -> resolveNorm(k.workActivityId, k.roleId, normType));
      Map<UUID, NosNormPair> roleMap = bySideGroup.computeIfAbsent(gk, k -> new LinkedHashMap<>());
      int rowNos = c.roleDays == null ? 0 : c.roleDays.intValue();
      NosNormPair existing = roleMap.get(c.roleId);
      int totalNos = (existing == null ? 0 : existing.nos()) + rowNos;
      roleMap.put(c.roleId, new NosNormPair(totalNos, nl.outputPerDay));
    }

    // Other-side expected per (DPR, activity) so the allocator can decide hiding.
    Map<DprActivityKey, BigDecimal> otherSideExpected = loadOtherSideExpectedPerDpr(
        projectId, fromDate, toDate, normType, supervisorUserId);

    // Sub-contractor qty per DPR — manpower + equipment did NOT do this portion, it was the
    // sub-contractor. Subtracted from qty_executed to give the effective company-resource
    // workdone that the allocator distributes. Clamped to ≥ 0 (a data-entry where the sub
    // qty exceeds the DPR qty yields effective = 0; roles get budget 0 → Eff 0%).
    Map<UUID, BigDecimal> subContractorQtyByDpr = loadSubContractorQtyByDpr(
        projectId, fromDate, toDate, supervisorUserId);

    // Hidden-side notes accumulated for this section.
    List<CapacityUtilizationReport.HiddenSideNote> hiddenNotes = new ArrayList<>();
    Map<UUID, RoleAccumulator> byRole = new LinkedHashMap<>();

    // Pass 2 — for each (DPR, activity) group, call the allocator and credit each role.
    for (var entry : bySideGroup.entrySet()) {
      GroupKey gk = entry.getKey();
      Map<UUID, NosNormPair> roleMap = entry.getValue();
      List<Contribution> contribs = contribsByGroup.get(gk);
      Contribution first = contribs.get(0);

      List<CapacityAllocator.RoleInput> inputs = new ArrayList<>(roleMap.size());
      BigDecimal sideExpected = BigDecimal.ZERO;
      for (var rm : roleMap.entrySet()) {
        NosNormPair p = rm.getValue();
        inputs.add(new CapacityAllocator.RoleInput(rm.getKey(), p.nos(), p.norm()));
        if (p.norm() != null && p.norm().signum() > 0 && p.nos() > 0) {
          sideExpected = sideExpected.add(p.norm().multiply(BigDecimal.valueOf(p.nos())));
        }
      }
      BigDecimal otherExp = otherSideExpected.getOrDefault(
          new DprActivityKey(gk.dprId(), gk.activityId()), BigDecimal.ZERO);
      String combo = loadNormCombinationForActivity(gk.activityId());
      BigDecimal dprQty = first.qtyExecuted == null ? BigDecimal.ZERO : first.qtyExecuted;
      BigDecimal subQty = subContractorQtyByDpr.getOrDefault(gk.dprId(), BigDecimal.ZERO);
      BigDecimal qtyDone = dprQty.subtract(subQty);
      if (qtyDone.signum() < 0) qtyDone = BigDecimal.ZERO;

      CapacityAllocator.AllocationResult alloc = CapacityAllocator.allocate(
          sideExpected, otherExp, qtyDone, combo, inputs);

      if (alloc.hidden()) {
        // Record one banner note per (activity) per section.
        String governingSide = "MANPOWER".equals(normType) ? "EQUIPMENT" : "MANPOWER";
        boolean alreadyNoted = hiddenNotes.stream()
            .anyMatch(n -> n.activityId().equals(gk.activityId()));
        if (!alreadyNoted) {
          hiddenNotes.add(new CapacityUtilizationReport.HiddenSideNote(
              gk.activityId(),
              nameByActivity.get(gk.activityId()),
              governingSide,
              combo == null ? "SERIES" : combo.toUpperCase()));
        }
        // Hidden side still credits actual NOS (so headcount totals stay accurate) but no qty/budget.
        // Dispatch per role: if the role's norm DID resolve on this side it was hidden by
        // competition (creditActualHidden); otherwise it's truly untracked (creditActualOnly).
        Map<UUID, Boolean> roleNormResolved = new HashMap<>();
        for (CapacityAllocator.RoleAlloc ra : alloc.roleAllocations()) {
          roleNormResolved.put(ra.roleId(), ra.normResolved());
        }
        for (Contribution c : contribs) {
          RoleAccumulator acc = byRole.computeIfAbsent(
              c.accKey(),
              k -> new RoleAccumulator(c.roleId, c.roleCode, c.roleName));
          boolean roleHasNorm = Boolean.TRUE.equals(roleNormResolved.get(c.roleId));
          if (roleHasNorm) {
            creditActualHidden(acc, c, referenceDate, referenceMonth);
          } else {
            creditActualOnly(acc, c, referenceDate, referenceMonth);
          }
        }
        continue;
      }

      // Visible side: credit allocated qty + actual NOS per role.
      Map<UUID, BigDecimal> allocByRole = new HashMap<>();
      for (CapacityAllocator.RoleAlloc ra : alloc.roleAllocations()) {
        allocByRole.put(ra.roleId(), ra.allocatedQty());
      }
      for (Contribution c : contribs) {
        RoleAccumulator acc = byRole.computeIfAbsent(
            c.accKey(),
            k -> new RoleAccumulator(c.roleId, c.roleCode, c.roleName));
        BigDecimal allocated = allocByRole.get(c.roleId);
        creditActualAndAlloc(acc, c, allocated, qtyDone, referenceDate, referenceMonth);
      }
    }

    // Rate per role: weighted by the variants actually deployed via DPRs in the window
    // (NOT the AVG across every variant of the role). This is the same source the bottom
    // SC180-classic table uses, so Rate / Day, MM Rate, Eq Rate / Day, and the resulting
    // Cost Implication all agree across every section of the report.
    Map<UUID, BigDecimal> roleRateCache = loadRoleRates(
        byRole.keySet(), normType, projectId, fromDate, toDate);
    // Per-bucket planned headcount: sum plannedUnits across activities whose plannedStart..
    // plannedFinish range intersects each bucket's window. Day = referenceDate (single day),
    // Month = calendar month of referenceDate, Cum = [fromDate, toDate]. Activities without
    // planned dates fall through to all buckets.
    Map<UUID, BucketedPlanned> rolePlannedUnitsCache = loadPlannedHeadcountByBucket(
        byRole.keySet(), projectId,
        referenceDate,
        referenceMonth.atDay(1), referenceMonth.atEndOfMonth(),
        fromDate, toDate);

    List<RoleRow> rows = new ArrayList<>(byRole.size());
    for (RoleAccumulator role : byRole.values()) {
      // Walk each (work_activity, role) pair. Norm resolution splits each pair into either
      // "tracked" (norm resolved AND output > 0) or "untracked" (allocator never credited qty —
      // the role's days on that activity don't drive a comparable budget). Util% only counts the
      // tracked side so a role on a mix of tracked + untracked activities isn't unfairly
      // penalised by activities that simply don't measure its productivity.
      BigDecimal dayBudgetDays = BigDecimal.ZERO;
      BigDecimal monthBudgetDays = BigDecimal.ZERO;
      BigDecimal cumBudgetDays = BigDecimal.ZERO;
      BigDecimal dayActualUntracked = BigDecimal.ZERO;
      BigDecimal monthActualUntracked = BigDecimal.ZERO;
      BigDecimal cumActualUntracked = BigDecimal.ZERO;
      BigDecimal dayActualHidden = BigDecimal.ZERO;
      BigDecimal monthActualHidden = BigDecimal.ZERO;
      BigDecimal cumActualHidden = BigDecimal.ZERO;
      boolean anyNormResolved = false;
      java.util.Set<String> normSources = new java.util.HashSet<>();
      for (ActivityRoleAccumulator ara : role.activityRoles.values()) {
        NormLookup nl = normCache.computeIfAbsent(
            new WorkActivityRoleKey(ara.workActivityId, ara.roleId),
            k -> resolveNorm(k.workActivityId, k.roleId, normType));
        boolean tracked = ara.normResolved
            && nl.outputPerDay != null && nl.outputPerDay.signum() > 0;
        if (tracked) {
          anyNormResolved = true;
          if (ara.dayQty.signum() > 0) {
            dayBudgetDays = dayBudgetDays.add(
                ara.dayQty.divide(nl.outputPerDay, 4, RoundingMode.HALF_UP));
          }
          if (ara.monthQty.signum() > 0) {
            monthBudgetDays = monthBudgetDays.add(
                ara.monthQty.divide(nl.outputPerDay, 4, RoundingMode.HALF_UP));
          }
          if (ara.cumQty.signum() > 0) {
            cumBudgetDays = cumBudgetDays.add(
                ara.cumQty.divide(nl.outputPerDay, 4, RoundingMode.HALF_UP));
          }
          if (nl.source != null) normSources.add(nl.source);
        } else {
          // This activity's days for this role contribute to total Actual but NOT to util/cost.
          dayActualUntracked = dayActualUntracked.add(ara.dayActualDays);
          monthActualUntracked = monthActualUntracked.add(ara.monthActualDays);
          cumActualUntracked = cumActualUntracked.add(ara.cumActualDays);
        }
        // Hidden-side days are a separate dimension — accumulated regardless of the
        // tracked/untracked branch so the frontend can show a distinct "suppressed by other
        // side" note and util/cost exclude these days from the tracked denominator.
        dayActualHidden = dayActualHidden.add(ara.dayActualHidden);
        monthActualHidden = monthActualHidden.add(ara.monthActualHidden);
        cumActualHidden = cumActualHidden.add(ara.cumActualHidden);
      }

      BigDecimal ratePerDay = roleRateCache.get(role.roleId);
      // Planned headcount per bucket — only activities whose planned date range intersects the
      // bucket's window contribute. So a role planned on activities scheduled for July won't
      // show up in May's Day/Month/Cum Planned. Frontend labels these "X nos".
      BucketedPlanned planned = rolePlannedUnitsCache.getOrDefault(
          role.roleId, BucketedPlanned.empty());
      BigDecimal plannedDaysDay = nullIfZero(planned.day());
      BigDecimal plannedDaysMonth = nullIfZero(planned.month());
      BigDecimal plannedDaysCum = nullIfZero(planned.cum());

      String normSourceLabel = normSources.size() == 1
          ? normSources.iterator().next()
          : (normSources.isEmpty() ? "NONE" : "MIXED");

      // Pass nulls when no norm matched — that keeps %Util + Cost columns as "—" downstream.
      BigDecimal dayBudget = anyNormResolved ? dayBudgetDays : null;
      BigDecimal monthBudget = anyNormResolved ? monthBudgetDays : null;
      BigDecimal cumBudget = anyNormResolved ? cumBudgetDays : null;
      // Cost = (actual − budget) × rate. With no budget, cost is undefined (not zero) —
      // pass null rate so buildPeriod won't fabricate an "overrun" of actual × rate.
      BigDecimal effectiveRate = anyNormResolved ? ratePerDay : null;

      rows.add(new RoleRow(
          role.roleId, role.roleCode, role.roleName, ratePerDay,
          buildPeriod(role.dayActualDays, dayBudget, role.dayQty(), plannedDaysDay,
              effectiveRate, workDays, dayActualUntracked, dayActualHidden, anyNormResolved),
          buildPeriod(role.monthActualDays, monthBudget, role.monthQty(), plannedDaysMonth,
              effectiveRate, workDays, monthActualUntracked, monthActualHidden, anyNormResolved),
          buildPeriod(role.cumActualDays, cumBudget, role.cumQty(), plannedDaysCum,
              effectiveRate, workDays, cumActualUntracked, cumActualHidden, anyNormResolved),
          normSourceLabel));
    }

    // Section totals — straight column sums across the rows.
    RolePeriod totalDay = sumPeriod(rows, RoleRow::forTheDay);
    RolePeriod totalMonth = sumPeriod(rows, RoleRow::forTheMonth);
    RolePeriod totalCum = sumPeriod(rows, RoleRow::cumulative);
    return new Section(rows, totalDay, totalMonth, totalCum, hiddenNotes);
  }

  /**
   * Credit the role's accumulator with actual NOS only — used when the allocator hid this
   * side for the (DPR, activity). The role still appears on the report so headcount totals
   * reflect everyone who was on site; just no qty/budget/efficiency.
   */
  private void creditActualOnly(
      RoleAccumulator acc, Contribution c, LocalDate referenceDate, YearMonth referenceMonth) {
    WorkActivityRoleKey waRoleKey = new WorkActivityRoleKey(c.workActivityId, c.roleId);
    ActivityRoleAccumulator ara = acc.activityRoles.computeIfAbsent(waRoleKey,
        k -> new ActivityRoleAccumulator(c.workActivityId, c.workActivityName,
            c.workActivityDefaultUnit, c.roleId));
    ara.cumActualDays = ara.cumActualDays.add(c.roleDays);
    acc.cumActualDays = acc.cumActualDays.add(c.roleDays);
    boolean isInMonth = YearMonth.from(c.reportDate).equals(referenceMonth);
    boolean isInDay = c.reportDate.equals(referenceDate);
    if (isInMonth) {
      ara.monthActualDays = ara.monthActualDays.add(c.roleDays);
      acc.monthActualDays = acc.monthActualDays.add(c.roleDays);
    }
    if (isInDay) {
      ara.dayActualDays = ara.dayActualDays.add(c.roleDays);
      acc.dayActualDays = acc.dayActualDays.add(c.roleDays);
    }
  }

  /**
   * Credit the role's accumulator for a hidden side WHERE THE ROLE'S NORM DID RESOLVE — the
   * activity tracks productivity for this role, but the allocator suppressed this side because
   * the other side governed under SERIES / SUBSTITUTE. Sets {@code normResolved=true} on the
   * ARA so the frontend doesn't show a "no norm" footer; records the days in the hidden bucket
   * so a distinct "suppressed by other side" note can be rendered separately from the
   * "no productivity norm" footnote.
   */
  private void creditActualHidden(
      RoleAccumulator acc, Contribution c, LocalDate referenceDate, YearMonth referenceMonth) {
    WorkActivityRoleKey waRoleKey = new WorkActivityRoleKey(c.workActivityId, c.roleId);
    ActivityRoleAccumulator ara = acc.activityRoles.computeIfAbsent(waRoleKey,
        k -> new ActivityRoleAccumulator(c.workActivityId, c.workActivityName,
            c.workActivityDefaultUnit, c.roleId));
    ara.cumActualDays = ara.cumActualDays.add(c.roleDays);
    ara.cumActualHidden = ara.cumActualHidden.add(c.roleDays);
    acc.cumActualDays = acc.cumActualDays.add(c.roleDays);
    boolean isInMonth = YearMonth.from(c.reportDate).equals(referenceMonth);
    boolean isInDay = c.reportDate.equals(referenceDate);
    if (isInMonth) {
      ara.monthActualDays = ara.monthActualDays.add(c.roleDays);
      ara.monthActualHidden = ara.monthActualHidden.add(c.roleDays);
      acc.monthActualDays = acc.monthActualDays.add(c.roleDays);
    }
    if (isInDay) {
      ara.dayActualDays = ara.dayActualDays.add(c.roleDays);
      ara.dayActualHidden = ara.dayActualHidden.add(c.roleDays);
      acc.dayActualDays = acc.dayActualDays.add(c.roleDays);
    }
    ara.normResolved = true;
  }

  /**
   * Credit the role's accumulator with allocated qty (once per DPR-role) AND actual NOS.
   * {@code allocated} is null when the role's norm didn't resolve — in that case we still
   * record the full DPR qty as informational so the frontend can show it on the untracked row.
   */
  private void creditActualAndAlloc(
      RoleAccumulator acc, Contribution c,
      BigDecimal allocated, BigDecimal dprQty,
      LocalDate referenceDate, YearMonth referenceMonth) {
    WorkActivityRoleKey waRoleKey = new WorkActivityRoleKey(c.workActivityId, c.roleId);
    ActivityRoleAccumulator ara = acc.activityRoles.computeIfAbsent(waRoleKey,
        k -> new ActivityRoleAccumulator(c.workActivityId, c.workActivityName,
            c.workActivityDefaultUnit, c.roleId));
    ara.cumActualDays = ara.cumActualDays.add(c.roleDays);
    acc.cumActualDays = acc.cumActualDays.add(c.roleDays);
    boolean isInMonth = YearMonth.from(c.reportDate).equals(referenceMonth);
    boolean isInDay = c.reportDate.equals(referenceDate);
    if (isInMonth) {
      ara.monthActualDays = ara.monthActualDays.add(c.roleDays);
      acc.monthActualDays = acc.monthActualDays.add(c.roleDays);
    }
    if (isInDay) {
      ara.dayActualDays = ara.dayActualDays.add(c.roleDays);
      acc.dayActualDays = acc.dayActualDays.add(c.roleDays);
    }
    BigDecimal qtyToCredit = allocated != null ? allocated : (dprQty == null ? BigDecimal.ZERO : dprQty);
    if (allocated != null) ara.normResolved = true;
    ara.cumQty = ara.cumQty.add(qtyToCredit);
    if (isInMonth) ara.monthQty = ara.monthQty.add(qtyToCredit);
    if (isInDay) ara.dayQty = ara.dayQty.add(qtyToCredit);
  }

  private RolePeriod buildPeriod(
      BigDecimal actualDays, BigDecimal budgetDays, BigDecimal qty,
      BigDecimal plannedDays, BigDecimal ratePerDay, int workDays,
      BigDecimal actualDaysUntracked,
      BigDecimal actualDaysOnHiddenSides,
      boolean normResolved) {
    boolean hasAny = (actualDays != null && actualDays.signum() > 0)
        || (budgetDays != null && budgetDays.signum() > 0)
        || (qty != null && qty.signum() > 0);
    if (!hasAny) return RolePeriod.empty();

    BigDecimal untracked = actualDaysUntracked == null ? BigDecimal.ZERO : actualDaysUntracked;
    BigDecimal hidden = actualDaysOnHiddenSides == null ? BigDecimal.ZERO : actualDaysOnHiddenSides;
    // Tracked actual: only days that contributed to a budget calculation. Hidden + untracked
    // days are subtracted so the efficiency % isn't artificially dragged down by either.
    BigDecimal trackedActual = (actualDays == null)
        ? null : actualDays.subtract(untracked).subtract(hidden);

    BigDecimal actualNos = workDays > 0 && actualDays != null
        ? actualDays.divide(BigDecimal.valueOf(workDays), 4, RoundingMode.HALF_UP) : null;
    BigDecimal budgetNos = workDays > 0 && budgetDays != null
        ? budgetDays.divide(BigDecimal.valueOf(workDays), 4, RoundingMode.HALF_UP) : null;
    BigDecimal plannedNos = workDays > 0 && plannedDays != null
        ? plannedDays.divide(BigDecimal.valueOf(workDays), 4, RoundingMode.HALF_UP) : null;
    BigDecimal utilPct = null;
    if (budgetDays != null && trackedActual != null && trackedActual.signum() > 0) {
      utilPct = budgetDays.divide(trackedActual, 4, RoundingMode.HALF_UP).multiply(HUNDRED);
    }
    BigDecimal costImpl = null;
    if (ratePerDay != null && trackedActual != null && budgetDays != null) {
      costImpl = trackedActual.subtract(budgetDays).multiply(ratePerDay)
          .setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal untrackedOut = untracked.signum() > 0 ? untracked : null;
    BigDecimal hiddenOut = hidden.signum() > 0 ? hidden : null;
    // Suppress "0.0" budget rendering when no tracked qty was actually credited — null reads
    // as "—" on the frontend, matching the "this side was suppressed" semantic.
    BigDecimal budgetOut = (budgetDays != null && budgetDays.signum() > 0) ? budgetDays : null;
    BigDecimal budgetNosOut = budgetOut == null ? null : budgetNos;
    return new RolePeriod(qty, budgetOut, budgetNosOut, plannedDays, plannedNos,
        actualDays, actualNos, untrackedOut, hiddenOut, utilPct, costImpl,
        normResolved, null, null);
  }

  private RolePeriod sumPeriod(List<RoleRow> rows, java.util.function.Function<RoleRow, RolePeriod> pick) {
    BigDecimal qty = BigDecimal.ZERO, bd = BigDecimal.ZERO, ad = BigDecimal.ZERO;
    BigDecimal pd = BigDecimal.ZERO, cost = BigDecimal.ZERO;
    BigDecimal untracked = BigDecimal.ZERO;
    BigDecimal hidden = BigDecimal.ZERO;
    boolean anyPlanned = false, anyCost = false, anyData = false, anyUntracked = false;
    boolean anyHidden = false;
    for (RoleRow r : rows) {
      RolePeriod p = pick.apply(r);
      if (p == null) continue;
      if (p.qty() != null) { qty = qty.add(p.qty()); anyData = true; }
      if (p.budgetDays() != null) { bd = bd.add(p.budgetDays()); anyData = true; }
      if (p.actualDays() != null) { ad = ad.add(p.actualDays()); anyData = true; }
      if (p.plannedDays() != null) { pd = pd.add(p.plannedDays()); anyPlanned = true; }
      if (p.costImplication() != null) { cost = cost.add(p.costImplication()); anyCost = true; }
      if (p.actualDaysUntracked() != null) {
        untracked = untracked.add(p.actualDaysUntracked()); anyUntracked = true;
      }
      if (p.actualDaysOnHiddenSides() != null) {
        hidden = hidden.add(p.actualDaysOnHiddenSides()); anyHidden = true;
      }
    }
    if (!anyData) return RolePeriod.empty();
    // Util% only makes sense when at least one row contributed a budget. When no role in the
    // section has a productivity norm, bd stays at zero and we keep util as null so the Total
    // pill renders "—" (grey) instead of misleading "0% red". Hidden-side days are excluded
    // from the tracked denominator the same way untracked days are.
    BigDecimal trackedActual = ad.subtract(untracked).subtract(hidden);
    BigDecimal utilPct = (trackedActual.signum() > 0 && bd.signum() > 0)
        ? bd.divide(trackedActual, 4, RoundingMode.HALF_UP).multiply(HUNDRED) : null;
    // Section total of qty is suppressed — the same DPR's qty_executed contributes to every
    // role on that DPR, so summing across roles would double/triple-count the activity output.
    // Qty stays meaningful only on the per-role rows.
    return new RolePeriod(
        null,
        bd.signum() == 0 ? null : bd,
        null, // section totals don't carry budget-Nos (sum of per-role Nos is not meaningful)
        anyPlanned ? pd : null,
        null,
        ad.signum() == 0 ? null : ad,
        null,
        anyUntracked ? untracked : null,
        anyHidden ? hidden : null,
        utilPct,
        anyCost ? cost : null,
        null, // normResolved is not meaningful at the section-total level
        null, null); // deprecated constrainedDays / constrainedBySide
  }

  // ─── Native-SQL data loaders ──────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private List<Contribution> loadManpowerContributions(
      UUID projectId, LocalDate fromDate, LocalDate toDate,
      UUID supervisorUserId) {
    // One row per (role, dpr) — role-days summed across the multiple manpower rows that may
    // exist on the same DPR for the same role. Joins activity + work_activity so the caller can
    // resolve per-activity norms downstream.
    List<Object[]> raw = em.createNativeQuery(
            "SELECT m.role_id, "
                + "       MAX(rr.code) AS role_code, "
                + "       COALESCE(MAX(rr.name), m.trade) AS role_name, "
                + "       d.id AS dpr_id, "
                + "       d.report_date, "
                + "       a.work_activity_id, "
                + "       MAX(wa.name) AS wa_name, "
                + "       MAX(wa.default_unit) AS wa_default_unit, "
                + "       MAX(d.qty_executed) AS qty_executed, "
                // role_days is the raw sum of nos. working_hours is logging metadata on the
                // DPR row and intentionally not multiplied in — DAY-basis rates are paid per
                // person regardless of hours worked, and we don't want a half-day attendance
                // to silently halve the role's contribution to Capacity Util.
                + "       SUM(COALESCE(m.nos, 0)) AS role_days, "
                + "       m.trade AS trade, "
                + "       a.id AS activity_id "
                + "FROM project.dpr_manpower m "
                + "JOIN project.daily_progress_reports d ON d.id = m.dpr_id "
                + "JOIN activity.activities a ON a.id = d.activity_id "
                + "LEFT JOIN resource.work_activities wa ON wa.id = a.work_activity_id "
                + "LEFT JOIN resource.resource_roles rr ON rr.id = m.role_id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND a.work_activity_id IS NOT NULL "
                // Filter by the supervisor who actually filed the DPR. Co-supervisors on a
                // shared activity each see only their own DPRs — matches the supervisor-dropdown
                // count semantics. NULL = project-wide.
                + "  AND (CAST(:supervisorUserId AS uuid) IS NULL "
                + "       OR d.supervisor_user_id = CAST(:supervisorUserId AS uuid)) "
                + "GROUP BY m.role_id, m.trade, d.id, d.report_date, a.work_activity_id, a.id")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("supervisorUserId",
            supervisorUserId != null ? supervisorUserId.toString() : null)
        .getResultList();
    return mapContributions(raw);
  }

  @SuppressWarnings("unchecked")
  private List<Contribution> loadEquipmentContributions(
      UUID projectId, LocalDate fromDate, LocalDate toDate,
      UUID supervisorUserId) {
    List<Object[]> raw = em.createNativeQuery(
            "SELECT e.role_id, "
                + "       MAX(rr.code) AS role_code, "
                + "       COALESCE(MAX(rr.name), e.equipment_type) AS role_name, "
                + "       d.id AS dpr_id, "
                + "       d.report_date, "
                + "       a.work_activity_id, "
                + "       MAX(wa.name) AS wa_name, "
                + "       MAX(wa.default_unit) AS wa_default_unit, "
                + "       MAX(d.qty_executed) AS qty_executed, "
                // Same hours-scaling rationale as manpower — equipment Actual = raw sum of nos.
                + "       SUM(COALESCE(e.nos, 0)) AS role_days, "
                + "       e.equipment_type AS trade, "
                + "       a.id AS activity_id "
                + "FROM project.dpr_equipment e "
                + "JOIN project.daily_progress_reports d ON d.id = e.dpr_id "
                + "JOIN activity.activities a ON a.id = d.activity_id "
                + "LEFT JOIN resource.work_activities wa ON wa.id = a.work_activity_id "
                + "LEFT JOIN resource.resource_roles rr ON rr.id = e.role_id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND a.work_activity_id IS NOT NULL "
                // Same filer-based filter as the manpower query above.
                + "  AND (CAST(:supervisorUserId AS uuid) IS NULL "
                + "       OR d.supervisor_user_id = CAST(:supervisorUserId AS uuid)) "
                + "GROUP BY e.role_id, e.equipment_type, d.id, d.report_date, a.work_activity_id, a.id")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("supervisorUserId",
            supervisorUserId != null ? supervisorUserId.toString() : null)
        .getResultList();
    return mapContributions(raw);
  }

  private List<Contribution> mapContributions(List<Object[]> raw) {
    List<Contribution> out = new ArrayList<>(raw.size());
    for (Object[] r : raw) {
      String trade = (String) r[10];
      UUID activityId = r.length > 11 ? (UUID) r[11] : null;
      out.add(new Contribution(
          (UUID) r[0],
          (String) r[1],
          (String) r[2],
          (UUID) r[3],
          ((java.sql.Date) r[4]).toLocalDate(),
          (UUID) r[5],
          (String) r[6],
          (String) r[7],
          toBigDecimal(r[8]),
          toBigDecimal(r[9]),
          trade,
          activityId));
    }
    return out;
  }

  /** Look up the WorkActivity's norm_combination by activity id (single round-trip per call). */
  @SuppressWarnings("unchecked")
  String loadNormCombinationForActivity(UUID activityId) {
    List<Object> rows = em.createNativeQuery(
            "SELECT COALESCE(wa.norm_combination, 'SERIES') " +
            "FROM activity.activities a " +
            "JOIN resource.work_activities wa ON wa.id = a.work_activity_id " +
            "WHERE a.id = :id")
        .setParameter("id", activityId)
        .setMaxResults(1)
        .getResultList();
    return rows.isEmpty() || rows.get(0) == null ? "SERIES" : rows.get(0).toString();
  }

  /**
   * For each (DPR, activity) pair in the report window, compute the OTHER side's expected
   * output from the DPR's actual resources. The result is keyed by {@code (dprId, activityId)}.
   *
   * <p>When this service is building the MANPOWER section, this returns each (DPR, activity)'s
   * EQUIPMENT expected (= Σ outputPerDay × NOS across equipment rows). The allocator uses this
   * as {@code otherSideExpected} to decide hiding in SERIES / SUBSTITUTE.
   *
   * <p>HRS is intentionally not used — per-day basis only.
   */
  @SuppressWarnings("unchecked")
  Map<DprActivityKey, BigDecimal> loadOtherSideExpectedPerDpr(
      UUID projectId, LocalDate fromDate, LocalDate toDate, String thisSideNormType,
      UUID supervisorUserId) {
    String otherSide = "MANPOWER".equals(thisSideNormType) ? "EQUIPMENT" : "MANPOWER";
    String table = "EQUIPMENT".equals(otherSide) ? "dpr_equipment" : "dpr_manpower";
    String normColumn = "EQUIPMENT".equals(otherSide)
        ? "COALESCE(rn.output_per_day, 0)"
        : "COALESCE(rn.output_per_man_per_day, rn.output_per_day, 0)";

    String sql =
        "SELECT d.id, d.activity_id, " +
        "       SUM(r.nos * " + normColumn + ") AS expected " +
        "FROM project." + table + " r " +
        "JOIN project.daily_progress_reports d ON d.id = r.dpr_id " +
        "JOIN activity.activities a ON a.id = d.activity_id " +
        "LEFT JOIN resource.productivity_norms rn ON rn.work_activity_id = a.work_activity_id " +
        "  AND rn.norm_type = :nt " +
        "  AND (rn.role_id = r.role_id OR " +
        "       (rn.role_id IS NULL AND rn.category_id IS NULL AND rn.grade_id IS NULL " +
        "        AND rn.make IS NULL AND rn.model IS NULL " +
        "        AND rn.resource_id IS NULL AND rn.resource_type_id IS NULL)) " +
        "  AND rn.category_id IS NULL AND rn.grade_id IS NULL " +
        "  AND rn.make IS NULL AND rn.model IS NULL " +
        "WHERE d.project_id = :pid AND d.report_date BETWEEN :from AND :to " +
        (supervisorUserId == null ? "" : "  AND d.supervisor_user_id = :sup ") +
        "GROUP BY d.id, d.activity_id";

    var q = em.createNativeQuery(sql)
        .setParameter("pid", projectId)
        .setParameter("from", fromDate)
        .setParameter("to", toDate)
        .setParameter("nt", otherSide);
    if (supervisorUserId != null) q.setParameter("sup", supervisorUserId);

    Map<DprActivityKey, BigDecimal> out = new HashMap<>();
    for (Object[] row : (List<Object[]>) q.getResultList()) {
      UUID dpr = (UUID) row[0];
      UUID act = (UUID) row[1];
      BigDecimal exp = toBigDecimal(row[2]);
      out.put(new DprActivityKey(dpr, act), exp == null ? BigDecimal.ZERO : exp);
    }
    return out;
  }

  record DprActivityKey(UUID dprId, UUID activityId) {}

  /**
   * Sum of {@code project.dpr_sub_contractor.quantity} per DPR within the report window.
   * Subtracted from each DPR's qty_executed to derive the effective qty the manpower /
   * equipment allocator distributes. Honors the supervisor filter so a supervisor-scoped
   * view doesn't accidentally subtract sub-contractor qty from someone else's DPR.
   */
  @SuppressWarnings("unchecked")
  Map<UUID, BigDecimal> loadSubContractorQtyByDpr(
      UUID projectId, LocalDate fromDate, LocalDate toDate, UUID supervisorUserId) {
    List<Object[]> rows = em.createNativeQuery(
            "SELECT d.id, COALESCE(SUM(sc.quantity), 0) "
                + "FROM project.daily_progress_reports d "
                + "JOIN project.dpr_sub_contractor sc ON sc.dpr_id = d.id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND (CAST(:supervisorUserId AS uuid) IS NULL "
                + "       OR d.supervisor_user_id = CAST(:supervisorUserId AS uuid)) "
                + "GROUP BY d.id")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("supervisorUserId",
            supervisorUserId != null ? supervisorUserId.toString() : null)
        .getResultList();
    Map<UUID, BigDecimal> out = new HashMap<>();
    for (Object[] r : rows) {
      UUID id = (UUID) r[0];
      BigDecimal qty = toBigDecimal(r[1]);
      if (id != null && qty != null) out.put(id, qty);
    }
    return out;
  }

  // ─── Norm resolution (variant → role → unscoped) ────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private NormLookup resolveNorm(UUID workActivityId, UUID roleId, String normType) {
    if (workActivityId == null || normType == null) return new NormLookup(null, "NONE");
    String preferredColumn = "EQUIPMENT".equalsIgnoreCase(normType)
        ? "n.output_per_day"
        : "COALESCE(n.output_per_man_per_day, n.output_per_day)";
    if (roleId != null) {
      BigDecimal roleLevel = singleBigDecimal(
          "SELECT " + preferredColumn + " "
              + "FROM resource.productivity_norms n "
              + "WHERE n.work_activity_id = :wa "
              + "  AND n.role_id = :role "
              + "  AND n.norm_type = :nt "
              + "  AND n.category_id IS NULL AND n.grade_id IS NULL "
              + "  AND n.make IS NULL AND n.model IS NULL "
              + "ORDER BY n.created_at NULLS LAST",
          Map.of("wa", workActivityId, "role", roleId, "nt", normType));
      if (roleLevel != null) return new NormLookup(roleLevel, "ROLE");
    }
    BigDecimal unscoped = singleBigDecimal(
        "SELECT " + preferredColumn + " "
            + "FROM resource.productivity_norms n "
            + "WHERE n.work_activity_id = :wa "
            + "  AND n.norm_type = :nt "
            + "  AND n.role_id IS NULL "
            + "  AND n.category_id IS NULL AND n.grade_id IS NULL "
            + "  AND n.make IS NULL AND n.model IS NULL "
            + "  AND n.resource_id IS NULL AND n.resource_type_id IS NULL "
            + "ORDER BY (" + preferredColumn + " IS NULL), n.created_at NULLS LAST",
        Map.of("wa", workActivityId, "nt", normType));
    if (unscoped != null) return new NormLookup(unscoped, "UNSCOPED");
    return new NormLookup(null, "NONE");
  }

  // ─── Per-role rate (cost implication denominator) ─────────────────────────────────────────

  /**
   * Effective rate per role, weighted by the variants actually deployed via DPRs in the window.
   *
   * <pre>
   *   rate = SUM(nos × hours/8 × variantRate) ÷ SUM(nos × hours/8)
   * </pre>
   *
   * <p>This is the same formula the bottom SC180-classic table uses, so the top section and the
   * supervisor section agree on Rate / Day, MM Rate, Eq Rate / Day, and the resulting Cost
   * Implication. Falls back to AVG across all role variants when the role has zero DPR rows in
   * the window (so the display rate still shows something for newly-planned roles).
   *
   * <p>{@code unit_rate} from the DPR row wins over the variant table rate (legacy DPRs may have
   * stamped a custom rate at save time); new DPRs leave {@code unit_rate} null and the variant
   * rate is used.
   */
  @SuppressWarnings("unchecked")
  private Map<UUID, BigDecimal> loadRoleRates(
      java.util.Set<UUID> roleIds, String normType,
      UUID projectId, LocalDate fromDate, LocalDate toDate) {
    if (roleIds == null || roleIds.isEmpty()) return Map.of();
    boolean isManpower = "MANPOWER".equalsIgnoreCase(normType);
    String dprTable = isManpower ? "project.dpr_manpower" : "project.dpr_equipment";
    String rateTable = isManpower ? "resource.manpower_role_rates" : "resource.equipment_role_variants";
    String variantFkCol = isManpower ? "manpower_role_rate_id" : "equipment_role_variant_id";

    Map<UUID, BigDecimal> out = new HashMap<>();

    // 1. DPR-weighted effective rate.
    List<Object[]> dprRows = em.createNativeQuery(
            "SELECT dr.role_id, "
                + "       SUM(COALESCE(dr.nos, 0) * COALESCE(dr.working_hours, " + DEFAULT_HOURS_PER_DAY + ") / " + DEFAULT_HOURS_PER_DAY + ") AS total_days, "
                + "       SUM(COALESCE(dr.nos, 0) * COALESCE(dr.working_hours, " + DEFAULT_HOURS_PER_DAY + ") / " + DEFAULT_HOURS_PER_DAY + " "
                + "         * COALESCE(dr.unit_rate, rate.rate, 0)) AS total_cost "
                + "FROM " + dprTable + " dr "
                + "JOIN project.daily_progress_reports d ON d.id = dr.dpr_id "
                + "LEFT JOIN " + rateTable + " rate ON rate.id = dr." + variantFkCol + " "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date BETWEEN :fromDate AND :toDate "
                + "  AND dr.role_id IN :ids "
                + "GROUP BY dr.role_id")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .setParameter("ids", roleIds)
        .getResultList();
    for (Object[] r : dprRows) {
      UUID roleId = (UUID) r[0];
      BigDecimal totalDays = toBigDecimal(r[1]);
      BigDecimal totalCost = toBigDecimal(r[2]);
      if (roleId == null || totalDays == null || totalDays.signum() <= 0
          || totalCost == null || totalCost.signum() <= 0) continue;
      out.put(roleId, totalCost.divide(totalDays, 4, RoundingMode.HALF_UP));
    }

    // 2. Fallback: AVG across variants for roles that had no DPR rows in the window — so the
    //    Rate / Day column on rows that are only "planned" still renders a number.
    java.util.Set<UUID> uncovered = new java.util.HashSet<>(roleIds);
    uncovered.removeAll(out.keySet());
    if (!uncovered.isEmpty()) {
      List<Object[]> fallback = em.createNativeQuery(
              "SELECT role_id, AVG(rate) FROM " + rateTable + " WHERE role_id IN :ids GROUP BY role_id")
          .setParameter("ids", uncovered)
          .getResultList();
      for (Object[] r : fallback) {
        if (r[0] != null && r[1] != null) {
          BigDecimal v = toBigDecimal(r[1]);
          if (v != null && v.signum() > 0) out.put((UUID) r[0], v);
        }
      }
    }
    return out;
  }

  // ─── Per-role × per-bucket planned headcount ──────────────────────────────────────────────

  /**
   * For each role, return planned headcount broken into three buckets (Day / Month / Cumulative).
   * An activity contributes its full {@code plannedUnits} to a bucket when <strong>either</strong>
   * the activity's planned date range intersects that bucket's window <strong>or</strong> the
   * activity has at least one DPR in the bucket window. The DPR-existence fallback handles
   * late-running activities (planned for 2024 but actually executed in 2026) so the Planned
   * column doesn't disappear just because the project slipped.
   *
   * <p>Activities without planned dates set are also included via the {@link #intersects}
   * "null-dates = always active" rule.
   */
  @SuppressWarnings("unchecked")
  private Map<UUID, BucketedPlanned> loadPlannedHeadcountByBucket(
      java.util.Set<UUID> roleIds, UUID projectId,
      LocalDate dayDate, LocalDate monthStart, LocalDate monthEnd,
      LocalDate cumStart, LocalDate cumEnd) {
    if (roleIds == null || roleIds.isEmpty()) return Map.of();

    // Pre-load activities with DPRs per bucket — late-running activities (planned 2024,
    // executing 2026) wouldn't otherwise show up under planned-date intersection alone.
    java.util.Set<UUID> dayActivities = loadActivitiesWithDpr(projectId, dayDate, dayDate);
    java.util.Set<UUID> monthActivities = loadActivitiesWithDpr(projectId, monthStart, monthEnd);
    java.util.Set<UUID> cumActivities = loadActivitiesWithDpr(projectId, cumStart, cumEnd);

    // Display Planned as raw nos the planner entered (headcount for MP/EQ, quantity for material).
    // The legacy planned_units column stores headcount × duration in person-days for DPR/EVA
    // rollups — surfacing that here would show "50 nos" when the user planned 10 masons for 5 days,
    // which they correctly read as wrong. COALESCE falls through to planned_units only for legacy
    // rows that pre-date the headcount/quantity columns.
    List<Object[]> rows = em.createNativeQuery(
            "SELECT ra.role_id, "
                + "       COALESCE(ra.headcount, ra.quantity, ra.planned_units) AS planned_nos, "
                + "       a.id, a.planned_start_date, a.planned_finish_date "
                + "FROM resource.resource_assignments ra "
                + "JOIN activity.activities a ON a.id = ra.activity_id "
                + "WHERE a.project_id = :projectId "
                + "  AND ra.role_id IN :ids")
        .setParameter("projectId", projectId)
        .setParameter("ids", roleIds)
        .getResultList();

    Map<UUID, BigDecimal> dayMap = new HashMap<>();
    Map<UUID, BigDecimal> monthMap = new HashMap<>();
    Map<UUID, BigDecimal> cumMap = new HashMap<>();
    for (Object[] r : rows) {
      UUID roleId = (UUID) r[0];
      BigDecimal units = toBigDecimal(r[1]);
      if (units == null || units.signum() <= 0) continue;
      UUID activityId = (UUID) r[2];
      LocalDate pStart = r[3] == null ? null : ((java.sql.Date) r[3]).toLocalDate();
      LocalDate pFinish = r[4] == null ? null : ((java.sql.Date) r[4]).toLocalDate();
      if (intersects(pStart, pFinish, dayDate, dayDate) || dayActivities.contains(activityId)) {
        dayMap.merge(roleId, units, BigDecimal::add);
      }
      if (intersects(pStart, pFinish, monthStart, monthEnd) || monthActivities.contains(activityId)) {
        monthMap.merge(roleId, units, BigDecimal::add);
      }
      if (intersects(pStart, pFinish, cumStart, cumEnd) || cumActivities.contains(activityId)) {
        cumMap.merge(roleId, units, BigDecimal::add);
      }
    }
    Map<UUID, BucketedPlanned> out = new HashMap<>();
    for (UUID roleId : roleIds) {
      out.put(roleId, new BucketedPlanned(
          dayMap.getOrDefault(roleId, BigDecimal.ZERO),
          monthMap.getOrDefault(roleId, BigDecimal.ZERO),
          cumMap.getOrDefault(roleId, BigDecimal.ZERO)));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private java.util.Set<UUID> loadActivitiesWithDpr(UUID projectId, LocalDate from, LocalDate to) {
    if (from == null || to == null) return java.util.Set.of();
    List<Object> rows = em.createNativeQuery(
            "SELECT DISTINCT activity_id FROM project.daily_progress_reports "
                + "WHERE project_id = :pid "
                + "  AND report_date BETWEEN :from AND :to "
                + "  AND activity_id IS NOT NULL")
        .setParameter("pid", projectId)
        .setParameter("from", from)
        .setParameter("to", to)
        .getResultList();
    java.util.Set<UUID> out = new java.util.HashSet<>();
    for (Object r : rows) {
      if (r instanceof UUID u) out.add(u);
      else if (r instanceof String s) out.add(UUID.fromString(s));
    }
    return out;
  }

  /**
   * Date-range intersection. Activities without any planned dates ({@code null/null}) are
   * treated as "always active" so they show up in every bucket — the user shouldn't lose
   * planning info just because dates aren't set yet.
   */
  private static boolean intersects(LocalDate aStart, LocalDate aEnd,
                                    LocalDate bStart, LocalDate bEnd) {
    if (aStart == null && aEnd == null) return true;
    LocalDate sa = aStart == null ? LocalDate.MIN : aStart;
    LocalDate ea = aEnd == null ? LocalDate.MAX : aEnd;
    return !ea.isBefore(bStart) && !sa.isAfter(bEnd);
  }

  private static BigDecimal nullIfZero(BigDecimal v) {
    return v == null || v.signum() == 0 ? null : v;
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private BigDecimal singleBigDecimal(String sql, Map<String, Object> params) {
    var q = em.createNativeQuery(sql);
    params.forEach(q::setParameter);
    List<Object> rows = q.setMaxResults(1).getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return null;
    return toBigDecimal(rows.get(0));
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return null;
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return null;
  }

  private static int defaultWorkDays() { return 26; }

  // ─── Internal types ──────────────────────────────────────────────────────────────────────

  private record Contribution(
      UUID roleId, String roleCode, String roleName,
      UUID dprId, LocalDate reportDate,
      UUID workActivityId, String workActivityName, String workActivityDefaultUnit,
      BigDecimal qtyExecuted, BigDecimal roleDays,
      String trade,
      /** Project-level activity id. Needed so the allocator can group contributions per (DPR,
       *  activity) — multiple activities can share the same WorkActivity master with different
       *  plans, but each runs its own per-DPR qty allocation. */
      UUID activityId) {
    /**
     * Stable accumulator key: uses the real roleId when present; otherwise derives a
     * deterministic UUID from the free-text trade string so distinct trades stay separate
     * even when both have {@code role_id = NULL}.
     */
    UUID accKey() {
      if (roleId != null) return roleId;
      return java.util.UUID.nameUUIDFromBytes(
          ("TRADE:" + (trade == null ? "" : trade.toLowerCase())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private record WorkActivityRoleKey(UUID workActivityId, UUID roleId) {}

  private record NormLookup(BigDecimal outputPerDay, String source) {}

  private record BucketedPlanned(BigDecimal day, BigDecimal month, BigDecimal cum) {
    static BucketedPlanned empty() {
      return new BucketedPlanned(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
  }

  private static class RoleAccumulator {
    final UUID roleId;
    final String roleCode;
    final String roleName;
    final Map<WorkActivityRoleKey, ActivityRoleAccumulator> activityRoles = new LinkedHashMap<>();
    BigDecimal dayActualDays = BigDecimal.ZERO;
    BigDecimal monthActualDays = BigDecimal.ZERO;
    BigDecimal cumActualDays = BigDecimal.ZERO;

    RoleAccumulator(UUID roleId, String roleCode, String roleName) {
      this.roleId = roleId;
      this.roleCode = roleCode;
      this.roleName = roleName;
    }

    BigDecimal dayQty() {
      BigDecimal s = BigDecimal.ZERO;
      for (ActivityRoleAccumulator a : activityRoles.values()) s = s.add(a.dayQty);
      return s;
    }
    BigDecimal monthQty() {
      BigDecimal s = BigDecimal.ZERO;
      for (ActivityRoleAccumulator a : activityRoles.values()) s = s.add(a.monthQty);
      return s;
    }
    BigDecimal cumQty() {
      BigDecimal s = BigDecimal.ZERO;
      for (ActivityRoleAccumulator a : activityRoles.values()) s = s.add(a.cumQty);
      return s;
    }
  }

  private static class ActivityRoleAccumulator {
    final UUID workActivityId;
    final String workActivityName;
    final String workActivityDefaultUnit;
    final UUID roleId;
    BigDecimal dayActualDays = BigDecimal.ZERO;
    BigDecimal monthActualDays = BigDecimal.ZERO;
    BigDecimal cumActualDays = BigDecimal.ZERO;
    BigDecimal dayActualHidden = BigDecimal.ZERO;
    BigDecimal monthActualHidden = BigDecimal.ZERO;
    BigDecimal cumActualHidden = BigDecimal.ZERO;
    BigDecimal dayQty = BigDecimal.ZERO;
    BigDecimal monthQty = BigDecimal.ZERO;
    BigDecimal cumQty = BigDecimal.ZERO;
    /** Set true once {@link CapacityAllocator} returns a non-null allocation for this role on
     *  this activity. Drives the {@code tracked} branch in the role roll-up loop — untracked
     *  rows still report actual NOS but don't drive budget / util%. */
    boolean normResolved = false;

    ActivityRoleAccumulator(UUID wa, String name, String unit, UUID roleId) {
      this.workActivityId = wa;
      this.workActivityName = name;
      this.workActivityDefaultUnit = unit;
      this.roleId = roleId;
    }
  }
}
