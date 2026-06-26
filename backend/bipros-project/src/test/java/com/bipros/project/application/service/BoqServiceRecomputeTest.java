package com.bipros.project.application.service;

import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BoqService.recomputeExecutedQtyApproved")
class BoqServiceRecomputeTest {

  @Mock BoqItemRepository boqItemRepository;
  @Mock ProjectRepository projectRepository;
  @Mock AuditService auditService;
  @Mock DailyProgressReportRepository dprRepository;
  @Mock EntityManager em;

  BoqService boqService;

  private final UUID projectId = UUID.randomUUID();
  private final UUID boqItemId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    boqService = new BoqService(boqItemRepository, projectRepository, auditService, dprRepository);
    ReflectionTestUtils.setField(boqService, "em", em);
  }

  @Test
  @DisplayName("sets qtyExecutedToDate to the approved sum and saves")
  void setsApprovedQtyAndSaves() {
    BoqItem item = boqItem(null);
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId))
        .thenReturn(new BigDecimal("42"));

    boqService.recomputeExecutedQtyApproved(projectId, boqItemId);

    ArgumentCaptor<BoqItem> saved = ArgumentCaptor.forClass(BoqItem.class);
    verify(boqItemRepository).save(saved.capture());
    assertThat(saved.getValue().getQtyExecutedToDate()).isEqualByComparingTo("42");
  }

  @Test
  @DisplayName("BoqCalculator.recompute is called — derived fields are updated")
  void derivedFieldsRecomputed() {
    BoqItem item = boqItem(null);
    item.setBoqQty(new BigDecimal("100"));
    item.setBoqRate(new BigDecimal("50"));
    item.setBudgetedRate(new BigDecimal("40"));
    item.setActualRate(new BigDecimal("45"));
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId))
        .thenReturn(new BigDecimal("60"));

    boqService.recomputeExecutedQtyApproved(projectId, boqItemId);

    // BoqCalculator sets percentComplete = qtyExecutedToDate / boqQty = 60/100 = 0.6
    assertThat(item.getPercentComplete()).isEqualByComparingTo("0.600000");
    // actualAmount = qtyExecutedToDate * actualRate = 60 * 45 = 2700
    assertThat(item.getActualAmount()).isEqualByComparingTo("2700.00");
  }

  @Test
  @DisplayName("treats null approved-sum (should not happen with COALESCE) as zero")
  void nullApprovedSumTreatedAsZero() {
    BoqItem item = boqItem(null);
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId)).thenReturn(null);

    boqService.recomputeExecutedQtyApproved(projectId, boqItemId);

    ArgumentCaptor<BoqItem> saved = ArgumentCaptor.forClass(BoqItem.class);
    verify(boqItemRepository).save(saved.capture());
    assertThat(saved.getValue().getQtyExecutedToDate()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("no-op when boqItemId does not exist")
  void noOpWhenItemNotFound() {
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.empty());

    boqService.recomputeExecutedQtyApproved(projectId, boqItemId);

    verify(boqItemRepository, never()).save(any());
    verify(dprRepository, never()).sumQtyExecutedByBoqItemIdApproved(any(), any());
  }

  @Test
  @DisplayName("no-op when item belongs to a different project (project mismatch)")
  void noOpWhenProjectMismatch() {
    UUID otherProject = UUID.randomUUID();
    BoqItem item = boqItem(null);
    item.setProjectId(otherProject);  // different project
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));

    boqService.recomputeExecutedQtyApproved(projectId, boqItemId);

    verify(boqItemRepository, never()).save(any());
  }

  @Test
  @DisplayName("manualOverride=TRUE item is still recomputed (existing qty path does not skip manual-override)")
  void manualOverrideItemIsNotSkipped() {
    BoqItem item = boqItem(Boolean.TRUE);
    when(boqItemRepository.findById(boqItemId)).thenReturn(Optional.of(item));
    when(dprRepository.sumQtyExecutedByBoqItemIdApproved(projectId, boqItemId))
        .thenReturn(new BigDecimal("10"));

    boqService.recomputeExecutedQtyApproved(projectId, boqItemId);

    verify(boqItemRepository).save(item);
    assertThat(item.getQtyExecutedToDate()).isEqualByComparingTo("10");
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private BoqItem boqItem(Boolean manualOverride) {
    BoqItem item = new BoqItem();
    item.setId(boqItemId);
    item.setProjectId(projectId);
    item.setItemNo("1.2.3");
    item.setDescription("Test item");
    item.setUnit("m3");
    item.setBoqQty(new BigDecimal("200"));
    item.setBoqRate(new BigDecimal("100"));
    item.setBudgetedRate(new BigDecimal("90"));
    item.setActualRate(new BigDecimal("95"));
    item.setManualOverride(manualOverride);
    return item;
  }
}
