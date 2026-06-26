package com.bipros.project.application.service;

import com.bipros.project.application.dto.DailyCostReportResponse;
import com.bipros.project.application.dto.DailyCostReportRow;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the joined DPR × BOQ formulas used for the Daily Cost Report. The hand-computed
 * reference values come from rows 32–43 of the workbook's "Daily Cost Report" sheet.
 */
class DailyCostReportServiceTest {

  private DailyProgressReportRepository dprRepo;
  private BoqItemRepository boqRepo;
  private ProjectRepository projectRepo;
  private DailyCostReportService service;

  private UUID projectId;
  private LocalDate d07 = LocalDate.of(2025, 4, 7);
  private LocalDate d11 = LocalDate.of(2025, 4, 11);

  @BeforeEach
  void setup() {
    dprRepo = mock(DailyProgressReportRepository.class);
    boqRepo = mock(BoqItemRepository.class);
    projectRepo = mock(ProjectRepository.class);
    service = new DailyCostReportService(dprRepo, boqRepo, projectRepo);
    projectId = UUID.randomUUID();
    when(projectRepo.existsById(projectId)).thenReturn(true);
  }

  @Test
  void earthwork_excavation_row_matches_workbook_line_32() {
    // Workbook row 32: 07-Apr-25, Earthwork – Excavation, 850 Cum, budgeted 180, actual 185
    // Expected: budgetedCost 153,000 ; actualCost 157,250 ; variance 4,250 ; variance % 2.777…%
    BoqItem boq11 = BoqItem.builder()
        .itemNo("1.1")
        .description("Earthwork – Excavation in all types of soil")
        .budgetedRate(new BigDecimal("180"))
        .actualRate(new BigDecimal("185"))
        .build();
    DailyProgressReport dpr = DailyProgressReport.builder()
        .projectId(projectId)
        .reportDate(d07)
        .supervisorName("R.K. Verma")
        .activityName("Earthwork – Excavation")
        .unit("Cum")
        .qtyExecuted(new BigDecimal("850"))
        .boqItemNo("1.1")
        .build();
    dpr.setId(UUID.randomUUID());

    when(dprRepo.findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
        ArgumentMatchers.eq(projectId), ArgumentMatchers.eq(DprApprovalStatus.APPROVED),
        ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(dpr));
    when(boqRepo.findByProjectIdOrderByItemNoAsc(projectId)).thenReturn(List.of(boq11));

    DailyCostReportResponse resp = service.generate(projectId, d07, d07);
    assertThat(resp.rows()).hasSize(1);
    DailyCostReportRow row = resp.rows().get(0);
    assertThat(row.budgetedCost()).isEqualByComparingTo("153000.00");
    assertThat(row.actualCost()).isEqualByComparingTo("157250.00");
    assertThat(row.variance()).isEqualByComparingTo("4250.00");
    // (185−180)/180 = 0.027777… rounded to 6dp
    assertThat(row.variancePercent()).isEqualByComparingTo("0.027778");
    assertThat(resp.periodBudgetedCost()).isEqualByComparingTo("153000.00");
    assertThat(resp.periodActualCost()).isEqualByComparingTo("157250.00");
  }

  @Test
  void dbm_row_matches_workbook_line_40_and_period_total_sums_correctly() {
    // Row 40 DBM 320 MT @ 4500/4620 → budgeted 1,440,000 / actual 1,478,400 / variance 38,400
    // Row 41 BC 180 MT @ 5200/5320 → budgeted 936,000 / actual 957,600 / variance 21,600
    BoqItem boq31 = BoqItem.builder()
        .itemNo("3.1").description("DBM – Dense Bituminous Macadam (50mm)")
        .budgetedRate(new BigDecimal("4500")).actualRate(new BigDecimal("4620")).build();
    BoqItem boq32 = BoqItem.builder()
        .itemNo("3.2").description("BC – Bituminous Concrete (40mm)")
        .budgetedRate(new BigDecimal("5200")).actualRate(new BigDecimal("5320")).build();

    DailyProgressReport dbm = DailyProgressReport.builder()
        .projectId(projectId).reportDate(d11).supervisorName("R.K. Verma")
        .activityName("DBM – Dense Bituminous Macadam").unit("MT")
        .qtyExecuted(new BigDecimal("320")).boqItemNo("3.1").build();
    dbm.setId(UUID.randomUUID());
    DailyProgressReport bc = DailyProgressReport.builder()
        .projectId(projectId).reportDate(d11).supervisorName("R.K. Verma")
        .activityName("BC – Bituminous Concrete").unit("MT")
        .qtyExecuted(new BigDecimal("180")).boqItemNo("3.2").build();
    bc.setId(UUID.randomUUID());

    when(dprRepo.findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
        ArgumentMatchers.eq(projectId), ArgumentMatchers.eq(DprApprovalStatus.APPROVED),
        ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(dbm, bc));
    when(boqRepo.findByProjectIdOrderByItemNoAsc(projectId)).thenReturn(List.of(boq31, boq32));

    DailyCostReportResponse resp = service.generate(projectId, d11, d11);

    assertThat(resp.rows().get(0).budgetedCost()).isEqualByComparingTo("1440000.00");
    assertThat(resp.rows().get(0).actualCost()).isEqualByComparingTo("1478400.00");
    assertThat(resp.rows().get(0).variance()).isEqualByComparingTo("38400.00");

    assertThat(resp.rows().get(1).budgetedCost()).isEqualByComparingTo("936000.00");
    assertThat(resp.rows().get(1).actualCost()).isEqualByComparingTo("957600.00");
    assertThat(resp.rows().get(1).variance()).isEqualByComparingTo("21600.00");

    assertThat(resp.periodBudgetedCost()).isEqualByComparingTo("2376000.00");
    assertThat(resp.periodActualCost()).isEqualByComparingTo("2436000.00");
    assertThat(resp.periodVariance()).isEqualByComparingTo("60000.00");
  }

  @Test
  void activity_with_no_matching_boq_yields_null_rates_without_exploding() {
    DailyProgressReport orphan = DailyProgressReport.builder()
        .projectId(projectId).reportDate(d07).supervisorName("M.D. Rao")
        .activityName("Totally-unknown activity with no BOQ")
        .unit("Cum").qtyExecuted(new BigDecimal("100")).build();
    orphan.setId(UUID.randomUUID());

    when(dprRepo.findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
        ArgumentMatchers.eq(projectId), ArgumentMatchers.eq(DprApprovalStatus.APPROVED),
        ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(orphan));
    when(boqRepo.findByProjectIdOrderByItemNoAsc(projectId)).thenReturn(List.of());

    DailyCostReportResponse resp = service.generate(projectId, d07, d07);

    assertThat(resp.rows()).hasSize(1);
    assertThat(resp.rows().get(0).budgetedCost()).isNull();
    assertThat(resp.rows().get(0).actualCost()).isNull();
    assertThat(resp.rows().get(0).variance()).isNull();
    assertThat(resp.periodBudgetedCost()).isEqualByComparingTo("0.00");
  }
}
