package com.bipros.scheduling.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.calendar.application.service.CalendarService;
import com.bipros.calendar.domain.model.Calendar;
import com.bipros.calendar.domain.model.CalendarType;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.scheduling.domain.algorithm.CalendarCalculator;
import com.bipros.scheduling.domain.repository.ScheduleActivityResultRepository;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingService — resolveDefaultCalendarId")
class SchedulingServiceCalendarResolutionTest {

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
  @DisplayName("fallsBackToProjectCalendar: no activity calendars → uses project.calendarId")
  void fallsBackToProjectCalendar() {
    // Arrange
    UUID projectId = UUID.randomUUID();
    UUID projectCalendarId = UUID.randomUUID();

    Activity activity = mock(Activity.class);
    when(activity.getCalendarId()).thenReturn(null);
    List<Activity> activities = List.of(activity);

    Project project = mock(Project.class);
    when(project.getCalendarId()).thenReturn(projectCalendarId);
    when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

    Calendar calendar = mock(Calendar.class);
    when(calendarRepository.findById(projectCalendarId)).thenReturn(Optional.of(calendar));

    // Act
    UUID resolved = service.resolveDefaultCalendarId(projectId, activities);

    // Assert
    assertEquals(projectCalendarId, resolved);
  }

  @Test
  @DisplayName("throwsClearErrorWhenNoCalendarAnywhere: no activity, project, project-scoped, or global calendar")
  void throwsClearErrorWhenNoCalendarAnywhere() {
    // Arrange
    UUID projectId = UUID.randomUUID();

    Activity activity = mock(Activity.class);
    when(activity.getCalendarId()).thenReturn(null);
    List<Activity> activities = List.of(activity);

    Project project = mock(Project.class);
    when(project.getCalendarId()).thenReturn(null);
    when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

    when(calendarRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
    when(calendarRepository.findByCalendarTypeAndIsDefaultTrue(CalendarType.GLOBAL))
        .thenReturn(Optional.empty());

    // Act + Assert
    ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
        () -> service.resolveDefaultCalendarId(projectId, activities));

    // The message must be informative enough for the user to know how to fix it
    String msg = ex.getMessage();
    assertTrue(msg != null && msg.contains("No calendar configured for this project"),
        "Expected message containing 'No calendar configured for this project' but got: " + msg);
  }
}
