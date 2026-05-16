package com.bipros.project.application.dto;

import com.bipros.project.domain.model.ConcretePour;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ConcretePourResponse(
    UUID id,
    UUID projectId,
    LocalDate pourDate,
    String site,
    String plantName,
    Long chainageM,
    String structure,
    String element,
    String gradeCode,
    BigDecimal quantityM3,
    BigDecimal slumpValue,
    BigDecimal temperatureC,
    String sectionLabel,
    UUID supervisorUserId,
    UUID dprId,
    String remarks,
    Instant createdAt
) {
  public static ConcretePourResponse from(ConcretePour p) {
    return new ConcretePourResponse(
        p.getId(),
        p.getProjectId(),
        p.getPourDate(),
        p.getSite(),
        p.getPlantName(),
        p.getChainageM(),
        p.getStructure(),
        p.getElement(),
        p.getGradeCode(),
        p.getQuantityM3(),
        p.getSlumpValue(),
        p.getTemperatureC(),
        p.getSectionLabel(),
        p.getSupervisorUserId(),
        p.getDprId(),
        p.getRemarks(),
        p.getCreatedAt()
    );
  }
}
