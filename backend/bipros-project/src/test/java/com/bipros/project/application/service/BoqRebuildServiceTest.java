package com.bipros.project.application.service;

import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.*;
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
  @Mock DprManpowerRepository manpowerRepo;
  @Mock DprEquipmentRepository equipmentRepo;
  @Mock DprMaterialRepository materialRepo;

  BoqRebuildService service;

  @BeforeEach
  void setUp() {
    service = new BoqRebuildService(boqRepo, dprRepo, manpowerRepo, equipmentRepo, materialRepo);
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
    when(manpowerRepo.sumLineCostByBoqItemIdApproved(projectId, boqId)).thenReturn(new BigDecimal("1500"));
    when(equipmentRepo.sumLineCostByBoqItemIdApproved(projectId, boqId)).thenReturn(new BigDecimal("500"));
    when(materialRepo.sumLineCostByBoqItemIdApproved(projectId, boqId)).thenReturn(BigDecimal.ZERO);

    int n = service.rebuildFromDprs(projectId);

    assertThat(n).isEqualTo(1);
    assertThat(item.getQtyExecutedToDate()).isEqualByComparingTo("200");
    assertThat(item.getActualRate()).isEqualByComparingTo("10"); // (1500+500+0)/200
    assertThat(item.getActualAmount()).isEqualByComparingTo("2000"); // recomputed by BoqCalculator
    verify(boqRepo).save(item);
    // Assert the approved-only repo methods are invoked (Deliverable 3 assertion)
    verify(dprRepo).sumQtyExecutedByBoqItemIdApproved(projectId, boqId);
    verify(manpowerRepo).sumLineCostByBoqItemIdApproved(projectId, boqId);
    verify(equipmentRepo).sumLineCostByBoqItemIdApproved(projectId, boqId);
    verify(materialRepo).sumLineCostByBoqItemIdApproved(projectId, boqId);
    // Non-approved variants must NOT be called
    verify(dprRepo, never()).sumQtyExecutedByBoqItemId(any(), any());
    verify(manpowerRepo, never()).sumLineCostByBoqItemId(any(), any());
    verify(equipmentRepo, never()).sumLineCostByBoqItemId(any(), any());
    verify(materialRepo, never()).sumLineCostByBoqItemId(any(), any());
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
