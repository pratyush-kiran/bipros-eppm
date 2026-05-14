package com.bipros.resource.application.dto.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One-shot save shape — role + variants in a single transaction. Backend swaps the variant
 * list with replace-by-key semantics: variants supplied with an {@code id} are updated,
 * variants without an id are inserted, and any existing variants whose id is NOT in the list
 * get deleted (provided they are not in use on any activity — deletion of in-use variants
 * throws VARIANT_IN_USE).
 */
public record RoleWithVariantsRequest(
    @NotBlank String code,
    @NotBlank String name,
    String description,
    @NotNull UUID resourceTypeId,
    Integer sortOrder,
    Boolean active,
    List<ManpowerVariantInput> manpowerVariants,
    List<EquipmentVariantInput> equipmentVariants,
    List<MaterialVariantInput> materialVariants) {

  public record ManpowerVariantInput(
      UUID id,
      UUID categoryId,
      UUID gradeId,
      String unit,
      BigDecimal rate,
      Boolean active) {}

  public record EquipmentVariantInput(
      UUID id,
      String make,
      String model,
      String unit,
      BigDecimal rate,
      Boolean active) {}

  public record MaterialVariantInput(
      UUID id,
      String specGrade,
      String unit,
      BigDecimal rate,
      Boolean active) {}
}
