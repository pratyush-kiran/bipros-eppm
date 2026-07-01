package com.bipros.api.service.progressgen;

import com.bipros.project.application.dto.BoqItemResponse;
import com.bipros.project.application.service.BoqService;
import com.bipros.project.domain.repository.BoqItemRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the BOQ item to attach to an activity's generated DPRs. Best activity-name match
 * (preferring positive boqQty); if none, picks ANY project BOQ item (same WBS first, positive qty).
 *
 * Note: BoqService has no list(UUID) method; fallback queries BoqItemRepository directly.
 */
@Component
@RequiredArgsConstructor
public class BoqLinkResolver {

  private final BoqService boqService;
  private final BoqItemRepository boqItemRepository;

  public record Resolved(UUID boqItemId, String itemNo, BigDecimal boqQty,
                         BigDecimal qtyExecutedToDate, boolean fallback) {}

  public Resolved resolve(UUID projectId, UUID activityId, UUID activityWbsNodeId) {
    List<BoqItemResponse> matches = boqService.listForActivity(projectId, activityId);
    BoqItemResponse best = pick(matches, activityWbsNodeId);
    if (best != null) return toResolved(best, false);
    // BoqService.list(UUID) does not exist — query the repo and map
    List<BoqItemResponse> all = boqItemRepository.findByProjectId(projectId)
        .stream().map(BoqItemResponse::from).toList();
    BoqItemResponse any = pick(all, activityWbsNodeId);
    if (any == null) return null;          // project has no BOQ items at all
    return toResolved(any, true);
  }

  private BoqItemResponse pick(List<BoqItemResponse> items, UUID wbsNodeId) {
    return items.stream()
        .filter(b -> b.boqQty() != null && b.boqQty().signum() > 0)
        .max(Comparator
            .comparingInt((BoqItemResponse b) -> Objects.equals(b.wbsNodeId(), wbsNodeId) ? 1 : 0)
            .thenComparing(b -> b.boqQty()))
        .or(() -> items.stream().findFirst())   // last resort: anything, even qty 0
        .orElse(null);
  }

  private Resolved toResolved(BoqItemResponse b, boolean fallback) {
    BigDecimal exec = b.qtyExecutedToDate() == null ? BigDecimal.ZERO : b.qtyExecutedToDate();
    return new Resolved(b.id(), b.itemNo(), b.boqQty(), exec, fallback);
  }
}
