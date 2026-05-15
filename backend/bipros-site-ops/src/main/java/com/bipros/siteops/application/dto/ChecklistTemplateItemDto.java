package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.EvidenceType;

import java.util.UUID;

public record ChecklistTemplateItemDto(
        UUID id,
        Integer sequence,
        String label,
        boolean mandatory,
        EvidenceType evidenceType
) {}
