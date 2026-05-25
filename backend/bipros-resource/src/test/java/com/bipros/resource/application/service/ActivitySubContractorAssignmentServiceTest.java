package com.bipros.resource.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.ActivitySubContractorAssignmentResponse;
import com.bipros.resource.application.dto.CreateActivitySubContractorAssignmentRequest;
import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.model.SubContractorWorkActivityMapping;
import com.bipros.resource.domain.model.master.SubContractorMaster;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import com.bipros.resource.domain.repository.SubContractorWorkActivityMappingRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivitySubContractorAssignmentService")
class ActivitySubContractorAssignmentServiceTest {

  @Mock private ActivitySubContractorAssignmentRepository assignmentRepository;
  @Mock private SubContractorMasterRepository masterRepository;
  @Mock private SubContractorWorkActivityMappingRepository mappingRepository;
  @Mock private ActivityRepository activityRepository;
  @Mock private WorkActivityRepository workActivityRepository;
  @Mock private AuditService auditService;

  @InjectMocks private ActivitySubContractorAssignmentService service;

  @Test
  @DisplayName("create throws UNIT_MISMATCH when mapping.unit != activity.unit")
  void createRejectsUnitMismatch() {
    UUID projectId = UUID.randomUUID();
    UUID activityId = UUID.randomUUID();
    UUID masterId = UUID.randomUUID();
    UUID scWorkTypeId = UUID.randomUUID();
    UUID activityWorkActivityId = UUID.randomUUID();

    // Activity → WorkActivity master with defaultUnit "Cum".
    Activity activity = new Activity();
    activity.setId(activityId);
    activity.setWorkActivityId(activityWorkActivityId);
    WorkActivity activityWa = WorkActivity.builder().defaultUnit("Cum").build();
    activityWa.setId(activityWorkActivityId);

    SubContractorMaster master = SubContractorMaster.builder()
        .code("SC1").name("Sub One").build();
    master.setId(masterId);

    // Mapping unit "Nos" -- mismatch vs activity unit "Cum".
    SubContractorWorkActivityMapping mapping = SubContractorWorkActivityMapping.builder()
        .subContractorMasterId(masterId)
        .scWorkTypeId(scWorkTypeId)
        .workTypeName("Carting")
        .unit("Nos")
        .ratePerUnit(new BigDecimal("100"))
        .build();

    when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
    when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
    when(mappingRepository.findBySubContractorMasterIdAndScWorkTypeId(masterId, scWorkTypeId))
        .thenReturn(Optional.of(mapping));
    when(workActivityRepository.findById(activityWorkActivityId))
        .thenReturn(Optional.of(activityWa));

    CreateActivitySubContractorAssignmentRequest req =
        new CreateActivitySubContractorAssignmentRequest(
            activityId.toString(),
            masterId.toString(),
            scWorkTypeId.toString(),
            new BigDecimal("10"));

    assertThatThrownBy(() -> service.create(projectId, req))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(ex -> assertThat(((BusinessRuleException) ex).getRuleCode())
            .isEqualTo("UNIT_MISMATCH"))
        .hasMessageContaining("Nos")
        .hasMessageContaining("Cum");
  }

