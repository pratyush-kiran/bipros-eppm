package com.bipros.hds.application.library.dto;

import com.bipros.hds.domain.enums.HdsDiscipline;

public record CreateHdsDocumentInput(String title, String shortCode, HdsDiscipline discipline,
                                     String issuingAuthority, String country, String description) {}
