package com.bipros.resource.application.dto.role;

import com.bipros.resource.application.dto.ResourceRoleResponse;

import java.util.List;

public record RoleWithVariantsResponse(
    ResourceRoleResponse role,
    List<ManpowerRoleRateResponse> manpowerVariants,
    List<EquipmentRoleVariantResponse> equipmentVariants,
    List<MaterialRoleVariantResponse> materialVariants) {}
