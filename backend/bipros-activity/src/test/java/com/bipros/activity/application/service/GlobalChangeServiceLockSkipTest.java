package com.bipros.activity.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.repository.ActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies GlobalChangeService.applyGlobalChange skips LOCKED rows and surfaces their codes
 * in the returned {@link GlobalChangeResult}, allowing the UI to show the user exactly which
 * activities were skipped without failing the whole bulk operation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalChangeService skips LOCKED rows")
class GlobalChangeServiceLockSkipTest {

  @Mock private ActivityRepository activityRepository;

  private GlobalChangeService service;
  private UUID projectId;

  @BeforeEach
  void setUp() {
    service = new GlobalChangeService(activityRepository);
    projectId = UUID.randomUUID();
    lenient().when(activityRepository.save(any(Activity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  private Activity activity(String code, ActivityEditStatus editStatus) {
    Activity a = new Activity();
    a.setId(UUID.randomUUID());
    a.setProjectId(projectId);
    a.setCode(code);
    a.setName("Activity " + code);
    a.setEditStatus(editStatus);
    a.setStatus(ActivityStatus.NOT_STARTED);
    a.setOriginalDuration(10.0);
    a.setRemainingDuration(10.0);
    a.setPercentComplete(0.0);
    return a;
  }

  @Test
  @DisplayName("applyGlobalChange skips LOCKED rows and reports counts + codes")
  void skipsLockedAndReports() {
    // Filter: status=NOT_STARTED matches all three. Middle one is LOCKED so should be skipped.
    Activity draftA = activity("ACT-A", ActivityEditStatus.DRAFT);
    Activity lockedB = activity("ACT-B", ActivityEditStatus.LOCKED);
    Activity draftC = activity("ACT-C", ActivityEditStatus.DRAFT);

    when(activityRepository.findByProjectId(projectId))
        .thenReturn(List.of(draftA, lockedB, draftC));

    // updateField=status is the only field whose switch case actually matches after
    // toLowerCase() in the production code — sufficient for verifying the lock-skip
    // contract, which is what this test is about.
    GlobalChangeRequest req = new GlobalChangeRequest(
        "status", "NOT_STARTED", "status", "IN_PROGRESS", GlobalChangeOperation.SET);

    GlobalChangeResult result = service.applyGlobalChange(projectId, req);

    assertThat(result.updatedCount()).isEqualTo(2);
    assertThat(result.skippedLocked()).isEqualTo(1);
    assertThat(result.skippedLockedCodes()).containsExactly("ACT-B");

    // Only the DRAFT rows were saved; the LOCKED one was not.
    verify(activityRepository, times(2)).save(any(Activity.class));
    verify(activityRepository, never()).save(lockedB);

    // The DRAFT rows actually picked up the new status.
    assertThat(draftA.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
    assertThat(draftC.getStatus()).isEqualTo(ActivityStatus.IN_PROGRESS);
    // The locked row was not mutated.
    assertThat(lockedB.getStatus()).isEqualTo(ActivityStatus.NOT_STARTED);
  }

  @Test
  @DisplayName("applyGlobalChange returns empty skipped list when no LOCKED rows")
  void noLockedRows() {
    Activity draftA = activity("ACT-A", ActivityEditStatus.DRAFT);
    Activity draftB = activity("ACT-B", ActivityEditStatus.DRAFT);

    when(activityRepository.findByProjectId(projectId))
        .thenReturn(List.of(draftA, draftB));

    GlobalChangeRequest req = new GlobalChangeRequest(
        "status", "NOT_STARTED", "status", "IN_PROGRESS", GlobalChangeOperation.SET);

    GlobalChangeResult result = service.applyGlobalChange(projectId, req);

    assertThat(result.updatedCount()).isEqualTo(2);
    assertThat(result.skippedLocked()).isZero();
    assertThat(result.skippedLockedCodes()).isEmpty();
    verify(activityRepository, times(2)).save(any(Activity.class));
  }
}
