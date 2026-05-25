package com.bipros.resource.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SubContractorMasterWithMappingsRequest(
    @NotBlank(message = "Code is required")
    @Size(max = 50, message = "Code must be at most 50 characters")
    String code,

    @NotBlank(message = "Name is required")
    @Size(max = 200, message = "Name must be at most 200 characters")
    String name,

    @Size(max = 300, message = "Location must be at most 300 characters")
    String location,

    @Size(max = 150, message = "Primary contact name must be at most 150 characters")
    String primaryContactName,

    @Size(max = 30, message = "Primary contact number must be at most 30 characters")
    String primaryContactNumber,

    @Size(max = 1000, message = "Remarks must be at most 1000 characters")
    String remarks,

    Boolean active,

    @Valid
    List<SubContractorWorkActivityMappingRow> workActivityMappings
) {
}
