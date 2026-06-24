package com.bipros.activity.application.listener;

import com.bipros.activity.application.percent.BoqProgressGuard;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.event.ResourceAssignmentActualsRolledUpEvent;
import com.bipros.common.util.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityUnitsPercentCompleteListener")
class ActivityUnitsPercentCompleteListenerTest {

  @Mock private ActivityRepository activityRepository;
  @Mock private PercentCompleteCalculator calculator;
  @Mock private AuditService auditService;
  @Mock private BoqProgressGuard boqProgressGuard;

  private ActivityUnitsPercentCompleteListener listener;

  private UUID activityId;
  private UUID projectId;
  private Activity activity;

  @BeforeEach
  void setUp() {
    listener = new ActivityUnitsPercentCompleteListener(activityRepository, calculator, auditService, boqProgressGuard);

    activityId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    activity = new Activity();
    activity.setId(activityId);
    activity.setProjectId(projectId);
    activity.setPercentCompleteType(PercentCompleteType.UNITS);
    activity.setPercentComplete(50.0);
    activity.setStatus(ActivityStatus.IN_PROGRESS);

    when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
    lenient().when(boqProgressGuard.isBoqDriven(activityId)).thenReturn(false);
  }

  @Nested
  @DisplayName("On resource assignment actuals rolled up")
  class OnRolledUp {

    @Test
    @DisplayName("updates percentComplete for UNITS-typed activity")
    void updatesUnitsActivity() {
      PercentCompleteCalculator.Result calcResult = new PercentCompleteCalculator.Result(
          75.0, ActivityStatus.IN_PROGRESS, null);
      when(calculator.calculate(eq(activity), eq(100.0), eq(75.0), any(LocalDate.class)))
          .thenReturn(calcResult);

      ResourceAssignmentActualsRolledUpEvent event = new ResourceAssignmentActualsRolledUpEvent(
          projectId, activityId, 100.0, 75.0);

      listener.onResourceAssignmentActualsRolledUp(event);

      assertEquals(75.0, activity.getPercentComplete());
      assertEquals(75.0, activity.getUnitsPercentComplete());
      assertEquals(ActivityStatus.IN_PROGRESS, activity.getStatus());
      verify(activityRepository).save(activity);
      verify(auditService).logUpdate("Activity", activityId, "percentComplete", 50.0, 75.0);
    }

    @Test
    @DisplayName("skips non-UNITS activities")
    void skipsNonUnits() {
      activity.setPercentCompleteType(PercentCompleteType.DURATION);

      ResourceAssignmentActualsRolledUpEvent event = new ResourceAssignmentActualsRolledUpEvent(
          projectId, activityId, 100.0, 75.0);

      listener.onResourceAssignmentActualsRolledUp(event);

      verify(calculator, never()).calculate(any(), any(), any(), any());
      verify(activityRepository, never()).save(any());
    }

    @Test
    @DisplayName("skips when activity not found")
    void skipsWhenNotFound() {
      when(activityRepository.findById(activityId)).thenReturn(Optional.empty());

      ResourceAssignmentActualsRolledUpEvent event = new ResourceAssignmentActualsRolledUpEvent(
          projectId, activityId, 100.0, 75.0);

      listener.onResourceAssignmentActualsRolledUp(event);

      verify(calculator, never()).calculate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("skips when calculator returns KEEP_PRIOR")
    void skipsOnKeepPrior() {
      when(calculator.calculate(any(), any(), any(), any()))
          .thenReturn(PercentCompleteCalculator.Result.KEEP_PRIOR);

      ResourceAssignmentActualsRolledUpEvent event = new ResourceAssignmentActualsRolledUpEvent(
          projectId, activityId, 100.0, 75.0);

      listener.onResourceAssignmentActualsRolledUp(event);

      verify(activityRepository, never()).save(any());
    }

    @Test
    @DisplayName("sets actualFinishDate when calculator forces it")
    void setsActualFinishDate() {
      LocalDate forcedFinish = LocalDate.now();
      PercentCompleteCalculator.Result calcResult = new PercentCompleteCalculator.Result(
          100.0, ActivityStatus.COMPLETED, forcedFinish);
      when(calculator.calculate(eq(activity), any(), any(), any(LocalDate.class)))
          .thenReturn(calcResult);

      ResourceAssignmentActualsRolledUpEvent event = new ResourceAssignmentActualsRolledUpEvent(
          projectId, activityId, 100.0, 100.0);

      listener.onResourceAssignmentActualsRolledUp(event);

      assertEquals(forcedFinish, activity.getActualFinishDate());
      assertEquals(ActivityStatus.COMPLETED, activity.getStatus());
    }

    @Test
    @DisplayName("audits status change")
    void auditsStatusChange() {
      PercentCompleteCalculator.Result calcResult = new PercentCompleteCalculator.Result(
          100.0, ActivityStatus.COMPLETED, null);
      when(calculator.calculate(eq(activity), any(), any(), any(LocalDate.class)))
          .thenReturn(calcResult);

      ResourceAssignmentActualsRolledUpEvent event = new ResourceAssignmentActualsRolledUpEvent(
          projectId, activityId, 100.0, 100.0);

      listener.onResourceAssignmentActualsRolledUp(event);

      verify(auditService).logUpdate("Activity", activityId, "percentComplete", 50.0, 100.0);
      verify(auditService).logUpdate("Activity", activityId, "status",
          ActivityStatus.IN_PROGRESS, ActivityStatus.COMPLETED);
    }
  }
}
