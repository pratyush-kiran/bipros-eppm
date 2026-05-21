package com.bipros.api.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.SafetyIncidentType;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.resource.domain.model.MaterialStock;
import com.bipros.resource.domain.model.StockStatusTag;
import com.bipros.resource.domain.repository.MaterialStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
 * Live data backing for the Field dashboard top-strip and "Active sites" section. Replaces the
 * hardcoded {@code mockActiveSites} array on the frontend with real DPR / equipment-log reads.
 *
 * <p>"As-of date" is the supervisor shift date the dashboard is reporting. When the caller omits
 * it, the service falls back to the most recent {@code report_date} in {@code daily_progress_reports}
 * for the project. Window for trailing metrics: 4 calendar days for operating-hours, 7 calendar
 * days for "active site" eligibility (matches the SCR-001 wording). Project calendar-aware
 * working-day filtering is deferred to Phase 2 (requires the calendar-day expansion service).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldDashboardSummaryService {

  private static final int OPERATING_HOURS_WINDOW_DAYS = 4;
  private static final int ACTIVE_SITE_WINDOW_DAYS = 7;

  private final DailyProgressReportRepository dprRepository;
  private final DprManpowerRepository dprManpowerRepository;
  private final DprEquipmentRepository dprEquipmentRepository;
  private final ActivityRepository activityRepository;
  private final MaterialStockRepository materialStockRepository;

  // ---------- Response shapes ----------

  public record FieldSummaryResponse(
      UUID projectId,
      LocalDate asOfDate,
      int workersOnSite,
      int equipmentDeployed,
      double operatingHours4d,
      int safetyIncidents,
      List<ActiveSite> activeSites,
      List<DailyWorklog> dailyWorklogs,
      double stockAvailabilityPct,
      int reorderBreachCount,
      int stockTrackedMaterialCount
  ) {}

  public record ActiveSite(
      UUID activityId,
      String activityName,
      String status,
      int workers,
      int equipment,
      int safetyIncidents
  ) {}

  /**
   * Per-day rollup feeding the "Daily worklogs" panel on the Field dashboard. One entry per
   * trailing-4 calendar day from {@code asOfDate} (oldest → newest). All numbers come from
   * DPR; {@code labour_returns} and {@code equipment_logs} are no longer consulted.
   */
  public record DailyWorklog(
      LocalDate date,
      int headCount,
      int equipmentCount,
      double operatingHours
  ) {}

  // ---------- Public API ----------

  @Transactional(readOnly = true)
  public FieldSummaryResponse getSummary(UUID projectId, LocalDate asOfDateParam) {
    LocalDate asOfDate = resolveAsOfDate(projectId, asOfDateParam);
    if (asOfDate == null) {
      return new FieldSummaryResponse(
          projectId, null, 0, 0, 0d, 0, List.of(), List.of(), 0d, 0, 0);
    }

    // ---- Top-strip: workers + equipment counts on the as-of date ----
    List<DailyProgressReport> dprsToday = dprRepository
        .findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, asOfDate, asOfDate);
    Set<UUID> dprIdsToday = dprsToday.stream()
        .map(DailyProgressReport::getId)
        .collect(Collectors.toSet());

    int workersOnSite = sumDistinctOrTotalWorkers(dprIdsToday);
    int equipmentDeployed = sumDistinctOrTotalEquipment(dprIdsToday);

    // ---- Operating hours over trailing 4 days (from dpr_equipment) ----
    LocalDate hoursFrom = asOfDate.minusDays(OPERATING_HOURS_WINDOW_DAYS - 1L);
    LocalDate siteWindowFrom = asOfDate.minusDays(ACTIVE_SITE_WINDOW_DAYS - 1L);
    List<DailyProgressReport> dprsWindow = dprRepository
        .findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, siteWindowFrom, asOfDate);
    Set<UUID> hoursWindowDprIds = dprsWindow.stream()
        .filter(d -> d.getReportDate() != null && !d.getReportDate().isBefore(hoursFrom))
        .map(DailyProgressReport::getId)
        .collect(Collectors.toSet());
    double operatingHours4d = hoursWindowDprIds.isEmpty()
        ? 0d
        : dprEquipmentRepository.findByDprIdIn(hoursWindowDprIds).stream()
            .map(e -> e.getWorkingHours())
            .filter(java.util.Objects::nonNull)
            .mapToDouble(BigDecimal::doubleValue)
            .sum();
    // NONE = supervisor explicitly confirmed "no safety issue" — should NOT be counted as an
    // incident. Only NEAR_MISS and INCIDENT contribute to the safety count.
    int safetyIncidents = (int) dprsWindow.stream()
        .map(DailyProgressReport::getSafetyIncidentType)
        .filter(t -> t != null && t != SafetyIncidentType.NONE)
        .count();

    // ---- Active site cards ----
    List<ActiveSite> activeSites = computeActiveSites(projectId, dprsWindow);

    // ---- Per-day worklogs (trailing 4 days, oldest → newest) ----
    List<DailyWorklog> dailyWorklogs = computeDailyWorklogs(asOfDate, dprsWindow);

    // ---- Stock availability + re-order breach (KPI quick wins) ----
    StockSnapshot stock = computeStockSnapshot(projectId);

    return new FieldSummaryResponse(
        projectId,
        asOfDate,
        workersOnSite,
        equipmentDeployed,
        round2(operatingHours4d),
        safetyIncidents,
        activeSites,
        dailyWorklogs,
        stock.availabilityPct(),
        stock.breachCount(),
        stock.totalTracked());
  }

  /**
   * Roll up {@code material_stock} status tags for the project.
   * <ul>
   *   <li>Stock Availability % = OK rows ÷ total tracked × 100</li>
   *   <li>Re-Order Breach Count = LOW + CRITICAL rows</li>
   * </ul>
   */
  private record StockSnapshot(double availabilityPct, int breachCount, int totalTracked) {}

  private StockSnapshot computeStockSnapshot(UUID projectId) {
    List<MaterialStock> stocks = materialStockRepository.findByProjectId(projectId);
    if (stocks.isEmpty()) return new StockSnapshot(0d, 0, 0);
    int ok = 0;
    int breach = 0;
    for (MaterialStock s : stocks) {
      StockStatusTag tag = s.getStockStatusTag();
      if (tag == null) continue;
      switch (tag) {
        case OK -> ok++;
        case LOW, CRITICAL -> breach++;
      }
    }
    int total = stocks.size();
    double pct = total > 0 ? (double) ok / total : 0d;
    return new StockSnapshot(round4(pct), breach, total);
  }

  /**
   * Build a 4-element list of per-day worklogs ending at {@code asOfDate}. Days with no DPR
   * activity still appear as zero-rows so the frontend renders four cards consistently.
   */
  private List<DailyWorklog> computeDailyWorklogs(
      LocalDate asOfDate, List<DailyProgressReport> windowDprs) {
    LocalDate from = asOfDate.minusDays(OPERATING_HOURS_WINDOW_DAYS - 1L);

    Map<LocalDate, List<DailyProgressReport>> dprsByDate = new HashMap<>();
    for (DailyProgressReport d : windowDprs) {
      if (d.getReportDate() == null) continue;
      if (d.getReportDate().isBefore(from)) continue;
      dprsByDate.computeIfAbsent(d.getReportDate(), k -> new ArrayList<>()).add(d);
    }

    Set<UUID> windowDprIds = dprsByDate.values().stream()
        .flatMap(List::stream)
        .map(DailyProgressReport::getId)
        .collect(Collectors.toSet());
    Map<UUID, List<DprManpower>> manpowerByDpr = windowDprIds.isEmpty()
        ? Map.of()
        : dprManpowerRepository.findByDprIdIn(windowDprIds).stream()
            .collect(Collectors.groupingBy(DprManpower::getDprId));
    Map<UUID, List<DprEquipment>> equipmentByDpr = windowDprIds.isEmpty()
        ? Map.of()
        : dprEquipmentRepository.findByDprIdIn(windowDprIds).stream()
            .collect(Collectors.groupingBy(DprEquipment::getDprId));

    List<DailyWorklog> out = new ArrayList<>(OPERATING_HOURS_WINDOW_DAYS);
    for (int i = 0; i < OPERATING_HOURS_WINDOW_DAYS; i++) {
      LocalDate day = from.plusDays(i);
      List<DailyProgressReport> dayDprs = dprsByDate.getOrDefault(day, List.of());
      int headCount = 0;
      double opHours = 0d;
      Set<UUID> equipmentResources = new HashSet<>();
      int equipmentRowFallback = 0;
      for (DailyProgressReport d : dayDprs) {
        for (DprManpower m : manpowerByDpr.getOrDefault(d.getId(), List.of())) {
          if (m.getNos() != null) headCount += m.getNos();
        }
        for (DprEquipment e : equipmentByDpr.getOrDefault(d.getId(), List.of())) {
          if (e.getWorkingHours() != null) opHours += e.getWorkingHours().doubleValue();
          if (e.getResourceId() != null) equipmentResources.add(e.getResourceId());
          else equipmentRowFallback++;
        }
      }
      int equipmentCount = equipmentResources.isEmpty()
          ? equipmentRowFallback
          : equipmentResources.size();
      out.add(new DailyWorklog(day, headCount, equipmentCount, round2(opHours)));
    }
    return out;
  }

  // ---------- Helpers ----------

  private LocalDate resolveAsOfDate(UUID projectId, LocalDate explicit) {
    if (explicit != null) return explicit;
    // Fall back to the most recent DPR report_date in the project — repository returns ascending,
    // so the last element is the latest. If the project has no DPRs yet, return null and the
    // caller will surface zeroed tiles.
    List<DailyProgressReport> all = dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId);
    if (all.isEmpty()) return null;
    return all.get(all.size() - 1).getReportDate();
  }

  /**
   * Workers on site = Σ of {@code dpr_manpower.nos} across DPRs for the as-of date. Falls back
   * to row count when {@code nos} is null on every row (legacy data). The framework's
   * "Headcount on site" tile is a deployed-people count, not a distinct-resource count.
   */
  private int sumDistinctOrTotalWorkers(Set<UUID> dprIds) {
    if (dprIds.isEmpty()) return 0;
    List<DprManpower> rows = dprManpowerRepository.findByDprIdIn(dprIds);
    int total = 0;
    int rowsWithNos = 0;
    for (DprManpower r : rows) {
      if (r.getNos() != null) {
        total += r.getNos();
        rowsWithNos++;
      }
    }
    return rowsWithNos > 0 ? total : rows.size();
  }

  private int sumDistinctOrTotalEquipment(Set<UUID> dprIds) {
    if (dprIds.isEmpty()) return 0;
    List<DprEquipment> rows = dprEquipmentRepository.findByDprIdIn(dprIds);
    int total = 0;
    int rowsWithNos = 0;
    for (DprEquipment r : rows) {
      if (r.getNos() != null) {
        total += r.getNos();
        rowsWithNos++;
      }
    }
    return rowsWithNos > 0 ? total : rows.size();
  }

  /**
   * Group DPRs in the trailing 7-day window by activity, intersect with the project's
   * IN_PROGRESS activities, and return the per-activity headcount + equipment + incident
   * counts. Activities without an {@code activity_id} on the DPR (legacy free-text rows)
   * are skipped — they have no canonical link to render against.
   */
  private List<ActiveSite> computeActiveSites(UUID projectId, List<DailyProgressReport> windowDprs) {
    if (windowDprs.isEmpty()) return List.of();

    Map<UUID, List<DailyProgressReport>> byActivity = new HashMap<>();
    for (DailyProgressReport d : windowDprs) {
      if (d.getActivityId() == null) continue;
      byActivity.computeIfAbsent(d.getActivityId(), k -> new ArrayList<>()).add(d);
    }
    if (byActivity.isEmpty()) return List.of();

    Map<UUID, Activity> activitiesById = activityRepository.findByProjectId(projectId).stream()
        .filter(a -> a.getStatus() == ActivityStatus.IN_PROGRESS)
        .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));

    Set<UUID> targetDprIds = new HashSet<>();
    for (Map.Entry<UUID, List<DailyProgressReport>> e : byActivity.entrySet()) {
      if (!activitiesById.containsKey(e.getKey())) continue;
      for (DailyProgressReport d : e.getValue()) targetDprIds.add(d.getId());
    }
    if (targetDprIds.isEmpty()) return List.of();

    Map<UUID, List<DprManpower>> manpowerByDpr = dprManpowerRepository.findByDprIdIn(targetDprIds).stream()
        .collect(Collectors.groupingBy(DprManpower::getDprId));
    Map<UUID, List<DprEquipment>> equipmentByDpr = dprEquipmentRepository.findByDprIdIn(targetDprIds).stream()
        .collect(Collectors.groupingBy(DprEquipment::getDprId));

    List<ActiveSite> out = new ArrayList<>(byActivity.size());
    for (Map.Entry<UUID, List<DailyProgressReport>> e : byActivity.entrySet()) {
      Activity a = activitiesById.get(e.getKey());
      if (a == null) continue;

      int workers = 0;
      int equipment = 0;
      int incidents = 0;
      for (DailyProgressReport d : e.getValue()) {
        SafetyIncidentType t = d.getSafetyIncidentType();
        if (t != null && t != SafetyIncidentType.NONE) incidents++;
        for (DprManpower m : manpowerByDpr.getOrDefault(d.getId(), List.of())) {
          if (m.getNos() != null) workers += m.getNos();
        }
        for (DprEquipment eq : equipmentByDpr.getOrDefault(d.getId(), List.of())) {
          if (eq.getNos() != null) equipment += eq.getNos();
        }
      }

      out.add(new ActiveSite(
          a.getId(),
          a.getName(),
          a.getStatus().name(),
          workers,
          equipment,
          incidents));
    }
    out.sort(Comparator.comparingInt(ActiveSite::workers).reversed());
    return out;
  }

  private static double round2(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private static double round4(double v) {
    return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
  }
}
