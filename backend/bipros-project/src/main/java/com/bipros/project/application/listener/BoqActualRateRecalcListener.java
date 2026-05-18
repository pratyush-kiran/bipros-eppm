package com.bipros.project.application.listener;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.project.application.service.BoqCalculator;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Workstream B2: keep {@code BoqItem.actualRate} in step with rolled-up DPR contributions.
 *
 * <p>On every {@link DprSubmittedEvent}, the touched BOQ item id (both the new value and the
 * prior one on UPDATE / DELETE, since a re-pointed DPR changes the math on both items) is
 * recomputed as:
 * <pre>
 *   actualRate = SUM(DPR child contributions ×  assignment.effective_rate)
 *              + SUM(material_consumption_logs.line_cost where activity_id ∈ DPRs)
 *              ─────────────────────────────────────────────────────────────────
 *              SUM(daily_progress_reports.qty_executed where boq_item_id = X)
 * </pre>
 *
 * <p>Rows with {@code manualOverride = TRUE} are skipped — the user has explicitly set the rate
 * and we will not overwrite it. Items where the denominator is zero are skipped to avoid
 * divide-by-zero.
 *
 * <p>Synchronous (no {@code @TransactionalEventListener}): runs in the same TX as the DPR write
 * so a rate-recalc failure rolls the DPR back. The recompute touches a single row per item and
 * uses {@link BoqCalculator#recompute} so all derived columns (actualAmount, costVariance,
 * costVariancePercent) stay consistent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoqActualRateRecalcListener {

  private static final int RATE_SCALE = 4;

  private final BoqItemRepository boqItemRepository;

  @PersistenceContext
  private EntityManager em;

  @EventListener
  @Transactional
  public void onDprSubmitted(DprSubmittedEvent event) {
    DprMutationType type = event.eventType();
    if (type == null) return;

    Set<UUID> touched = new LinkedHashSet<>();
    if (event.boqItemId() != null) touched.add(event.boqItemId());
    if (event.oldBoqItemId() != null) touched.add(event.oldBoqItemId());
    if (touched.isEmpty()) return;

    for (UUID boqItemId : touched) {
      try {
        recompute(event.projectId(), boqItemId);
      } catch (Exception ex) {
        // One bad row mustn't take the whole DPR write with it — log and move on.
        log.warn("[BoqActualRateRecalc] project={} boqItemId={} recompute failed: {}",
            event.projectId(), boqItemId, ex.getMessage(), ex);
      }
    }
  }

  private void recompute(UUID projectId, UUID boqItemId) {
    BoqItem item = boqItemRepository.findById(boqItemId).orElse(null);
    if (item == null) return;
    if (!projectId.equals(item.getProjectId())) return;
    if (Boolean.TRUE.equals(item.getManualOverride())) {
      log.debug("[BoqActualRateRecalc] skip manual override boqItemId={}", boqItemId);
      return;
    }

    BigDecimal qty = sumQtyExecuted(boqItemId);
    if (qty == null || qty.signum() == 0) {
      log.debug("[BoqActualRateRecalc] skip zero qty boqItemId={}", boqItemId);
      return;
    }

    BigDecimal cost = sumActualCost(boqItemId);
    if (cost == null) cost = BigDecimal.ZERO;

    BigDecimal newRate = cost.divide(qty, RATE_SCALE, RoundingMode.HALF_UP);
    item.setActualRate(newRate);
    BoqCalculator.recompute(item);
    boqItemRepository.save(item);
    log.info("[BoqActualRateRecalc] boqItemId={} actualRate={} (cost={}, qty={})",
        boqItemId, newRate, cost, qty);
  }

  /**
   * Sum of {@code qty_executed} across all DPRs whose {@code boq_item_id} matches. We
   * intentionally ignore approval status here — the BOQ item-level rate is a project-wide
   * average and must reflect every recorded execution, not just the approved subset.
   */
  private BigDecimal sumQtyExecuted(UUID boqItemId) {
    Query q = em.createNativeQuery(
        "SELECT COALESCE(SUM(qty_executed), 0) FROM project.daily_progress_reports "
            + "WHERE boq_item_id = :boqItemId");
    q.setParameter("boqItemId", boqItemId);
    Object o = q.getSingleResult();
    return toBigDecimal(o);
  }

  /**
   * Total actual cost attributed to DPRs that touched this BOQ item. Mirrors the math in
   * {@code ActivityCostQueryService.sumDprContribFiltered} but pivots on {@code boq_item_id}
   * instead of {@code activity_id}, so it captures only the DPRs that explicitly point at the
   * item (not the whole activity). Includes material consumption logs whose linked activity
   * was the activity of one of those DPRs.
   */
  private BigDecimal sumActualCost(UUID boqItemId) {
    String sql =
        "SELECT COALESCE(SUM(u.contrib), 0) FROM ( "
            + "  SELECT (c.nos * COALESCE(a.effective_rate, 0))::numeric AS contrib "
            + "    FROM project.dpr_manpower c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.manpower_role_rate_id = c.manpower_role_rate_id "
            + "   WHERE d.boq_item_id = :boqItemId "
            + "  UNION ALL "
            + "  SELECT (c.nos * COALESCE(a.effective_rate, 0))::numeric "
            + "    FROM project.dpr_equipment c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.equipment_role_variant_id = c.equipment_role_variant_id "
            + "   WHERE d.boq_item_id = :boqItemId "
            + "  UNION ALL "
            + "  SELECT (c.quantity * COALESCE(a.effective_rate, 0))::numeric "
            + "    FROM project.dpr_material c "
            + "    JOIN project.daily_progress_reports d ON c.dpr_id = d.id "
            + "    LEFT JOIN resource.resource_assignments a "
            + "      ON a.activity_id = d.activity_id "
            + "     AND a.material_role_variant_id = c.material_role_variant_id "
            + "   WHERE d.boq_item_id = :boqItemId "
            + "  UNION ALL "
            + "  SELECT COALESCE(mcl.line_cost, 0)::numeric "
            + "    FROM resource.material_consumption_logs mcl "
            + "   WHERE mcl.activity_id IN ( "
            + "     SELECT DISTINCT d2.activity_id FROM project.daily_progress_reports d2 "
            + "      WHERE d2.boq_item_id = :boqItemId AND d2.activity_id IS NOT NULL) "
            + "     AND mcl.line_cost IS NOT NULL "
            + ") u";
    Query q = em.createNativeQuery(sql);
    q.setParameter("boqItemId", boqItemId);
    return toBigDecimal(q.getSingleResult());
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return BigDecimal.ZERO;
    if (o instanceof BigDecimal b) return b;
    if (o instanceof Number n) return new BigDecimal(n.toString());
    return new BigDecimal(o.toString());
  }
}
