package com.bipros.resource.application.dto;

import com.bipros.resource.domain.model.master.SubContractorMaster;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubContractorMasterResponse(
    UUID id,
    String code,
    String name,
    String location,
    String primaryContactName,
    String primaryContactNumber,
    String remarks,
    Boolean active,
    List<SubContractorWorkActivityMappingRow> workActivityMappings,
    Instant createdAt,
    Instant updatedAt) {

  public static SubContractorMasterResponse from(
      SubContractorMaster m, List<SubContractorWorkActivityMappingRow> mappings) {
    return new SubContractorMasterResponse(
        m.getId(),
        m.getCode(),
        m.getName(),
        m.getLocation(),
        m.getPrimaryContactName(),
        m.getPrimaryContactNumber(),
        m.getRemarks(),
        m.getActive(),
        mappings == null ? List.of() : mappings,
        m.getCreatedAt(),
        m.getUpdatedAt());
  }

  public static SubContractorMasterResponse from(SubContractorMaster m) {
    return from(m, List.of());
  }
}
