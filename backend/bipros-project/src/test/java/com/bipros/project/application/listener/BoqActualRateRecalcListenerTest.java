package com.bipros.project.application.listener;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.project.application.service.BoqActualCostQuery;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link BoqActualRateRecalcListener}. The listener delegates actual-cost to
 * {@link BoqActualCostQuery} and qty to {@link DailyProgressReportRepository}, so tests mock
 * those rather than the EntityManager.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BoqActualRateRecalcListener — recompute via shared cost query")
class BoqActualRateRecalcListenerTest {

  @Mock private BoqItemRepository boqItemRepository;
  @Mock private DailyProgressReportRepository dprRepository;
  @Mock private BoqActualCostQuery boqActualCostQuery;
  @Mock private EntityManager em;  // injected for the MCL fanout path; not exercised here

  private BoqActualRateRecalcListener listener;

  private final UUID projectId = UUID.randomUUID();
  private final UUID boqItemId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    listener = new BoqActualRateRecalcListener(boqItemRepository, dprRepository, boqActualCostQuery);
    ReflectionTestUtils.setField(listener, "em", em);
  }

  @Test
  @DisplayName("recompute calls sumQtyExecutedByBoqItemIdApproved on dprRepository")
  void recomputeUsesApprovedOnlyQtyFromRepo() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId))
        .thenReturn(new BigDecimal("100"));
    when(boqActualCostQuery.sumActualCost(projectId, boqItemId))
        .thenReturn(new BigDecimal("100000"));

    listener.onDprSubmitted(event());

    verify(dprRepository).sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId);
    // non-approved variant must not be called
    verify(dprRepository, never()).sumQtyExecutedByBoqItemId(any(), any());
  }

  @Test
  @DisplayName("recompute delegates cost to BoqActualCostQuery (not EntityManager)")
  void recomputeDelegatesCostToSharedQuery() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId))
        .thenReturn(new BigDecimal("100"));
    when(boqActualCostQuery.sumActualCost(projectId, boqItemId))
        .thenReturn(new BigDecimal("100000"));

    listener.onDprSubmitted(event());

    verify(boqActualCostQuery).sumActualCost(projectId, boqItemId);
    // EntityManager must NOT be called for cost or qty (only the MCL fanout uses it)
    verify(em, never()).createNativeQuery(any());
  }

  @Test
  @DisplayName("actualRate = cost / qty, actualAmount = qtyExecutedToDate × actualRate")
  void recomputeUsesSubContractorBackedCost() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId))
        .thenReturn(new BigDecimal("100"));
    when(boqActualCostQuery.sumActualCost(projectId, boqItemId))
        .thenReturn(new BigDecimal("127500"));   // includes sub-contractor contribution

    listener.onDprSubmitted(event());

    ArgumentCaptor<BoqItem> saved = ArgumentCaptor.forClass(BoqItem.class);
    verify(boqItemRepository).save(saved.capture());
    BoqItem out = saved.getValue();
    // 127500 / 100 = 1275.0000 (scale 4 from RATE_SCALE)
    assertThat(out.getActualRate()).isEqualByComparingTo("1275.0000");
    // BoqCalculator.recompute: actualAmount = qtyExecutedToDate × actualRate = 100 × 1275 = 127500.00
    assertThat(out.getActualAmount()).isEqualByComparingTo("127500.00");
  }

  @Test
  @DisplayName("skips recompute when qty is zero")
  void skipsWhenQtyIsZero() {
    BoqItem item = boqItem();
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId))
        .thenReturn(BigDecimal.ZERO);

    listener.onDprSubmitted(event());

    verify(boqItemRepository, never()).save(any());
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private BoqItem boqItem() {
    BoqItem item = new BoqItem();
    item.setId(boqItemId);
    item.setProjectId(projectId);
    item.setBoqQty(new BigDecimal("200"));
    item.setBoqRate(new BigDecimal("4000"));
    item.setBudgetedRate(new BigDecimal("4000"));
    item.setQtyExecutedToDate(new BigDecimal("100"));
    item.setManualOverride(Boolean.FALSE);
    return item;
  }

  private DprSubmittedEvent event() {
    return DprSubmittedEvent.withoutChildren(
        projectId, UUID.randomUUID(), java.time.LocalDate.of(2026, 5, 22),
        "Test Activity", null, new BigDecimal("100"), null, null,
        DprMutationType.CREATED, UUID.randomUUID(), boqItemId, boqItemId);
  }
}
