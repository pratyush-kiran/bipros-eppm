package com.bipros.project.application.service;

import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.BoqStatus;
import com.bipros.project.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Rebuilds every BOQ item's qtyExecutedToDate + actualRate from DPRs, from scratch (idempotent). */
@Service
@RequiredArgsConstructor
@Slf4j
public class BoqRebuildService {

  private final BoqItemRepository boqRepo;
  private final DailyProgressReportRepository dprRepo;
  private final DprManpowerRepository manpowerRepo;
  private final DprEquipmentRepository equipmentRepo;
  private final DprMaterialRepository materialRepo;

  @Transactional
  public int rebuildFromDprs(UUID projectId) {
    int rebuilt = 0;
    for (BoqItem item : boqRepo.findByProjectId(projectId)) {
      if (Boolean.TRUE.equals(item.getManualOverride())) continue;
      UUID boqId = item.getId();
      BigDecimal qty = nz(dprRepo.sumQtyExecutedByBoqItemId(projectId, boqId));
      BigDecimal cost = nz(manpowerRepo.sumLineCostByBoqItemId(projectId, boqId))
          .add(nz(equipmentRepo.sumLineCostByBoqItemId(projectId, boqId)))
          .add(nz(materialRepo.sumLineCostByBoqItemId(projectId, boqId)));
      BigDecimal actualRate = qty.signum() == 0
          ? BigDecimal.ZERO
          : cost.divide(qty, 4, RoundingMode.HALF_UP);

      item.setQtyExecutedToDate(qty);
      item.setActualRate(actualRate);
      BoqCalculator.recompute(item);          // reuse canonical derived-field math
      item.setStatus(autoStatus(item));
      boqRepo.save(item);
      rebuilt++;
    }
    log.info("[BoqRebuildService] project {} rebuilt {} BOQ items from DPRs", projectId, rebuilt);
    return rebuilt;
  }

  /** Mirror of BoqService.applyAutoStatus (private static there); ON_HOLD preserved. */
  private static BoqStatus autoStatus(BoqItem item) {
    if (item.getStatus() == BoqStatus.ON_HOLD) return BoqStatus.ON_HOLD;
    BigDecimal qty = nz(item.getQtyExecutedToDate());
    BigDecimal boqQty = nz(item.getBoqQty());
    if (boqQty.signum() == 0 || qty.signum() == 0) return BoqStatus.PENDING;
    if (qty.compareTo(boqQty) > 0) return BoqStatus.OVERRUN;
    if (qty.compareTo(boqQty) >= 0) return BoqStatus.COMPLETED;
    return BoqStatus.ACTIVE;
  }

  private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
