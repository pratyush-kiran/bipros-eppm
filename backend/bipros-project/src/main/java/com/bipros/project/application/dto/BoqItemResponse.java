package com.bipros.project.application.dto;

import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.BoqStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record BoqItemResponse(
    UUID id,
    UUID projectId,
    String itemNo,
    String description,
    String unit,
    UUID wbsNodeId,
    BigDecimal boqQty,
    BigDecimal boqRate,
    BigDecimal boqAmount,
    BigDecimal budgetedRate,
    BigDecimal budgetedAmount,
    BigDecimal qtyExecutedToDate,
    BigDecimal actualRate,
    BigDecimal actualAmount,
    BigDecimal percentComplete,
    BigDecimal costVariance,
    BigDecimal costVariancePercent,
    String chapter,
    BoqStatus status,
    Boolean manualOverride
) {
  public static BoqItemResponse from(BoqItem b) {
    return new BoqItemResponse(
        b.getId(),
        b.getProjectId(),
        b.getItemNo(),
        b.getDescription(),
        b.getUnit(),
        b.getWbsNodeId(),
        b.getBoqQty(),
        b.getBoqRate(),
        b.getBoqAmount(),
        b.getBudgetedRate(),
        b.getBudgetedAmount(),
        b.getQtyExecutedToDate(),
        b.getActualRate(),
        b.getActualAmount(),
        b.getPercentComplete(),
        b.getCostVariance(),
        b.getCostVariancePercent(),
        b.getChapter(),
        b.getStatus(),
        b.getManualOverride()
    );
  }
}
