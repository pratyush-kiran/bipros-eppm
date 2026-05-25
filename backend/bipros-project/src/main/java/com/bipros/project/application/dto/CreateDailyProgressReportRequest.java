package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.SafetyIncidentType;
import com.bipros.project.domain.model.Shift;
import com.bipros.project.domain.model.Side;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record CreateDailyProgressReportRequest(
    @NotNull @PastOrPresent(message = "reportDate must not be in the future") LocalDate reportDate,

    /**
     * Optional FK to {@code public.users.id} for the supervisor (Phase 4.1 cutover). When set,
     * the service snapshots the user's current display name into {@code supervisorName}; when
     * null, {@code supervisorName} is taken verbatim (free-text "Other" entry).
     */
    UUID supervisorUserId,

    @NotBlank String supervisorName,

    @PositiveOrZero Long chainageFromM,
    @PositiveOrZero Long chainageToM,

    UUID activityId,

    @NotBlank String activityName,

    UUID wbsNodeId,

    /** New canonical FK to {@code project.boq_items.id}. Preferred over {@code boqItemNo}. */
    UUID boqItemId,

    /** Legacy string match; supply {@code boqItemId} instead on new clients. */
    String boqItemNo,

    @NotBlank String unit,

    @NotNull @Positive BigDecimal qtyExecuted,

    String weatherCondition,

    String remarks,

    // --- Multi-section enrichment fields (all optional) ---

    Side side,
    String landmark,
    LocalTime startTime,
    LocalTime endTime,
    Shift shift,
    DprApprovalStatus approvalStatus,
    String contractorName,
    String delayReason,
    String safetyObservation,
    SafetyIncidentType safetyIncidentType,

    @Valid List<DprManpowerRow> manpower,
    @Valid List<DprEquipmentRow> equipment,
    @Valid List<DprMaterialRow> materials,
    @Valid List<DprSubContractorRow> subContractors,
    @Valid List<DprIssueRow> issues
) {}
