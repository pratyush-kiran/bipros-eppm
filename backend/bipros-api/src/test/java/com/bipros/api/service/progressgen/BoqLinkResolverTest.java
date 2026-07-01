package com.bipros.api.service.progressgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bipros.project.application.dto.BoqItemResponse;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.application.service.BoqService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BoqService.list(UUID) does not exist; fallback uses BoqItemRepository.findByProjectId(UUID).
 * BoqItemResponse has 20 components (verified against real record).
 */
@ExtendWith(MockitoExtension.class)
class BoqLinkResolverTest {

  @Mock BoqService boqService;
  @Mock BoqItemRepository boqItemRepository;
  @InjectMocks BoqLinkResolver resolver;

  // Helper: creates a BoqItemResponse with 20 components (real arity).
  // Components: id, projectId, itemNo, description, unit, wbsNodeId,
  //             boqQty, boqRate, boqAmount, budgetedRate, budgetedAmount, qtyExecutedToDate,
  //             actualRate, actualAmount, percentComplete, costVariance, costVariancePercent,
  //             chapter, status, manualOverride
  private static BoqItemResponse boq(UUID id, String no, BigDecimal qty) {
    return new BoqItemResponse(
        id, UUID.randomUUID(), no, "desc", "Cum", UUID.randomUUID(),
        qty, BigDecimal.TEN,
        null, null, null, BigDecimal.TEN,
        null, null, null, null, null, null, null, null);
  }

  // Helper: creates a BoqItem entity usable in repo mocks (resolved via BoqItemResponse.from).
  private static BoqItem boqItem(UUID id, String no, BigDecimal qty) {
    BoqItem item = BoqItem.builder()
        .projectId(UUID.randomUUID())
        .itemNo(no)
        .description("desc")
        .unit("Cum")
        .boqQty(qty)
        .build();
    item.setId(id);
    return item;
  }

  @Test
  void prefersActivityMatchWithPositiveQty() {
    UUID pid = UUID.randomUUID(), aid = UUID.randomUUID();
    BoqItemResponse a = boq(UUID.randomUUID(), "2.1", new BigDecimal("100"));
    when(boqService.listForActivity(pid, aid)).thenReturn(List.of(a));
    var r = resolver.resolve(pid, aid, null);
    assertThat(r.boqItemId()).isEqualTo(a.id());
    assertThat(r.fallback()).isFalse();
  }

  @Test
  void fallsBackToAnyPositiveQtyWhenNoActivityMatch() {
    UUID pid = UUID.randomUUID(), aid = UUID.randomUUID();
    when(boqService.listForActivity(pid, aid)).thenReturn(List.of());
    UUID anyId = UUID.randomUUID();
    BoqItem anyItem = boqItem(anyId, "9.9", new BigDecimal("50"));
    when(boqItemRepository.findByProjectId(pid)).thenReturn(List.of(anyItem));
    var r = resolver.resolve(pid, aid, null);
    assertThat(r.boqItemId()).isEqualTo(anyId);
    assertThat(r.fallback()).isTrue();
  }
}
