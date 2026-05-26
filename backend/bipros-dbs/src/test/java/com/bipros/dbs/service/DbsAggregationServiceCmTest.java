package com.bipros.dbs.service;

import com.bipros.dbs.domain.model.DbsDailyCm;
import com.bipros.dbs.domain.model.DbsDailySupervisor;
import com.bipros.dbs.domain.repository.DbsDailyCmRepository;
import com.bipros.dbs.domain.repository.DbsDailyEngineerRepository;
import com.bipros.dbs.domain.repository.DbsDailyProjectRepository;
import com.bipros.dbs.domain.repository.DbsDailySupervisorRepository;
import com.bipros.dbs.service.calculator.SectionAManpowerCalculator;
import com.bipros.dbs.service.calculator.SectionBAdminCalculator;
import com.bipros.dbs.service.calculator.SectionCMachineryCalculator;
import com.bipros.dbs.service.calculator.SectionDFuelCalculator;
import com.bipros.dbs.service.calculator.SectionEMaterialCalculator;
import com.bipros.dbs.service.calculator.SectionFBoqCalculator;
import com.bipros.dbs.service.calculator.SectionFBoqCalculator.BoqCumulative;
import com.bipros.project.application.service.ProjectTeamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.when;

/**
 * Phase 4: verifies that {@code recomputeCmDay} produces a {@link DbsDailyCm} whose
 * amount columns equal the sum of the contributing {@code DbsDailySupervisor} rows.
 *
 * <p>This test only stubs the supervisor-row finder and the upsert path — the rest of
 * the aggregation service surface is mocked out so the assertions stay focused on the
 * CM-tier arithmetic.
 */
@ExtendWith(MockitoExtension.class)
class DbsAggregationServiceCmTest {

    @Mock private DbsDailySupervisorRepository supervisorRepo;
    @Mock private DbsDailyEngineerRepository engineerRepo;
    @Mock private DbsDailyProjectRepository projectRepo;
    @Mock private DbsDailyCmRepository cmRepo;
    @Mock private SectionAManpowerCalculator manpowerCalc;
    @Mock private SectionBAdminCalculator adminCalc;
    @Mock private SectionCMachineryCalculator machineryCalc;
    @Mock private SectionDFuelCalculator fuelCalc;
    @Mock private SectionEMaterialCalculator materialCalc;
    @Mock private SectionFBoqCalculator boqCalc;
    @Mock private ProjectTeamService projectTeamService;
    @Mock private ObjectMapper objectMapper;
    @Mock private RegisterAggregationService registerAggregationService;
    @Mock private DbsRecomputeLock recomputeLock;

    @InjectMocks private DbsAggregationService service;

    @Test
    @DisplayName("recomputeCmDay sums supervisor rows under the same CM")
    void recomputeCmDay_sumsSupervisorRows() {
        UUID projectId = UUID.randomUUID();
        UUID cmId = UUID.randomUUID();
        UUID sup1 = UUID.randomUUID();
        UUID sup2 = UUID.randomUUID();
        UUID eng1 = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 18);

        DbsDailySupervisor row1 = DbsDailySupervisor.builder()
            .projectId(projectId)
            .supervisorUserId(sup1)
            .engineerUserId(eng1)
            .constructionManagerUserId(cmId)
            .reportDate(date)
            .manpowerAmount(new BigDecimal("1000.00"))
            .adminAmount(new BigDecimal("100.00"))
            .machineryAmount(new BigDecimal("500.00"))
            .fuelAmount(new BigDecimal("50.00"))
            .materialAmount(new BigDecimal("2000.00"))
            .boqForTheDayAmount(new BigDecimal("5000.00"))
            .boqPlannedAmount(new BigDecimal("100000.00"))
            .boqAchievedAmount(new BigDecimal("60000.00"))
            .totalExpense(new BigDecimal("3650.00"))
            .totalIncome(new BigDecimal("5000.00"))
            // Phase 7: supervisor row carries the split. Direct + Prelim = boqForTheDayAmount.
            .directCost(new BigDecimal("4500.00"))
            .prelimCost(new BigDecimal("500.00"))
            .build();

