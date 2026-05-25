package com.bipros.hds.application.library.dto;

import com.bipros.hds.domain.enums.HdsDiscipline;

public record UpdateHdsDocumentInput(String title, HdsDiscipline discipline,
                                     String issuingAuthority, String country, String description) {}
