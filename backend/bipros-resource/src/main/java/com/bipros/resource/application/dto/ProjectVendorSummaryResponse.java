package com.bipros.resource.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProjectVendorSummaryResponse(
    UUID supplierOrganisationId,
    int materialCount,
    int receiptCount,
    BigDecimal totalValueReceived,
    LocalDate lastReceiptDate,
    List<VendorReceiptLine> receipts,
    List<VendorMaterialLine> materials
) {}
