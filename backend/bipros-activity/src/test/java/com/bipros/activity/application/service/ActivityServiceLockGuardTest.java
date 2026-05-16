package com.bipros.activity.application.service;

import com.bipros.activity.application.dto.ActivityResponse;
import com.bipros.activity.application.dto.SetSupervisorRequest;
import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.application.percent.PercentCompleteCalculator.Result;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the editStatus (DRAFT/LOCKED) lifecycle on Activity:
 * <ul>
 *   <li>Locked activities reject mutating operations with ACTIVITY_LOCKED.</li>
 *   <li>Draft activities allow the same operations.</li>
 *   <li>lockActivity / unlockActivity flip the field idempotently.</li>
 *   <li>applyActuals skips LOCKED activities mid-batch.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService editStatus lock guard")
class ActivityServiceLockGuardTest {

  @Mock private ActivityRepository activityRepository;
  @Mock private ActivitySupervisorRepository activitySupervisorRepository;
  @Mock private ActivityRelationshipRepository relationshipRepository;
  @Mock private AuditService auditService;
  @Mock private ProjectAccessGuard projectAccess;
  @Mock private ProjectRepository projectRepository;
  @Mock private PercentCompleteCalculator percentCompleteCalculator;
  @Mock private ActivityStepRepository stepRepository;

  private ActivityService service;
  private UUID activityId;
  private UUID projectId;

