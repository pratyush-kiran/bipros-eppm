package com.bipros.project.application.listener;

import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.project.application.service.BoqService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Keeps {@code BoqItem.qtyExecutedToDate} in step with DPR mutations. Listens within the same
 * transaction as the DPR write (synchronous {@code @EventListener}, not
 * {@code @TransactionalEventListener}) so a BOQ recompute failure rolls the DPR write back.
 *
 * <p>Uses an approved-only from-scratch recompute via
 * {@link BoqService#recomputeExecutedQtyApproved} — no delta accumulation. Every call sets the
 * qty to the exact sum of APPROVED DPRs for that item, eliminating the double-count drift class
 * of bugs that delta add/subtract accumulated over edit/repoint/delete cycles.
 *
 * <p>Note on legacy DPRs linked only by {@code boqItemNo} (no {@code boqItemId}): if both
 * {@code boqItemId} and {@code oldBoqItemId} are null the recompute is a no-op. This is safe
 * because the data-repair tool (T1) back-linked all existing DPRs by id, and new DPRs always
 * set {@code boqItemId}. Unlinked-by-id DPRs do not affect approved qty anyway since they carry
 * only DRAFT/SUBMITTED status (pre-approval-workflow rows never received an APPROVED stamp).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DprBoqSyncListener {

  private final BoqService boqService;

  @EventListener
  public void onDprSubmitted(DprSubmittedEvent event) {
    Stream.of(event.boqItemId(), event.oldBoqItemId())
        .filter(Objects::nonNull)
        .distinct()
        .forEach(boqItemId ->
            boqService.recomputeExecutedQtyApproved(event.projectId(), boqItemId));
  }
}