        DbsDailySupervisor row2 = DbsDailySupervisor.builder()
            .projectId(projectId)
            .supervisorUserId(sup2)
            .engineerUserId(eng1)
            .constructionManagerUserId(cmId)
            .reportDate(date)
            .manpowerAmount(new BigDecimal("2000.00"))
            .adminAmount(new BigDecimal("200.00"))
            .machineryAmount(new BigDecimal("800.00"))
            .fuelAmount(new BigDecimal("100.00"))
            .materialAmount(new BigDecimal("3000.00"))
            .boqForTheDayAmount(new BigDecimal("8000.00"))
            .boqPlannedAmount(new BigDecimal("100000.00"))
            .boqAchievedAmount(new BigDecimal("60000.00"))
            .totalExpense(new BigDecimal("6100.00"))
            .totalIncome(new BigDecimal("8000.00"))
            .directCost(new BigDecimal("7000.00"))
            .prelimCost(new BigDecimal("1000.00"))
            .build();

        when(supervisorRepo.findByProjectIdAndReportDateAndConstructionManagerUserId(projectId, date, cmId))
            .thenReturn(List.of(row1, row2));
        when(cmRepo.findByProjectIdAndCmUserIdAndReportDate(projectId, cmId, date))
            .thenReturn(Optional.empty());
        when(cmRepo.save(any(DbsDailyCm.class))).thenAnswer(inv -> inv.getArgument(0));
        // BOQ cumulative now comes from a deduped scope query, not a sum across supervisor
        // rows. Stub the planned/achieved this CM scope is expected to see:
        //   planned = 200000 (single unique BOQ item both supervisors touched),
        //   achieved = 120000 (cumulative qty × rate at the time of recompute).
        when(boqCalc.computeCumulativeForScope(eq(projectId), eq(date), any()))
            .thenReturn(new BoqCumulative(new BigDecimal("200000.00"), new BigDecimal("120000.00")));

        DbsDailyCm result = service.recomputeCmDay(projectId, cmId, date);

        ArgumentCaptor<DbsDailyCm> captor = ArgumentCaptor.forClass(DbsDailyCm.class);
        org.mockito.Mockito.verify(cmRepo).save(captor.capture());
        DbsDailyCm saved = captor.getValue();

        assertThat(saved.getProjectId()).isEqualTo(projectId);
        assertThat(saved.getCmUserId()).isEqualTo(cmId);
        assertThat(saved.getReportDate()).isEqualTo(date);
        assertThat(saved.getManpowerAmount()).isEqualByComparingTo("3000.00");
        assertThat(saved.getAdminAmount()).isEqualByComparingTo("300.00");
        assertThat(saved.getMachineryAmount()).isEqualByComparingTo("1300.00");
        assertThat(saved.getFuelAmount()).isEqualByComparingTo("150.00");
        assertThat(saved.getMaterialAmount()).isEqualByComparingTo("5000.00");
        assertThat(saved.getBoqForTheDayAmount()).isEqualByComparingTo("13000.00");
        assertThat(saved.getBoqPlannedToDate()).isEqualByComparingTo("200000.00");
        assertThat(saved.getBoqAchievedToDate()).isEqualByComparingTo("120000.00");
        // Phase 7: direct = 4500 + 7000 = 11500; prelim = 500 + 1000 = 1500.
        assertThat(saved.getDirectCost()).isEqualByComparingTo("11500.00");
        assertThat(saved.getPrelimCost()).isEqualByComparingTo("1500.00");
        assertThat(saved.getTotalCostInclPrelims()).isEqualByComparingTo("13000.00");
        // pctAchieved = 120000 / 200000 * 100 = 60.00
        assertThat(saved.getPctAchieved()).isEqualByComparingTo("60.0000");
        // engineerIds collected from supervisor rows; only eng1 here.
        assertThat(saved.getEngineerIds()).containsExactly(eng1);
        assertThat(saved.getSupervisorCount()).isEqualTo(2);

        assertThat(result).isSameAs(saved);
    }
}
