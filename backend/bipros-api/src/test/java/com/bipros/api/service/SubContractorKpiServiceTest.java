package com.bipros.api.service;

import com.bipros.api.service.SubContractorKpiService.SubContractorKpiResponse;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.SubContractorMasterRepository;
import com.bipros.resource.domain.repository.SubContractorWorkActivityMappingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubContractorKpiServiceTest {

    @Mock private SubContractorMasterRepository scMasterRepo;
    @Mock private SubContractorWorkActivityMappingRepository scMappingRepo;
    @Mock private ActivitySubContractorAssignmentRepository assignmentRepo;
    @Mock private DprSubContractorRepository dprScRepo;
    @Mock private DailyProgressReportRepository dprRepo;
    @Mock private EntityManager em;
    @Mock private Query mainQuery;
    @Mock private Query orphanQuery;

    @Test
    void emptyProjectReturnsZeroResponse() {
        UUID projectId = UUID.randomUUID();

        when(em.createNativeQuery(any(String.class))).thenReturn(mainQuery, orphanQuery);
        when(mainQuery.setParameter(any(String.class), any())).thenReturn(mainQuery);
        when(mainQuery.getResultList()).thenReturn(List.of());
        when(orphanQuery.setParameter(any(String.class), any())).thenReturn(orphanQuery);
        when(orphanQuery.getSingleResult()).thenReturn(0L);

        SubContractorKpiService service = new SubContractorKpiService(
                scMasterRepo, scMappingRepo, assignmentRepo, dprScRepo, dprRepo);
        ReflectionTestUtils.setField(service, "em", em);

        SubContractorKpiResponse r = service.compute(
                projectId, LocalDate.of(2026, 4, 24), LocalDate.of(2026, 5, 24));

        assertThat(r.activeSubContractors()).isZero();
        assertThat(r.workTypesTracked()).isZero();
        assertThat(r.totalPlannedQty().signum()).isZero();
        assertThat(r.totalActualQty().signum()).isZero();
        assertThat(r.perScWorkType()).isEmpty();
        assertThat(r.bottomProductivity()).isEmpty();
        assertThat(r.topByCost()).isEmpty();
        assertThat(r.bottomOutputAchievement()).isEmpty();
        assertThat(r.unmatchedDprRows()).isZero();
    }
}
