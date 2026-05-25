package com.bipros.resource.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.ActivitySubContractorAssignmentResponse;
import com.bipros.resource.application.dto.CreateActivitySubContractorAssignmentRequest;
import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.model.master.SubContractorMaster;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import com.bipros.resource.domain.repository.SubContractorWorkActivityMappingRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ActivitySubContractorAssignmentService {

  private final ActivitySubContractorAssignmentRepository assignmentRepository;
  private final SubContractorMasterRepository masterRepository;
  private final SubContractorWorkActivityMappingRepository mappingRepository;
  private final ActivityRepository activityRepository;
  private final WorkActivityRepository workActivityRepository;
  private final AuditService auditService;

  @Transactional(readOnly = true)
  public List<ActivitySubContractorAssignmentResponse> listForActivity(
      UUID projectId, UUID activityId) {
    List<ActivitySubContractorAssignment> rows =
        assignmentRepository.findByProjectIdAndActivityId(projectId, activityId);
    if (rows.isEmpty()) return List.of();

    var masterIds = rows.stream()
        .map(ActivitySubContractorAssignment::getSubContractorMasterId)
        .distinct()
        .toList();
    Map<UUID, SubContractorMaster> masterMap = masterRepository.findAllById(masterIds).stream()
        .collect(Collectors.toMap(SubContractorMaster::getId, m -> m));

    return rows.stream()
        .map(r -> ActivitySubContractorAssignmentResponse.from(
            r, masterMap.get(r.getSubContractorMasterId())))
        .toList();
  }

  public ActivitySubContractorAssignmentResponse create(
      UUID projectId, CreateActivitySubContractorAssignmentRequest req) {
    UUID activityId = UUID.fromString(req.activityId());
    UUID masterId = UUID.fromString(req.subContractorMasterId());
    UUID scWorkTypeId = UUID.fromString(req.scWorkTypeId());

    Activity activity = loadActivity(activityId);
    assertActivityEditable(activity);

    SubContractorMaster master = masterRepository.findById(masterId)
        .orElseThrow(() -> new ResourceNotFoundException("SubContractorMaster", masterId));

    var mapping = mappingRepository.findBySubContractorMasterIdAndScWorkTypeId(
        masterId, scWorkTypeId)
        .orElseThrow(() -> new BusinessRuleException(
            "SC_WORK_TYPE_NOT_FOUND",
            "No work type mapping found for sub-contractor " + master.getName()
                + " and work type " + scWorkTypeId));

    // Belt-and-braces guard: the mapping's unit must match the activity's workdone unit.
    // The unit resolves via Activity → WorkActivity.defaultUnit; the same resolution chain
    // is mirrored in the DPR form's unit-default logic.
    String activityUnit = resolveActivityUnit(activity);
    if (activityUnit != null && mapping.getUnit() != null
        && !activityUnit.equalsIgnoreCase(mapping.getUnit())) {
      throw new BusinessRuleException(
          "UNIT_MISMATCH",
          "Sub-contractor work-activity unit '" + mapping.getUnit()
              + "' does not match activity unit '" + activityUnit + "'.");
    }

    BigDecimal rate = mapping.getRatePerUnit() != null ? mapping.getRatePerUnit() : BigDecimal.ZERO;
    BigDecimal plannedUnits = req.plannedUnits() != null ? req.plannedUnits() : BigDecimal.ZERO;
    BigDecimal plannedCost = rate.multiply(plannedUnits);

    ActivitySubContractorAssignment assignment = ActivitySubContractorAssignment.builder()
        .activityId(activityId)
        .projectId(projectId)
        .subContractorMasterId(masterId)
        .scWorkTypeId(scWorkTypeId)
        .workTypeName(mapping.getWorkTypeName())
        .unit(mapping.getUnit())
        .plannedUnits(plannedUnits)
        .ratePerUnit(rate)
        .plannedCost(plannedCost)
        .build();

    ActivitySubContractorAssignment saved = assignmentRepository.save(assignment);
    log.info("Sub-contractor assignment created: id={}, activity={}, master={}, scWorkType={}",
        saved.getId(), activityId, masterId, scWorkTypeId);

    ActivitySubContractorAssignmentResponse response =
        ActivitySubContractorAssignmentResponse.from(saved, master);
    auditService.logCreate("ActivitySubContractorAssignment", saved.getId(), response);
    return response;
  }

  public void delete(UUID assignmentId) {
    ActivitySubContractorAssignment a = assignmentRepository.findById(assignmentId)
        .orElseThrow(() -> new ResourceNotFoundException("ActivitySubContractorAssignment", assignmentId));
    assertActivityEditable(loadActivity(a.getActivityId()));
    assignmentRepository.delete(a);
    auditService.logDelete("ActivitySubContractorAssignment", assignmentId);
  }

  private Activity loadActivity(UUID activityId) {
    if (activityId == null) return null;
    return activityRepository.findById(activityId).orElse(null);
  }

  private void assertActivityEditable(Activity activity) {
    if (activity == null) return;
    if (activity.getEditStatus() == ActivityEditStatus.LOCKED) {
      throw new BusinessRuleException(
          "ACTIVITY_LOCKED",
          "Activity '" + activity.getCode() + "' is locked. Unlock it before editing.");
    }
  }

  /**
   * Resolves the activity's workdone unit via {@code Activity.workActivityId →
   * WorkActivity.defaultUnit}. Returns null when the activity has no work-activity link
   * or the link doesn't resolve, so callers can skip the unit-match check in that case.
   */
  private String resolveActivityUnit(Activity activity) {
    if (activity == null || activity.getWorkActivityId() == null) return null;
    WorkActivity wa = workActivityRepository.findById(activity.getWorkActivityId()).orElse(null);
    return wa == null ? null : wa.getDefaultUnit();
  }
}
