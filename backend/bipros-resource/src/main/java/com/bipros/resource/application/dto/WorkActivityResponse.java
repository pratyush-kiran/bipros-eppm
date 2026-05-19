package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.NormCombination;
import com.bipros.resource.domain.model.WorkActivity;

import java.time.Instant;
import java.util.UUID;

public record WorkActivityResponse(
    UUID id,
    String code,
    String name,
    String defaultUnit,
    String discipline,
    String description,
    Integer sortOrder,
    Boolean active,
    /** How Manpower + Equipment norms combine on the DPR preview. Default SERIES. */
    NormCombination normCombination,
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    String updatedBy) {

  public static WorkActivityResponse from(WorkActivity wa) {
    return new WorkActivityResponse(
        wa.getId(),
        wa.getCode(),
        wa.getName(),
        wa.getDefaultUnit(),
        wa.getDiscipline(),
        wa.getDescription(),
        wa.getSortOrder(),
        wa.getActive(),
        wa.getNormCombination() == null ? NormCombination.SERIES : wa.getNormCombination(),
        wa.getCreatedAt(),
        wa.getUpdatedAt(),
        wa.getCreatedBy(),
        wa.getUpdatedBy());
  }
}
