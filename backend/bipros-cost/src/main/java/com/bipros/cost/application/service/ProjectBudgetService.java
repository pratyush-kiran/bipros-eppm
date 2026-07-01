package com.bipros.cost.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.util.AuditService;
import com.bipros.cost.application.dto.*;
import com.bipros.cost.domain.entity.BudgetChangeLog;
import com.bipros.cost.domain.entity.BudgetChangeLog.ChangeStatus;
import com.bipros.cost.domain.entity.BudgetChangeLog.ChangeType;
import com.bipros.cost.domain.repository.BudgetChangeLogRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ProjectBudgetService {

    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final BudgetChangeLogRepository budgetChangeLogRepository;
    private final ProjectAccessGuard projectAccess;
    private final AuditService auditService;

    @PersistenceContext
    private EntityManager em;

    public ProjectBudgetResponse setInitialBudget(UUID projectId, BigDecimal amount) {
        log.info("Setting initial budget for project {}: {}", projectId, amount);
        projectAccess.requireEdit(projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        if (project.getOriginalBudget() != null) {
            throw new BusinessRuleException("BUDGET_ALREADY_SET",
                "Initial budget has already been set. Use budget changes to modify.");
        }

        if (amount == null || amount.signum() <= 0) {
            throw new BusinessRuleException("INVALID_BUDGET_AMOUNT",
                "Budget amount must be greater than zero.");
        }

        project.setOriginalBudget(amount);
        project.setCurrentBudget(amount);
        project.setBudgetUpdatedAt(Instant.now());
        projectRepository.save(project);

        auditService.logUpdate("Project", projectId, "originalBudget", null, amount);
        auditService.logUpdate("Project", projectId, "currentBudget", null, amount);

        log.info("Initial budget set for project {}: {}", projectId, amount);
        return buildBudgetResponse(project);
    }

    public BudgetChangeLogResponse requestChange(UUID projectId, CreateBudgetChangeRequest request) {
        log.info("Requesting budget change for project {}: {} {}", projectId, request.changeType(), request.amount());
        projectAccess.requireEdit(projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        if (project.getOriginalBudget() == null) {
            throw new BusinessRuleException("BUDGET_NOT_SET",
                "Set the initial budget before requesting changes.");
        }

        validateChangeRequest(projectId, request);

        UUID currentUserId = projectAccess.currentUserId();

        BudgetChangeLog changeLog = new BudgetChangeLog();
        changeLog.setProjectId(projectId);
        changeLog.setFromWbsNodeId(request.fromWbsNodeId());
        changeLog.setToWbsNodeId(request.toWbsNodeId());
        changeLog.setAmount(request.amount());
        changeLog.setChangeType(request.changeType());
        changeLog.setStatus(ChangeStatus.PENDING);
        changeLog.setReason(request.reason());
        changeLog.setRequestedBy(currentUserId);
        changeLog.setRequestedAt(Instant.now());

        BudgetChangeLog saved = budgetChangeLogRepository.save(changeLog);
        auditService.logCreate("BudgetChangeLog", saved.getId(), request);

        log.info("Budget change requested: {}", saved.getId());
        return buildChangeLogResponse(saved);
    }

    public BudgetChangeLogResponse approveChange(UUID projectId, UUID changeId, UUID decidedBy) {
        log.info("Approving budget change: {}", changeId);

        BudgetChangeLog changeLog = budgetChangeLogRepository.findById(changeId)
            .orElseThrow(() -> new ResourceNotFoundException("BudgetChangeLog", changeId));

        if (!projectId.equals(changeLog.getProjectId())) {
            throw new ResourceNotFoundException("BudgetChangeLog in project " + projectId, changeId);
        }

        if (changeLog.getStatus() != ChangeStatus.PENDING) {
            throw new BusinessRuleException("CHANGE_NOT_PENDING",
                "Only pending changes can be approved. Current status: " + changeLog.getStatus());
        }

        changeLog.setStatus(ChangeStatus.APPROVED);
        changeLog.setDecidedBy(decidedBy);
        changeLog.setDecidedAt(Instant.now());
        budgetChangeLogRepository.save(changeLog);

        recomputeCurrentBudget(changeLog.getProjectId());

        auditService.logUpdate("BudgetChangeLog", changeId, "status", ChangeStatus.PENDING, ChangeStatus.APPROVED);
        log.info("Budget change approved: {}", changeId);
        return buildChangeLogResponse(changeLog);
    }

    public BudgetChangeLogResponse rejectChange(UUID projectId, UUID changeId, UUID decidedBy, String reason) {
        log.info("Rejecting budget change: {}", changeId);

        BudgetChangeLog changeLog = budgetChangeLogRepository.findById(changeId)
            .orElseThrow(() -> new ResourceNotFoundException("BudgetChangeLog", changeId));

        if (!projectId.equals(changeLog.getProjectId())) {
            throw new ResourceNotFoundException("BudgetChangeLog in project " + projectId, changeId);
        }

        if (changeLog.getStatus() != ChangeStatus.PENDING) {
            throw new BusinessRuleException("CHANGE_NOT_PENDING",
                "Only pending changes can be rejected. Current status: " + changeLog.getStatus());
        }

        changeLog.setStatus(ChangeStatus.REJECTED);
        changeLog.setDecidedBy(decidedBy);
        changeLog.setDecidedAt(Instant.now());
        if (reason != null) {
            changeLog.setReason(changeLog.getReason() + " | Rejection: " + reason);
        }
        budgetChangeLogRepository.save(changeLog);

        auditService.logUpdate("BudgetChangeLog", changeId, "status", ChangeStatus.PENDING, ChangeStatus.REJECTED);
        log.info("Budget change rejected: {}", changeId);
        return buildChangeLogResponse(changeLog);
    }

    @Transactional(readOnly = true)
    public ProjectBudgetResponse getBudgetSummary(UUID projectId) {
        projectAccess.requireRead(projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        return buildBudgetResponse(project);
    }

    @Transactional(readOnly = true)
    public List<BudgetChangeLogResponse> getChangeLog(UUID projectId) {
        projectAccess.requireRead(projectId);

        List<BudgetChangeLog> changes = budgetChangeLogRepository.findByProjectIdOrderByRequestedAtDesc(projectId);

        // Batch-load WBS node codes for display
        Set<UUID> wbsNodeIds = new HashSet<>();
        for (BudgetChangeLog c : changes) {
            if (c.getFromWbsNodeId() != null) wbsNodeIds.add(c.getFromWbsNodeId());
            if (c.getToWbsNodeId() != null) wbsNodeIds.add(c.getToWbsNodeId());
        }
        Map<UUID, String> wbsCodeMap = new HashMap<>();
        if (!wbsNodeIds.isEmpty()) {
            wbsNodeRepository.findAllById(wbsNodeIds).forEach(n -> wbsCodeMap.put(n.getId(), n.getCode()));
        }

        // Batch-load user names
        Set<UUID> userIds = new HashSet<>();
        for (BudgetChangeLog c : changes) {
            if (c.getRequestedBy() != null) userIds.add(c.getRequestedBy());
            if (c.getDecidedBy() != null) userIds.add(c.getDecidedBy());
        }
        Map<UUID, String> userNameMap = resolveUserNames(userIds);

        return changes.stream()
            .map(c -> toChangeLogResponse(c, wbsCodeMap, userNameMap))
            .collect(Collectors.toList());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void validateChangeRequest(UUID projectId, CreateBudgetChangeRequest request) {
        if (request.changeType() == ChangeType.TRANSFER) {
            if (request.fromWbsNodeId() == null || request.toWbsNodeId() == null) {
                throw new BusinessRuleException("TRANSFER_REQUIRES_WBS",
                    "Transfer changes require both fromWbsNodeId and toWbsNodeId.");
            }
            if (request.fromWbsNodeId().equals(request.toWbsNodeId())) {
                throw new BusinessRuleException("TRANSFER_SAME_WBS",
                    "Source and destination WBS nodes must be different.");
            }
        }

        if (request.changeType() == ChangeType.REDUCTION && request.fromWbsNodeId() == null) {
            throw new BusinessRuleException("REDUCTION_REQUIRES_WBS",
                "Reduction changes require fromWbsNodeId.");
        }

        if (request.changeType() == ChangeType.ADDITION && request.toWbsNodeId() == null) {
            throw new BusinessRuleException("ADDITION_REQUIRES_WBS",
                "Addition changes require toWbsNodeId.");
        }
    }

    private void recomputeCurrentBudget(UUID projectId) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        BigDecimal base = project.getOriginalBudget() != null ? project.getOriginalBudget() : BigDecimal.ZERO;

        List<BudgetChangeLog> approved = budgetChangeLogRepository
            .findByProjectIdAndStatusOrderByRequestedAtDesc(projectId, ChangeStatus.APPROVED);

        BigDecimal additions = BigDecimal.ZERO;
        BigDecimal reductions = BigDecimal.ZERO;

        for (BudgetChangeLog change : approved) {
            switch (change.getChangeType()) {
                case ADDITION -> additions = additions.add(change.getAmount());
                case REDUCTION -> reductions = reductions.add(change.getAmount());
                case TRANSFER -> { /* transfers redistribute, don't change total */ }
            }
        }

        BigDecimal newCurrentBudget = base.add(additions).subtract(reductions);
        project.setCurrentBudget(newCurrentBudget);
        project.setBudgetUpdatedAt(Instant.now());
        projectRepository.save(project);

        auditService.logUpdate("Project", projectId, "currentBudget", null, newCurrentBudget);
        log.info("Recomputed current budget for project {}: {}", projectId, newCurrentBudget);
    }

    private ProjectBudgetResponse buildBudgetResponse(Project project) {
        UUID projectId = project.getId();

        List<BudgetChangeLog> changes = budgetChangeLogRepository.findByProjectIdOrderByRequestedAtDesc(projectId);

        BigDecimal pendingAdditions = BigDecimal.ZERO;
        BigDecimal pendingReductions = BigDecimal.ZERO;
        BigDecimal approvedAdditions = BigDecimal.ZERO;
        BigDecimal approvedReductions = BigDecimal.ZERO;
        int pendingCount = 0;
        int approvedCount = 0;

        for (BudgetChangeLog c : changes) {
            if (c.getStatus() == ChangeStatus.PENDING) {
                pendingCount++;
                switch (c.getChangeType()) {
                    case ADDITION -> pendingAdditions = pendingAdditions.add(c.getAmount());
                    case REDUCTION -> pendingReductions = pendingReductions.add(c.getAmount());
                    case TRANSFER -> { /* no net effect */ }
                }
            } else if (c.getStatus() == ChangeStatus.APPROVED) {
                approvedCount++;
                switch (c.getChangeType()) {
                    case ADDITION -> approvedAdditions = approvedAdditions.add(c.getAmount());
                    case REDUCTION -> approvedReductions = approvedReductions.add(c.getAmount());
                    case TRANSFER -> { /* no net effect */ }
                }
            }
        }

        return new ProjectBudgetResponse(
            project.getOriginalBudget(),
            project.getCurrentBudget(),
            pendingAdditions,
            pendingReductions,
            approvedAdditions,
            approvedReductions,
            pendingCount,
            approvedCount,
            project.getBudgetCurrency(),
            project.getBudgetUpdatedAt()
        );
    }

    private BudgetChangeLogResponse buildChangeLogResponse(BudgetChangeLog c) {
        Map<UUID, String> wbsCodeMap = new HashMap<>();
        Set<UUID> wbsIds = new HashSet<>();
        if (c.getFromWbsNodeId() != null) wbsIds.add(c.getFromWbsNodeId());
        if (c.getToWbsNodeId() != null) wbsIds.add(c.getToWbsNodeId());
        if (!wbsIds.isEmpty()) {
            wbsNodeRepository.findAllById(wbsIds).forEach(n -> wbsCodeMap.put(n.getId(), n.getCode()));
        }

        Set<UUID> userIds = new HashSet<>();
        if (c.getRequestedBy() != null) userIds.add(c.getRequestedBy());
        if (c.getDecidedBy() != null) userIds.add(c.getDecidedBy());
        Map<UUID, String> userNameMap = resolveUserNames(userIds);

        return toChangeLogResponse(c, wbsCodeMap, userNameMap);
    }

    private BudgetChangeLogResponse toChangeLogResponse(BudgetChangeLog c,
                                                          Map<UUID, String> wbsCodeMap,
                                                          Map<UUID, String> userNameMap) {
        return new BudgetChangeLogResponse(
            c.getId(),
            c.getProjectId(),
            c.getFromWbsNodeId(),
            c.getFromWbsNodeId() != null ? wbsCodeMap.get(c.getFromWbsNodeId()) : null,
            c.getToWbsNodeId(),
            c.getToWbsNodeId() != null ? wbsCodeMap.get(c.getToWbsNodeId()) : null,
            c.getAmount(),
            c.getChangeType(),
            c.getStatus(),
            c.getReason(),
            c.getRequestedBy(),
            userNameMap.get(c.getRequestedBy()),
            c.getDecidedBy(),
            c.getDecidedBy() != null ? userNameMap.get(c.getDecidedBy()) : null,
            c.getRequestedAt(),
            c.getDecidedAt()
        );
    }

    private Map<UUID, String> resolveUserNames(Set<UUID> userIds) {
        if (userIds.isEmpty()) return Collections.emptyMap();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT id, first_name, last_name FROM public.users WHERE id IN (:ids)")
                .setParameter("ids", userIds)
                .getResultList();
            Map<UUID, String> map = new HashMap<>();
            for (Object[] row : rows) {
                UUID id = (UUID) row[0];
                String first = row[1] != null ? row[1].toString() : "";
                String last = row[2] != null ? row[2].toString() : "";
                String fullName = (first + " " + last).trim();
                map.put(id, fullName.isEmpty() ? null : fullName);
            }
            return map;
        } catch (Exception e) {
            log.debug("Could not resolve user names: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
