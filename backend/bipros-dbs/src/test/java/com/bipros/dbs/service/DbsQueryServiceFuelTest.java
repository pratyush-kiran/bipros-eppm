package com.bipros.dbs.service;

import com.bipros.dbs.api.dto.DbsSupervisorDayResponse;
import com.bipros.dbs.config.DbsProperties;
import com.bipros.dbs.domain.model.DbsDailySupervisor;
import com.bipros.dbs.domain.repository.DbsDailyCmRepository;
import com.bipros.dbs.domain.repository.DbsDailyEngineerRepository;
import com.bipros.dbs.domain.repository.DbsDailyProjectRepository;
import com.bipros.dbs.domain.repository.DbsDailySupervisorRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Section D (Fuel) is derived from Section C (Machinery) at READ time, so the DBS shows the
 * correct fuel for any supervisor / period without any recompute. This test proves the
 * derivation holds even when the stored row is STALE (fuel=0, totalExpense without fuel) —
 * i.e. a day that pre-dates the fuel-rule change and was never recomputed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DbsQueryService — read-time fuel derivation")
class DbsQueryServiceFuelTest {

    @Mock private DbsDailySupervisorRepository supervisorRepo;
    @Mock private DbsDailyEngineerRepository engineerRepo;
    @Mock private DbsDailyProjectRepository projectRepo;
    @Mock private DbsDailyCmRepository cmRepo;
    @Mock private DbsAggregationService aggregationService;
    @Mock private DailyProgressReportRepository dprRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private DbsAlertEvaluator alertEvaluator;
    @Mock private DbsProperties dbsProperties;

    @InjectMocks private DbsQueryService service;

    @Test
    @DisplayName("supervisor day derives fuel = 35% × machinery even when the stored fuel is stale (0)")
    void derivesFuelWhenStoredRowIsStale() {
        UUID projectId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 3, 30);

        // Stored as if never recomputed since the fuel-rule change:
        //   fuel = 0, totalExpense = manpower(2.58) + machinery(52.00) = 54.58 (no fuel).
        // supervisorUserId = null so the mapper skips DB-backed name resolution.
        DbsDailySupervisor row = DbsDailySupervisor.builder()
            .projectId(projectId)
            .supervisorUserId(null)
            .reportDate(date)
            .materialAmount(BigDecimal.ZERO)
            .manpowerAmount(new BigDecimal("2.58"))
            .adminAmount(BigDecimal.ZERO)
            .machineryAmount(new BigDecimal("52.00"))
            .fuelAmount(BigDecimal.ZERO)
            .subcontractAmount(BigDecimal.ZERO)
            .totalExpense(new BigDecimal("54.58"))
            .totalIncome(new BigDecimal("892.80"))
            .contribution(new BigDecimal("838.22"))
            .contributionPct(new BigDecimal("0.9391"))
            .build();

        when(dbsProperties.getFuelMachineryCostRatio()).thenReturn(new BigDecimal("0.35"));
        when(supervisorRepo.findByProjectIdAndSupervisorUserIdAndReportDate(projectId, null, date))
            .thenReturn(Optional.of(row));

        DbsSupervisorDayResponse r = service.getSupervisorDay(projectId, null, date);

        assertThat(r.fuelAmount()).isEqualByComparingTo("18.20");         // 52.00 × 0.35
        assertThat(r.totalExpense()).isEqualByComparingTo("72.78");       // 54.58 − 0 + 18.20
        assertThat(r.totalIncome()).isEqualByComparingTo("892.80");
        assertThat(r.contribution()).isEqualByComparingTo("820.02");      // 892.80 − 72.78
        assertThat(r.contributionPct()).isEqualByComparingTo("0.9185");   // 820.02 / 892.80, 4dp HALF_UP
    }
}
