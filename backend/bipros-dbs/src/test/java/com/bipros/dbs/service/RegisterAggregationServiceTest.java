package com.bipros.dbs.service;

import com.bipros.dbs.domain.model.DbsEquipmentRegisterRow;
import com.bipros.dbs.domain.model.DbsManpowerRegisterRow;
import com.bipros.dbs.domain.repository.DbsEquipmentRegisterRowRepository;
import com.bipros.dbs.domain.repository.DbsManpowerRegisterRowRepository;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.model.Shift;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 unit test for {@link RegisterAggregationService#recompute(UUID, LocalDate)}.
 *
 * <p>Two DPRs on the same date, both with one Grader equipment row — one DAY, one
 * NIGHT, both supervisors reporting up to the same CM. After recompute we expect
 * exactly 2 equipment register rows (one per shift), each carrying the summed
 * count under that CM.
 */
@ExtendWith(MockitoExtension.class)
class RegisterAggregationServiceTest {

    @Mock private DbsEquipmentRegisterRowRepository equipmentRepo;
    @Mock private DbsManpowerRegisterRowRepository manpowerRepo;
    @Mock private ProjectTeamService projectTeamService;
    @Mock private EntityManager em;
    @Mock private Query equipmentQuery;
    @Mock private Query manpowerQuery;

    @InjectMocks private RegisterAggregationService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID cmId = UUID.randomUUID();
    private final UUID sup1 = UUID.randomUUID();
    private final UUID sup2 = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 5, 18);

    @BeforeEach
    void injectEntityManager() throws Exception {
        // @InjectMocks won't satisfy @PersistenceContext (it's not a constructor /
        // setter), so wire the mocked EntityManager via reflection.
        Field f = RegisterAggregationService.class.getDeclaredField("em");
        f.setAccessible(true);
        f.set(service, em);
    }

    @Test
    @DisplayName("recompute: two Grader DPRs (DAY + NIGHT) under same CM produce 2 rows")
    void recompute_aggregatesEquipmentByShiftAndCm() {
        // Two supervisor DPRs, both deploying 1 Grader: sup1 = DAY, sup2 = NIGHT.
        // Each row in the native query result mirrors the SELECT clause column order:
        //   [equipment_type, shift, nos, hrs, rate, line_cost, supervisor_user_id]
        Object[] dayRow = new Object[]{
            "Grader", "DAY", 1, new BigDecimal("8.00"), new BigDecimal("500.0000"),
            new BigDecimal("4000.00"), sup1
        };
        Object[] nightRow = new Object[]{
            "Grader", "NIGHT", 1, new BigDecimal("8.00"), new BigDecimal("500.0000"),
            new BigDecimal("4000.00"), sup2
        };

        // First createNativeQuery call = equipment SQL; second = manpower SQL (returns
        // empty so manpowerRepo.saveAll is not invoked).
        when(em.createNativeQuery(anyString()))
            .thenReturn(equipmentQuery)
            .thenReturn(manpowerQuery);
        when(equipmentQuery.setParameter(anyString(), any())).thenReturn(equipmentQuery);
        when(manpowerQuery.setParameter(anyString(), any())).thenReturn(manpowerQuery);
        when(equipmentQuery.getResultList()).thenReturn(List.<Object[]>of(dayRow, nightRow));
        when(manpowerQuery.getResultList()).thenReturn(List.of());

        // Both supervisors resolve to the same CM.
        when(projectTeamService.resolveCmFor(projectId, sup1)).thenReturn(Optional.of(cmId));
        when(projectTeamService.resolveCmFor(projectId, sup2)).thenReturn(Optional.of(cmId));

        // delete* methods return int — let Mockito default (0) work without stubbing.
        lenient().when(equipmentRepo.deleteByProjectIdAndReportDate(eq(projectId), eq(date))).thenReturn(0);
        lenient().when(manpowerRepo.deleteByProjectIdAndReportDate(eq(projectId), eq(date))).thenReturn(0);

        service.recompute(projectId, date);

        // Verify the delete-then-insert idempotency contract.
        verify(equipmentRepo).deleteByProjectIdAndReportDate(projectId, date);
        verify(manpowerRepo).deleteByProjectIdAndReportDate(projectId, date);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DbsEquipmentRegisterRow>> captor =
            ArgumentCaptor.forClass(List.class);
        verify(equipmentRepo).saveAll(captor.capture());

        List<DbsEquipmentRegisterRow> saved = captor.getValue();
        assertThat(saved).hasSize(2);

        DbsEquipmentRegisterRow dayOut = saved.stream()
            .filter(r -> r.getShift() == Shift.DAY).findFirst().orElseThrow();
        DbsEquipmentRegisterRow nightOut = saved.stream()
            .filter(r -> r.getShift() == Shift.NIGHT).findFirst().orElseThrow();

        assertThat(dayOut.getProjectId()).isEqualTo(projectId);
        assertThat(dayOut.getReportDate()).isEqualTo(date);
        assertThat(dayOut.getCmUserId()).isEqualTo(cmId);
        assertThat(dayOut.getEquipmentType()).isEqualTo("Grader");
        assertThat(dayOut.getCountNos()).isEqualTo(1);
        assertThat(dayOut.getWorkingHours()).isEqualByComparingTo("8.00");
        assertThat(dayOut.getLineCost()).isEqualByComparingTo("4000.00");

        assertThat(nightOut.getCmUserId()).isEqualTo(cmId);
        assertThat(nightOut.getEquipmentType()).isEqualTo("Grader");
        assertThat(nightOut.getCountNos()).isEqualTo(1);
    }
}
