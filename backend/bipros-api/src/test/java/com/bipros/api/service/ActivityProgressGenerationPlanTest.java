package com.bipros.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivityEditStatus;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.api.dto.ActivityProgressGenerationRequest;
import com.bipros.api.service.progressgen.ActivityPlan;
import com.bipros.api.service.progressgen.BoqLinkResolver;
import com.bipros.api.service.progressgen.PlannedDpr;
import com.bipros.api.service.progressgen.ResourceRowBuilder;
import com.bipros.api.service.progressgen.ScheduleSpreader;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure planning test for {@link ActivityProgressGenerationService#plan} — no Spring, no DB. Real
 * {@link ResourceRowBuilder}/{@link ScheduleSpreader} + a fixed {@link Clock}; repos and
 * {@link BoqLinkResolver} are mocked. The two execution-only collaborators (ActivityService,
 * DailyProgressReportService) are null because {@code plan(...)} never touches them.
 */
@ExtendWith(MockitoExtension.class)
class ActivityProgressGenerationPlanTest {

  @Mock ActivityRepository activityRepo;
  @Mock ResourceAssignmentRepository resourceAssignmentRepo;
  @Mock ActivitySupervisorRepository activitySupervisorRepo;
  @Mock ActivitySubContractorAssignmentRepository scAssignmentRepo;
  @Mock WorkActivityRepository workActivityRepo;
  @Mock BoqLinkResolver boqLinkResolver;

  private final Clock clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);
  private final LocalDate today = LocalDate.of(2026, 6, 30);

  private ActivityProgressGenerationService service;

  @BeforeEach
  void setUp() {
    service = new ActivityProgressGenerationService(
        activityRepo, resourceAssignmentRepo, activitySupervisorRepo, scAssignmentRepo,
        workActivityRepo, boqLinkResolver, new ResourceRowBuilder(), new ScheduleSpreader(),
        clock, /* activityService */ null, /* dprService */ null);
  }

  private static Activity zeroPctActivity(
      UUID id, String code, String name, UUID wbs, ActivityEditStatus edit, LocalDate plannedStart) {
    Activity a = new Activity();
    a.setId(id);
    a.setCode(code);
    a.setName(name);
    a.setProjectId(UUID.randomUUID());
    a.setWbsNodeId(wbs);
    a.setPercentComplete(0.0);
    a.setEditStatus(edit);
    a.setPlannedStartDate(plannedStart);
    return a;
  }

  @Test
  void plansOneActivityWithCappedQtySplitAcrossDatesAndSkipsNoSupervisor() {
    UUID projectId = UUID.randomUUID();
    UUID aid = UUID.randomUUID();
    UUID wbs = UUID.randomUUID();
    UUID noSupId = UUID.randomUUID();
    UUID supUserId = UUID.randomUUID();
    UUID boqItemId = UUID.randomUUID();

    Activity target = zeroPctActivity(
        aid, "A-001", "Earthworks", wbs, ActivityEditStatus.DRAFT, LocalDate.of(2026, 6, 20));
    Activity noSup = zeroPctActivity(
        noSupId, "A-002", "Survey", UUID.randomUUID(), ActivityEditStatus.LOCKED,
        LocalDate.of(2026, 6, 25));

    when(activityRepo.findByProjectId(projectId)).thenReturn(List.of(target, noSup));
    when(activitySupervisorRepo.findByActivityId(aid))
        .thenReturn(List.of(new ActivitySupervisor(aid, supUserId, "Eng. Alpha")));
    when(activitySupervisorRepo.findByActivityId(noSupId)).thenReturn(List.of());
    when(boqLinkResolver.resolve(projectId, aid, wbs)).thenReturn(
        new BoqLinkResolver.Resolved(boqItemId, "2.1", new BigDecimal("100"), BigDecimal.ZERO, false));
    when(resourceAssignmentRepo.findByActivityId(aid)).thenReturn(List.of(
        ResourceAssignment.builder()
            .activityId(aid).roleId(UUID.randomUUID())
            .manpowerRoleRateId(UUID.randomUUID()).headcount(2).build()));

    List<ActivityPlan> plans = service.plan(projectId, new ActivityProgressGenerationRequest());

    assertThat(plans).hasSize(2);

    ActivityPlan generated = plans.stream()
        .filter(p -> p.activityId().equals(aid)).findFirst().orElseThrow();
    ActivityPlan skipped = plans.stream()
        .filter(p -> p.activityId().equals(noSupId)).findFirst().orElseThrow();

    // --- generated activity ---
    assertThat(generated.supervisorUserId()).isEqualTo(supUserId);
    assertThat(generated.boqItemId()).isEqualTo(boqItemId);
    assertThat(generated.boqFallback()).isFalse();
    assertThat(generated.needsLock()).isTrue();                 // DRAFT + autoLockDraft default
    assertThat(generated.targetPercent()).isBetween(40, 60);    // within the requested band

    // qtyTotal == boqQty * pct / 100, capped by remaining BOQ budget (100).
    BigDecimal expectedQty = new BigDecimal("100")
        .multiply(BigDecimal.valueOf(generated.targetPercent()))
        .divide(new BigDecimal("100"), 3, RoundingMode.HALF_UP);
    assertThat(generated.qtyTotal()).isEqualByComparingTo(expectedQty);

    // dprs spread across <= datesPerActivity (3) dates, all <= today, qty summing to qtyTotal.
    assertThat(generated.dprs()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
    assertThat(generated.dprs()).allSatisfy(
        d -> assertThat(d.reportDate()).isBeforeOrEqualTo(today));
    BigDecimal sum = generated.dprs().stream()
        .map(PlannedDpr::qtyExecuted).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(generated.qtyTotal());

    // Scaled resource rows ride the first DPR only (rollup sums all rows for the activity).
    assertThat(generated.dprs().get(0).manpower()).hasSize(1);
    assertThat(generated.dprs().subList(1, generated.dprs().size()))
        .allSatisfy(d -> assertThat(d.manpower()).isEmpty());

    // --- skipped (no supervisor) activity ---
    assertThat(skipped.dprs()).isEmpty();
    assertThat(skipped.warnings()).contains("SKIPPED_NO_SUPERVISOR");
  }
}
