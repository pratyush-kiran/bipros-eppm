package com.bipros.activity.application.service;

import com.bipros.activity.application.dto.UpdateActivityRequest;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.PercentCompleteType;
import com.bipros.activity.domain.model.RelationshipType;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService predecessor validation")
class ActivityServicePredecessorValidationTest {

  @Mock private ActivityRepository activityRepository;
  @Mock private ActivitySupervisorRepository activitySupervisorRepository;
  @Mock private ActivityRelationshipRepository relationshipRepository;
  @Mock private AuditService auditService;
  @Mock private ProjectAccessGuard projectAccess;
  @Mock private ProjectRepository projectRepository;
  @Mock private PercentCompleteCalculator percentCompleteCalculator;
  @Mock private ActivityStepRepository stepRepository;

  private ActivityService service;

  private UUID successorId;
  private UUID predecessorId;
  private Activity successor;
  private Activity predecessor;

  @BeforeEach
  void setUp() {
    service = new ActivityService(activityRepository, activitySupervisorRepository, relationshipRepository, auditService, projectAccess, projectRepository, percentCompleteCalculator, stepRepository, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));

    successorId = UUID.randomUUID();
    predecessorId = UUID.randomUUID();

    successor = new Activity();
    successor.setId(successorId);
    successor.setCode("ACT-7.3");
    successor.setName("Testing Phase 2");
    successor.setProjectId(UUID.randomUUID());
    successor.setWbsNodeId(UUID.randomUUID());
    successor.setPercentComplete(0.0);
    successor.setStatus(ActivityStatus.NOT_STARTED);
    successor.setPercentCompleteType(PercentCompleteType.PHYSICAL);

    predecessor = new Activity();
    predecessor.setId(predecessorId);
    predecessor.setCode("ACT-1.1");
    predecessor.setName("Earthwork – Excavation");

    when(activityRepository.findById(successorId)).thenReturn(Optional.of(successor));
    lenient().when(activityRepository.findById(predecessorId)).thenReturn(Optional.of(predecessor));
    lenient().when(activityRepository.save(any(Activity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(stepRepository.countByActivityId(any(UUID.class))).thenReturn(0L);
  }

  private ActivityRelationship rel(RelationshipType type, double lag) {
    ActivityRelationship r = new ActivityRelationship();
    r.setPredecessorActivityId(predecessorId);
    r.setSuccessorActivityId(successorId);
    r.setRelationshipType(type);
    r.setLag(lag);
    r.setProjectId(successor.getProjectId());
    r.setIsExternal(false);
    return r;
  }

  @Nested
  @DisplayName("FS — Finish-to-Start")
  class FinishToStart {

    @Test
    @DisplayName("blocks setting actualStartDate when predecessor has not finished")
    void blocksWhenPredecessorNotFinished() {
      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.FINISH_TO_START, 2.0)));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          10.0, null, LocalDate.of(2026, 4, 20), null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateActivity(successorId, req));
      assertEquals("PREDECESSOR_NOT_SATISFIED", ex.getRuleCode());
    }

    @Test
    @DisplayName("blocks deriving IN_PROGRESS via percentComplete > 0 when predecessor has not finished")
    void blocksWhenOnlyPercentChanged() {
      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.FINISH_TO_START, 0.0)));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          25.0, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertThrows(BusinessRuleException.class, () -> service.updateActivity(successorId, req));
    }

    @Test
    @DisplayName("allows setting actualStartDate once predecessor has finished")
    void allowsWhenPredecessorFinished() {
      predecessor.setActualStartDate(LocalDate.of(2026, 4, 1));
      predecessor.setActualFinishDate(LocalDate.of(2026, 4, 10));
      predecessor.setPercentComplete(100.0);
      predecessor.setStatus(ActivityStatus.COMPLETED);

      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.FINISH_TO_START, 2.0)));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          10.0, null, LocalDate.of(2026, 4, 12), null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(successorId, req));
    }
  }

  @Nested
  @DisplayName("SS — Start-to-Start")
  class StartToStart {

    @Test
    @DisplayName("blocks start when predecessor has not started")
    void blocksWhenPredecessorNotStarted() {
      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.START_TO_START, 0.0)));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          null, null, LocalDate.of(2026, 4, 20), null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertThrows(BusinessRuleException.class, () -> service.updateActivity(successorId, req));
    }

    @Test
    @DisplayName("allows start once predecessor has started")
    void allowsWhenPredecessorStarted() {
      predecessor.setActualStartDate(LocalDate.of(2026, 4, 15));

      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.START_TO_START, 0.0)));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          null, null, LocalDate.of(2026, 4, 18), null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(successorId, req));
    }
  }

  @Nested
  @DisplayName("FF — Finish-to-Finish")
  class FinishToFinish {

    @Test
    @DisplayName("blocks finish when predecessor has not finished")
    void blocksFinishWhenPredecessorNotFinished() {
      successor.setActualStartDate(LocalDate.of(2026, 4, 20));
      successor.setStatus(ActivityStatus.IN_PROGRESS);

      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.FINISH_TO_FINISH, 0.0)));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          100.0, null, null, LocalDate.of(2026, 4, 30),
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertThrows(BusinessRuleException.class, () -> service.updateActivity(successorId, req));
    }

    @Test
    @DisplayName("allows starting (without finishing) even when predecessor has not finished")
    void allowsStartUnderFf() {
      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.FINISH_TO_FINISH, 0.0)));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          25.0, null, LocalDate.of(2026, 4, 20), null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(successorId, req));
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("activity with no predecessors is unrestricted")
    void noPredecessorsUnrestricted() {
      when(relationshipRepository.findBySuccessorActivityId(successorId)).thenReturn(List.of());

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          50.0, null, LocalDate.of(2026, 4, 20), null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(successorId, req));
    }

    @Test
    @DisplayName("external (cross-project) relationships are not enforced")
    void externalRelationshipsSkipped() {
      ActivityRelationship external = rel(RelationshipType.FINISH_TO_START, 0.0);
      external.setIsExternal(true);
      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(external));

      UpdateActivityRequest req = new UpdateActivityRequest(
          null, null, null, null, null, null, null, null, null,
          10.0, null, LocalDate.of(2026, 4, 20), null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(successorId, req));
    }

    @Test
    @DisplayName("non-progress edits (like name) bypass predecessor checks")
    void nameOnlyEditsBypass() {
      // No relationship lookup needed; ensure pure-name edit doesn't trip validation
      UpdateActivityRequest req = new UpdateActivityRequest(
          "Renamed", null, null, null, null, null, null, null, null,
          null, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

      assertDoesNotThrow(() -> service.updateActivity(successorId, req));
    }

    @Test
    @DisplayName("updateProgress() also enforces predecessor constraints")
    void updateProgressEnforces() {
      when(relationshipRepository.findBySuccessorActivityId(successorId))
          .thenReturn(List.of(rel(RelationshipType.FINISH_TO_START, 2.0)));

      BusinessRuleException ex = assertThrows(BusinessRuleException.class,
          () -> service.updateProgress(successorId, 10.0, LocalDate.of(2026, 4, 20), null));
      assertEquals("PREDECESSOR_NOT_SATISFIED", ex.getRuleCode());
    }
  }
}
