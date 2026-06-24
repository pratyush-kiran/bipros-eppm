package com.bipros.scheduling.application.service;

import com.bipros.activity.domain.model.ActivityRelationship;
import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.calendar.application.service.CalendarService;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.scheduling.application.dto.FloatPathResponse;
import com.bipros.scheduling.domain.algorithm.CalendarCalculator;
import com.bipros.scheduling.domain.model.ScheduleActivityResult;
import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.model.ScheduleStatus;
import com.bipros.scheduling.domain.repository.ScheduleActivityResultRepository;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingService — getFloatPaths adjacency population")
class SchedulingServiceFloatPathsTest {

  @Mock private ScheduleResultRepository scheduleResultRepository;
  @Mock private ScheduleActivityResultRepository scheduleActivityResultRepository;
  @Mock private CalendarCalculator calendarCalculator;
  @Mock private ActivityRepository activityRepository;
  @Mock private ActivityRelationshipRepository activityRelationshipRepository;
  @Mock private CalendarService calendarService;
  @Mock private PertEstimateService pertEstimateService;
  @Mock private ScheduleHealthService scheduleHealthService;
  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ProjectRepository projectRepository;
  @Mock private CalendarRepository calendarRepository;
  @Mock private ScheduleFailureRecorder failureRecorder;

  private SchedulingService service;

  @BeforeEach
  void setUp() {
    service = new SchedulingService(
        scheduleResultRepository,
        scheduleActivityResultRepository,
        calendarCalculator,
        activityRepository,
        activityRelationshipRepository,
        calendarService,
        pertEstimateService,
        scheduleHealthService,
        auditService,
        eventPublisher,
        projectRepository,
        calendarRepository,
        failureRecorder
    );
  }

  @Test
  @DisplayName("getFloatPaths returns a multi-node chain when adjacency is populated from relationships")
  void getFloatPathsReturnsMultiNodeChain() {
    // Arrange
    UUID projectId = UUID.randomUUID();
    UUID scheduleResultId = UUID.randomUUID();

    UUID actA = UUID.randomUUID();
    UUID actB = UUID.randomUUID();
    UUID actC = UUID.randomUUID();

    // Build a ScheduleResult stub with the required ID
    ScheduleResult scheduleResult = new ScheduleResult();
    scheduleResult.setId(scheduleResultId);
    scheduleResult.setStatus(ScheduleStatus.COMPLETED);

    when(scheduleResultRepository.findTopByProjectIdAndStatusOrderByCalculatedAtDesc(projectId, ScheduleStatus.COMPLETED))
        .thenReturn(Optional.of(scheduleResult));

    // Three activity results, all with zero total float → all eligible for float path tracing
    ScheduleActivityResult sarA = buildSar(scheduleResultId, actA, 0.0);
    ScheduleActivityResult sarB = buildSar(scheduleResultId, actB, 0.0);
    ScheduleActivityResult sarC = buildSar(scheduleResultId, actC, 0.0);

    when(scheduleActivityResultRepository.findByScheduleResultId(scheduleResultId))
        .thenReturn(List.of(sarA, sarB, sarC));

    // Relationships: A → B → C (FINISH_TO_START)
    ActivityRelationship relAB = buildRelationship(projectId, actA, actB);
    ActivityRelationship relBC = buildRelationship(projectId, actB, actC);

    when(activityRelationshipRepository.findByProjectId(projectId))
        .thenReturn(List.of(relAB, relBC));

    // Act
    List<FloatPathResponse> paths = service.getFloatPaths(projectId);

    // Assert: at least one path must contain more than one activity (A→B→C chain)
    boolean hasMultiNodePath = paths.stream()
        .anyMatch(p -> p.activities().size() > 1);
    assertTrue(hasMultiNodePath,
        "Expected at least one float path with more than one activity (multi-node chain A→B→C), " +
        "but all paths were single-node. The adjacency map was likely not populated.");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static ScheduleActivityResult buildSar(UUID scheduleResultId, UUID activityId, double totalFloat) {
    ScheduleActivityResult sar = new ScheduleActivityResult();
    sar.setScheduleResultId(scheduleResultId);
    sar.setActivityId(activityId);
    sar.setTotalFloat(totalFloat);
    sar.setFreeFloat(totalFloat);
    sar.setRemainingDuration(5.0);
    sar.setIsCritical(false);
    return sar;
  }

  private static ActivityRelationship buildRelationship(UUID projectId, UUID predecessorId, UUID successorId) {
    ActivityRelationship rel = new ActivityRelationship();
    rel.setProjectId(projectId);
    rel.setPredecessorActivityId(predecessorId);
    rel.setSuccessorActivityId(successorId);
    rel.setRelationshipType(RelationshipType.FINISH_TO_START);
    rel.setLag(0.0);
    rel.setIsExternal(false);
    return rel;
  }
}
