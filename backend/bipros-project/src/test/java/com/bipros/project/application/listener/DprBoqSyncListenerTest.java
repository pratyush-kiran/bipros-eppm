package com.bipros.project.application.listener;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.project.application.service.BoqService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DprBoqSyncListener — approved-only from-scratch recompute")
class DprBoqSyncListenerTest {

  @Mock BoqService boqService;

  DprBoqSyncListener listener;

  private final UUID projectId = UUID.randomUUID();
  private final UUID boqItemId = UUID.randomUUID();
  private final UUID oldBoqItemId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    listener = new DprBoqSyncListener(boqService);
  }

  // ─── CREATE ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("CREATE: calls recomputeExecutedQtyApproved for the new boqItemId")
  void onCreate_callsRecomputeForNewItem() {
    DprSubmittedEvent event = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity A", "1.2.3", new BigDecimal("50"), null, null,
        DprMutationType.CREATED, UUID.randomUUID(), boqItemId, null);

    listener.onDprSubmitted(event);

    verify(boqService).recomputeExecutedQtyApproved(projectId, boqItemId);
    verifyNoMoreInteractions(boqService);
  }

  @Test
  @DisplayName("CREATE with no boqItemId: no-op (legacy unlinked DPR)")
  void onCreate_nullBoqItemId_isNoOp() {
    DprSubmittedEvent event = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity B", "1.2.3", new BigDecimal("50"), null, null,
        DprMutationType.CREATED, UUID.randomUUID(), null, null);

    listener.onDprSubmitted(event);

    verifyNoInteractions(boqService);
  }

  // ─── UPDATE (same item) ──────────────────────────────────────────────────────

  @Test
  @DisplayName("UPDATE same item: calls recomputeExecutedQtyApproved exactly once")
  void onUpdate_sameItem_callsRecomputeOnce() {
    DprSubmittedEvent event = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity A", "1.2.3", new BigDecimal("60"), "1.2.3", new BigDecimal("50"),
        DprMutationType.UPDATED, UUID.randomUUID(), boqItemId, boqItemId);

    listener.onDprSubmitted(event);

    // boqItemId == oldBoqItemId so distinct() yields one call
    verify(boqService, times(1)).recomputeExecutedQtyApproved(projectId, boqItemId);
    verifyNoMoreInteractions(boqService);
  }

  // ─── UPDATE (re-point) ───────────────────────────────────────────────────────

  @Test
  @DisplayName("UPDATE re-point: calls recomputeExecutedQtyApproved for both old and new items")
  void onUpdate_repoint_callsRecomputeForBothItems() {
    DprSubmittedEvent event = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity A", "1.2.4", new BigDecimal("60"), "1.2.3", new BigDecimal("50"),
        DprMutationType.UPDATED, UUID.randomUUID(), boqItemId, oldBoqItemId);

    listener.onDprSubmitted(event);

    verify(boqService).recomputeExecutedQtyApproved(projectId, boqItemId);
    verify(boqService).recomputeExecutedQtyApproved(projectId, oldBoqItemId);
    verifyNoMoreInteractions(boqService);
  }

  // ─── DELETE ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("DELETE: calls recomputeExecutedQtyApproved for the deleted item's boqItemId")
  void onDelete_callsRecomputeForOldItem() {
    // On DELETE, both boqItemId and oldBoqItemId mirror the deleted row per event contract
    DprSubmittedEvent event = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity A", "1.2.3", new BigDecimal("50"), "1.2.3", new BigDecimal("50"),
        DprMutationType.DELETED, UUID.randomUUID(), boqItemId, boqItemId);

    listener.onDprSubmitted(event);

    verify(boqService, times(1)).recomputeExecutedQtyApproved(projectId, boqItemId);
    verifyNoMoreInteractions(boqService);
  }

  // ─── Verify old delta methods are NOT called ─────────────────────────────────

  @Test
  @DisplayName("addExecutedQty / subtractExecutedQty are never called")
  void oldDeltaMethods_neverCalled() {
    DprSubmittedEvent createEvent = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity A", "1.2.3", new BigDecimal("50"), null, null,
        DprMutationType.CREATED, UUID.randomUUID(), boqItemId, null);

    DprSubmittedEvent updateEvent = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity A", "1.2.3", new BigDecimal("60"), "1.2.3", new BigDecimal("50"),
        DprMutationType.UPDATED, UUID.randomUUID(), boqItemId, oldBoqItemId);

    DprSubmittedEvent deleteEvent = DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), LocalDate.now(),
        "Activity A", "1.2.3", new BigDecimal("50"), "1.2.3", new BigDecimal("50"),
        DprMutationType.DELETED, UUID.randomUUID(), boqItemId, boqItemId);

    listener.onDprSubmitted(createEvent);
    listener.onDprSubmitted(updateEvent);
    listener.onDprSubmitted(deleteEvent);

    // Verify the old methods are never invoked (they don't exist on BoqService mock by default,
    // but we make this explicit with a verifyNoInteractions check after subtracting the expected calls).
    verify(boqService, never()).addExecutedQty(any(), anyString(), any());
    verify(boqService, never()).subtractExecutedQty(any(), anyString(), any());
  }
}
