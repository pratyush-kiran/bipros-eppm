package com.bipros.api.service;

import com.bipros.api.dto.BudgetCorrectionRequest;
import com.bipros.api.dto.BudgetCorrectionResponse;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.cost.domain.entity.BudgetChangeLog;
import com.bipros.cost.domain.repository.BudgetChangeLogRepository;
import com.bipros.evm.application.dto.CalculateEvmRequest;
import com.bipros.evm.application.service.EvmService;
import com.bipros.evm.domain.entity.EtcMethod;
import com.bipros.evm.domain.entity.EvmTechnique;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectBudgetCorrectionService {

    private final ProjectRepository projectRepository;
    private final BudgetChangeLogRepository budgetChangeLogRepository;
    private final EvmService evmService;
    private final AuditService auditService;

    /**
     * Directly corrects a project's Budget-At-Completion (originalBudget), preserving the
     * invariant: {@code currentBudget = correctedBudget + approvedNet}.
     *
     * <p>All budget values are in major-unit scale:
     * crores (1e7) for INR, millions (1e6) for all other currencies.
     */
    @Transactional
    public BudgetCorrectionResponse correctBudget(UUID projectId, BudgetCorrectionRequest req) {
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        if (req.getCorrectedBudget() == null || req.getCorrectedBudget().signum() <= 0) {
            throw new BusinessRuleException(
                    "BUDGET_CORRECTION_INVALID",
                    "correctedBudget must be a positive value");
        }

        BigDecimal corrected = req.getCorrectedBudget();

        // Compute approvedNet = Σ(ADDITION) − Σ(REDUCTION) over APPROVED change-log rows.
        // TRANSFER rows contribute 0.
        List<BudgetChangeLog> approvedLogs = budgetChangeLogRepository
                .findByProjectIdAndStatusOrderByRequestedAtDesc(projectId, BudgetChangeLog.ChangeStatus.APPROVED);

        BigDecimal approvedNet = BigDecimal.ZERO;
        for (BudgetChangeLog entry : approvedLogs) {
            if (entry.getChangeType() == BudgetChangeLog.ChangeType.ADDITION) {
                approvedNet = approvedNet.add(entry.getAmount());
            } else if (entry.getChangeType() == BudgetChangeLog.ChangeType.REDUCTION) {
                approvedNet = approvedNet.subtract(entry.getAmount());
            }
            // TRANSFER: no effect on total budget
        }

        BigDecimal oldOriginal = project.getOriginalBudget();
        BigDecimal newCurrent = corrected.add(approvedNet);

        project.setOriginalBudget(corrected);
        project.setCurrentBudget(newCurrent);
        projectRepository.save(project);

        log.info("[ProjectBudgetCorrectionService] project={} originalBudget {} → {} currentBudget → {} approvedNet={} reason={}",
                projectId, oldOriginal, corrected, newCurrent, approvedNet, req.getReason());

        // Audit: record old vs new originalBudget; reason is included in the field name for traceability.
        String auditField = "originalBudget" + (req.getReason() != null ? " [" + req.getReason() + "]" : "");
        auditService.logUpdate("Project", projectId, auditField, oldOriginal, corrected);

        // Compute rawCurrencyEquivalent: currentBudget × majorUnitFactor
        String currency = project.getBudgetCurrency();
        BigDecimal majorUnitFactor = "INR".equalsIgnoreCase(currency)
                ? new BigDecimal("10000000")
                : new BigDecimal("1000000");
        BigDecimal rawEquivalent = newCurrent.multiply(majorUnitFactor);

        // Optionally recompute and persist a fresh EVM snapshot
        boolean evmRecomputed = false;
        if (req.isRecomputeEvm()) {
            try {
                evmService.calculateEvm(
                        projectId,
                        new CalculateEvmRequest(
                                EvmTechnique.ACTIVITY_PERCENT_COMPLETE,
                                EtcMethod.CPI_BASED));
                evmRecomputed = true;
                log.info("[ProjectBudgetCorrectionService] EVM snapshot recomputed for project={}", projectId);
            } catch (Exception e) {
                log.warn("[ProjectBudgetCorrectionService] EVM recompute failed for project={}: {}", projectId, e.getMessage(), e);
            }
        }

        return new BudgetCorrectionResponse(
                projectId,
                currency,
                corrected,
                newCurrent,
                rawEquivalent,
                approvedNet,
                evmRecomputed);
    }
}
