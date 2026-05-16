package com.bipros.activity.application.service;

import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.application.percent.PercentCompleteCalculator.Result;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivityStepRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ActivityService#applyActuals} delegates % / status / forced-finish to
 * {@link PercentCompleteCalculator} (single source of truth) instead of computing inline,
 * while still owning the actualStart / actualFinish stamping that's specific to the data-date
 * sweep.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService.applyActuals delegates to PercentCompleteCalculator")
class ActivityServiceApplyActualsTest {

  @Mock private ActivityRepository activityRepository;
  @Mock private ActivitySupervisorRepository activitySupervisorRepository;
  @Mock private ActivityRelationshipRepository relationshipRepository;
  @Mock private AuditService auditService;
  @Mock private ProjectAccessGuard projectAccess;
  @Mock private ProjectRepository projectRepository;
  @Mock private PercentCompleteCalculator percentCompleteCalculator;
  @Mock private ActivityStepRepository stepRepository;

  private ActivityService service;
  private UUID projectId;
  private LocalDate dataDate;

  @BeforeEach
  void setUp() {
    service = new ActivityService(activityRepository, activitySupervisorRepository,
        relationshipRepository, auditService, projectAccess, projectRepository,
        percentCompleteCalculator, stepRepository,
        org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    projectId = UUID.randomUUID();
    dataDate = LocalDate.of(2026, 5, 5);

    lenient().when(activityRepository.save(any(Activity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private Activity newActivity(LocalDate plannedStart, LocalDate plannedFinish,
                               Double originalDuration, PercentCompleteType type) {
    Activity a = new Activity();
    a.setId(UUID.randomUUID());
    a.setProjectId(projectId);
    a.setPlannedStartDate(plannedStart);
    a.setPlannedFinishDate(plannedFinish);
    a.setOriginalDuration(originalDuration);
    a.setPercentCompleteType(type);
    a.setStatus(ActivityStatus.NOT_STARTED);
    a.setPercentComplete(0.0);
    return a;
  }

  @Test
  @DisplayName("dataDate after plannedStart with no actualStart → stamps actualStart and saves")
  void stampsActualStartWhenDataDatePassedPlanned() {
    Activity a = newActivity(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 20),
        20.0, PercentCompleteType.DURATION);
    when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a));
    when(percentCompleteCalculator.calculate(any(), eq(null), eq(null), eq(dataDate)))
        .thenReturn(new Result(20.0, ActivityStatus.IN_PROGRESS, null));

    service.applyActuals(projectId, dataDate);

    assertThat(a.getActualStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(a.getActualFinishDate()).isNull();
    assertThat(a.getPercentComplete()).isEqualTo(20.0);
    assertThat(a.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
    verify(activityRepository).save(a);
  }

  @Test
  @DisplayName("mid-flight DURATION → calculator-supplied percent persisted; no inline math")
  void midFlightPercentFromCalculator() {
    Activity a = newActivity(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 11),
        10.0, PercentCompleteType.DURATION);
    a.setActualStartDate(LocalDate.of(2026, 5, 1));
    when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a));
    // Calculator says 40% — applyActuals must trust this value, not recompute its own.
    when(percentCompleteCalculator.calculate(any(), eq(null), eq(null), eq(dataDate)))
        .thenReturn(new Result(40.0, ActivityStatus.IN_PROGRESS, null));

    service.applyActuals(projectId, dataDate);

    assertThat(a.getPercentComplete()).isEqualTo(40.0);
    assertThat(a.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
    // dataDate (2026-05-05) is not yet past plannedFinish (2026-05-11) — no auto-stamp.
    assertThat(a.getActualFinishDate()).isNull();
    verify(activityRepository).save(a);
  }

  @Test
  @DisplayName("dataDate after plannedFinish → stamps actualFinish and applies calculator's 100% / COMPLETED")
  void stampsActualFinishAndCompletes() {
    Activity a = newActivity(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4),
        3.0, PercentCompleteType.DURATION);
    when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a));
    // After actualFinishDate is stamped from plannedFinish, calculator sees the finish set
    // and returns 100/COMPLETED on the same call.
    when(percentCompleteCalculator.calculate(any(), eq(null), eq(null), eq(dataDate)))
        .thenReturn(new Result(100.0, ActivityStatus.COMPLETED, null));

    service.applyActuals(projectId, dataDate);

    assertThat(a.getActualStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(a.getActualFinishDate()).isEqualTo(LocalDate.of(2026, 5, 4));
    assertThat(a.getPercentComplete()).isEqualTo(100.0);
    assertThat(a.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
    verify(activityRepository).save(a);
  }

  @Test
  @DisplayName("originalDuration=0 → calculator returns KEEP_PRIOR; no NaN, no spurious save")
  void zeroDurationKeepsPrior() {
    Activity a = newActivity(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1),
        0.0, PercentCompleteType.DURATION);
    // dataDate (2026-05-05) is BEFORE plannedStart so no actual-date stamping happens —
    // and KEEP_PRIOR result means no % / status writes either, so save() should never fire.
    when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a));
    when(percentCompleteCalculator.calculate(any(), eq(null), eq(null), eq(dataDate)))
        .thenReturn(Result.KEEP_PRIOR);

    service.applyActuals(projectId, dataDate);

    assertThat(a.getActualStartDate()).isNull();
    assertThat(a.getActualFinishDate()).isNull();
    assertThat(a.getPercentComplete()).isEqualTo(0.0);
    assertThat(a.getStatus()).isEqualTo(ActivityStatus.NOT_STARTED);
    verify(activityRepository, never()).save(any(Activity.class));
  }

  @Test
  @DisplayName("calculator's forcedActualFinish stamps when not already set")
  void forcedActualFinishStamped() {
    // UNITS hitting 100% — calculator forces actualFinishDate via the dedicated field.
    Activity a = newActivity(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 30),
        29.0, PercentCompleteType.UNITS);
    a.setActualStartDate(LocalDate.of(2026, 5, 1));
    when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(a));
    LocalDate forced = LocalDate.of(2026, 5, 5);
    when(percentCompleteCalculator.calculate(any(), eq(null), eq(null), eq(dataDate)))
        .thenReturn(new Result(100.0, ActivityStatus.COMPLETED, forced));

    service.applyActuals(projectId, dataDate);

    assertThat(a.getActualFinishDate()).isEqualTo(forced);
    assertThat(a.getPercentComplete()).isEqualTo(100.0);
    assertThat(a.getStatus()).isEqualTo(ActivityStatus.COMPLETED);
  }

  @Test
  @DisplayName("multiple activities → calculator invoked once per activity, save only when changed")
  void perActivityDelegationAndSelectiveSave() {
    Activity changed = newActivity(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 30),
        29.0, PercentCompleteType.DURATION);
    changed.setActualStartDate(LocalDate.of(2026, 5, 1));
    Activity unchanged = newActivity(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
        29.0, PercentCompleteType.DURATION);

    when(activityRepository.findByProjectId(projectId))
        .thenReturn(List.of(changed, unchanged));
    when(percentCompleteCalculator.calculate(eq(changed), eq(null), eq(null), eq(dataDate)))
        .thenReturn(new Result(15.0, ActivityStatus.IN_PROGRESS, null));
    when(percentCompleteCalculator.calculate(eq(unchanged), eq(null), eq(null), eq(dataDate)))
        .thenReturn(Result.KEEP_PRIOR);

    service.applyActuals(projectId, dataDate);

    ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
    verify(activityRepository, times(1)).save(captor.capture());
    assertThat(captor.getValue()).isSameAs(changed);
  }
}
