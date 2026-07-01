package com.bipros.project.application.service;

import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BoqRebuildServiceTest {

  @Mock BoqItemRepository boqRepo;
  @Mock DailyProgressReportRepository dprRepo;
  @Mock BoqActualCostQuery boqActualCostQuery;

  BoqRebuildService service;

  @BeforeEach
  void setUp() {
    service = new BoqRebuildService(boqRepo, dprRepo, boqActualCostQuery);
  }

  @Test
  void rebuildsQtyAndActualRateFromScratch() {
    UUID projectId = UUID.randomUUID();
    UUID boqId = UUID.randomUUID();
    BoqItem item = new BoqItem();
    item.setId(boqId); item.setProjectId(projectId); item.setItemNo("2.3.6(i)");
    item.setBoqQty(new BigDecimal("1000")); item.setBoqRate(new BigDecimal("92"));
    item.setBudgetedRate(new BigDecimal("80")); item.setManualOverride(null);

    when(boqRepo.findByProjectId(projectId)).thenReturn(List.of(item));
    when(dprRepo.sumQtyExecutedByBoqItemIdApproved(projectId, boqId)).thenReturn(new BigDecimal("200"));
    // shared cost query covers MP + EQ + MAT + SC + MCL (2000 = 1500 MP + 500 EQ + 0 MAT)
    when(boqActualCostQuery.sumActualCost(projectId, boqId)).thenReturn(new BigDecimal("2000"));

    int n = service.rebuildFromDprs(projectId);

    assertThat(n).isEqualTo(1);
    assertThat(item.getQtyExecutedToDate()).isEqualByComparingTo("200");
    assertThat(item.getActualRate()).isEqualByComparingTo("10"); // 2000/200
    assertThat(item.getActualAmount()).isEqualByComparingTo("2000"); // recomputed by BoqCalculator
    verify(boqRepo).save(item);
    // Assert the shared cost query and approved-only qty method are invoked
    verify(dprRepo).sumQtyExecutedByBoqItemIdApproved(projectId, boqId);
    verify(boqActualCostQuery).sumActualCost(projectId, boqId);
    // Non-approved qty variant must NOT be called
    verify(dprRepo, never()).sumQtyExecutedByBoqItemId(any(), any());
  }

  @Test
  void skipsManualOverrideItems() {
    UUID projectId = UUID.randomUUID();
    BoqItem item = new BoqItem();
    item.setId(UUID.randomUUID()); item.setProjectId(projectId); item.setManualOverride(Boolean.TRUE);
    when(boqRepo.findByProjectId(projectId)).thenReturn(List.of(item));

    int n = service.rebuildFromDprs(projectId);

    assertThat(n).isZero();
    verify(boqRepo, never()).save(any());
  }
}
