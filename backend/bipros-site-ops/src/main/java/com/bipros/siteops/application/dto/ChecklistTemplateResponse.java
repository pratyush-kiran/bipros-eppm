package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.ChecklistType;

import java.util.List;
import java.util.UUID;

public record ChecklistTemplateResponse(
        UUID id,
        String code,
        String name,
        ChecklistType type,
        boolean active,
        List<ChecklistTemplateItemDto> items
) {}
