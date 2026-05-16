package com.bipros.resource.application.service;

import com.bipros.resource.application.dto.LabourMasterDashboardSummary;
import com.bipros.resource.application.dto.ProjectLabourDeploymentRequest;
import com.bipros.resource.application.dto.ProjectLabourDeploymentResponse;
import com.bipros.resource.domain.model.LabourCategory;
import com.bipros.resource.domain.model.LabourDesignation;
import com.bipros.resource.domain.model.LabourGrade;
import com.bipros.resource.domain.model.LabourStatus;
import com.bipros.resource.domain.model.NationalityType;
import com.bipros.resource.domain.model.ProjectLabourDeployment;
import com.bipros.resource.domain.repository.LabourDesignationRepository;
import com.bipros.resource.domain.repository.ProjectLabourDeploymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectLabourDeploymentServiceTest {

    @Mock LabourDesignationRepository designationRepo;
    @Mock ProjectLabourDeploymentRepository deploymentRepo;
    @Mock LabourDesignationService designationService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ProjectLabourDeploymentService service;

    private LabourDesignation pm() {
        LabourDesignation d = LabourDesignation.builder()
            .code("SM-001").designation("Project Manager")
            .category(LabourCategory.SITE_MANAGEMENT).trade("Civil")
            .grade(LabourGrade.A).nationality(NationalityType.OMANI_OR_EXPAT)
            .experienceYearsMin(15).defaultDailyRate(new BigDecimal("125.00"))
            .currency("OMR").skills(List.of()).certifications(List.of())
            .status(LabourStatus.ACTIVE).sortOrder(1).build();
        d.setId(UUID.randomUUID());
        return d;
    }

    @Test
    void create_usesActualRateWhenProvided() {
        UUID projectId = UUID.randomUUID();
        LabourDesignation d = pm();
        when(designationRepo.findById(d.getId())).thenReturn(Optional.of(d));
        when(deploymentRepo.existsByProjectIdAndDesignationId(projectId, d.getId())).thenReturn(false);
        when(deploymentRepo.save(any(ProjectLabourDeployment.class)))
            .thenAnswer(inv -> { ProjectLabourDeployment p = inv.getArgument(0);
                                  p.setId(UUID.randomUUID()); return p; });

        ProjectLabourDeploymentResponse out = service.create(projectId,
            new ProjectLabourDeploymentRequest(d.getId(), 1, new BigDecimal("130.00"), null));

        assertThat(out.effectiveRate()).isEqualByComparingTo("130.00");
        assertThat(out.dailyCost()).isEqualByComparingTo("130.00");
    }

    @Test
    void create_fallsBackToDesignationDefaultRate() {
        UUID projectId = UUID.randomUUID();
        LabourDesignation d = pm();
        when(designationRepo.findById(d.getId())).thenReturn(Optional.of(d));
        when(deploymentRepo.existsByProjectIdAndDesignationId(projectId, d.getId())).thenReturn(false);
        when(deploymentRepo.save(any(ProjectLabourDeployment.class)))
            .thenAnswer(inv -> { ProjectLabourDeployment p = inv.getArgument(0);
                                  p.setId(UUID.randomUUID()); return p; });

        ProjectLabourDeploymentResponse out = service.create(projectId,
            new ProjectLabourDeploymentRequest(d.getId(), 2, null, null));

        assertThat(out.effectiveRate()).isEqualByComparingTo("125.00");
        assertThat(out.dailyCost()).isEqualByComparingTo("250.00");
    }

    @Test
    void dashboardSummary_aggregatesAcrossCategories() {
        UUID projectId = UUID.randomUUID();
        LabourDesignation d = pm();
        ProjectLabourDeployment dep = ProjectLabourDeployment.builder()
            .projectId(projectId).designationId(d.getId())
            .workerCount(1).actualDailyRate(null).build();
        dep.setId(UUID.randomUUID());

        when(deploymentRepo.findAllByProjectId(projectId)).thenReturn(List.of(dep));
        when(designationRepo.findAllById(List.of(d.getId()))).thenReturn(List.of(d));

        LabourMasterDashboardSummary out = service.dashboard(projectId);

        assertThat(out.totalDesignations()).isEqualTo(1);
        assertThat(out.totalWorkforce()).isEqualTo(1);
        assertThat(out.dailyPayroll()).isEqualByComparingTo("125.00");
        assertThat(out.skillCategoryCount()).isEqualTo(1);
        assertThat(out.byCategory()).hasSize(1);
    }
}