  @BeforeEach
  void setUp() {
    service = new ActivityService(activityRepository, activitySupervisorRepository,
        relationshipRepository, auditService, projectAccess, projectRepository,
        percentCompleteCalculator, stepRepository, mock(ApplicationEventPublisher.class));
    lenient().when(activitySupervisorRepository.findByActivityId(any())).thenReturn(List.of());
    lenient().when(activitySupervisorRepository.findByActivityIdIn(any())).thenReturn(List.of());

    activityId = UUID.randomUUID();
    projectId = UUID.randomUUID();

    lenient().when(activityRepository.save(any(Activity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(relationshipRepository.findBySuccessorActivityId(any()))
        .thenReturn(List.of());
    lenient().when(relationshipRepository.findByPredecessorActivityId(any()))
        .thenReturn(List.of());
  }

  /** Build a fresh activity scaffold. Caller sets editStatus / pct type as needed. */
  private Activity newActivity(ActivityEditStatus editStatus) {
    Activity a = new Activity();
    a.setId(activityId);
    a.setCode("ACT-LOCK");
    a.setName("Lockable activity");
    a.setProjectId(projectId);
    a.setWbsNodeId(UUID.randomUUID());
    a.setPercentComplete(0.0);
    a.setStatus(ActivityStatus.NOT_STARTED);
    a.setPercentCompleteType(PercentCompleteType.PHYSICAL);
    a.setEditStatus(editStatus);
    return a;
  }

  private UpdateActivityRequest renameOnlyRequest() {
    return new UpdateActivityRequest(
        "Renamed", null, null, null, null, null, null, null, null,
        null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  // ─── LOCKED activities reject mutators ────────────────────────────────────────────

  @Nested
  @DisplayName("LockedActivity")
  class LockedActivity {

    @BeforeEach
    void lockIt() {
      Activity locked = newActivity(ActivityEditStatus.LOCKED);
      when(activityRepository.findById(activityId)).thenReturn(Optional.of(locked));
    }

    @Test
    @DisplayName("updateActivity throws ACTIVITY_LOCKED when editStatus=LOCKED")
    void updateActivityRejected() {
      assertThatThrownBy(() -> service.updateActivity(activityId, renameOnlyRequest()))
          .isInstanceOf(BusinessRuleException.class)
          .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
              .isEqualTo("ACTIVITY_LOCKED"));
      verify(activityRepository, never()).save(any(Activity.class));
    }

    @Test
    @DisplayName("updateProgress throws ACTIVITY_LOCKED when editStatus=LOCKED")
    void updateProgressRejected() {
      assertThatThrownBy(() -> service.updateProgress(activityId, 25.0, null, null))
          .isInstanceOf(BusinessRuleException.class)
          .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
              .isEqualTo("ACTIVITY_LOCKED"));
      verify(activityRepository, never()).save(any(Activity.class));
    }

    @Test
    @DisplayName("setSupervisor throws ACTIVITY_LOCKED when editStatus=LOCKED")
    void setSupervisorRejected() {
      SetSupervisorRequest req = new SetSupervisorRequest(UUID.randomUUID(), "Some Supervisor");
      assertThatThrownBy(() -> service.setSupervisor(activityId, req))
          .isInstanceOf(BusinessRuleException.class)
          .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
              .isEqualTo("ACTIVITY_LOCKED"));
      verify(activityRepository, never()).save(any(Activity.class));
    }

    @Test
    @DisplayName("deleteActivity throws ACTIVITY_LOCKED when editStatus=LOCKED")
    void deleteActivityRejected() {
      assertThatThrownBy(() -> service.deleteActivity(activityId))
          .isInstanceOf(BusinessRuleException.class)
          .satisfies(t -> assertThat(((BusinessRuleException) t).getRuleCode())
              .isEqualTo("ACTIVITY_LOCKED"));
      verify(activityRepository, never()).deleteById(any());
    }
  }

  // ─── DRAFT activities allow the same mutators ─────────────────────────────────────

  @Nested
  @DisplayName("DraftActivity")
  class DraftActivity {

    @BeforeEach
    void draftIt() {
      Activity draft = newActivity(ActivityEditStatus.DRAFT);
      when(activityRepository.findById(activityId)).thenReturn(Optional.of(draft));
      // updateProgress -> stepRepository.countByActivityId is called for the PHYSICAL guard
      lenient().when(stepRepository.countByActivityId(activityId)).thenReturn(0L);
    }

    @Test
    @DisplayName("updateActivity succeeds when editStatus=DRAFT")
    void updateActivityAllowed() {
      assertThatCode(() -> service.updateActivity(activityId, renameOnlyRequest()))
          .doesNotThrowAnyException();
      verify(activityRepository).save(any(Activity.class));
    }

    @Test
    @DisplayName("updateProgress succeeds when editStatus=DRAFT")
    void updateProgressAllowed() {
      assertThatCode(() -> service.updateProgress(activityId, 42.0, null, null))
          .doesNotThrowAnyException();
      verify(activityRepository).save(any(Activity.class));
    }

    @Test
    @DisplayName("setSupervisor succeeds when editStatus=DRAFT")
    void setSupervisorAllowed() {
      SetSupervisorRequest req = new SetSupervisorRequest(UUID.randomUUID(), "Some Supervisor");
      assertThatCode(() -> service.setSupervisor(activityId, req))
          .doesNotThrowAnyException();
      verify(activityRepository).save(any(Activity.class));
    }

    @Test
    @DisplayName("deleteActivity succeeds when editStatus=DRAFT")
    void deleteActivityAllowed() {
      assertThatCode(() -> service.deleteActivity(activityId))
          .doesNotThrowAnyException();
      verify(activityRepository).deleteById(activityId);
    }
  }

  // ─── lockActivity / unlockActivity flow ───────────────────────────────────────────

  @Nested
  @DisplayName("LockFlow")
  class LockFlow {

    @Test
    @DisplayName("lockActivity sets editStatus to LOCKED and returns updated response")
    void lockSetsLocked() {
      Activity draft = newActivity(ActivityEditStatus.DRAFT);
      when(activityRepository.findById(activityId)).thenReturn(Optional.of(draft));

      ActivityResponse resp = service.lockActivity(activityId);

      assertThat(draft.getEditStatus()).isEqualTo(ActivityEditStatus.LOCKED);
      assertThat(resp).isNotNull();
      verify(activityRepository, times(1)).save(draft);
      verify(auditService).logUpdate(eq("Activity"), eq(activityId), eq("editStatus"),
          eq(ActivityEditStatus.DRAFT), eq(ActivityEditStatus.LOCKED));
    }

    @Test
    @DisplayName("lockActivity is idempotent when already LOCKED (no save, no audit)")
    void lockIdempotent() {
      Activity locked = newActivity(ActivityEditStatus.LOCKED);
      when(activityRepository.findById(activityId)).thenReturn(Optional.of(locked));

      service.lockActivity(activityId);

      // Already locked → no second flip; no save call, no audit entry.
      verify(activityRepository, never()).save(any(Activity.class));
      verify(auditService, never()).logUpdate(eq("Activity"), eq(activityId), eq("editStatus"),
          any(), any());
      assertThat(locked.getEditStatus()).isEqualTo(ActivityEditStatus.LOCKED);
    }

    @Test
    @DisplayName("unlockActivity sets editStatus back to DRAFT")
    void unlockSetsDraft() {
      Activity locked = newActivity(ActivityEditStatus.LOCKED);
      when(activityRepository.findById(activityId)).thenReturn(Optional.of(locked));

      ActivityResponse resp = service.unlockActivity(activityId);

      assertThat(locked.getEditStatus()).isEqualTo(ActivityEditStatus.DRAFT);
      assertThat(resp).isNotNull();
      verify(activityRepository, times(1)).save(locked);
      verify(auditService).logUpdate(eq("Activity"), eq(activityId), eq("editStatus"),
          eq(ActivityEditStatus.LOCKED), eq(ActivityEditStatus.DRAFT));
    }

    @Test
    @DisplayName("unlockActivity is idempotent when already DRAFT (no save, no audit)")
    void unlockIdempotent() {
      Activity draft = newActivity(ActivityEditStatus.DRAFT);
      when(activityRepository.findById(activityId)).thenReturn(Optional.of(draft));

      service.unlockActivity(activityId);

      verify(activityRepository, never()).save(any(Activity.class));
      verify(auditService, never()).logUpdate(eq("Activity"), eq(activityId), eq("editStatus"),
          any(), any());
      assertThat(draft.getEditStatus()).isEqualTo(ActivityEditStatus.DRAFT);
    }
  }

  // ─── applyActuals skips LOCKED rows in the batch loop ─────────────────────────────

  @Nested
  @DisplayName("ApplyActuals")
  class ApplyActuals {

    @Test
    @DisplayName("applyActuals skips LOCKED activities (only the DRAFT one is saved)")
    void skipsLocked() {
      LocalDate dataDate = LocalDate.of(2026, 5, 5);

      // DRAFT activity: planned dates pulled forward so applyActuals has something to write.
      Activity draft = newActivity(ActivityEditStatus.DRAFT);
      draft.setId(UUID.randomUUID());
      draft.setCode("ACT-DRAFT");
      draft.setPlannedStartDate(LocalDate.of(2026, 5, 1));
      draft.setPlannedFinishDate(LocalDate.of(2026, 5, 30));
      draft.setOriginalDuration(29.0);
      draft.setPercentCompleteType(PercentCompleteType.DURATION);

      // LOCKED activity: also has plannable dates but must be skipped entirely.
      Activity locked = newActivity(ActivityEditStatus.LOCKED);
      locked.setId(UUID.randomUUID());
      locked.setCode("ACT-LOCKED");
      locked.setPlannedStartDate(LocalDate.of(2026, 5, 1));
      locked.setPlannedFinishDate(LocalDate.of(2026, 5, 30));
      locked.setOriginalDuration(29.0);
      locked.setPercentCompleteType(PercentCompleteType.DURATION);

      when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(draft, locked));
      // Only the DRAFT activity reaches the calculator; locked is short-circuited before.
      when(percentCompleteCalculator.calculate(eq(draft), eq(null), eq(null), eq(dataDate)))
          .thenReturn(new Result(15.0, ActivityStatus.IN_PROGRESS, null));

      service.applyActuals(projectId, dataDate);

      // Calculator only called for DRAFT.
      verify(percentCompleteCalculator, times(1))
          .calculate(eq(draft), eq(null), eq(null), eq(dataDate));
      verify(percentCompleteCalculator, never())
          .calculate(eq(locked), any(), any(), any());

      // Save only on DRAFT.
      verify(activityRepository, times(1)).save(draft);
      verify(activityRepository, never()).save(locked);
    }
  }
}
