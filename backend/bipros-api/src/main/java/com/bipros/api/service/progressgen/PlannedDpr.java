package com.bipros.api.service.progressgen;

import com.bipros.project.application.dto.DprEquipmentRow;
import com.bipros.project.application.dto.DprManpowerRow;
import com.bipros.project.application.dto.DprMaterialRow;
import com.bipros.project.application.dto.DprSubContractorRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PlannedDpr(
    LocalDate reportDate, UUID supervisorUserId, String supervisorName, BigDecimal qtyExecuted,
    List<DprManpowerRow> manpower, List<DprEquipmentRow> equipment,
    List<DprMaterialRow> materials, List<DprSubContractorRow> subContractors) {}
