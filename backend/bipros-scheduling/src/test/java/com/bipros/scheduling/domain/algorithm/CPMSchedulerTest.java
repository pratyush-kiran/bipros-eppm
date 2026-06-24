package com.bipros.scheduling.domain.algorithm;

import com.bipros.activity.domain.model.RelationshipType;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.scheduling.domain.model.SchedulingOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CPMScheduler Tests")
class CPMSchedulerTest {

  private CPMScheduler scheduler;
  private LocalDate projectStartDate;
  private UUID defaultCalendarId;
  private MockCalendarCalculator mockCalendar;

  @BeforeEach
  void setUp() {
    defaultCalendarId = UUID.randomUUID();
    projectStartDate = LocalDate.of(2025, 1, 1);
    mockCalendar = new MockCalendarCalculator();
    scheduler = new CPMScheduler(mockCalendar, defaultCalendarId);
  }

  @Nested
  @DisplayName("Simple chain: A->B->C")
  class SimpleChainTests {

    @Test
    @DisplayName("verifies early start, early finish, late start, late finish for all activities")
    void simpleChainScheduling() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();
      UUID activityC = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0), // 5 days
          createActivity(activityB, 3.0), // 3 days
          createActivity(activityC, 4.0)  // 4 days
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 0),
          new SchedulableRelationship(activityB, activityC, "FS", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      // A: ES=2025-01-01, EF=2025-01-06 (5 days)
      ScheduledActivity a = scheduledMap.get(activityA);
      assertEquals(projectStartDate, a.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 6), a.getEarlyFinish());

      // B: ES=2025-01-06, EF=2025-01-09 (3 days)
      ScheduledActivity b = scheduledMap.get(activityB);
      assertEquals(LocalDate.of(2025, 1, 6), b.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 9), b.getEarlyFinish());

      // C: ES=2025-01-09, EF=2025-01-13 (4 days)
      ScheduledActivity c = scheduledMap.get(activityC);
      assertEquals(LocalDate.of(2025, 1, 9), c.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 13), c.getEarlyFinish());

      // Project finishes when C finishes, so all activities on critical path
      // Total float should be 0 for all
      assertEquals(0, a.getTotalFloat());
      assertEquals(0, b.getTotalFloat());
      assertEquals(0, c.getTotalFloat());

      // All should be critical
      assertTrue(a.isCritical());
      assertTrue(b.isCritical());
      assertTrue(c.isCritical());

      // LS = ES, LF = EF for all (critical path)
      assertEquals(a.getEarlyStart(), a.getLateStart());
      assertEquals(a.getEarlyFinish(), a.getLateFinish());
      assertEquals(b.getEarlyStart(), b.getLateStart());
      assertEquals(b.getEarlyFinish(), b.getLateFinish());
      assertEquals(c.getEarlyStart(), c.getLateStart());
      assertEquals(c.getEarlyFinish(), c.getLateFinish());
    }
  }

  @Nested
  @DisplayName("Parallel paths: A->B->D, A->C->D")
  class ParallelPathsTests {

    @Test
    @DisplayName("critical path is A->B->D, C should have float")
    void parallelPathsScheduling() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();
      UUID activityC = UUID.randomUUID();
      UUID activityD = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(activityB, 5.0),
          createActivity(activityC, 3.0),
          createActivity(activityD, 2.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 0),
          new SchedulableRelationship(activityA, activityC, "FS", 0),
          new SchedulableRelationship(activityB, activityD, "FS", 0),
          new SchedulableRelationship(activityC, activityD, "FS", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity a = scheduledMap.get(activityA);
      ScheduledActivity b = scheduledMap.get(activityB);
      ScheduledActivity c = scheduledMap.get(activityC);
      ScheduledActivity d = scheduledMap.get(activityD);

      // A->B->D is longer: 5 + 5 + 2 = 12 days
      // A->C->D is shorter: 5 + 3 + 2 = 10 days
      // Total float for C = 2 days
      assertTrue(a.isCritical());
      assertTrue(b.isCritical());
      assertTrue(d.isCritical());
      assertFalse(c.isCritical());

      assertEquals(0, a.getTotalFloat());
      assertEquals(0, b.getTotalFloat());
      assertEquals(0, d.getTotalFloat());
      assertEquals(2, c.getTotalFloat());
    }
  }

  @Nested
  @DisplayName("SS relationship: A-SS(lag=2)->B")
  class SSRelationshipTests {

    @Test
    @DisplayName("B starts 2 days after A starts")
    void startToStartWithLag() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(activityB, 3.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "SS", 2)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity a = scheduledMap.get(activityA);
      ScheduledActivity b = scheduledMap.get(activityB);

      // A starts on 2025-01-01
      assertEquals(projectStartDate, a.getEarlyStart());

      // B should start 2 days after A starts
      assertEquals(LocalDate.of(2025, 1, 3), b.getEarlyStart());

      // A finishes on 2025-01-06, B finishes on 2025-01-06
      assertEquals(LocalDate.of(2025, 1, 6), a.getEarlyFinish());
      assertEquals(LocalDate.of(2025, 1, 6), b.getEarlyFinish());
    }
  }

  @Nested
  @DisplayName("FF relationship: A-FF->B")
  class FFRelationshipTests {

    @Test
    @DisplayName("B's early start is constrained by A's early finish")
    void finishToFinish() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(activityB, 3.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FF", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity a = scheduledMap.get(activityA);
      ScheduledActivity b = scheduledMap.get(activityB);

      // A finishes on 2025-01-06
      assertEquals(LocalDate.of(2025, 1, 6), a.getEarlyFinish());

      // B's ES is constrained to be at least A's EF (from FF relationship)
      // So B starts on 2025-01-06 and finishes on 2025-01-09
      assertEquals(LocalDate.of(2025, 1, 6), b.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 9), b.getEarlyFinish());
    }
  }

  @Nested
  @DisplayName("SF relationship: A-SF->B")
  class SFRelationshipTests {

    @Test
    @DisplayName("B's ES is constrained by A's start date (SF relationship)")
    void startToFinish() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(activityB, 3.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "SF", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity a = scheduledMap.get(activityA);
      ScheduledActivity b = scheduledMap.get(activityB);

      // A starts on 2025-01-01
      assertEquals(projectStartDate, a.getEarlyStart());

      // B's ES is constrained by A's start (from SF relationship)
      // B starts on 2025-01-01 and finishes on 2025-01-04
      assertEquals(projectStartDate, b.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 4), b.getEarlyFinish());
    }
  }

  @Nested
  @DisplayName("Lag and Lead relationships")
  class LagLeadTests {

    @Test
    @DisplayName("A->B with lag=2, A->C with lag=-1")
    void lagAndLeadRelationships() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();
      UUID activityC = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(activityB, 2.0),
          createActivity(activityC, 2.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 2), // +2 days lag
          new SchedulableRelationship(activityA, activityC, "FS", -1) // -1 day lead
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity a = scheduledMap.get(activityA);
      ScheduledActivity b = scheduledMap.get(activityB);
      ScheduledActivity c = scheduledMap.get(activityC);

      // A: ES=2025-01-01, EF=2025-01-06
      assertEquals(projectStartDate, a.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 6), a.getEarlyFinish());

      // B with lag=2: ES=2025-01-08, EF=2025-01-10
      assertEquals(LocalDate.of(2025, 1, 8), b.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 10), b.getEarlyFinish());

      // C with lead=-1: ES=2025-01-05, EF=2025-01-07
      assertEquals(LocalDate.of(2025, 1, 5), c.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 7), c.getEarlyFinish());
    }
  }

  @Nested
  @DisplayName("RelationshipType.code() feeds CPM engine correctly")
  class RelationshipTypeCodeContractTests {

    @Test
    @DisplayName("SS+lag=2 via RelationshipType.code() shifts successor start by 2 days")
    void startToStartViaEnumCode() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(activityB, 3.0)
      );

      // Use RelationshipType.START_TO_START.code() rather than a bare "SS" literal
      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, RelationshipType.START_TO_START.code(), 2)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity a = scheduledMap.get(activityA);
      ScheduledActivity b = scheduledMap.get(activityB);

      // A starts on 2025-01-01
      assertEquals(projectStartDate, a.getEarlyStart());
      // SS+lag=2 → B starts 2 days after A starts (2025-01-03)
      assertEquals(LocalDate.of(2025, 1, 3), b.getEarlyStart());
      // B has duration=3, so finishes on 2025-01-06
      assertEquals(LocalDate.of(2025, 1, 6), b.getEarlyFinish());
    }
  }

  @Nested
  @DisplayName("Constraint handling")
  class ConstraintTests {

    @Test
    @DisplayName("START_ON_OR_AFTER constraint pushes start date")
    void startOnOrAfterConstraint() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();

      LocalDate constraintDate = LocalDate.of(2025, 1, 15);

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivityWithConstraint(
              activityB,
              3.0,
              "START_ON_OR_AFTER",
              constraintDate,
              null,
              null
          )
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity b = scheduledMap.get(activityB);

      // B would normally start on 2025-01-06, but constraint pushes it to 2025-01-15
      assertEquals(constraintDate, b.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 18), b.getEarlyFinish());
    }

    @Test
    @DisplayName("START_ON constraint sets exact start date")
    void startOnConstraint() {
      UUID activityA = UUID.randomUUID();

      LocalDate constraintDate = LocalDate.of(2025, 1, 10);

      List<SchedulableActivity> activities = List.of(
          createActivityWithConstraint(
              activityA,
              5.0,
              "START_ON",
              constraintDate,
              null,
              null
          )
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          List.of(),
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      ScheduledActivity a = scheduled.get(0);

      assertEquals(constraintDate, a.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 15), a.getEarlyFinish());
    }
  }

  @Nested
  @DisplayName("Milestone (zero-duration activity)")
  class MilestoneTests {

    @Test
    @DisplayName("milestone has ES=EF")
    void milestoneDuration() {
      UUID activityA = UUID.randomUUID();
      UUID milestone = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(milestone, 0.0) // Zero-duration milestone
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, milestone, "FS", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      ScheduledActivity m = scheduledMap.get(milestone);

      // Milestone ES=EF
      assertEquals(m.getEarlyStart(), m.getEarlyFinish());
      // Should be on 2025-01-06 (when A finishes)
      assertEquals(LocalDate.of(2025, 1, 6), m.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 6), m.getEarlyFinish());
    }
  }

  @Nested
  @DisplayName("Circular dependency detection")
  class CircularDependencyTests {

    @Test
    @DisplayName("throws BusinessRuleException when circular dependency detected")
    void circularDependencyDetection() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();
      UUID activityC = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 2.0),
          createActivity(activityB, 2.0),
          createActivity(activityC, 2.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 0),
          new SchedulableRelationship(activityB, activityC, "FS", 0),
          new SchedulableRelationship(activityC, activityA, "FS", 0) // Circular!
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      BusinessRuleException exception = assertThrows(
          BusinessRuleException.class,
          () -> scheduler.schedule(data)
      );

      assertEquals("CIRCULAR_DEPENDENCY", exception.getRuleCode());
      assertTrue(exception.getMessage().contains("Circular dependency"));
    }
  }

  @Nested
  @DisplayName("Dangling relationship (references a missing activity)")
  class DanglingRelationshipTests {

    @Test
    @DisplayName("relationship to a missing activity reports DANGLING_RELATIONSHIP, not CIRCULAR_DEPENDENCY")
    void relationshipToMissingActivityReportsDanglingNotCircular() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();
      UUID missingId = UUID.randomUUID(); // not in the activity list

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 3.0),
          createActivity(activityB, 3.0)
      );

      // successor points to an activity that does not exist in the schedule data
      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 0),
          new SchedulableRelationship(activityB, missingId, "FS", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      BusinessRuleException exception = assertThrows(
          BusinessRuleException.class,
          () -> scheduler.schedule(data)
      );

      assertEquals("DANGLING_RELATIONSHIP", exception.getRuleCode());
      assertTrue(exception.getMessage().contains("missing activity"),
          "Message should mention 'missing activity' but was: " + exception.getMessage());
      assertFalse(exception.getMessage().toLowerCase().contains("circular"),
          "Message must NOT mention 'circular' but was: " + exception.getMessage());
    }
  }

  @Nested
  @DisplayName("Single activity")
  class SingleActivityTests {

    @Test
    @DisplayName("single activity has ES=project start, TF=0")
    void singleActivity() {
      UUID activityA = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          List.of(),
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      ScheduledActivity a = scheduled.get(0);

      assertEquals(projectStartDate, a.getEarlyStart());
      assertEquals(LocalDate.of(2025, 1, 6), a.getEarlyFinish());
      assertEquals(0, a.getTotalFloat());
      assertTrue(a.isCritical());
    }
  }

  @Nested
  @DisplayName("Multiple critical paths")
  class MultipleCriticalPathsTests {

    @Test
    @DisplayName("two parallel paths of equal length are both critical")
    void multipleCriticalPaths() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();
      UUID activityC = UUID.randomUUID();
      UUID activityD = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 5.0),
          createActivity(activityB, 5.0),
          createActivity(activityC, 5.0),
          createActivity(activityD, 2.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 0),
          new SchedulableRelationship(activityA, activityC, "FS", 0),
          new SchedulableRelationship(activityB, activityD, "FS", 0),
          new SchedulableRelationship(activityC, activityD, "FS", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      // Both paths are 5 + 5 + 2 = 12 days
      assertTrue(scheduledMap.get(activityA).isCritical());
      assertTrue(scheduledMap.get(activityB).isCritical());
      assertTrue(scheduledMap.get(activityC).isCritical());
      assertTrue(scheduledMap.get(activityD).isCritical());

      assertEquals(0, scheduledMap.get(activityA).getTotalFloat());
      assertEquals(0, scheduledMap.get(activityB).getTotalFloat());
      assertEquals(0, scheduledMap.get(activityC).getTotalFloat());
      assertEquals(0, scheduledMap.get(activityD).getTotalFloat());
    }
  }

  @Nested
  @DisplayName("Float calculations")
  class FloatCalculationTests {

    @Test
    @DisplayName("total float and free float calculated correctly")
    void floatCalculations() {
      UUID activityA = UUID.randomUUID();
      UUID activityB = UUID.randomUUID();
      UUID activityC = UUID.randomUUID();
      UUID activityD = UUID.randomUUID();

      List<SchedulableActivity> activities = List.of(
          createActivity(activityA, 10.0),
          createActivity(activityB, 5.0),
          createActivity(activityC, 3.0),
          createActivity(activityD, 2.0)
      );

      List<SchedulableRelationship> relationships = List.of(
          new SchedulableRelationship(activityA, activityB, "FS", 0),
          new SchedulableRelationship(activityA, activityC, "FS", 0),
          new SchedulableRelationship(activityB, activityD, "FS", 0),
          new SchedulableRelationship(activityC, activityD, "FS", 0)
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          activities,
          relationships,
          SchedulingOption.RETAINED_LOGIC
      );

      List<ScheduledActivity> scheduled = scheduler.schedule(data);
      Map<UUID, ScheduledActivity> scheduledMap = buildScheduledMap(scheduled);

      // Critical path: A -> B -> D (10 + 5 + 2 = 17 days)
      // Non-critical: C (3 days), total duration A -> C -> D = 10 + 3 + 2 = 15 days
      // TF for C = 2 days

      ScheduledActivity c = scheduledMap.get(activityC);
      assertEquals(2, c.getTotalFloat());
      assertFalse(c.isCritical());
    }
  }

  @Nested
  @DisplayName("RETAINED_LOGIC: IN_PROGRESS activity with no actuals floors earlyStart to dataDate")
  class InProgressDataDateFloorTests {

    @Test
    @DisplayName("IN_PROGRESS activity with null actuals gets earlyStart == dataDate")
    void inProgressNoActualsUsesDataDate() {
      UUID activityId = UUID.randomUUID();
      LocalDate dataDate = LocalDate.of(2025, 3, 1);

      // IN_PROGRESS, no actualStartDate, no actualFinishDate
      SchedulableActivity activity = new SchedulableActivity(
          activityId,
          5.0,
          5.0,
          defaultCalendarId,
          "Task",
          "IN_PROGRESS",
          50,
          null,  // actualStartDate
          null,  // actualFinishDate
          null,
          null,
          null,
          null
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          dataDate,
          projectStartDate,
          null,
          List.of(activity),
          List.of(),
          SchedulingOption.RETAINED_LOGIC
      );

      CPMScheduler.ScheduleOutput output = scheduler.scheduleWithWarnings(data);
      ScheduledActivity result = output.activities().get(0);

      assertEquals(dataDate, result.getEarlyStart(),
          "IN_PROGRESS activity with no actuals must be floored to dataDate");
    }
  }

  @Nested
  @DisplayName("Null-safe actuals: completed without actual start")
  class CompletedWithoutActualStartTests {

    @Test
    @DisplayName("COMPLETED activity with actualFinish but no actualStart does not crash")
    void completedActivityWithoutActualStartDoesNotCrash() {
      UUID activityId = UUID.randomUUID();
      LocalDate finish = LocalDate.of(2025, 1, 10);

      // COMPLETED activity: actualFinishDate set, actualStartDate null, originalDuration=5
      SchedulableActivity activity = new SchedulableActivity(
          activityId,
          5.0,   // originalDuration
          5.0,   // remainingDuration
          defaultCalendarId,
          "Task",
          "COMPLETED",
          100,
          null,          // actualStartDate — null (the bug trigger)
          finish,        // actualFinishDate
          null,
          null,
          null,
          null
      );

      ScheduleData data = new ScheduleData(
          UUID.randomUUID(),
          projectStartDate,
          projectStartDate,
          null,
          List.of(activity),
          List.of(),
          SchedulingOption.RETAINED_LOGIC
      );

      CPMScheduler.ScheduleOutput output = assertDoesNotThrow(() -> scheduler.scheduleWithWarnings(data));
      List<ScheduledActivity> scheduled = output.activities();
      assertEquals(1, scheduled.size());

      ScheduledActivity result = scheduled.get(0);

      // earlyStart must be non-null (back-calculated: finish - 5 days = 2025-01-05)
      assertNotNull(result.getEarlyStart(), "earlyStart must not be null for completed activity");
      assertEquals(finish, result.getEarlyFinish());

      // lateStart must also be non-null (backward pass same guard)
      assertNotNull(result.getLateStart(), "lateStart must not be null for completed activity");
      assertEquals(finish, result.getLateFinish());

      // totalFloat must be a finite number, not an NPE
      assertTrue(Double.isFinite(result.getTotalFloat()), "totalFloat must be finite");
    }
  }

  // Utility methods

  private SchedulableActivity createActivity(UUID id, double duration) {
    return new SchedulableActivity(
        id,
        duration,
        duration,
        defaultCalendarId,
        "Task",
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        null
    );
  }

  private SchedulableActivity createActivityWithConstraint(
      UUID id,
      double duration,
      String primaryConstraintType,
      LocalDate primaryConstraintDate,
      String secondaryConstraintType,
      LocalDate secondaryConstraintDate) {
    return new SchedulableActivity(
        id,
        duration,
        duration,
        defaultCalendarId,
        "Task",
        null,
        0,
        null,
        null,
        primaryConstraintType,
        primaryConstraintDate,
        secondaryConstraintType,
        secondaryConstraintDate
    );
  }

  private Map<UUID, ScheduledActivity> buildScheduledMap(List<ScheduledActivity> scheduled) {
    Map<UUID, ScheduledActivity> map = new HashMap<>();
    for (ScheduledActivity activity : scheduled) {
      map.put(activity.getActivityId(), activity);
    }
    return map;
  }

  /**
   * Mock CalendarCalculator that treats every day as working day with 8 hours.
   * This simplifies testing by removing calendar complexity.
   */
  static class MockCalendarCalculator implements CalendarCalculator {

    @Override
    public boolean isWorkingDay(UUID calendarId, LocalDate date) {
      return true;
    }

    @Override
    public double getWorkingHours(UUID calendarId, LocalDate date) {
      return 8.0;
    }

    @Override
    public LocalDate addWorkingDays(UUID calendarId, LocalDate start, double days) {
      return start.plusDays((long) days);
    }

    @Override
    public LocalDate subtractWorkingDays(UUID calendarId, LocalDate from, double days) {
      return from.minusDays((long) days);
    }

    @Override
    public double countWorkingDays(UUID calendarId, LocalDate start, LocalDate end) {
      // Count days between start (inclusive) and end (exclusive)
      // For float calculation: from start to start = 0
      if (start.equals(end)) {
        return 0;
      }
      if (start.isAfter(end)) {
        return 0;
      }
      return (double) ChronoUnit.DAYS.between(start, end);
    }
  }
}
