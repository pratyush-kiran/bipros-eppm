package com.bipros.hds.api.dto;

import com.bipros.hds.domain.enums.HdsDiscipline;

public record UpdateHdsDocumentRequest(String title, HdsDiscipline discipline,
                                       String issuingAuthority, String country, String description) {}
