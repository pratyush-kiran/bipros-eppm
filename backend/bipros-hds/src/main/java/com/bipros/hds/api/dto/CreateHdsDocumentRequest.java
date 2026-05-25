package com.bipros.hds.api.dto;

import com.bipros.hds.domain.enums.HdsDiscipline;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateHdsDocumentRequest(@NotBlank @Size(max = 255) String title,
                                       @NotBlank @Size(max = 32) String shortCode,
                                       @NotNull HdsDiscipline discipline,
                                       @Size(max = 255) String issuingAuthority,
                                       @Size(max = 2) String country,
                                       String description) {}
