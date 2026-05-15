package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.WorkfrontStatus;

public record UpdateWorkfrontRequest(
        String wbsCode,
        String locationCode,
        WorkfrontStatus status,
        String blockers,
        String notes
) {
}
