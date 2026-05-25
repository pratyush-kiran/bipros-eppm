package com.bipros.project.application.dto;

import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.SafetyIncidentType;
import com.bipros.project.domain.model.Shift;
import com.bipros.project.domain.model.Side;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record DailyProgressReportResponse(
    UUID id,
    UUID projectId,
    LocalDate reportDate,
    UUID supervisorUserId,
    String supervisorName,
    Long chainageFromM,
    Long chainageToM,
    UUID activityId,
    String activityName,
    UUID wbsNodeId,
    UUID boqItemId,
    String boqItemNo,
    String unit,
    BigDecimal qtyExecuted,
    BigDecimal cumulativeQty,
    String weatherCondition,
    String remarks,

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

    List<DprManpowerRow> manpower,
    List<DprEquipmentRow> equipment,
    List<DprMaterialRow> materials,
    List<DprSubContractorRow> subContractors,
    List<DprAttachmentResponse> attachments,
    List<DprIssueRow> issues,
    List<String> warnings
) {
  /**
   * Convenience constructor for legacy call sites (audit logging) that don't have computed
   * cumulative, child rows, or attachments on hand. Sets cumulativeQty to the row's qtyExecuted
   * as a placeholder; the list endpoint always computes the real cumulative via the service.
   */
  public static DailyProgressReportResponse from(DailyProgressReport r) {
    return from(r, r.getQtyExecuted(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public static DailyProgressReportResponse from(DailyProgressReport r, BigDecimal cumulativeQty) {
    return from(r, cumulativeQty, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /** Read-path overload — children + attachments + issues, no warnings. */
  public static DailyProgressReportResponse from(
      DailyProgressReport r,
      BigDecimal cumulativeQty,
      List<DprManpowerRow> manpower,
      List<DprEquipmentRow> equipment,
      List<DprMaterialRow> materials,
      List<DprSubContractorRow> subContractors,
      List<DprAttachmentResponse> attachments,
      List<DprIssueRow> issues) {
    return from(r, cumulativeQty, manpower, equipment, materials, subContractors, attachments, issues, List.of());
  }

  public static DailyProgressReportResponse from(
      DailyProgressReport r,
      BigDecimal cumulativeQty,
      List<DprManpowerRow> manpower,
      List<DprEquipmentRow> equipment,
      List<DprMaterialRow> materials,
      List<DprSubContractorRow> subContractors,
      List<DprAttachmentResponse> attachments,
      List<DprIssueRow> issues,
      List<String> warnings) {
    return new DailyProgressReportResponse(
        r.getId(),
        r.getProjectId(),
        r.getReportDate(),
        r.getSupervisorUserId(),
        r.getSupervisorName(),
        r.getChainageFromM(),
        r.getChainageToM(),
        r.getActivityId(),
        r.getActivityName(),
        r.getWbsNodeId(),
        r.getBoqItemId(),
        r.getBoqItemNo(),
        r.getUnit(),
        r.getQtyExecuted(),
        cumulativeQty,
        r.getWeatherCondition(),
        r.getRemarks(),
        r.getSide(),
        r.getLandmark(),
        r.getStartTime(),
        r.getEndTime(),
        r.getShift(),
        r.getApprovalStatus(),
        r.getContractorName(),
        r.getDelayReason(),
        r.getSafetyObservation(),
        r.getSafetyIncidentType(),
        manpower,
        equipment,
        materials,
        subContractors,
        attachments,
        issues,
        warnings
    );
  }
}
