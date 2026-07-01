package com.bipros.resource.application.dto;

import java.util.UUID;

public record VendorMaterialLine(
    UUID materialId,
    String code,
    String name,
    String category,
    String unit
) {}