  @Test
  @DisplayName("create succeeds when mapping.unit matches activity.unit (case-insensitive)")
  void createSucceedsWhenUnitsMatch() {
    UUID projectId = UUID.randomUUID();
    UUID activityId = UUID.randomUUID();
    UUID masterId = UUID.randomUUID();
    UUID scWorkTypeId = UUID.randomUUID();
    UUID activityWorkActivityId = UUID.randomUUID();

    Activity activity = new Activity();
    activity.setId(activityId);
    activity.setWorkActivityId(activityWorkActivityId);
    WorkActivity activityWa = WorkActivity.builder().defaultUnit("Cum").build();
    activityWa.setId(activityWorkActivityId);

    SubContractorMaster master = SubContractorMaster.builder()
        .code("SC1").name("Sub One").build();
    master.setId(masterId);

    // Mapping unit "cum" -- case-insensitive match for activity unit "Cum".
    SubContractorWorkActivityMapping mapping = SubContractorWorkActivityMapping.builder()
        .subContractorMasterId(masterId)
        .scWorkTypeId(scWorkTypeId)
        .workTypeName("Carting")
        .unit("cum")
        .ratePerUnit(new BigDecimal("150"))
        .build();

    when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
    when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
    when(mappingRepository.findBySubContractorMasterIdAndScWorkTypeId(masterId, scWorkTypeId))
        .thenReturn(Optional.of(mapping));
    when(workActivityRepository.findById(activityWorkActivityId))
        .thenReturn(Optional.of(activityWa));
    when(assignmentRepository.save(any(ActivitySubContractorAssignment.class)))
        .thenAnswer(inv -> {
          ActivitySubContractorAssignment toSave = inv.getArgument(0);
          toSave.setId(UUID.randomUUID());
          return toSave;
        });

    CreateActivitySubContractorAssignmentRequest req =
        new CreateActivitySubContractorAssignmentRequest(
            activityId.toString(),
            masterId.toString(),
            scWorkTypeId.toString(),
            new BigDecimal("10"));

    ActivitySubContractorAssignmentResponse response = service.create(projectId, req);

    ArgumentCaptor<ActivitySubContractorAssignment> captor =
        ArgumentCaptor.forClass(ActivitySubContractorAssignment.class);
    verify(assignmentRepository, times(1)).save(captor.capture());
    ActivitySubContractorAssignment saved = captor.getValue();
    assertThat(saved.getPlannedUnits()).isEqualByComparingTo(new BigDecimal("10"));
    assertThat(saved.getRatePerUnit()).isEqualByComparingTo(new BigDecimal("150"));
    assertThat(saved.getPlannedCost()).isEqualByComparingTo(new BigDecimal("1500"));
    assertThat(response).isNotNull();
  }

  @Test
  @DisplayName("create skips unit-match check when activity unit cannot be resolved")
  void createSkipsUnitCheckWhenActivityUnitIsNull() {
    UUID projectId = UUID.randomUUID();
    UUID activityId = UUID.randomUUID();
    UUID masterId = UUID.randomUUID();
    UUID scWorkTypeId = UUID.randomUUID();
    UUID activityWorkActivityId = UUID.randomUUID();

    Activity activity = new Activity();
    activity.setId(activityId);
    activity.setWorkActivityId(activityWorkActivityId);

    SubContractorMaster master = SubContractorMaster.builder()
        .code("SC1").name("Sub One").build();
    master.setId(masterId);

    // Mapping has a unit but activity's WorkActivity can't be resolved -> skip check.
    SubContractorWorkActivityMapping mapping = SubContractorWorkActivityMapping.builder()
        .subContractorMasterId(masterId)
        .scWorkTypeId(scWorkTypeId)
        .workTypeName("Carting")
        .unit("Nos")
        .ratePerUnit(new BigDecimal("100"))
        .build();

    when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
    when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
    when(mappingRepository.findBySubContractorMasterIdAndScWorkTypeId(masterId, scWorkTypeId))
        .thenReturn(Optional.of(mapping));
    // WorkActivity lookup returns empty -> resolved unit is null -> check is short-circuited.
    when(workActivityRepository.findById(activityWorkActivityId))
        .thenReturn(Optional.empty());
    when(assignmentRepository.save(any(ActivitySubContractorAssignment.class)))
        .thenAnswer(inv -> {
          ActivitySubContractorAssignment toSave = inv.getArgument(0);
          toSave.setId(UUID.randomUUID());
          return toSave;
        });

    CreateActivitySubContractorAssignmentRequest req =
        new CreateActivitySubContractorAssignmentRequest(
            activityId.toString(),
            masterId.toString(),
            scWorkTypeId.toString(),
            new BigDecimal("5"));

    assertThatCode(() -> service.create(projectId, req)).doesNotThrowAnyException();
    verify(assignmentRepository, times(1)).save(any(ActivitySubContractorAssignment.class));
  }
}
