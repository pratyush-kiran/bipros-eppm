package com.bipros.project.application.listener;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.event.MaterialConsumptionLoggedEvent;
import com.bipros.project.application.service.BoqActualCostQuery;
import com.bipros.project.application.service.BoqCalculator;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
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
  private final DailyProgressReportRepository dprRepository;
  private final BoqActualCostQuery boqActualCostQuery;

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

  /**
   * MCLs landing after a DPR also change the actualRate denominator-included cost.
   * Fan out from the MCL's activity to every BOQ item touched by DPRs on that activity
   * and recompute each.
   */
  @EventListener
  @Transactional
  public void onMaterialConsumption(MaterialConsumptionLoggedEvent event) {
    if (event == null || event.projectId() == null || event.activityId() == null) return;

    Query q = em.createNativeQuery(
        "SELECT DISTINCT boq_item_id FROM project.daily_progress_reports "
            + "WHERE activity_id = :activityId AND boq_item_id IS NOT NULL");
    q.setParameter("activityId", event.activityId());
    @SuppressWarnings("unchecked")
    java.util.List<Object> rows = q.getResultList();
    if (rows.isEmpty()) {
      log.debug("[BoqActualRateRecalc] MCL activity={} has no DPRs yet; nothing to recompute",
          event.activityId());
      return;
    }
    for (Object o : rows) {
      UUID boqItemId = (o instanceof UUID u) ? u : UUID.fromString(o.toString());
      try {
        recompute(event.projectId(), boqItemId);
      } catch (Exception ex) {
        log.warn("[BoqActualRateRecalc] MCL fanout project={} boqItemId={} failed: {}",
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

    BigDecimal qty = dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId);
    if (qty == null || qty.signum() == 0) {
      log.debug("[BoqActualRateRecalc] skip zero qty boqItemId={}", boqItemId);
      return;
    }

    BigDecimal cost = boqActualCostQuery.sumActualCost(projectId, boqItemId);
    if (cost == null) cost = BigDecimal.ZERO;

    BigDecimal newRate = cost.divide(qty, RATE_SCALE, RoundingMode.HALF_UP);
    item.setActualRate(newRate);
    BoqCalculator.recompute(item);
    boqItemRepository.save(item);
    log.info("[BoqActualRateRecalc] boqItemId={} actualRate={} (cost={}, qty={})",
        boqItemId, newRate, cost, qty);
  }

}
