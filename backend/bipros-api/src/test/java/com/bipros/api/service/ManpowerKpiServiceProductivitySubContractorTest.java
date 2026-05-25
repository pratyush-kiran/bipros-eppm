package com.bipros.api.service;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.repository.ManpowerAttendanceRepository;
import com.bipros.resource.domain.repository.ManpowerFinancialsRepository;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManpowerKpiServiceProductivitySubContractorTest {

    @Mock private BoqItemRepository boqItemRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ResourceRepository resourceRepository;
    @Mock private ManpowerAttendanceRepository attendanceRepository;
    @Mock private ManpowerFinancialsRepository financialsRepository;
    @Mock private ProductivityNormRepository productivityNormRepository;
    @Mock private ResourceAssignmentRepository resourceAssignmentRepository;
    @Mock private DailyProgressReportRepository dprRepository;
    @Mock private DprManpowerRepository dprManpowerRepository;
    @Mock private DprSubContractorRepository dprSubContractorRepository;

    @Test
    void productivityFactorSubtractsSubContractorQtyFromQtyExecuted() {
        UUID projectId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID boqItemId = UUID.randomUUID();
        UUID workActivityId = UUID.randomUUID();

        // 100 Tonne completed in window — 50 from crew, 50 from SC
        DailyProgressReport dpr = new DailyProgressReport();
        dpr.setProjectId(projectId);
        dpr.setActivityId(activityId);
        dpr.setBoqItemId(boqItemId);
        dpr.setQtyExecuted(new BigDecimal("100"));
        dpr.setReportDate(LocalDate.of(2026, 5, 23));
        dpr.setId(UUID.randomUUID());

        DprManpower mp = new DprManpower();
        mp.setDprId(dpr.getId());
        mp.setNos(10);
        mp.setLineCost(new BigDecimal("57.00"));    // 10 helpers × 5.70/day

        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setName("Asphalt Laying");
        activity.setWorkActivityId(workActivityId);

        BoqItem boq = new BoqItem();
        boq.setId(boqItemId);
        boq.setItemNo("2.1.5");
        boq.setDescription("Asphalt Laying");
        boq.setUnit("Tonne");
        boq.setBoqQty(new BigDecimal("500"));

        ProductivityNorm norm = new ProductivityNorm();
        norm.setNormType(ProductivityNormType.MANPOWER);
        norm.setOutputPerManPerDay(new BigDecimal("2.5"));     // norm: 2.5 Tonne/person/day
        norm.setUnit("Tonne");

        // Service calls findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc
        when(dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(dpr));
        when(dprManpowerRepository.findByDprIdIn(any())).thenReturn(List.of(mp));
        // Service calls findAllById for activities
        when(activityRepository.findAllById(any())).thenReturn(List.of(activity));
        // Service calls findByProjectIdOrderByItemNoAsc for BOQ items
        when(boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId)).thenReturn(List.of(boq));
        // Norm lookup goes through activity.workActivityId
        when(productivityNormRepository.findByWorkActivityId(workActivityId)).thenReturn(List.of(norm));
        when(resourceAssignmentRepository.findByProjectId(projectId)).thenReturn(List.of());

        // SC contributed 50 Tonne to this activity
        List<Object[]> scByActivity = new java.util.ArrayList<>();
        scByActivity.add(new Object[]{activityId, new BigDecimal("50")});
        when(dprSubContractorRepository.sumQuantityByProjectGroupedByActivity(projectId))
                .thenReturn(scByActivity);

        ManpowerKpiService service = new ManpowerKpiService(
                boqItemRepository, activityRepository, resourceRepository,
                attendanceRepository, financialsRepository, productivityNormRepository,
                resourceAssignmentRepository, dprRepository, dprManpowerRepository,
                dprSubContractorRepository);

        var response = service.compute(projectId, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        // Without fix: PF = (100/10) / 2.5 = 4.0 (inflated — credits SC's 50 to crew)
        // With fix:    PF = ((100−50)/10) / 2.5 = 2.0 (real — crew's true productivity)
        assertThat(response.headlineProductivityFactor()).isEqualTo(2.0d);
    }

    @Test
    void costPerUnitSubtractsSubContractorQtyFromDenominator() {
        UUID projectId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID boqItemId = UUID.randomUUID();

        DailyProgressReport dpr = new DailyProgressReport();
        dpr.setProjectId(projectId);
        dpr.setActivityId(activityId);
        dpr.setBoqItemId(boqItemId);
        dpr.setQtyExecuted(new BigDecimal("100"));    // 50 by crew + 50 by SC
        dpr.setReportDate(LocalDate.of(2026, 5, 23));
        dpr.setId(UUID.randomUUID());

        DprManpower mp = new DprManpower();
        mp.setDprId(dpr.getId());
        mp.setNos(10);
        mp.setLineCost(new BigDecimal("100.00"));     // crew labour cost

        BoqItem boq = new BoqItem();
        boq.setId(boqItemId);
        boq.setItemNo("2.1.5");
        boq.setDescription("Asphalt Laying");
        boq.setUnit("Tonne");
        boq.setBoqQty(new BigDecimal("500"));

        when(dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(any(), any(), any()))
                .thenReturn(List.of(dpr));
        when(dprManpowerRepository.findByDprIdIn(any())).thenReturn(List.of(mp));
        when(activityRepository.findAllById(any())).thenReturn(List.of());
        when(boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId)).thenReturn(List.of(boq));
        when(resourceAssignmentRepository.findByProjectId(projectId)).thenReturn(List.of());

        // SC contributed 50 Tonne to this BOQ item
        List<Object[]> scByBoqItem = new java.util.ArrayList<>();
        scByBoqItem.add(new Object[]{boqItemId, new BigDecimal("50")});
        when(dprSubContractorRepository.sumQuantityByProjectGroupedByActivity(projectId))
                .thenReturn(List.of());
        when(dprSubContractorRepository.sumQuantityByProjectGroupedByBoqItem(projectId))
                .thenReturn(scByBoqItem);

        ManpowerKpiService service = new ManpowerKpiService(
                boqItemRepository, activityRepository, resourceRepository,
                attendanceRepository, financialsRepository, productivityNormRepository,
                resourceAssignmentRepository, dprRepository, dprManpowerRepository,
                dprSubContractorRepository);

        var response = service.compute(projectId, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        // Without fix: cpu = 100 / 100 = 1.0
        // With fix:    cpu = 100 / (100 − 50) = 2.0 (real crew cost per crew-delivered unit)
        assertThat(response.labourCostPerUnit()).hasSize(1);
        assertThat(response.labourCostPerUnit().get(0).costPerUnit()).isEqualTo(2.0d);
        assertThat(response.labourCostPerUnit().get(0).qtyExecuted()).isEqualTo(50.0d);
    }
}
