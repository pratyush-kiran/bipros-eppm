package com.bipros.resource.application.dto;

import java.util.UUID;

public record SubContractorWorkTypeDto(
    UUID id,
    String name,
    String defaultUnit
) {
}
