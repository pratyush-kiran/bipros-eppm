package com.bipros.resource.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record VendorReceiptLine(
    UUID grnId,
    String grnNumber,
    LocalDate receivedDate,
    UUID materialId,
    String materialName,
    String unit,
    BigDecimal quantity,
    BigDecimal unitRate,
    BigDecimal amount,
    BigDecimal acceptedQuantity,
    BigDecimal rejectedQuantity
) {}
