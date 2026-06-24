package com.bipros.scheduling.application.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivityStatus;
import com.bipros.activity.domain.model.ActivityType;
import com.bipros.activity.domain.repository.ActivityRelationshipRepository;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.calendar.application.service.CalendarService;
import com.bipros.calendar.application.service.CalendarSnapshot;
import com.bipros.calendar.domain.model.CalendarWorkWeek;
import com.bipros.calendar.domain.model.DayType;
import com.bipros.calendar.domain.repository.CalendarRepository;
import com.bipros.common.util.AuditService;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.scheduling.domain.algorithm.CalendarCalculator;
import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.model.SchedulingOption;
import com.bipros.scheduling.domain.repository.ScheduleActivityResultRepository;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@code SchedulingService.scheduleProject} honours project-level schedule
 * metadata: data date, planned start, and must-finish-by deadline (I5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingService — project schedule metadata (I5)")
class SchedulingServiceProjectMetadataTest {

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

    /** A Mon–Fri calendar snapshot reused across tests. */
    private UUID calendarId;
    private CalendarSnapshot monFriSnapshot;

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

        calendarId = UUID.randomUUID();

        // Build a standard Mon–Fri work week snapshot
        Map<DayOfWeek, CalendarWorkWeek> workWeek = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : DayOfWeek.values()) {
            DayType type = (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY)
                ? DayType.NON_WORKING : DayType.WORKING;
            workWeek.put(d, CalendarWorkWeek.builder()
                .calendarId(calendarId)
                .dayOfWeek(d)
                .dayType(type)
                .totalWorkHours(type == DayType.WORKING ? 8.0 : 0.0)
                .build());
        }
        monFriSnapshot = new CalendarSnapshot(calendarId, workWeek, Collections.emptyMap());
    }

    /**
     * Stubs all dependencies so that {@code scheduleProject} can run to completion,
     * then returns an ArgumentCaptor already configured to capture the saved {@link ScheduleResult}.
     * The caller asserts the captured value.
     */
    private ArgumentCaptor<ScheduleResult> runScheduleProject(
            UUID projectId, Project project, LocalDate activityStart) {

        // Activity stub — one simple NOT_STARTED task
        Activity activity = new Activity();
        activity.setId(UUID.randomUUID());
        activity.setProjectId(projectId);
        activity.setWbsNodeId(UUID.randomUUID());
        activity.setCode("A1");
        activity.setName("Task 1");
        activity.setActivityType(ActivityType.TASK_DEPENDENT);
        activity.setStatus(ActivityStatus.NOT_STARTED);
        activity.setEditStatus(ActivityEditStatus.DRAFT);
        activity.setOriginalDuration(5.0);
        activity.setRemainingDuration(5.0);
        activity.setCalendarId(calendarId);
        activity.setPlannedStartDate(activityStart);

        when(activityRepository.findByProjectId(projectId)).thenReturn(List.of(activity));
        when(activityRelationshipRepository.findByProjectId(projectId))
            .thenReturn(Collections.emptyList());
        when(pertEstimateService.getByActivities(any())).thenReturn(Collections.emptyList());

        // Calendar resolution: activity has calendarId → step 1 returns it directly.
        // CalendarService.loadSnapshot is called by SnapshotCalendarCalculator.
        when(calendarService.loadSnapshot(eq(calendarId), any(), any()))
            .thenReturn(monFriSnapshot);

        // Project repository — used both by resolveDefaultCalendarId (step 2 fallback) and
        // the new project-metadata load. Both calls share the same stub.
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        // Save stub — return a minimal ScheduleResult so the response builder doesn't NPE
        ArgumentCaptor<ScheduleResult> captor = ArgumentCaptor.forClass(ScheduleResult.class);
        when(scheduleResultRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(scheduleActivityResultRepository.saveAll(any())).thenReturn(Collections.emptyList());
        when(activityRepository.saveAll(any())).thenReturn(Collections.emptyList());

        service.scheduleProject(projectId, SchedulingOption.RETAINED_LOGIC);
        return captor;
    }

    // -------------------------------------------------------------------------
    // Test: data date comes from the project
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("usesProjectDataDate: saved ScheduleResult.dataDate matches project.dataDate")
    void usesProjectDataDate() {
        UUID projectId = UUID.randomUUID();
        LocalDate projectDataDate = LocalDate.of(2026, 3, 1);
        LocalDate activityStart  = LocalDate.of(2026, 3, 2);   // Monday

        Project project = mock(Project.class);
        when(project.getDataDate()).thenReturn(projectDataDate);
        when(project.getPlannedStartDate()).thenReturn(null);    // fall back to activity min
        when(project.getMustFinishByDate()).thenReturn(null);

        ArgumentCaptor<ScheduleResult> captor =
            runScheduleProject(projectId, project, activityStart);

        assertEquals(projectDataDate, captor.getValue().getDataDate(),
            "ScheduleResult.dataDate must equal project.dataDate, not LocalDate.now()");
    }

    // -------------------------------------------------------------------------
    // Test: when project has no data date, falls back to today (regression guard)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fallsBackToTodayWhenProjectDataDateIsNull: dataDate is not null")
    void fallsBackToTodayWhenProjectDataDateIsNull() {
        UUID projectId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        LocalDate activityStart = LocalDate.of(2026, 3, 3);    // Tuesday

        Project project = mock(Project.class);
        when(project.getDataDate()).thenReturn(null);
        when(project.getPlannedStartDate()).thenReturn(null);
        when(project.getMustFinishByDate()).thenReturn(null);

        ArgumentCaptor<ScheduleResult> captor =
            runScheduleProject(projectId, project, activityStart);

        // The exact value of "today" can vary in CI — just confirm it is not null
        assertEquals(today, captor.getValue().getDataDate(),
            "When project.dataDate is null, should fall back to LocalDate.now()");
    }
}
