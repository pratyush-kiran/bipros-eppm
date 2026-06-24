package com.bipros.evm.application.service;

import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.entity.EvmTechnique;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.application.service.DprActualCostLookup;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.cost.domain.repository.CostAccountRepository;
import com.bipros.common.util.AuditService;
import com.bipros.activity.application.percent.PercentCompleteCalculator;
import com.bipros.udf.application.service.FormulaEngine;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvmServiceComputeSnapshotTest {

    @Mock EvmCalculationRepository evmCalculationRepository;
    @Mock ActivityRepository activityRepository;
    @Mock ActivityExpenseRepository activityExpenseRepository;
    @Mock ResourceAssignmentRepository resourceAssignmentRepository;
    @Mock ActivitySubContractorAssignmentRepository activitySubContractorAssignmentRepository;
    @Mock CostAccountRepository costAccountRepository;
    @Mock WbsNodeRepository wbsNodeRepository;
    @Mock ProjectRepository projectRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock FormulaEngine formulaEngine;
    @Mock DprActualCostLookup dprActualCostLookup;
    @Mock PercentCompleteCalculator percentCompleteCalculator;

    @InjectMocks EvmService evmService;

    @Test
    void computeEvmSnapshot_setsBacFromCurrentBudgetAndDoesNotPersist() {
        UUID projectId = UUID.randomUUID();
        Project project = new Project();
        project.setBudgetCurrency("OMR");
        project.setCurrentBudget(new BigDecimal("50"));   // 50 OMR major-unit = 50,000,000 raw
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(activityRepository.findByProjectId(projectId)).thenReturn(List.of());
        when(activityExpenseRepository.findByProjectId(projectId)).thenReturn(List.of());
        when(resourceAssignmentRepository.findByProjectId(projectId)).thenReturn(List.of());
        when(activitySubContractorAssignmentRepository.findByProjectId(projectId)).thenReturn(List.of());
        when(dprActualCostLookup.sumByActivity(projectId)).thenReturn(java.util.Map.of());

        EvmCalculation snapshot = evmService.computeEvmSnapshot(
                projectId, EvmTechnique.ACTIVITY_PERCENT_COMPLETE, EtcMethod.CPI_BASED);

        assertThat(snapshot.getBudgetAtCompletion()).isEqualByComparingTo("50000000");
        verify(evmCalculationRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
    }
}
