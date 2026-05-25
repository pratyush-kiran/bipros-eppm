package com.bipros.hds.api.dto;

import com.bipros.hds.domain.HdsDocument;
import com.bipros.hds.domain.enums.HdsDiscipline;

import java.time.Instant;
import java.util.UUID;

public record HdsDocumentResponse(UUID id, String title, String shortCode, HdsDiscipline discipline,
                                  String issuingAuthority, String country, String description,
                                  Instant createdAt, Instant updatedAt) {
    public static HdsDocumentResponse from(HdsDocument d) {
        return new HdsDocumentResponse(d.getId(), d.getTitle(), d.getShortCode(), d.getDiscipline(),
            d.getIssuingAuthority(), d.getCountry(), d.getDescription(),
            d.getCreatedAt(), d.getUpdatedAt());
    }
}
