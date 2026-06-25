package com.bipros.api.service;

import com.bipros.api.dto.BudgetCorrectionRequest;
import com.bipros.api.dto.BudgetCorrectionResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.cost.domain.entity.BudgetChangeLog;
import com.bipros.cost.domain.repository.BudgetChangeLogRepository;
import com.bipros.evm.application.dto.CalculateEvmRequest;
import com.bipros.evm.application.dto.EvmCalculationResponse;
import com.bipros.evm.application.service.EvmService;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmTechnique;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectBudgetCorrectionServiceTest {

    @Mock
    ProjectRepository projectRepository;

    @Mock
    BudgetChangeLogRepository budgetChangeLogRepository;

    @Mock
    EvmService evmService;

    @Mock
    AuditService auditService;

    ProjectBudgetCorrectionService service;

    @BeforeEach
    void setUp() {
        service = new ProjectBudgetCorrectionService(
                projectRepository, budgetChangeLogRepository, evmService, auditService);
    }

    // --- helpers ---

    private Project makeProject(UUID projectId, String currency) {
        Project p = new Project();
        p.setOriginalBudget(new BigDecimal("10"));
        p.setCurrentBudget(new BigDecimal("10"));
        p.setBudgetCurrency(currency);
        return p;
    }

    private BudgetChangeLog makeLog(BudgetChangeLog.ChangeType type, String amount) {
        BudgetChangeLog log = new BudgetChangeLog();
        log.setChangeType(type);
        log.setAmount(new BigDecimal(amount));
        log.setStatus(BudgetChangeLog.ChangeStatus.APPROVED);
        return log;
    }

    // --- tests ---

    @Test
    void correctsBudgetAndPreservesInvariant_noApprovedChanges() {
        UUID projectId = UUID.randomUUID();
        Project project = makeProject(projectId, "OMR");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(budgetChangeLogRepository.findByProjectIdAndStatusOrderByRequestedAtDesc(
                projectId, BudgetChangeLog.ChangeStatus.APPROVED))
                .thenReturn(List.of());

        BudgetCorrectionRequest req = new BudgetCorrectionRequest();
        req.setCorrectedBudget(new BigDecimal("50"));
        req.setRecomputeEvm(false);

        BudgetCorrectionResponse resp = service.correctBudget(projectId, req);

        // Project entity updated correctly
        assertThat(project.getOriginalBudget()).isEqualByComparingTo("50");
        assertThat(project.getCurrentBudget()).isEqualByComparingTo("50");

        // Response fields
        assertThat(resp.originalBudget()).isEqualByComparingTo("50");
        assertThat(resp.currentBudget()).isEqualByComparingTo("50");
        assertThat(resp.approvedNet()).isEqualByComparingTo("0");
        // OMR → 1e6 factor: 50 × 1_000_000 = 50_000_000
        assertThat(resp.rawCurrencyEquivalent()).isEqualByComparingTo("50000000");
        assertThat(resp.currency()).isEqualTo("OMR");

        // Repository save must be invoked
        verify(projectRepository).save(project);
    }

    @Test
    void addsApprovedNetToCurrent() {
        UUID projectId = UUID.randomUUID();
        Project project = makeProject(projectId, "OMR");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        // ADDITION 10, REDUCTION 4 → net +6
        BudgetChangeLog addition = makeLog(BudgetChangeLog.ChangeType.ADDITION, "10");
        BudgetChangeLog reduction = makeLog(BudgetChangeLog.ChangeType.REDUCTION, "4");
        when(budgetChangeLogRepository.findByProjectIdAndStatusOrderByRequestedAtDesc(
                projectId, BudgetChangeLog.ChangeStatus.APPROVED))
                .thenReturn(List.of(addition, reduction));

        BudgetCorrectionRequest req = new BudgetCorrectionRequest();
        req.setCorrectedBudget(new BigDecimal("50"));
        req.setRecomputeEvm(false);

        BudgetCorrectionResponse resp = service.correctBudget(projectId, req);

        // invariant: currentBudget = corrected(50) + approvedNet(6) = 56
        assertThat(project.getOriginalBudget()).isEqualByComparingTo("50");
        assertThat(project.getCurrentBudget()).isEqualByComparingTo("56");
        assertThat(resp.approvedNet()).isEqualByComparingTo("6");
        assertThat(resp.currentBudget()).isEqualByComparingTo("56");
    }

    @Test
    void rejectsNonPositive_zero() {
        UUID projectId = UUID.randomUUID();
        Project project = makeProject(projectId, "INR");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        BudgetCorrectionRequest req = new BudgetCorrectionRequest();
        req.setCorrectedBudget(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.correctBudget(projectId, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsNonPositive_negative() {
        UUID projectId = UUID.randomUUID();
        Project project = makeProject(projectId, "INR");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        BudgetCorrectionRequest req = new BudgetCorrectionRequest();
        req.setCorrectedBudget(new BigDecimal("-5"));

        assertThatThrownBy(() -> service.correctBudget(projectId, req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void projectNotFound_throwsResourceNotFoundException() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        BudgetCorrectionRequest req = new BudgetCorrectionRequest();
        req.setCorrectedBudget(new BigDecimal("10"));

        assertThatThrownBy(() -> service.correctBudget(projectId, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void recomputeEvmTrue_invokesEvmCalc() {
        UUID projectId = UUID.randomUUID();
        Project project = makeProject(projectId, "OMR");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(budgetChangeLogRepository.findByProjectIdAndStatusOrderByRequestedAtDesc(
                projectId, BudgetChangeLog.ChangeStatus.APPROVED))
                .thenReturn(List.of());

        // Stub EvmService to return a non-null response (21-field record)
        EvmCalculationResponse evmResp = new EvmCalculationResponse(
                UUID.randomUUID(),    // id
                projectId,            // projectId
                null,                 // wbsNodeId
                null,                 // activityId
                null,                 // financialPeriodId
                LocalDate.now(),      // dataDate
                BigDecimal.ZERO,      // budgetAtCompletion
                BigDecimal.ZERO,      // plannedValue
                BigDecimal.ZERO,      // earnedValue
                BigDecimal.ZERO,      // actualCost
                BigDecimal.ZERO,      // scheduleVariance
                BigDecimal.ZERO,      // costVariance
                0.0,                  // schedulePerformanceIndex
                0.0,                  // costPerformanceIndex
                0.0,                  // toCompletePerformanceIndex
                BigDecimal.ZERO,      // estimateAtCompletion
                BigDecimal.ZERO,      // estimateToComplete
                BigDecimal.ZERO,      // varianceAtCompletion
                null,                 // evmTechnique
                null,                 // etcMethod
                0.0                   // performancePercentComplete
        );
        when(evmService.calculateEvm(eq(projectId), any(CalculateEvmRequest.class)))
                .thenReturn(evmResp);

        BudgetCorrectionRequest req = new BudgetCorrectionRequest();
        req.setCorrectedBudget(new BigDecimal("50"));
        req.setRecomputeEvm(true);

        BudgetCorrectionResponse resp = service.correctBudget(projectId, req);

        assertThat(resp.evmRecomputed()).isTrue();

        ArgumentCaptor<CalculateEvmRequest> captor = ArgumentCaptor.forClass(CalculateEvmRequest.class);
        verify(evmService).calculateEvm(eq(projectId), captor.capture());
        assertThat(captor.getValue().technique()).isEqualTo(EvmTechnique.ACTIVITY_PERCENT_COMPLETE);
        assertThat(captor.getValue().etcMethod()).isEqualTo(EtcMethod.CPI_BASED);
    }

    @Test
    void recomputeEvmFalse_doesNotInvokeEvmCalc() {
        UUID projectId = UUID.randomUUID();
        Project project = makeProject(projectId, "OMR");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(budgetChangeLogRepository.findByProjectIdAndStatusOrderByRequestedAtDesc(
                projectId, BudgetChangeLog.ChangeStatus.APPROVED))
                .thenReturn(List.of());

        BudgetCorrectionRequest req = new BudgetCorrectionRequest();
        req.setCorrectedBudget(new BigDecimal("50"));
        req.setRecomputeEvm(false);

        BudgetCorrectionResponse resp = service.correctBudget(projectId, req);

        assertThat(resp.evmRecomputed()).isFalse();
        verify(evmService, never()).calculateEvm(any(), any());
    }
}
