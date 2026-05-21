package com.bipros.activity.application.listener;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.util.AuditService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Keeps {@code Activity.percentComplete} in sync with the BOQ rollup. Fires AFTER_COMMIT on every
 * DPR mutation so the BOQ qty / actual-rate listeners have already updated
 * {@code BoqItem.qtyExecutedToDate}. For the DPR's activity, computes:
 *
 * <pre>
 *   percent = Σ boq.qtyExecutedToDate  ÷  Σ boq.boqQty   × 100
 *   (across all BOQ items referenced by DPRs of this activity)
 * </pre>
 *
 * <p>The denominator weighting is by BOQ quantity (raw qty, not amount) — keeps the math
 * consistent with the BOQ table's "% Complete" column and with the Insights tab's
 * Cumulative Progress / Output Achievement KPIs.
 *
 * <p><b>Overrides manual edits.</b> When at least one DPR for the activity points at a BOQ item,
 * the activity's {@code percentComplete} is rewritten from the BOQ rollup. Activities whose DPRs
 * have no {@code boqItemId} (legacy free-text rows) are left untouched. This is the
 * "BOQ is the source of truth" mode confirmed with the user 2026-05-20.
 *
 * <p>Runs in its own transaction so a failure here never rolls back the DPR write.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityProgressFromBoqListener {

  private final ActivityRepository activityRepository;
  private final AuditService auditService;

  @PersistenceContext
  private EntityManager em;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onDprSubmitted(DprSubmittedEvent event) {
    if (event.activityId() == null) return;
    recompute(event.activityId());
  }

  private void recompute(UUID activityId) {
    Activity activity = activityRepository.findById(activityId).orElse(null);
    if (activity == null) return;

    // Sum qty_executed_to_date and boq_qty across every BOQ item referenced by a DPR on this
    // activity. Activities with no BOQ-linked DPRs return zero rows and we skip the update.
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT COALESCE(SUM(b.qty_executed_to_date), 0), COALESCE(SUM(b.boq_qty), 0) "
                + "FROM project.boq_items b "
                + "WHERE b.id IN ("
                + "  SELECT DISTINCT d.boq_item_id FROM project.daily_progress_reports d "
                + "  WHERE d.activity_id = :activityId AND d.boq_item_id IS NOT NULL) "
                + "AND b.boq_qty IS NOT NULL AND b.boq_qty > 0")
        .setParameter("activityId", activityId)
        .getResultList();

    if (rows.isEmpty() || rows.get(0) == null) return;
    Object[] row = rows.get(0);
    BigDecimal totalExecuted = toBigDecimal(row[0]);
    BigDecimal totalBoqQty = toBigDecimal(row[1]);
    if (totalBoqQty.signum() <= 0) return;  // no BOQ-linked DPR rows yet — leave alone

    double newPct = totalExecuted
        .divide(totalBoqQty, 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .min(BigDecimal.valueOf(100))
        .doubleValue();

    Double oldPct = activity.getPercentComplete();
    if (oldPct != null && Math.abs(oldPct - newPct) < 0.01d) return;  // no meaningful change

    activity.setPercentComplete(newPct);
    activityRepository.save(activity);
    log.info("ActivityProgressFromBoqListener: activity={} percentComplete {} -> {} "
            + "(qtyExec={}, boqQty={})",
        activityId, oldPct, newPct, totalExecuted, totalBoqQty);

    auditService.logUpdate("Activity", activityId, "percentComplete", oldPct, newPct);
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return BigDecimal.ZERO;
    if (o instanceof BigDecimal b) return b;
    if (o instanceof Number n) return new BigDecimal(n.toString());
    return new BigDecimal(o.toString());
  }
}
