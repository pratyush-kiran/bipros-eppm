package com.bipros.reporting.materialconsumption;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.resource.domain.model.MaterialConsumptionLog;
import com.bipros.resource.domain.model.MaterialIssue;
import com.bipros.resource.domain.repository.MaterialConsumptionLogRepository;
import com.bipros.resource.domain.repository.MaterialIssueRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only Material Consumption Report. Joins {@link MaterialConsumptionLog} with optional
 * issued-quantity data from {@link MaterialIssue} and best-effort planned-qty from
 * {@link BoqItem}, then layers filtering / grouping / alerts on top.
 *
 * <p>Defensive throughout: every cross-module lookup (supervisor name, storekeeper name, WBS
 * name) is wrapped so a missing row simply leaves the field null rather than failing the
 * whole report.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class MaterialConsumptionReportService {

  private static final String GROUP_DAY = "DAY";
  private static final String GROUP_MATERIAL = "MATERIAL";
  private static final String GROUP_ACTIVITY = "ACTIVITY";
  private static final String GROUP_SUPERVISOR = "SUPERVISOR";

  private final MaterialConsumptionLogRepository consumptionRepo;
  private final MaterialIssueRepository issueRepo;
  private final BoqItemRepository boqItemRepo;
  private final ActivityRepository activityRepo;
  private final ActivitySupervisorRepository activitySupervisorRepo;

  @PersistenceContext private EntityManager em;

  public MaterialConsumptionReportResponse generate(MaterialConsumptionFilter filter) {
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(filter.projectId(), "projectId");

    LocalDate from = filter.from() != null ? filter.from() : LocalDate.now().minusMonths(1);
    LocalDate to = filter.to() != null ? filter.to() : LocalDate.now();
    if (to.isBefore(from)) {
      LocalDate tmp = from;
      from = to;
      to = tmp;
    }

    List<MaterialConsumptionLog> logs = safeFetchLogs(filter.projectId(), from, to);
    logs = applyLogFilters(logs, filter);

    // Issued totals per (activity, material) for the same window.
    Map<IssueKey, BigDecimal> issuedByKey = sumIssuedQuantities(filter.projectId(), from, to);

    // Caches to avoid N+1 lookups.
    Map<UUID, String> userNameCache = new HashMap<>();
    Map<UUID, String> wbsNameCache = new HashMap<>();
    Map<UUID, Activity> activityCache = new HashMap<>();
    Map<UUID, UUID> activityToSupervisorCache = new HashMap<>();
    Map<UUID, BoqPlanned> plannedByActivity = buildPlannedByActivityCache(filter.projectId());

    List<MaterialConsumptionRow> rawRows = new ArrayList<>(logs.size());
    for (MaterialConsumptionLog log : logs) {
      UUID supervisorUserId = resolveSupervisorUserId(log.getActivityId(), activityToSupervisorCache);

      // Optional filter on supervisor — applied here because we needed the lookup first.
      if (filter.supervisorUserId() != null && !filter.supervisorUserId().equals(supervisorUserId)) {
        continue;
      }

      Activity activity = safeFetchActivity(log.getActivityId(), activityCache);

      BigDecimal consumed = nz(log.getConsumed());
      BigDecimal received = nz(log.getReceived());
      BigDecimal openingStock = nz(log.getOpeningStock());
      BigDecimal closingStock = log.getClosingStock();
      BigDecimal balance = closingStock != null
          ? closingStock
          : openingStock.add(received).subtract(consumed);

      BigDecimal issuedQty = issuedByKey.getOrDefault(
          new IssueKey(log.getActivityId(), log.getResourceId()), null);

      BoqPlanned planned = plannedByActivity.get(log.getActivityId());
      BigDecimal plannedQty = planned != null && planned.qty() != null
          ? planned.qty()
          : BigDecimal.ZERO;
      BigDecimal unitRate = log.getUnitRate();
      BigDecimal actualCost = log.getLineCost();
      if (actualCost == null && unitRate != null) {
        actualCost = unitRate.multiply(consumed);
      }
      BigDecimal plannedCost = null;
      if (planned != null && planned.qty() != null && planned.rate() != null) {
        plannedCost = planned.qty().multiply(planned.rate());
      } else if (unitRate != null && plannedQty.signum() > 0) {
        plannedCost = unitRate.multiply(plannedQty);
      }

      BigDecimal variance = null;
      BigDecimal variancePercent = null;
      if (actualCost != null && plannedCost != null) {
        variance = actualCost.subtract(plannedCost);
        if (plannedCost.signum() != 0) {
          variancePercent = variance.divide(plannedCost, 6, RoundingMode.HALF_UP);
        }
      }

      List<String> alerts = MaterialConsumptionAlertEvaluator.evaluate(
          plannedQty, consumed, balance, plannedCost, actualCost, unitRate);

      String activityName = activity != null
          ? (activity.getName() != null ? activity.getName() : activity.getCode())
          : null;
      String supervisorName = supervisorUserId != null
          ? resolveUserName(supervisorUserId, userNameCache)
          : null;
      String storekeeperName = log.getIssuedByUserId() != null
          ? resolveUserName(log.getIssuedByUserId(), userNameCache)
          : log.getIssuedBy();
      String wbsName = log.getWbsNodeId() != null
          ? resolveWbsName(log.getWbsNodeId(), wbsNameCache)
          : null;

      rawRows.add(new MaterialConsumptionRow(
          filter.projectId(),
          log.getLogDate(),
          log.getLogDate(),
          log.getWbsNodeId(),
          wbsName,
          log.getActivityId(),
          activityName,
          supervisorUserId,
          supervisorName,
          log.getIssuedByUserId(),
          storekeeperName,
          log.getMaterialRateMasterId(),
          log.getMaterialName(),
          log.getUnit(),
          plannedQty,
          issuedQty,
          consumed,
          balance,
          log.getWastagePercent(),
          unitRate,
          plannedCost,
          actualCost,
          variance,
          variancePercent,
          alerts));
    }

    List<MaterialConsumptionRow> finalRows = groupRows(rawRows, filter.groupBy(), from, to);
    Map<String, BigDecimal> totals = computeTotals(finalRows);
    Map<String, Integer> alertCounts = computeAlertCounts(finalRows);

    return new MaterialConsumptionReportResponse(
        from, to, filter.groupBy(), finalRows, totals, alertCounts);
  }

  // ── Fetch helpers ────────────────────────────────────────────────────────────────────────

  private List<MaterialConsumptionLog> safeFetchLogs(UUID projectId, LocalDate from, LocalDate to) {
    try {
      return consumptionRepo.findByProjectIdAndLogDateBetweenOrderByLogDateAscIdAsc(
          projectId, from, to);
    } catch (Exception e) {
      log.warn("Material consumption log fetch failed for project {}: {}", projectId, e.getMessage());
      return Collections.emptyList();
    }
  }

  private List<MaterialConsumptionLog> applyLogFilters(
      List<MaterialConsumptionLog> logs, MaterialConsumptionFilter filter) {
    List<MaterialConsumptionLog> out = new ArrayList<>(logs.size());
    for (MaterialConsumptionLog l : logs) {
      if (filter.wbsNodeId() != null && !filter.wbsNodeId().equals(l.getWbsNodeId())) continue;
      if (filter.activityId() != null && !filter.activityId().equals(l.getActivityId())) continue;
      if (filter.storekeeperUserId() != null
          && !filter.storekeeperUserId().equals(l.getIssuedByUserId())) continue;
      if (filter.materialRateMasterId() != null
          && !filter.materialRateMasterId().equals(l.getMaterialRateMasterId())) continue;
      out.add(l);
    }
    return out;
  }

  private Map<IssueKey, BigDecimal> sumIssuedQuantities(
      UUID projectId, LocalDate from, LocalDate to) {
    Map<IssueKey, BigDecimal> out = new HashMap<>();
    try {
      List<MaterialIssue> issues = issueRepo.findByProjectIdAndIssueDateBetween(projectId, from, to);
      for (MaterialIssue i : issues) {
        IssueKey k = new IssueKey(i.getActivityId(), i.getMaterialId());
        out.merge(k, nz(i.getQuantity()), BigDecimal::add);
      }
    } catch (Exception e) {
      log.debug("Material issue aggregation failed for project {}: {}", projectId, e.getMessage());
    }
    return out;
  }

  private Map<UUID, BoqPlanned> buildPlannedByActivityCache(UUID projectId) {
    Map<UUID, BoqPlanned> out = new HashMap<>();
    try {
      // Best-effort: BOQ items don't directly carry activity_id, so map via WBS node when a
      // join row exists in activity.activities → boq_items via wbs_node_id. For v1 we simply
      // index BoqItems by wbs_node_id and rely on activity.wbs_node_id to find a match later;
      // any unmatched activity gets a zero plannedQty.
      List<BoqItem> items = boqItemRepo.findByProjectIdOrderByItemNoAsc(projectId);
      // Group BoqItems by wbs_node_id (multiple may exist; sum qty).
      Map<UUID, BigDecimal> qtyByWbs = new HashMap<>();
      Map<UUID, BigDecimal> rateByWbs = new HashMap<>();
      for (BoqItem b : items) {
        if (b.getWbsNodeId() == null) continue;
        qtyByWbs.merge(b.getWbsNodeId(), nz(b.getBoqQty()), BigDecimal::add);
        // Rate: prefer budgetedRate, fall back to boqRate.
        BigDecimal rate = b.getBudgetedRate() != null ? b.getBudgetedRate() : b.getBoqRate();
        if (rate != null) {
          rateByWbs.putIfAbsent(b.getWbsNodeId(), rate);
        }
      }
      // Now resolve activities → wbs_node and look up.
      List<Activity> activities = activityRepo.findByProjectId(projectId);
      for (Activity a : activities) {
        if (a.getWbsNodeId() == null) continue;
        BigDecimal qty = qtyByWbs.get(a.getWbsNodeId());
        BigDecimal rate = rateByWbs.get(a.getWbsNodeId());
        if (qty != null || rate != null) {
          out.put(a.getId(), new BoqPlanned(qty, rate));
        }
      }
    } catch (Exception e) {
      log.debug("Planned-qty cache build failed for project {}: {}", projectId, e.getMessage());
    }
    return out;
  }

  private Activity safeFetchActivity(UUID activityId, Map<UUID, Activity> cache) {
    if (activityId == null) return null;
    if (cache.containsKey(activityId)) return cache.get(activityId);
    try {
      Activity a = activityRepo.findById(activityId).orElse(null);
      cache.put(activityId, a);
      return a;
    } catch (Exception e) {
      cache.put(activityId, null);
      return null;
    }
  }

  /**
   * Picks the first supervisor (by created_at) for the given activity. If the activity has
   * no supervisors, returns null.
   */
  private UUID resolveSupervisorUserId(UUID activityId, Map<UUID, UUID> cache) {
    if (activityId == null) return null;
    if (cache.containsKey(activityId)) return cache.get(activityId);
    UUID supervisorId = null;
    try {
      Object row = em.createNativeQuery(
              "SELECT user_id FROM activity.activity_supervisors "
                  + "WHERE activity_id = ?1 ORDER BY created_at ASC LIMIT 1")
          .setParameter(1, activityId)
          .getResultList()
          .stream()
          .findFirst()
          .orElse(null);
      if (row != null) {
        supervisorId = UUID.fromString(row.toString());
      }
    } catch (Exception e) {
      log.trace("Supervisor lookup failed for activity {}: {}", activityId, e.getMessage());
    }
    cache.put(activityId, supervisorId);
    return supervisorId;
  }

  private String resolveUserName(UUID userId, Map<UUID, String> cache) {
    if (userId == null) return null;
    if (cache.containsKey(userId)) return cache.get(userId);
    String name = null;
    try {
      Object row = em.createNativeQuery(
              "SELECT COALESCE(NULLIF(TRIM(CONCAT_WS(' ', first_name, last_name)), ''), username) "
                  + "FROM public.users WHERE id = ?1")
          .setParameter(1, userId)
          .getResultList()
          .stream()
          .findFirst()
          .orElse(null);
      if (row != null) name = row.toString();
    } catch (Exception e) {
      log.trace("User name lookup failed for {}: {}", userId, e.getMessage());
    }
    cache.put(userId, name);
    return name;
  }

  private String resolveWbsName(UUID wbsNodeId, Map<UUID, String> cache) {
    if (wbsNodeId == null) return null;
    if (cache.containsKey(wbsNodeId)) return cache.get(wbsNodeId);
    String name = null;
    try {
      Object row = em.createNativeQuery(
              "SELECT name FROM project.wbs_nodes WHERE id = ?1")
          .setParameter(1, wbsNodeId)
          .getResultList()
          .stream()
          .findFirst()
          .orElse(null);
      if (row != null) name = row.toString();
    } catch (Exception e) {
      log.trace("WBS name lookup failed for {}: {}", wbsNodeId, e.getMessage());
    }
    cache.put(wbsNodeId, name);
    return name;
  }

  // ── Grouping ─────────────────────────────────────────────────────────────────────────────

  private List<MaterialConsumptionRow> groupRows(
      List<MaterialConsumptionRow> rows, String groupBy, LocalDate from, LocalDate to) {
    if (groupBy == null || groupBy.isBlank()) return rows;
    String key = groupBy.trim().toUpperCase();
    Map<Object, List<MaterialConsumptionRow>> buckets = new LinkedHashMap<>();
    for (MaterialConsumptionRow r : rows) {
      Object bucketKey = switch (key) {
        case GROUP_DAY -> r.fromDate();
        case GROUP_MATERIAL -> r.materialRateMasterId() != null
            ? r.materialRateMasterId()
            : ("name:" + (r.materialName() != null ? r.materialName() : ""));
        case GROUP_ACTIVITY -> r.activityId();
        case GROUP_SUPERVISOR -> r.supervisorUserId();
        default -> null;
      };
      buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(r);
    }
    List<MaterialConsumptionRow> out = new ArrayList<>(buckets.size());
    for (List<MaterialConsumptionRow> bucket : buckets.values()) {
      out.add(aggregateBucket(bucket, key, from, to));
    }
    return out;
  }

  private MaterialConsumptionRow aggregateBucket(
      List<MaterialConsumptionRow> bucket, String key, LocalDate globalFrom, LocalDate globalTo) {
    MaterialConsumptionRow first = bucket.get(0);

    BigDecimal plannedQty = BigDecimal.ZERO;
    BigDecimal issuedQty = null;
    BigDecimal consumedQty = BigDecimal.ZERO;
    BigDecimal balanceQty = BigDecimal.ZERO;
    BigDecimal plannedCost = null;
    BigDecimal actualCost = null;
    BigDecimal wastageWeightedNum = BigDecimal.ZERO;
    BigDecimal wastageWeightedDen = BigDecimal.ZERO;

    List<String> aggAlerts = new ArrayList<>();
    for (MaterialConsumptionRow r : bucket) {
      plannedQty = plannedQty.add(nz(r.plannedQty()));
      if (r.issuedQty() != null) {
        issuedQty = (issuedQty == null ? BigDecimal.ZERO : issuedQty).add(r.issuedQty());
      }
      consumedQty = consumedQty.add(nz(r.consumedQty()));
      balanceQty = balanceQty.add(nz(r.balanceQty()));
      if (r.plannedCost() != null) {
        plannedCost = (plannedCost == null ? BigDecimal.ZERO : plannedCost).add(r.plannedCost());
      }
      if (r.actualCost() != null) {
        actualCost = (actualCost == null ? BigDecimal.ZERO : actualCost).add(r.actualCost());
      }
      if (r.wastagePercent() != null) {
        BigDecimal w = nz(r.consumedQty());
        wastageWeightedNum = wastageWeightedNum.add(r.wastagePercent().multiply(w));
        wastageWeightedDen = wastageWeightedDen.add(w);
      }
      for (String a : r.alerts()) if (!aggAlerts.contains(a)) aggAlerts.add(a);
    }

    BigDecimal wastagePercent = wastageWeightedDen.signum() > 0
        ? wastageWeightedNum.divide(wastageWeightedDen, 4, RoundingMode.HALF_UP)
        : null;
    BigDecimal variance = null;
    BigDecimal variancePercent = null;
    if (actualCost != null && plannedCost != null) {
      variance = actualCost.subtract(plannedCost);
      if (plannedCost.signum() != 0) {
        variancePercent = variance.divide(plannedCost, 6, RoundingMode.HALF_UP);
      }
    }
    BigDecimal unitRate = consumedQty.signum() > 0 && actualCost != null
        ? actualCost.divide(consumedQty, 6, RoundingMode.HALF_UP)
        : null;

    // Carry only the bucket-defining dimensions.
    LocalDate fromDate = GROUP_DAY.equals(key) ? first.fromDate() : globalFrom;
    LocalDate toDate = GROUP_DAY.equals(key) ? first.fromDate() : globalTo;
    UUID activityId = GROUP_ACTIVITY.equals(key) ? first.activityId() : null;
    String activityName = GROUP_ACTIVITY.equals(key) ? first.activityName() : null;
    UUID supervisorUserId = GROUP_SUPERVISOR.equals(key) ? first.supervisorUserId() : null;
    String supervisorName = GROUP_SUPERVISOR.equals(key) ? first.supervisorName() : null;
    UUID materialRateMasterId = GROUP_MATERIAL.equals(key) ? first.materialRateMasterId() : null;
    String materialName = GROUP_MATERIAL.equals(key) ? first.materialName() : null;
    String unit = GROUP_MATERIAL.equals(key) ? first.unit() : null;

    return new MaterialConsumptionRow(
        first.projectId(),
        fromDate,
        toDate,
        null,
        null,
        activityId,
        activityName,
        supervisorUserId,
        supervisorName,
        null,
        null,
        materialRateMasterId,
        materialName,
        unit,
        plannedQty,
        issuedQty,
        consumedQty,
        balanceQty,
        wastagePercent,
        unitRate,
        plannedCost,
        actualCost,
        variance,
        variancePercent,
        aggAlerts);
  }

  // ── Totals & alert counts ────────────────────────────────────────────────────────────────

  private Map<String, BigDecimal> computeTotals(List<MaterialConsumptionRow> rows) {
    BigDecimal plannedCost = BigDecimal.ZERO;
    BigDecimal actualCost = BigDecimal.ZERO;
    BigDecimal wastageNum = BigDecimal.ZERO;
    BigDecimal wastageDen = BigDecimal.ZERO;
    boolean anyPlanned = false;
    boolean anyActual = false;
    for (MaterialConsumptionRow r : rows) {
      if (r.plannedCost() != null) {
        plannedCost = plannedCost.add(r.plannedCost());
        anyPlanned = true;
      }
      if (r.actualCost() != null) {
        actualCost = actualCost.add(r.actualCost());
        anyActual = true;
      }
      if (r.wastagePercent() != null) {
        BigDecimal w = nz(r.consumedQty());
        wastageNum = wastageNum.add(r.wastagePercent().multiply(w));
        wastageDen = wastageDen.add(w);
      }
    }
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    out.put("plannedCost", anyPlanned ? plannedCost : BigDecimal.ZERO);
    out.put("actualCost", anyActual ? actualCost : BigDecimal.ZERO);
    out.put("variance", anyActual && anyPlanned
        ? actualCost.subtract(plannedCost)
        : BigDecimal.ZERO);
    out.put("wastagePercent_avg", wastageDen.signum() > 0
        ? wastageNum.divide(wastageDen, 4, RoundingMode.HALF_UP)
        : BigDecimal.ZERO);
    return out;
  }

  private Map<String, Integer> computeAlertCounts(List<MaterialConsumptionRow> rows) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (MaterialConsumptionRow r : rows) {
      for (String code : r.alerts()) {
        out.merge(code, 1, Integer::sum);
      }
    }
    return out;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** (activity_id, resource_id == material_id in the issue table) key for the issued-qty cache. */
  private record IssueKey(UUID activityId, UUID materialId) {}

  /** Planned (qty, rate) lookup result. Either may be null. */
  private record BoqPlanned(BigDecimal qty, BigDecimal rate) {}
}
