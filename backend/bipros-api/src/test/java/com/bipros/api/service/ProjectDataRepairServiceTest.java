package com.bipros.api.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.model.ActivitySupervisor;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.activity.domain.repository.ActivitySupervisorRepository;
import com.bipros.api.dto.DataHealthResponse;
import com.bipros.api.dto.RepairReport;
import com.bipros.api.dto.RepairRequest;
import com.bipros.dbs.service.DbsAggregationService;
import com.bipros.project.application.service.BoqRebuildService;
import com.bipros.project.application.service.DailyProgressReportService;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.ManpowerCategory;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.resource.application.service.role.RoleProductivityNormResolver;
import com.bipros.resource.application.service.role.RoleRateResolver;
import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import com.bipros.resource.domain.repository.role.EquipmentRoleVariantRepository;
import com.bipros.resource.domain.repository.role.ManpowerRoleRateRepository;
import com.bipros.resource.domain.repository.role.MaterialRoleVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDataRepairServiceTest {

  @Mock DailyProgressReportRepository dprRepo;
  @Mock ActivityRepository activityRepo;
  @Mock ActivitySupervisorRepository activitySupervisorRepo;
  @Mock DprManpowerRepository dprManpowerRepo;
  @Mock ManpowerRoleRateRepository manpowerRoleRateRepo;
  @Mock EquipmentRoleVariantRepository equipmentRoleVariantRepo;
  @Mock MaterialRoleVariantRepository materialRoleVariantRepo;
  @Mock ManpowerCategoryMasterRepository manpowerCategoryMasterRepo;
  @Mock GradeMasterRepository gradeMasterRepo;
  @Mock WorkActivityRepository workActivityRepo;
  @Mock DprEquipmentRepository dprEquipmentRepo;
  @Mock RoleProductivityNormResolver normResolver;
  @Mock RoleRateResolver rateResolver;
  @Mock ResourceAssignmentRepository resourceAssignmentRepo;
  @Mock BoqRebuildService boqRebuildService;
  @Mock DailyProgressReportService dprService;
  @Mock DbsAggregationService dbsAggregationService;
  @Mock ActivitySubContractorAssignmentRepository scAssignmentRepo;

  ProjectDataRepairService service;

  @BeforeEach
  void setUp() {
    service = new ProjectDataRepairService(
        dprRepo, activityRepo, activitySupervisorRepo,
        dprManpowerRepo, manpowerRoleRateRepo,
        equipmentRoleVariantRepo, materialRoleVariantRepo,
        manpowerCategoryMasterRepo, gradeMasterRepo,
        workActivityRepo, dprEquipmentRepo,
        normResolver, rateResolver, resourceAssignmentRepo,
        boqRebuildService, dprService, dbsAggregationService, scAssignmentRepo);
    // No Spring context here, so the @Autowired self-proxy is never injected; wire it to the
    // same instance so repair(...)'s self.repairXxx(...) calls run the real methods (with mocked
    // repos) instead of NPE-ing. Behavior is identical to a direct call.
    service.self = service;
  }

  @Test
  void diagnoseCountsResourceLessAndSupervisorIssues() {
    UUID projectId = UUID.randomUUID();
    DailyProgressReport shell = DailyProgressReport.builder()
        .projectId(projectId).supervisorName("System").activityName("A")
        .unit("CUM").qtyExecuted(new BigDecimal("10")).build();
    when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(shell));
    when(dprRepo.findMinReportDate(projectId)).thenReturn(Optional.empty());
    when(dprRepo.findMaxReportDate(projectId)).thenReturn(Optional.empty());

    DataHealthResponse r = service.diagnose(projectId);

    assertThat(r.projectId()).isEqualTo(projectId);
    assertThat(r.dprTotal()).isEqualTo(1);
  }

  @Nested
  class SupervisorHygiene {

    @Test
    void reassignsSystemDprToActivityPrimarySupervisor() {
      UUID projectId = UUID.randomUUID();
      UUID activityId = UUID.randomUUID();
      UUID supId = UUID.randomUUID();

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).activityId(activityId)
          .supervisorName("System").activityName("A").unit("cum")
          .qtyExecuted(new BigDecimal("10")).build();
      Activity act = new Activity();
      act.setId(activityId); act.setProjectId(projectId); act.setSupervisorUserId(supId);
      ActivitySupervisor as = new ActivitySupervisor();
      as.setActivityId(activityId); as.setUserId(supId); as.setUserNameSnapshot("K. Barman");

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(activityRepo.findByProjectId(projectId)).thenReturn(List.of(act));
      when(activitySupervisorRepo.findByActivityIdIn(any())).thenReturn(List.of(as));

      int n = service.repairSupervisors(projectId, false);

      assertThat(n).isEqualTo(1);
      assertThat(dpr.getSupervisorUserId()).isEqualTo(supId);
      assertThat(dpr.getSupervisorName()).isEqualTo("K. Barman");
      verify(dprRepo).save(dpr);
    }

    @Test
    void dryRunDoesNotSave() {
      UUID projectId = UUID.randomUUID();
      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of());
      when(activityRepo.findByProjectId(projectId)).thenReturn(List.of());
      int n = service.repairSupervisors(projectId, true);
      assertThat(n).isZero();
      // No DPRs/activities → nothing to reassign, nothing saved. (The supervisor pre-load
      // is a single read-only query against an empty id set, so no per-DPR round-trips.)
      verify(dprRepo, never()).save(any());
    }
  }

  @Nested
  class RateLabels {

    @Test
    void setsNullCategoryToSkilledAndSaves() {
      UUID projectId = UUID.randomUUID();
      UUID dprId = UUID.randomUUID();

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).supervisorName("Test").activityName("A")
          .unit("cum").qtyExecuted(new BigDecimal("10")).build();
      dpr.setId(dprId);

      DprManpower manpower = DprManpower.builder()
          .dprId(dprId).trade("Mason").nos(2).build();
      // category is null by default

      ManpowerCategoryMaster skilled = new ManpowerCategoryMaster();
      skilled.setId(UUID.randomUUID());

      GradeMaster gradeA = new GradeMaster();
      gradeA.setId(UUID.randomUUID());

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(dprManpowerRepo.findByDprId(dprId)).thenReturn(List.of(manpower));
      when(manpowerCategoryMasterRepo.findByName("Skilled")).thenReturn(Optional.of(skilled));
      when(gradeMasterRepo.findByCode("A")).thenReturn(Optional.of(gradeA));

      int n = service.repairRateLabels(projectId, false);

      assertThat(n).isGreaterThanOrEqualTo(1);
      assertThat(manpower.getCategory()).isEqualTo(ManpowerCategory.SKILLED);
      verify(dprManpowerRepo).save(manpower);
    }

    @Test
    void dryRunDoesNotSave() {
      UUID projectId = UUID.randomUUID();
      UUID dprId = UUID.randomUUID();

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).supervisorName("Test").activityName("A")
          .unit("cum").qtyExecuted(new BigDecimal("10")).build();
      dpr.setId(dprId);

      DprManpower manpower = DprManpower.builder()
          .dprId(dprId).trade("Helper").nos(1).build();
      // category is null

      ManpowerCategoryMaster skilled = new ManpowerCategoryMaster();
      skilled.setId(UUID.randomUUID());
      GradeMaster gradeA = new GradeMaster();
      gradeA.setId(UUID.randomUUID());

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(dprManpowerRepo.findByDprId(dprId)).thenReturn(List.of(manpower));
      when(manpowerCategoryMasterRepo.findByName("Skilled")).thenReturn(Optional.of(skilled));
      when(gradeMasterRepo.findByCode("A")).thenReturn(Optional.of(gradeA));

      int n = service.repairRateLabels(projectId, true);

      assertThat(n).isEqualTo(1);
      assertThat(manpower.getCategory()).isNull(); // unchanged — dry run
      verify(dprManpowerRepo, never()).save(manpower);
    }
  }

  @Nested
  class UnitAlignment {

    @Test
    void rewritesUnitOnMismatch() {
      UUID projectId = UUID.randomUUID();
      UUID activityId = UUID.randomUUID();
      UUID workActivityId = UUID.randomUUID();

      Activity act = new Activity();
      act.setId(activityId);
      act.setProjectId(projectId);
      act.setWorkActivityId(workActivityId);

      WorkActivity wa = WorkActivity.builder()
          .code("WA001").name("Earthwork").defaultUnit("sqm").build();

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).activityId(activityId)
          .supervisorName("Test").activityName("A")
          .unit("CUM") // mismatch — work activity says "sqm"
          .qtyExecuted(new BigDecimal("50")).build();

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(activityRepo.findByProjectId(projectId)).thenReturn(List.of(act));
      when(workActivityRepo.findById(workActivityId)).thenReturn(Optional.of(wa));

      int n = service.repairUnits(projectId, false);

      assertThat(n).isEqualTo(1);
      assertThat(dpr.getUnit()).isEqualTo("sqm");
      verify(dprRepo).save(dpr);
    }

    @Test
    void noChangeWhenUnitMatches() {
      UUID projectId = UUID.randomUUID();
      UUID activityId = UUID.randomUUID();
      UUID workActivityId = UUID.randomUUID();

      Activity act = new Activity();
      act.setId(activityId);
      act.setProjectId(projectId);
      act.setWorkActivityId(workActivityId);

      WorkActivity wa = WorkActivity.builder()
          .code("WA002").name("Grading").defaultUnit("cum").build();

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).activityId(activityId)
          .supervisorName("Test").activityName("B")
          .unit("CUM") // same as work activity (case-insensitive)
          .qtyExecuted(new BigDecimal("20")).build();

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(activityRepo.findByProjectId(projectId)).thenReturn(List.of(act));
      when(workActivityRepo.findById(workActivityId)).thenReturn(Optional.of(wa));

      int n = service.repairUnits(projectId, false);

      assertThat(n).isZero();
      verify(dprRepo, never()).save(dpr);
    }

    @Test
    void dryRunDoesNotSave() {
      UUID projectId = UUID.randomUUID();
      UUID activityId = UUID.randomUUID();
      UUID workActivityId = UUID.randomUUID();

      Activity act = new Activity();
      act.setId(activityId);
      act.setProjectId(projectId);
      act.setWorkActivityId(workActivityId);

      WorkActivity wa = WorkActivity.builder()
          .code("WA003").name("Paving").defaultUnit("sqm").build();

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).activityId(activityId)
          .supervisorName("Test").activityName("C")
          .unit("cum") // mismatch
          .qtyExecuted(new BigDecimal("30")).build();

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(activityRepo.findByProjectId(projectId)).thenReturn(List.of(act));
      when(workActivityRepo.findById(workActivityId)).thenReturn(Optional.of(wa));

      int n = service.repairUnits(projectId, true);

      assertThat(n).isEqualTo(1);
      assertThat(dpr.getUnit()).isEqualTo("cum"); // unchanged — dry run
      verify(dprRepo, never()).save(dpr);
    }
  }

  @Nested
  class Rescale {

    @Test
    void rescalesManpowerNosIntoEfficiencyBandAndSetsLineCost() {
      UUID projectId = UUID.randomUUID();
      UUID activityId = UUID.randomUUID();
      UUID workActivityId = UUID.randomUUID();
      UUID roleId = UUID.randomUUID();
      UUID rateId = UUID.randomUUID();
      UUID dprId = UUID.randomUUID();

      Activity act = new Activity();
      act.setId(activityId);
      act.setProjectId(projectId);
      act.setWorkActivityId(workActivityId);

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).activityId(activityId)
          .supervisorName("Test").activityName("Earthwork").unit("cum")
          .qtyExecuted(new BigDecimal("200")).build();
      dpr.setId(dprId);

      DprManpower row = DprManpower.builder()
          .dprId(dprId).trade("Mason").nos(55)
          .roleId(roleId).manpowerRoleRateId(rateId).build();

      ProductivityNorm norm = ProductivityNorm.builder()
          .outputPerManPerDay(new BigDecimal("100")).build();

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(activityRepo.findByProjectId(projectId)).thenReturn(List.of(act));
      when(dprManpowerRepo.findByDprId(dprId)).thenReturn(List.of(row));
      when(dprEquipmentRepo.findByDprId(dprId)).thenReturn(List.of());
      when(normResolver.resolveByRole(
          eq(workActivityId), eq(roleId), any(), any(), any(), any(),
          eq(ProductivityNormType.MANPOWER)))
          .thenReturn(Optional.of(norm));
      when(rateResolver.resolveRate(eq(projectId), eq("MANPOWER"), eq(rateId)))
          .thenReturn(new BigDecimal("600"));

      int n = service.repairRescale(projectId, false);

      assertThat(n).isEqualTo(1);
      // budgetDays = 200/100 = 2; eff in [0.85,1.05] → nos ∈ {2,3}
      assertThat(row.getNos()).isIn(2, 3);
      double eff = (200.0 / 100.0) / row.getNos();
      assertThat(eff).isBetween(0.85, 1.05);
      assertThat(row.getUnitRate()).isEqualByComparingTo("600");
      assertThat(row.getLineCost())
          .isEqualByComparingTo(new BigDecimal("600").multiply(new BigDecimal(row.getNos())));
      verify(dprManpowerRepo).save(row);
    }

    @Test
    void dryRunDoesNotSave() {
      UUID projectId = UUID.randomUUID();
      UUID activityId = UUID.randomUUID();
      UUID workActivityId = UUID.randomUUID();
      UUID roleId = UUID.randomUUID();
      UUID rateId = UUID.randomUUID();
      UUID dprId = UUID.randomUUID();

      Activity act = new Activity();
      act.setId(activityId);
      act.setProjectId(projectId);
      act.setWorkActivityId(workActivityId);

      DailyProgressReport dpr = DailyProgressReport.builder()
          .projectId(projectId).activityId(activityId)
          .supervisorName("Test").activityName("Earthwork").unit("cum")
          .qtyExecuted(new BigDecimal("200")).build();
      dpr.setId(dprId);

      DprManpower row = DprManpower.builder()
          .dprId(dprId).trade("Mason").nos(55)
          .roleId(roleId).manpowerRoleRateId(rateId).build();

      ProductivityNorm norm = ProductivityNorm.builder()
          .outputPerManPerDay(new BigDecimal("100")).build();

      when(dprRepo.findByProjectId(projectId)).thenReturn(List.of(dpr));
      when(activityRepo.findByProjectId(projectId)).thenReturn(List.of(act));
      when(dprManpowerRepo.findByDprId(dprId)).thenReturn(List.of(row));
      when(dprEquipmentRepo.findByDprId(dprId)).thenReturn(List.of());
      when(normResolver.resolveByRole(
          eq(workActivityId), eq(roleId), any(), any(), any(), any(),
          eq(ProductivityNormType.MANPOWER)))
          .thenReturn(Optional.of(norm));

      int n = service.repairRescale(projectId, true);

      assertThat(n).isEqualTo(1);
      assertThat(row.getNos()).isEqualTo(55); // unchanged — dry run
      verify(dprManpowerRepo, never()).save(row);
    }
  }

  @Nested
  class Orchestration {

    private UUID projectId;
    private Activity act;

    @org.junit.jupiter.api.BeforeEach
    void setUpOrchestration() {
      projectId = UUID.randomUUID();
      act = new Activity();
      act.setId(UUID.randomUUID());
      act.setProjectId(projectId);

      // diagnose stubs (called before and after; lenient because dry-run calls diagnose once)
      org.mockito.Mockito.lenient().when(dprRepo.findByProjectId(projectId)).thenReturn(List.of());
      org.mockito.Mockito.lenient().when(dprRepo.findMinReportDate(projectId))
          .thenReturn(Optional.of(LocalDate.of(2026, 1, 1)));
      org.mockito.Mockito.lenient().when(dprRepo.findMaxReportDate(projectId))
          .thenReturn(Optional.of(LocalDate.of(2026, 1, 31)));

      // repairSupervisors stub (lenient — dryRun=true still calls this)
      org.mockito.Mockito.lenient().when(activityRepo.findByProjectId(projectId))
          .thenReturn(List.of(act));

      // repairRateLabels stubs (lenient)
      org.mockito.Mockito.lenient().when(manpowerCategoryMasterRepo.findByName("Skilled"))
          .thenReturn(Optional.empty());
      org.mockito.Mockito.lenient().when(gradeMasterRepo.findByCode("A"))
          .thenReturn(Optional.empty());

      // Phase B stubs — lenient because dry-run test must NOT call these
      org.mockito.Mockito.lenient().when(boqRebuildService.rebuildFromDprs(projectId)).thenReturn(3);
      org.mockito.Mockito.lenient()
          .when(dbsAggregationService.recomputeRange(eq(projectId), any(), any()))
          .thenReturn(List.of());
      org.mockito.Mockito.lenient().when(scAssignmentRepo.findByProjectId(projectId))
          .thenReturn(List.of());
    }

    @Test
    void dryRunFalse_invokesRebuildDepsAndReportsAllPhases() {
      RepairRequest req = new RepairRequest();
      req.setDryRun(false);
      req.setPhases(null); // all phases

      RepairReport report = service.repair(projectId, req);

      assertThat(report.dryRun()).isFalse();
      assertThat(report.changedByPhase()).containsKeys(
          "SUPERVISORS", "RATE_LABELS", "UNITS", "RESCALE", "REBUILD");
      verify(boqRebuildService).rebuildFromDprs(projectId);
      verify(dbsAggregationService).recomputeRange(
          eq(projectId),
          eq(LocalDate.of(2026, 1, 1)),
          eq(LocalDate.of(2026, 1, 31)));
    }

    @Test
    void dryRunTrue_doesNotInvokeRebuildDeps() {
      RepairRequest req = new RepairRequest();
      req.setDryRun(true);
      req.setPhases(null); // all phases

      RepairReport report = service.repair(projectId, req);

      assertThat(report.dryRun()).isTrue();
      assertThat(report.changedByPhase()).containsKey("REBUILD");
      assertThat(report.changedByPhase().get("REBUILD")).isEqualTo(0);
      verifyNoInteractions(boqRebuildService);
      verifyNoInteractions(dbsAggregationService);
    }
  }
}
