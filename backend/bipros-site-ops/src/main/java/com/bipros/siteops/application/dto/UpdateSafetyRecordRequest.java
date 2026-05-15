package com.bipros.siteops.application.dto;

import com.bipros.siteops.domain.model.SafetyKind;
import com.bipros.siteops.domain.model.SafetySeverity;

import java.time.Instant;

public record UpdateSafetyRecordRequest(
        SafetyKind kind,
        Instant occurredAt,
        String locationCode,
        SafetySeverity severity,
        String description,
        String immediateAction,
        String peopleInvolved
) {}
