package com.bipros.project.application.service;

import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.project.application.dto.DailyCostReportResponse;
import com.bipros.project.application.dto.DailyCostReportRow;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generates the Daily Cost Report (Section B of the workbook "Daily Cost Report" sheet) by joining
 * Daily Progress Report rows with the project's BOQ items. The rate lookup tries, in order:
 *
 * <ol>
 *   <li>{@code DPR.boqItemId} → exact match on {@code BoqItem.id} (Workstream B1)</li>
 *   <li>{@code DPR.boqItemNo} → exact match on {@code BoqItem.itemNo} (legacy)</li>
 *   <li>{@code DPR.activityName} substring-match against {@code BoqItem.description} (legacy)</li>
 * </ol>
 *
 * <p>When neither produces a hit, the row still appears in the report with {@code null} rates and
 * {@code null} costs — never silently replaced with zeros — so downstream reviewers can spot a
 * broken activity-to-BOQ link rather than thinking the work was free.
 *
 * <p>Workstream B3 — each row also carries {@code etc} / {@code eac}, projected from the latest
 * {@code evm.evm_calculations} snapshot for the row's activity, proportional to the row's share
 * of the activity's total actual cost (rowActualCost / activityActualCost × activityEtc).
 */
@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class DailyCostReportService {

  private static final int AMOUNT_SCALE = 2;
  private static final int RATIO_SCALE = 6;

  private final DailyProgressReportRepository dprRepository;
  private final BoqItemRepository boqItemRepository;
  private final ProjectRepository projectRepository;

  @PersistenceContext
  private EntityManager em;

  public DailyCostReportResponse generate(UUID projectId, LocalDate from, LocalDate to) {
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Project", projectId);
    }

    List<DailyProgressReport> dprRows = (from != null && to != null)
        ? dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, from, to)
        : dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId);

    return buildResponse(projectId, dprRows, from, to, null);
  }

  /**
   * Drilldown view: same row shape, restricted to DPRs that contributed to a single BoQ item's
   * actual cost in the window. Reuses {@link #generate}'s pipeline so the totals and per-row
   * fields (including ETC/EAC) stay identical to what the main report shows.
   */
  public DailyCostReportResponse drilldown(UUID projectId, UUID boqItemId,
                                           LocalDate from, LocalDate to) {
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Project", projectId);
    }
    if (boqItemId == null) {
      throw new IllegalArgumentException("boqItemId is required for drilldown");
    }
    List<DailyProgressReport> all = (from != null && to != null)
        ? dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, from, to)
        : dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId);
    List<DailyProgressReport> filtered = new ArrayList<>();
    for (DailyProgressReport d : all) {
      if (boqItemId.equals(d.getBoqItemId())) {
        filtered.add(d);
      }
    }
    return buildResponse(projectId, filtered, from, to, boqItemId);
  }

  private DailyCostReportResponse buildResponse(UUID projectId, List<DailyProgressReport> dprRows,
                                                 LocalDate from, LocalDate to,
                                                 UUID drilldownBoqItemId) {
    List<BoqItem> boqItems = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
    Map<String, BoqItem> boqByItemNo = new HashMap<>();
    Map<UUID, BoqItem> boqById = new HashMap<>();
    for (BoqItem b : boqItems) {
      if (b.getItemNo() != null) {
        boqByItemNo.put(b.getItemNo(), b);
      }
      if (b.getId() != null) {
        boqById.put(b.getId(), b);
      }
    }

    // Resolve all rows first so we know which BoQ each maps to, then compute the per-activity
    // share denominator from those resolved rows.
    record Resolved(DailyProgressReport dpr, BoqItem boq, boolean legacy,
                    BigDecimal budgetedRate, BigDecimal actualRate, BigDecimal qty,
                    BigDecimal budgetedCost, BigDecimal actualCost,
                    BigDecimal variance, BigDecimal variancePct) {}

    // Persisted DPR child-row costs per dprId — the authoritative actual-cost source. EVM AC
     // reads the same numbers (via DprActualCostLookup), so the Daily Cost Report must agree.
     // Falls back to BoqItem.actualRate × qty only when a DPR has no child line_cost at all.
    Map<UUID, BigDecimal> dprActualCostById = loadActualCostByDprId(projectId, dprRows);

    List<Resolved> resolvedRows = new ArrayList<>(dprRows.size());
    BigDecimal periodBudgeted = BigDecimal.ZERO;
    BigDecimal periodActual = BigDecimal.ZERO;
    int legacyFallbackCount = 0;
    Set<UUID> activityIds = new HashSet<>();
    Map<UUID, BigDecimal> activityActualCostSum = new HashMap<>();

    for (DailyProgressReport d : dprRows) {
      BoqMatch match = resolveBoqItem(d, boqById, boqByItemNo, boqItems);
      BoqItem b = match.item();
      if (match.viaLegacyFallback()) legacyFallbackCount++;
      BigDecimal budgetedRate = b != null ? b.getBudgetedRate() : null;
      BigDecimal qty = d.getQtyExecuted() != null ? d.getQtyExecuted() : BigDecimal.ZERO;
      BigDecimal budgetedCost = (budgetedRate == null)
          ? null
          : qty.multiply(budgetedRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

      // Prefer the persisted DPR line_cost sum; fall back to BoqItem.actualRate × qty so legacy
      // data (no DPR children) still renders. Derive actualRate from the sum when available.
      BigDecimal dprSum = dprActualCostById.get(d.getId());
      BigDecimal actualCost;
      BigDecimal actualRate;
      if (dprSum != null && dprSum.signum() != 0) {
        actualCost = dprSum.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        actualRate = qty.signum() == 0
            ? null
            : dprSum.divide(qty, AMOUNT_SCALE, RoundingMode.HALF_UP);
      } else {
        BigDecimal boqActualRate = b != null ? b.getActualRate() : null;
        actualRate = boqActualRate;
        actualCost = (boqActualRate == null)
            ? null
            : qty.multiply(boqActualRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
      }
      BigDecimal variance = null;
      BigDecimal variancePct = null;
      if (budgetedCost != null && actualCost != null) {
        variance = actualCost.subtract(budgetedCost);
        if (budgetedCost.signum() != 0) {
          variancePct = variance.divide(budgetedCost, RATIO_SCALE, RoundingMode.HALF_UP);
        }
        periodBudgeted = periodBudgeted.add(budgetedCost);
        periodActual = periodActual.add(actualCost);
      }
      resolvedRows.add(new Resolved(d, b, match.viaLegacyFallback(),
          budgetedRate, actualRate, qty, budgetedCost, actualCost, variance, variancePct));

      if (d.getActivityId() != null) {
        activityIds.add(d.getActivityId());
        if (actualCost != null) {
          activityActualCostSum.merge(d.getActivityId(), actualCost, BigDecimal::add);
        }
      }
    }

    // Fetch the latest EVM snapshot per activity in one shot. Activities without a snapshot
    // fall back to project-level CPI projection; if there is no project-level snapshot either,
    // fall back to plain (budgetedCost - actualCost) so the UI shows a sensible ETC instead
    // of "—" forever. The fallbacks are explicit (no silent zeros): we only render numbers
    // when we have either an EVM snapshot or a budgetedCost to anchor against.
    Map<UUID, ActivityEvm> evmByActivity = loadLatestEvmByActivity(projectId, activityIds);
    ProjectEvm projectEvm = loadLatestProjectLevelEvm(projectId);

    List<DailyCostReportRow> rows = new ArrayList<>(resolvedRows.size());
    for (Resolved r : resolvedRows) {
      BigDecimal etc = null;
      BigDecimal eac = null;
      UUID actId = r.dpr().getActivityId();
      if (actId != null && r.actualCost() != null) {
        ActivityEvm evm = evmByActivity.get(actId);
        BigDecimal activityActual = activityActualCostSum.getOrDefault(actId, BigDecimal.ZERO);
        if (evm != null && activityActual.signum() > 0) {
          BigDecimal share = r.actualCost().divide(activityActual, 8, RoundingMode.HALF_UP);
          if (evm.etc() != null) {
            etc = evm.etc().multiply(share).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
          }
          if (evm.eac() != null) {
            eac = evm.eac().multiply(share).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
          }
        }
      }
      // Fallback 1 — project-level EVM CPI applied to the row's remaining budget. Triggers when
      // no per-activity EVM snapshot exists (which is the common case: EvmService persists only
      // the project-level summary, not per-activity rows). Use the row's own budgetedCost / actual
      // so per-row variance still drives the projection.
      if ((etc == null || eac == null) && projectEvm != null
          && r.budgetedCost() != null && r.actualCost() != null) {
        // Clamp ETC at >= 0 — a row that is already over budget has zero work remaining
        // to forecast against; reporting a negative ETC (or negative remaining when CPI<=0)
        // is nonsense. EAC then equals actualCost (work done, nothing left to do).
        BigDecimal remaining = r.budgetedCost().subtract(r.actualCost()).max(BigDecimal.ZERO);
        BigDecimal cpi = projectEvm.cpi();
        BigDecimal etcFallback;
        if (cpi != null && cpi.signum() > 0) {
          etcFallback = remaining.divide(cpi, AMOUNT_SCALE, RoundingMode.HALF_UP);
        } else {
          etcFallback = remaining.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        if (etc == null) etc = etcFallback;
        if (eac == null) {
          eac = r.actualCost().add(etcFallback).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
      }
      // Fallback 2 — no EVM at all: plain budget remaining. Keeps the column populated so a
      // viewer can spot row-level cost overruns even before the first EVM calculation.
      // Clamp at >= 0 for the same reason as Fallback 1.
      if ((etc == null || eac == null) && r.budgetedCost() != null && r.actualCost() != null) {
        BigDecimal remaining = r.budgetedCost().subtract(r.actualCost()).max(BigDecimal.ZERO)
            .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (etc == null) etc = remaining;
        if (eac == null) {
          eac = r.actualCost().add(remaining).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
      }
      BoqItem b = r.boq();
      rows.add(new DailyCostReportRow(
          r.dpr().getId(),
          r.dpr().getReportDate(),
          r.dpr().getActivityName(),
          r.qty(),
          r.dpr().getUnit(),
          b != null ? b.getId() : null,
          b != null ? b.getItemNo() : null,
          r.budgetedRate(),
          r.actualRate(),
          r.budgetedCost(),
          r.actualCost(),
          r.variance(),
          r.variancePct(),
          etc,
          eac,
          r.dpr().getSupervisorName()));
    }

    BigDecimal periodVariance = periodActual.subtract(periodBudgeted).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    BigDecimal periodVariancePct = periodBudgeted.signum() == 0
        ? null
        : periodVariance.divide(periodBudgeted, RATIO_SCALE, RoundingMode.HALF_UP);

    if (drilldownBoqItemId != null) {
      log.info("[DailyCostReport drilldown] project={} boqItemId={} rows={} actual={}",
          projectId, drilldownBoqItemId, rows.size(), periodActual);
    } else {
      log.info("[DailyCostReport] project={}, rows={}, periodBudgeted={}, periodActual={}, variance={}",
          projectId, rows.size(), periodBudgeted, periodActual, periodVariance);
      if (legacyFallbackCount > 0) {
        log.warn(
            "[DailyCostReport] project={} resolved {} DPR row(s) to a BOQ via the legacy itemNo/"
                + "description fallback. Backfill boq_item_id on those DPRs so the fallback path "
                + "can be removed.",
            projectId, legacyFallbackCount);
      }
    }

    return new DailyCostReportResponse(
        from,
        to,
        rows,
        periodBudgeted.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
        periodActual.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
        periodVariance,
        periodVariancePct);
  }

  /** Carries both the matched BoqItem and a flag indicating the legacy-fallback path was used. */
  private record BoqMatch(BoqItem item, boolean viaLegacyFallback) {
    static BoqMatch direct(BoqItem b) { return new BoqMatch(b, false); }
    static BoqMatch legacy(BoqItem b) { return new BoqMatch(b, b != null); }
  }

  /** Lightweight projection of an EvmCalculation — only the fields the projection uses. */
  private record ActivityEvm(BigDecimal etc, BigDecimal eac) {}

  /**
   * Project-level EVM snapshot used as the fallback when no per-activity row exists. CPI is the
   * only field consumed; ETC/EAC are recomputed per row from CPI × (budget − actual).
   */
  private record ProjectEvm(BigDecimal cpi) {}

  /**
   * Resolve a BoqItem for a DPR row. Order of preference:
   * <ol>
   *   <li>{@code boq_item_id} FK — the new canonical linkage (Workstream B1).</li>
   *   <li>{@code boq_item_no} string exact match — legacy.</li>
   *   <li>{@code activity_name} substring against {@code BoqItem.description} — legacy.</li>
   * </ol>
   * The boolean on {@link BoqMatch} flags steps 2 and 3 so the caller can warn (one summary
   * WARN per generate() call) and we can later remove the fallback once the data is clean.
   */
  private BoqMatch resolveBoqItem(DailyProgressReport d, Map<UUID, BoqItem> byId,
                                  Map<String, BoqItem> byItemNo, List<BoqItem> all) {
    if (d.getBoqItemId() != null) {
      BoqItem byFk = byId.get(d.getBoqItemId());
      if (byFk != null) return BoqMatch.direct(byFk);
    }
    if (d.getBoqItemNo() != null && !d.getBoqItemNo().isBlank()) {
      BoqItem byNo = byItemNo.get(d.getBoqItemNo());
      if (byNo != null) return BoqMatch.legacy(byNo);
    }
    String activity = d.getActivityName();
    if (activity == null || activity.isBlank()) return new BoqMatch(null, false);
    String needle = activity.toLowerCase(Locale.ROOT);
    // Pick the first BOQ whose description contains (or is contained in) the activity text.
    for (BoqItem b : all) {
      if (b.getDescription() == null) continue;
      String desc = b.getDescription().toLowerCase(Locale.ROOT);
      if (desc.startsWith(needle) || desc.contains(needle) || needle.contains(desc.split("[(\\-]")[0].trim())) {
        return BoqMatch.legacy(b);
      }
    }
    return new BoqMatch(null, false);
  }

  /**
   * Fetch the latest {@code evm_calculations} row per activity in a single native query
   * (DISTINCT ON, ordered by data_date DESC) for the activities that appear on this report.
   * Crossing schemas via raw SQL avoids a maven dep on bipros-evm from this module — matches
   * the existing pattern in {@code DailyProgressReportService}.
   */
  /**
   * Sum {@code line_cost} across {@code dpr_manpower + dpr_equipment + dpr_material} per dprId
   * for the rows in this report window. Cross-schema raw SQL — matches the existing pattern in
   * {@link DprActualCostLookup}. Returns an empty map when there are no DPRs.
   */
  @SuppressWarnings("unchecked")
  private Map<UUID, BigDecimal> loadActualCostByDprId(UUID projectId, List<DailyProgressReport> dprs) {
    if (dprs == null || dprs.isEmpty() || em == null) return Map.of();
    List<UUID> dprIds = new ArrayList<>(dprs.size());
    for (DailyProgressReport d : dprs) {
      if (d.getId() != null) dprIds.add(d.getId());
    }
    if (dprIds.isEmpty()) return Map.of();
    String sql =
        "SELECT dpr_id, SUM(line_cost) FROM ("
            + "  SELECT dpr_id, COALESCE(SUM(line_cost), 0) AS line_cost FROM project.dpr_manpower "
            + "    WHERE dpr_id IN (:dprIds) GROUP BY dpr_id "
            + "  UNION ALL "
            + "  SELECT dpr_id, COALESCE(SUM(line_cost), 0) AS line_cost FROM project.dpr_equipment "
            + "    WHERE dpr_id IN (:dprIds) GROUP BY dpr_id "
            + "  UNION ALL "
            + "  SELECT dpr_id, COALESCE(SUM(line_cost), 0) AS line_cost FROM project.dpr_material "
            + "    WHERE dpr_id IN (:dprIds) GROUP BY dpr_id "
            + ") combined GROUP BY dpr_id";
    Query q = em.createNativeQuery(sql);
    q.setParameter("dprIds", dprIds);
    List<Object[]> rows = q.getResultList();
    Map<UUID, BigDecimal> out = new HashMap<>(rows.size());
    for (Object[] r : rows) {
      UUID dprId = (UUID) r[0];
      BigDecimal amount = r[1] == null ? BigDecimal.ZERO
          : (r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString()));
      out.put(dprId, amount);
    }
    return out;
  }

  /**
   * Load the most recent project-level EVM row (wbs_node_id IS NULL AND activity_id IS NULL).
   * Used as the fallback when {@link #loadLatestEvmByActivity} returns nothing for an activity —
   * the EvmService only persists a single project-level snapshot today, so per-row ETC/EAC must
   * be derived from project CPI × the row's own (budget − actual). Returns null when no EVM
   * calculation has ever been run for the project.
   */
  @SuppressWarnings("unchecked")
  private ProjectEvm loadLatestProjectLevelEvm(UUID projectId) {
    if (em == null) return null;
    String sql =
        "SELECT cost_performance_index FROM evm.evm_calculations "
            + "WHERE project_id = :projectId AND wbs_node_id IS NULL AND activity_id IS NULL "
            + "ORDER BY data_date DESC, created_at DESC LIMIT 1";
    Query q = em.createNativeQuery(sql);
    q.setParameter("projectId", projectId);
    List<Object> rows = q.getResultList();
    if (rows.isEmpty()) return null;
    Object raw = rows.get(0);
    if (raw == null) return new ProjectEvm(null);
    BigDecimal cpi = (raw instanceof BigDecimal b)
        ? b
        : (raw instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null);
    return new ProjectEvm(cpi);
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, ActivityEvm> loadLatestEvmByActivity(UUID projectId, Set<UUID> activityIds) {
    if (activityIds.isEmpty()) return Map.of();
    String sql =
        "SELECT DISTINCT ON (activity_id) activity_id, estimate_to_complete, estimate_at_completion "
            + "FROM evm.evm_calculations "
            + "WHERE project_id = :projectId AND activity_id IN (:activityIds) "
            + "ORDER BY activity_id, data_date DESC, created_at DESC";
    Query q = em.createNativeQuery(sql);
    q.setParameter("projectId", projectId);
    q.setParameter("activityIds", activityIds);
    List<Object[]> rows = q.getResultList();
    Map<UUID, ActivityEvm> out = new HashMap<>(rows.size());
    for (Object[] r : rows) {
      UUID actId = (UUID) r[0];
      BigDecimal etc = r[1] == null ? null : (BigDecimal) r[1];
      BigDecimal eac = r[2] == null ? null : (BigDecimal) r[2];
      out.put(actId, new ActivityEvm(etc, eac));
    }
    return out;
  }
}
