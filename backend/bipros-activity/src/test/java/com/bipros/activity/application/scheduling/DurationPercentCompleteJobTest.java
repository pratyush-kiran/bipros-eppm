package com.bipros.activity.application.scheduling;

import com.bipros.activity.application.percent.BoqProgressGuard;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.common.scheduling.ScheduledJobLeaseRepository;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.Project;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DurationPercentCompleteJob")
class DurationPercentCompleteJobTest {

  @Mock private ActivityRepository activityRepository;
  @Mock private ProjectRepository projectRepository;
  @Mock private PercentCompleteCalculator calculator;
  @Mock private AuditService auditService;
  @Mock private ScheduledJobLeaseRepository leaseRepository;
  @Mock private BoqProgressGuard boqProgressGuard;

  private DurationPercentCompleteJob job;
  private UUID projectId;

  @BeforeEach
  void setUp() {
    job = new DurationPercentCompleteJob(activityRepository, projectRepository,
        calculator, auditService, leaseRepository, boqProgressGuard);
    projectId = UUID.randomUUID();

    // Default: lease acquired
    when(leaseRepository.tryAcquire(anyString(), any(), any(), anyString())).thenReturn(1);
    lenient().when(boqProgressGuard.isBoqDriven(any())).thenReturn(false);
  }

  @Nested
  @DisplayName("Job execution")
  class Execution {

    @Test
    @DisplayName("skips when another node holds the lease")
    void skipsWhenLeaseFails() {
      when(leaseRepository.tryAcquire(anyString(), any(), any(), anyString())).thenReturn(0);

      job.run();

      verify(activityRepository, never()).findByPercentCompleteTypeAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("skips when no in-progress DURATION activities exist")
    void skipsWhenNoActivities() {
      when(activityRepository.findByPercentCompleteTypeAndStatusIn(
          eq(PercentCompleteType.DURATION), anyList())).thenReturn(List.of());

      job.run();

      verify(calculator, never()).calculate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("updates DURATION activities with computed percent")
    void updatesDurationActivities() {
      Activity activity = createActivity(PercentCompleteType.DURATION);
      when(activityRepository.findByPercentCompleteTypeAndStatusIn(
          eq(PercentCompleteType.DURATION), anyList())).thenReturn(List.of(activity));

      Project project = new Project();
      project.setDataDate(LocalDate.of(2026, 4, 29));
      when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

      PercentCompleteCalculator.Result result = new PercentCompleteCalculator.Result(
          60.0, ActivityStatus.IN_PROGRESS, null);
      when(calculator.calculate(eq(activity), isNull(), isNull(), any(LocalDate.class)))
          .thenReturn(result);

      job.run();

      assertEquals(60.0, activity.getPercentComplete());
      assertEquals(60.0, activity.getDurationPercentComplete());
      verify(activityRepository).save(activity);
      verify(auditService).logUpdate("Activity", activity.getId(), "percentComplete", 0.0, 60.0);
    }

    @Test
    @DisplayName("uses project dataDate when available")
    void usesProjectDataDate() {
      Activity activity = createActivity(PercentCompleteType.DURATION);
      when(activityRepository.findByPercentCompleteTypeAndStatusIn(
          eq(PercentCompleteType.DURATION), anyList())).thenReturn(List.of(activity));

      Project project = new Project();
      project.setDataDate(LocalDate.of(2026, 4, 15));
      when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

      PercentCompleteCalculator.Result result = new PercentCompleteCalculator.Result(
          50.0, ActivityStatus.IN_PROGRESS, null);
      when(calculator.calculate(eq(activity), isNull(), isNull(),
          eq(LocalDate.of(2026, 4, 15)))).thenReturn(result);

      job.run();

      assertEquals(50.0, activity.getPercentComplete());
    }

    @Test
    @DisplayName("skips activity when project has no dataDate")
    void skipsWhenNoDataDate() {
      Activity activity = createActivity(PercentCompleteType.DURATION);
      when(activityRepository.findByPercentCompleteTypeAndStatusIn(
          eq(PercentCompleteType.DURATION), anyList())).thenReturn(List.of(activity));

      Project project = new Project();
      project.setDataDate(null);
      when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

      job.run();
      verify(activityRepository, never()).save(any());
    }

    @Test
    @DisplayName("skips activities where calculator returns KEEP_PRIOR")
    void skipsKeepPrior() {
      Activity activity = createActivity(PercentCompleteType.DURATION);
      when(activityRepository.findByPercentCompleteTypeAndStatusIn(
          eq(PercentCompleteType.DURATION), anyList())).thenReturn(List.of(activity));

      Project project = new Project();
      project.setDataDate(LocalDate.of(2026, 4, 29));
      when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

      when(calculator.calculate(any(), any(), any(), any()))
          .thenReturn(PercentCompleteCalculator.Result.KEEP_PRIOR);

      job.run();

      verify(activityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updates status when calculator provides different status")
    void updatesStatus() {
      Activity activity = createActivity(PercentCompleteType.DURATION);
      activity.setStatus(ActivityStatus.NOT_STARTED);
      when(activityRepository.findByPercentCompleteTypeAndStatusIn(
          eq(PercentCompleteType.DURATION), anyList())).thenReturn(List.of(activity));

      Project project = new Project();
      project.setDataDate(LocalDate.of(2026, 4, 29));
      when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

      PercentCompleteCalculator.Result result = new PercentCompleteCalculator.Result(
          100.0, ActivityStatus.COMPLETED, LocalDate.of(2026, 4, 29));
      when(calculator.calculate(any(), any(), any(), any())).thenReturn(result);

      job.run();

      assertEquals(ActivityStatus.COMPLETED, activity.getStatus());
      assertEquals(LocalDate.of(2026, 4, 29), activity.getActualFinishDate());
    }

    @Test
    @DisplayName("processes multiple activities grouped by project")
    void processesMultipleActivities() {
      Activity a1 = createActivity(PercentCompleteType.DURATION);
      Activity a2 = createActivity(PercentCompleteType.DURATION);
      a2.setProjectId(projectId); // same project
      when(activityRepository.findByPercentCompleteTypeAndStatusIn(
          eq(PercentCompleteType.DURATION), anyList())).thenReturn(List.of(a1, a2));

      Project project = new Project();
      project.setDataDate(LocalDate.of(2026, 4, 29));
      when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

      PercentCompleteCalculator.Result result = new PercentCompleteCalculator.Result(
          45.0, ActivityStatus.IN_PROGRESS, null);
      when(calculator.calculate(any(Activity.class), isNull(), isNull(), any(LocalDate.class)))
          .thenReturn(result);

      job.run();

      verify(activityRepository, times(2)).save(any(Activity.class));
      // Project loaded only once (distinct by projectId)
      verify(projectRepository, times(1)).findById(projectId);
    }
  }

  private Activity createActivity(PercentCompleteType type) {
    Activity activity = new Activity();
    activity.setId(UUID.randomUUID());
    activity.setProjectId(projectId);
    activity.setCode("ACT-TEST");
    activity.setName("Test");
    activity.setWbsNodeId(UUID.randomUUID());
    activity.setPercentCompleteType(type);
    activity.setPercentComplete(0.0);
    activity.setStatus(ActivityStatus.IN_PROGRESS);
    activity.setActualStartDate(LocalDate.of(2026, 4, 1));
    activity.setOriginalDuration(30.0);
    return activity;
  }
}
