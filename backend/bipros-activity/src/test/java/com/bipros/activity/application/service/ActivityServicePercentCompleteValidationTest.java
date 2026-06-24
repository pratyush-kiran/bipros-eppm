package com.bipros.activity.application.service;

import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.application.percent.BoqProgressGuard;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivityStepRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService percentComplete validation")
class ActivityServicePercentCompleteValidationTest {

  @Mock private ActivityRepository activityRepository;
  @Mock private ActivitySupervisorRepository activitySupervisorRepository;
  @Mock private ActivityRelationshipRepository relationshipRepository;
  @Mock private AuditService auditService;
  @Mock private ProjectAccessGuard projectAccess;
  @Mock private ProjectRepository projectRepository;
  @Mock private PercentCompleteCalculator percentCompleteCalculator;
  @Mock private ActivityStepRepository stepRepository;
  @Mock private BoqProgressGuard boqProgressGuard;

  private ActivityService service;
  private UUID activityId;
  private Activity activity;

  @BeforeEach
  void setUp() {
    service = new ActivityService(activityRepository, activitySupervisorRepository,
        relationshipRepository, auditService, projectAccess, projectRepository,
        percentCompleteCalculator, stepRepository,
        org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
        boqProgressGuard);

    activityId = UUID.randomUUID();
    activity = new Activity();
    activity.setId(activityId);
    activity.setCode("ACT-1");
    activity.setName("Test Activity");
    activity.setProjectId(UUID.randomUUID());
    activity.setWbsNodeId(UUID.randomUUID());
    activity.setPercentComplete(0.0);
    activity.setStatus(ActivityStatus.NOT_STARTED);

    when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
    lenient().when(activityRepository.save(any(Activity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(relationshipRepository.findBySuccessorActivityId(any()))
        .thenReturn(List.of());
  }

  @Nested
  @DisplayName("updateActivity")
  class UpdateActivity {

    @Test
    @DisplayName("rejects manual % for UNITS type")
    void rejectsUnits() {
      activity.setPercentCompleteType(PercentCompleteType.UNITS);

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          50.0, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateActivity(activityId, req));
      assertEquals("PERCENT_COMPLETE_NOT_MANUAL", ex.getRuleCode());
    }

    @Test
    @DisplayName("rejects manual % for DURATION type")
    void rejectsDuration() {
      activity.setPercentCompleteType(PercentCompleteType.DURATION);

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          50.0, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateActivity(activityId, req));
      assertEquals("PERCENT_COMPLETE_NOT_MANUAL", ex.getRuleCode());
    }

    @Test
    @DisplayName("accepts manual % for PHYSICAL when no steps exist")
    void acceptsPhysicalNoSteps() {
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      when(stepRepository.countByActivityId(activityId)).thenReturn(0L);

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          42.0, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(activityId, req));
    }

    @Test
    @DisplayName("rejects manual % for PHYSICAL when steps exist")
    void rejectsPhysicalWithSteps() {
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      when(stepRepository.countByActivityId(activityId)).thenReturn(3L);

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          42.0, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateActivity(activityId, req));
      assertEquals("PERCENT_COMPLETE_OWNED_BY_STEPS", ex.getRuleCode());
    }

    @Test
    @DisplayName("evaluates guard against post-update type when type also changes")
    void evaluatesAgainstPostUpdateType() {
      activity.setPercentCompleteType(PercentCompleteType.DURATION);

      // Request tries to change type to PHYSICAL AND set % in one call
      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, PercentCompleteType.PHYSICAL, null, null, null,
          42.0, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      when(stepRepository.countByActivityId(activityId)).thenReturn(0L);

      // Should succeed — post-update type is PHYSICAL
      assertDoesNotThrow(() -> service.updateActivity(activityId, req));
    }

    @Test
    @DisplayName("rejects when type changes to UNITS and % is also set in same call")
    void rejectsWhenChangingToNonPhysical() {
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);

      // Request changes type to DURATION AND sets %
      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, PercentCompleteType.DURATION, null, null, null,
          42.0, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateActivity(activityId, req));
      assertEquals("PERCENT_COMPLETE_NOT_MANUAL", ex.getRuleCode());
    }

    @Test
    @DisplayName("allows non-percent edits regardless of type")
    void allowsNonPercentEdits() {
      activity.setPercentCompleteType(PercentCompleteType.DURATION);

      UpdateActivityRequest req = new UpdateActivityRequest(
          "New Name", null, null, null, null, null, null, null, null,
          null, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(activityId, req));
    }

    @Test
    @DisplayName("setting actualFinishDate forces 100% + COMPLETED for a DURATION activity")
    void finishDateCompletesDurationActivity() {
      activity.setPercentCompleteType(PercentCompleteType.DURATION);
      activity.setActualStartDate(LocalDate.of(2026, 4, 1));
      activity.setPercentComplete(30.0);

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          null, null, null, LocalDate.of(2026, 6, 1),
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      service.updateActivity(activityId, req);

      ArgumentCaptor<Activity> saved = ArgumentCaptor.forClass(Activity.class);
      verify(activityRepository).save(saved.capture());
      assertThat(saved.getValue().getPercentComplete()).isEqualTo(100.0);
      assertThat(saved.getValue().getStatus()).isEqualTo(ActivityStatus.COMPLETED);
    }
  }

  @Nested
  @DisplayName("updateProgress")
  class UpdateProgress {

    @Test
    @DisplayName("rejects manual % for UNITS type")
    void rejectsUnits() {
      activity.setPercentCompleteType(PercentCompleteType.UNITS);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateProgress(activityId, 50.0, null, null));
      assertEquals("PERCENT_COMPLETE_NOT_MANUAL", ex.getRuleCode());
    }

    @Test
    @DisplayName("rejects manual % for DURATION type")
    void rejectsDuration() {
      activity.setPercentCompleteType(PercentCompleteType.DURATION);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateProgress(activityId, 50.0, null, null));
      assertEquals("PERCENT_COMPLETE_NOT_MANUAL", ex.getRuleCode());
    }

    @Test
    @DisplayName("rejects manual % for PHYSICAL with steps")
    void rejectsPhysicalWithSteps() {
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      when(stepRepository.countByActivityId(activityId)).thenReturn(1L);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateProgress(activityId, 50.0, null, null));
      assertEquals("PERCENT_COMPLETE_OWNED_BY_STEPS", ex.getRuleCode());
    }

    @Test
    @DisplayName("accepts manual % for PHYSICAL without steps")
    void acceptsPhysicalNoSteps() {
      activity.setPercentCompleteType(PercentCompleteType.PHYSICAL);
      when(stepRepository.countByActivityId(activityId)).thenReturn(0L);

      assertDoesNotThrow(() -> service.updateProgress(activityId, 42.0, null, null));
    }
  }
}
