package com.bipros.cost.application.service;

import com.bipros.common.dto.PagedResponse;
import com.bipros.common.event.ActivityExpenseRecordedEvent;
import com.bipros.common.event.CostAccountCreatedEvent;
import com.bipros.common.event.CostAccountUpdatedEvent;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.util.AuditService;
import com.bipros.cost.application.dto.*;
import com.bipros.cost.domain.entity.*;
import com.bipros.cost.domain.repository.*;
import com.bipros.project.application.service.DprActualCostLookup;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.repository.GoodsReceiptNoteRepository;
import com.bipros.resource.domain.repository.MaterialStockRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CostService {

    private final CostAccountRepository costAccountRepository;
    private final ActivityExpenseRepository activityExpenseRepository;
    private final FundingSourceRepository fundingSourceRepository;
    private final ProjectFundingRepository projectFundingRepository;
    private final FinancialPeriodRepository financialPeriodRepository;
    private final StorePeriodPerformanceRepository storePeriodPerformanceRepository;
    private final RaBillRepository raBillRepository;
    private final RaBillItemRepository raBillItemRepository;
    private final DprEstimateRepository dprEstimateRepository;
    private final RetentionMoneyRepository retentionMoneyRepository;
    private final CashFlowForecastRepository cashFlowForecastRepository;
    private final CashFlowForecastEngine cashFlowForecastEngine;
    private final SatelliteGateService satelliteGateService;
    private final AuditService auditService;
    // PMS MasterData wiring — material procurement + on-hand stock enrich the cost summary.
    private final GoodsReceiptNoteRepository goodsReceiptNoteRepository;
    private final MaterialStockRepository materialStockRepository;
    private final ResourceAssignmentRepository resourceAssignmentRepository;
    private final ProjectAccessGuard projectAccess;
    private final ProjectRepository projectRepository;
    private final ApplicationEventPublisher eventPublisher;
    // Pulls supervisor-entered DPR persisted line_cost into the rollup. DPR rows compute and
    // persist a line_cost per child row but nothing was copying that figure into either
    // ActivityExpense.actualCost or ResourceAssignment.actualCost, leaving Cost summaries at 0
    // on projects that report cost only via DPRs. See FIX7 / A9–A10.
    private final DprActualCostLookup dprActualCostLookup;
    private final FinancialPeriodAutoGenerator financialPeriodAutoGenerator;

    // Cost Account Operations
    @Transactional
    public CostAccountDto createCostAccount(CreateCostAccountRequest request) {
        if (costAccountRepository.findByCode(request.code()).isPresent()) {
            throw new BusinessRuleException("COST_ACCOUNT_DUPLICATE_CODE",
                    "Cost account with code " + request.code() + " already exists");
        }

        var entity = new CostAccount();
        entity.setCode(request.code());
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setParentId(request.parentId());
        entity.setSortOrder(request.sortOrder());

        var saved = costAccountRepository.save(entity);
        auditService.logCreate("CostAccount", saved.getId(), CostAccountDto.from(saved));
        eventPublisher.publishEvent(
            new CostAccountCreatedEvent(null, saved.getId(), saved.getCode(), saved.getName())
        );
        return CostAccountDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<CostAccountDto> getCostAccountTree() {
        return costAccountRepository.findAllByOrderBySortOrder()
                .stream()
                .map(CostAccountDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CostAccountDto getCostAccount(UUID id) {
        var entity = costAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CostAccount", id));
        return CostAccountDto.from(entity);
    }

    @Transactional
    public CostAccountDto updateCostAccount(UUID id, UpdateCostAccountRequest request) {
        var entity = costAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CostAccount", id));

        entity.setName(request.name());
        entity.setDescription(request.description());

        var updated = costAccountRepository.save(entity);
        auditService.logUpdate("CostAccount", id, "costAccount", null, CostAccountDto.from(updated));
        eventPublisher.publishEvent(
            new CostAccountUpdatedEvent(null, updated.getId(), updated.getCode(), updated.getName())
        );
        return CostAccountDto.from(updated);
    }

    @Transactional
    public void deleteCostAccount(UUID id) {
        var entity = costAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CostAccount", id));

        var childrenCount = costAccountRepository.findByParentIdOrderBySortOrder(id).size();
        if (childrenCount > 0) {
            throw new BusinessRuleException("COST_ACCOUNT_HAS_CHILDREN",
                    "Cannot delete cost account with child accounts");
        }

        costAccountRepository.delete(entity);
        auditService.logDelete("CostAccount", id);
    }

    // Activity Expense Operations
    @Transactional
    public ActivityExpenseDto createExpense(UUID projectId, CreateActivityExpenseRequest request) {
        projectAccess.requireEdit(projectId);
        var entity = new ActivityExpense();
        entity.setProjectId(projectId);
        entity.setActivityId(request.activityId());
        entity.setCostAccountId(request.costAccountId());
        entity.setName(request.name() != null ? request.name() : request.description());
        entity.setDescription(request.description());
        entity.setExpenseCategory(request.category() != null ? request.category() : request.expenseCategory());
        BigDecimal amount = request.amount() != null ? request.amount() : request.actualCost();
        entity.setBudgetedCost(request.budgetedCost() != null ? request.budgetedCost() : amount);
        entity.setActualCost(amount);
        entity.setRemainingCost(request.remainingCost() != null ? request.remainingCost() : BigDecimal.ZERO);
        entity.setAtCompletionCost(request.atCompletionCost() != null ? request.atCompletionCost() : amount);
        entity.setPercentComplete(request.percentComplete() != null ? request.percentComplete() : 0.0);
        entity.setPlannedStartDate(request.plannedStartDate());
        entity.setPlannedFinishDate(request.plannedFinishDate());
        entity.setActualStartDate(request.expenseDate() != null ? request.expenseDate() : request.actualStartDate());
        entity.setActualFinishDate(request.actualFinishDate());
        entity.setCurrency(request.currency());

        var saved = activityExpenseRepository.save(entity);
        auditService.logCreate("ActivityExpense", saved.getId(), ActivityExpenseDto.from(saved));
        eventPublisher.publishEvent(new ActivityExpenseRecordedEvent(
            saved.getProjectId(), saved.getId(), saved.getActivityId()));
        return ActivityExpenseDto.from(saved);
    }

    @Transactional
    public ActivityExpenseDto updateExpense(UUID id, UpdateActivityExpenseRequest request) {
        var entity = activityExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ActivityExpense", id));
        projectAccess.requireEdit(entity.getProjectId());

        if (request.costAccountId() != null) entity.setCostAccountId(request.costAccountId());
        if (request.name() != null) entity.setName(request.name());
        if (request.description() != null) entity.setDescription(request.description());
        String category = request.category() != null ? request.category() : request.expenseCategory();
        if (category != null) entity.setExpenseCategory(category);
        BigDecimal amount = request.amount() != null ? request.amount() : request.actualCost();
        if (amount != null) {
            entity.setActualCost(amount);
            entity.setBudgetedCost(request.budgetedCost() != null ? request.budgetedCost() : amount);
            entity.setRemainingCost(request.remainingCost() != null ? request.remainingCost() : BigDecimal.ZERO);
            entity.setAtCompletionCost(request.atCompletionCost() != null ? request.atCompletionCost() : amount);
        }
        if (request.percentComplete() != null) entity.setPercentComplete(request.percentComplete());
        if (request.plannedStartDate() != null) entity.setPlannedStartDate(request.plannedStartDate());
        if (request.plannedFinishDate() != null) entity.setPlannedFinishDate(request.plannedFinishDate());
        LocalDate expenseDate = request.expenseDate() != null ? request.expenseDate() : request.actualStartDate();
        if (expenseDate != null) entity.setActualStartDate(expenseDate);
        if (request.actualFinishDate() != null) entity.setActualFinishDate(request.actualFinishDate());
        if (request.currency() != null) entity.setCurrency(request.currency());

        var saved = activityExpenseRepository.save(entity);
        auditService.logUpdate("ActivityExpense", id, "expense", null, ActivityExpenseDto.from(saved));
        eventPublisher.publishEvent(new ActivityExpenseRecordedEvent(
            saved.getProjectId(), saved.getId(), saved.getActivityId()));
        return ActivityExpenseDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ActivityExpenseDto> getExpensesByProject(UUID projectId) {
        projectAccess.requireRead(projectId);
        return activityExpenseRepository.findByProjectId(projectId)
                .stream()
                .map(ActivityExpenseDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PagedResponse<ActivityExpenseDto> getExpensesByProjectPaged(UUID projectId, int page, int size) {
        projectAccess.requireRead(projectId);
        var pageResult = activityExpenseRepository.findByProjectId(projectId, PageRequest.of(page, size));
        var content = pageResult.getContent().stream()
                .map(ActivityExpenseDto::from)
                .collect(Collectors.toList());
        return PagedResponse.of(content, pageResult.getTotalElements(),
                pageResult.getTotalPages(), pageResult.getNumber(), pageResult.getSize());
    }

    @Transactional(readOnly = true)
    public List<ActivityExpenseDto> getExpensesByActivity(UUID activityId) {
        return activityExpenseRepository.findByActivityId(activityId)
                .stream()
                .map(ActivityExpenseDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteExpense(UUID id) {
        var entity = activityExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ActivityExpense", id));
        projectAccess.requireEdit(entity.getProjectId());
        activityExpenseRepository.delete(entity);
        auditService.logDelete("ActivityExpense", id);
    }

    // Funding Source Operations
    @Transactional
    public FundingSourceDto createFundingSource(CreateFundingSourceRequest request) {
        if (request.code() != null && fundingSourceRepository.findByCode(request.code()).isPresent()) {
            throw new BusinessRuleException("FUNDING_SOURCE_DUPLICATE_CODE",
                    "Funding source with code " + request.code() + " already exists");
        }

        var entity = new FundingSource();
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setCode(request.code());
        entity.setTotalAmount(request.totalAmount());
        entity.setAllocatedAmount(request.allocatedAmount());
        entity.setRemainingAmount(request.remainingAmount());

        var saved = fundingSourceRepository.save(entity);
        auditService.logCreate("FundingSource", saved.getId(), FundingSourceDto.from(saved));
        return FundingSourceDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<FundingSourceDto> getAllFundingSources() {
        return fundingSourceRepository.findAll()
                .stream()
                .map(FundingSourceDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public FundingSourceDto getFundingSource(UUID id) {
        var entity = fundingSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FundingSource", id));
        return FundingSourceDto.from(entity);
    }

    @Transactional
    public void deleteFundingSource(UUID id) {
        var entity = fundingSourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FundingSource", id));
        fundingSourceRepository.delete(entity);
        auditService.logDelete("FundingSource", id);
    }

    // Project Funding Operations
    @Transactional
    public ProjectFundingDto assignFundingToProject(CreateProjectFundingRequest request) {
        var fundingSource = fundingSourceRepository.findById(request.fundingSourceId())
                .orElseThrow(() -> new ResourceNotFoundException("FundingSource", request.fundingSourceId()));

        var entity = new ProjectFunding();
        entity.setProjectId(request.projectId());
        entity.setFundingSourceId(request.fundingSourceId());
        entity.setWbsNodeId(request.wbsNodeId());
        entity.setAllocatedAmount(request.allocatedAmount());

        var saved = projectFundingRepository.save(entity);
        auditService.logCreate("ProjectFunding", saved.getId(), ProjectFundingDto.from(saved));
        return ProjectFundingDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ProjectFundingDto> getProjectFunding(UUID projectId) {
        return projectFundingRepository.findByProjectId(projectId)
                .stream()
                .map(ProjectFundingDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteProjectFunding(UUID id) {
        var entity = projectFundingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectFunding", id));
        projectFundingRepository.delete(entity);
        auditService.logDelete("ProjectFunding", id);
    }

    // Financial Period Operations
    @Transactional
    public FinancialPeriodDto createFinancialPeriod(CreateFinancialPeriodRequest request) {
        var entity = new FinancialPeriod();
        entity.setProjectId(request.projectId());
        entity.setName(request.name());
        entity.setStartDate(request.startDate());
        entity.setEndDate(request.endDate());
        entity.setPeriodType(request.periodType());
        entity.setIsClosed(false);
        entity.setSortOrder(request.sortOrder());

        var saved = financialPeriodRepository.save(entity);
        auditService.logCreate("FinancialPeriod", saved.getId(), FinancialPeriodDto.from(saved));
        return FinancialPeriodDto.from(saved);
    }

    @Transactional
    public List<FinancialPeriodDto> getAllFinancialPeriods(UUID projectId) {
        financialPeriodAutoGenerator.ensureForProject(projectId);
        return financialPeriodRepository.findByProjectIdOrderBySortOrderAsc(projectId)
                .stream()
                .map(FinancialPeriodDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<FinancialPeriodDto> getOpenFinancialPeriods(UUID projectId) {
        financialPeriodAutoGenerator.ensureForProject(projectId);
        return financialPeriodRepository.findByProjectIdAndIsClosedFalseOrderBySortOrderAsc(projectId)
                .stream()
                .map(FinancialPeriodDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public FinancialPeriodDto getFinancialPeriod(UUID id) {
        var entity = financialPeriodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialPeriod", id));
        return FinancialPeriodDto.from(entity);
    }

    @Transactional
    public FinancialPeriodDto closePeriod(UUID id) {
        var entity = financialPeriodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialPeriod", id));
        entity.setIsClosed(true);
        var saved = financialPeriodRepository.save(entity);
        auditService.logUpdate("FinancialPeriod", id, "isClosed", false, true);
        return FinancialPeriodDto.from(saved);
    }

    @Transactional
    public void deleteFinancialPeriod(UUID id) {
        var entity = financialPeriodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FinancialPeriod", id));
        financialPeriodRepository.delete(entity);
        auditService.logDelete("FinancialPeriod", id);
    }

    // Store Period Performance Operations
    @Transactional
    public StorePeriodPerformanceDto storePeriodPerformance(CreateStorePeriodPerformanceRequest request) {
        var entity = new StorePeriodPerformance();
        entity.setProjectId(request.projectId());
        entity.setFinancialPeriodId(request.financialPeriodId());
        entity.setActivityId(request.activityId());
        entity.setActualLaborCost(request.actualLaborCost());
        entity.setActualNonlaborCost(request.actualNonlaborCost());
        entity.setActualMaterialCost(request.actualMaterialCost());
        entity.setActualExpenseCost(request.actualExpenseCost());
        entity.setActualLaborUnits(request.actualLaborUnits());
        entity.setActualNonlaborUnits(request.actualNonlaborUnits());
        entity.setActualMaterialUnits(request.actualMaterialUnits());
        entity.setEarnedValueCost(request.earnedValueCost());
        entity.setPlannedValueCost(request.plannedValueCost());

        var saved = storePeriodPerformanceRepository.save(entity);
        auditService.logCreate("StorePeriodPerformance", saved.getId(), StorePeriodPerformanceDto.from(saved));
        return StorePeriodPerformanceDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<StorePeriodPerformanceDto> getProjectPeriodPerformance(UUID projectId) {
        return storePeriodPerformanceRepository.findByProjectId(projectId)
                .stream()
                .map(StorePeriodPerformanceDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public StorePeriodPerformanceDto getProjectLevelPerformance(UUID projectId, UUID financialPeriodId) {
        var entity = storePeriodPerformanceRepository.findByProjectIdAndFinancialPeriodIdAndActivityIdIsNull(projectId, financialPeriodId)
                .orElseThrow(() -> new ResourceNotFoundException("StorePeriodPerformance", projectId));
        return StorePeriodPerformanceDto.from(entity);
    }

    @Transactional
    public void deleteStorePeriodPerformance(UUID id) {
        var entity = storePeriodPerformanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("StorePeriodPerformance", id));
        storePeriodPerformanceRepository.delete(entity);
        auditService.logDelete("StorePeriodPerformance", id);
    }

    // RA Bill Operations
    @Transactional
    public RaBillDto createRaBill(CreateRaBillRequest request) {
        if (raBillRepository.findByBillNumber(request.billNumber()).isPresent()) {
            throw new BusinessRuleException("RABILL_DUPLICATE_NUMBER",
                    "RA Bill with number " + request.billNumber() + " already exists");
        }

        var entity = new RaBill();
        entity.setProjectId(request.projectId());
        entity.setContractId(request.contractId());
        entity.setWbsPackageCode(request.wbsPackageCode());
        entity.setBillNumber(request.billNumber());
        entity.setBillPeriodFrom(request.billPeriodFrom());
        entity.setBillPeriodTo(request.billPeriodTo());
        entity.setGrossAmount(request.grossAmount());
        applyDeductions(entity, request);
        entity.setNetAmount(request.netAmount());
        entity.setContractorClaimedPercent(request.contractorClaimedPercent());
        entity.setStatus(RaBill.RaBillStatus.DRAFT);
        entity.setRemarks(request.remarks());

        satelliteGateService.evaluate(entity);

        var saved = raBillRepository.save(entity);
        auditService.logCreate("RaBill", saved.getId(), RaBillDto.from(saved));
        return RaBillDto.from(saved);
    }

    private void applyDeductions(RaBill entity, CreateRaBillRequest request) {
        entity.setMobAdvanceRecovery(request.mobAdvanceRecovery());
        entity.setRetention5Pct(request.retention5Pct());
        entity.setTds2Pct(request.tds2Pct());
        entity.setGst18Pct(request.gst18Pct());
        BigDecimal total = BigDecimal.ZERO;
        if (request.mobAdvanceRecovery() != null) total = total.add(request.mobAdvanceRecovery());
        if (request.retention5Pct() != null) total = total.add(request.retention5Pct());
        if (request.tds2Pct() != null) total = total.add(request.tds2Pct());
        if (request.gst18Pct() != null) total = total.add(request.gst18Pct());
        if (total.signum() > 0) {
            entity.setDeductions(total);
        } else if (request.deductions() != null) {
            entity.setDeductions(request.deductions());
        } else {
            entity.setDeductions(BigDecimal.ZERO);
        }
    }

    @Transactional(readOnly = true)
    public List<RaBillDto> getRaBillsByProject(UUID projectId) {
        return raBillRepository.findByProjectIdOrderByBillNumberDesc(projectId)
                .stream()
                .map(RaBillDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RaBillDto getRaBill(UUID raBillId) {
        var entity = raBillRepository.findById(raBillId)
                .orElseThrow(() -> new ResourceNotFoundException("RaBill", raBillId));
        return RaBillDto.from(entity);
    }

    @Transactional
    public RaBillDto updateRaBill(UUID raBillId, CreateRaBillRequest request) {
        var entity = raBillRepository.findById(raBillId)
                .orElseThrow(() -> new ResourceNotFoundException("RaBill", raBillId));

        if (!entity.getStatus().equals(RaBill.RaBillStatus.DRAFT)) {
            throw new BusinessRuleException("RABILL_NOT_DRAFT",
                    "Only DRAFT RA Bills can be updated");
        }

        entity.setBillNumber(request.billNumber());
        entity.setBillPeriodFrom(request.billPeriodFrom());
        entity.setBillPeriodTo(request.billPeriodTo());
        entity.setGrossAmount(request.grossAmount());
        entity.setWbsPackageCode(request.wbsPackageCode());
        applyDeductions(entity, request);
        entity.setNetAmount(request.netAmount());
        entity.setContractorClaimedPercent(request.contractorClaimedPercent());
        entity.setRemarks(request.remarks());
        satelliteGateService.evaluate(entity);

        var saved = raBillRepository.save(entity);
        auditService.logUpdate("RaBill", raBillId, "raBill", null, RaBillDto.from(saved));
        return RaBillDto.from(saved);
    }

    // RA Bill Item Operations
    @Transactional
    public RaBillItemDto addRaBillItem(CreateRaBillItemRequest request) {
        var raBill = raBillRepository.findById(request.raBillId())
                .orElseThrow(() -> new ResourceNotFoundException("RaBill", request.raBillId()));

        var entity = new RaBillItem();
        entity.setRaBillId(request.raBillId());
        entity.setItemCode(request.itemCode());
        entity.setDescription(request.description());
        entity.setUnit(request.unit());
        entity.setRate(request.rate());
        entity.setPreviousQuantity(request.previousQuantity());
        entity.setCurrentQuantity(request.currentQuantity());
        entity.setCumulativeQuantity(request.cumulativeQuantity());
        entity.setAmount(request.amount());

        var saved = raBillItemRepository.save(entity);
        auditService.logCreate("RaBillItem", saved.getId(), RaBillItemDto.from(saved));
        return RaBillItemDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<RaBillItemDto> getRaBillItems(UUID raBillId) {
        return raBillItemRepository.findByRaBillIdOrderByCreatedAt(raBillId)
                .stream()
                .map(RaBillItemDto::from)
                .collect(Collectors.toList());
    }

    // DPR Estimate Operations
    @Transactional
    public DprEstimateDto createDprEstimate(CreateDprEstimateRequest request) {
        var entity = new DprEstimate();
        entity.setProjectId(request.projectId());
        entity.setWbsNodeId(request.wbsNodeId());
        entity.setCostCategory(DprEstimate.CostCategory.valueOf(request.costCategory()));
        entity.setEstimatedAmount(request.estimatedAmount());
        entity.setRevisedAmount(request.revisedAmount());
        entity.setRemarks(request.remarks());

        var saved = dprEstimateRepository.save(entity);
        auditService.logCreate("DprEstimate", saved.getId(), DprEstimateDto.from(saved));
        return DprEstimateDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DprEstimateDto> getDprEstimatesByProject(UUID projectId) {
        return dprEstimateRepository.findByProjectIdOrderByCreatedAt(projectId)
                .stream()
                .map(DprEstimateDto::from)
                .collect(Collectors.toList());
    }

    // Cash Flow Forecast Operations
    @Transactional
    public CashFlowForecastDto createCashFlowForecast(CreateCashFlowForecastRequest request) {
        var entity = new CashFlowForecast();
        entity.setProjectId(request.projectId());
        entity.setPeriod(request.period());
        entity.setPlannedAmount(request.plannedAmount() != null ? request.plannedAmount() : java.math.BigDecimal.ZERO);
        entity.setActualAmount(request.actualAmount() != null ? request.actualAmount() : java.math.BigDecimal.ZERO);
        entity.setForecastAmount(request.forecastAmount() != null ? request.forecastAmount() : java.math.BigDecimal.ZERO);
        entity.setCumulativePlanned(request.cumulativePlanned());
        entity.setCumulativeActual(request.cumulativeActual());
        entity.setCumulativeForecast(request.cumulativeForecast());

        var saved = cashFlowForecastRepository.save(entity);
        auditService.logCreate("CashFlowForecast", saved.getId(), CashFlowForecastDto.from(saved));
        return CashFlowForecastDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<CashFlowForecastDto> getCashFlowForecastByProject(UUID projectId) {
        return cashFlowForecastRepository.findByProjectIdOrderByPeriodAsc(projectId)
                .stream()
                .map(CashFlowForecastDto::from)
                .collect(Collectors.toList());
    }

    // Cost Summary
    @Transactional(readOnly = true)
    public CostSummaryDto getCostSummary(UUID projectId) {
        var expenses = activityExpenseRepository.findByProjectId(projectId);

        BigDecimal totalBudget = expenses.stream()
                .map(e -> e.getBudgetedCost() != null ? e.getBudgetedCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // FIX-14: match EVM BAC — add ResourceAssignment.plannedCost so the two agree.
        // EvmRollupService.getActivityBac sums both expense.budgetedCost AND assignment.plannedCost;
        // previously this method only counted the expense side, yielding totalBudget=0 on projects
        // that define cost purely through resource assignments (e.g. Oman-Demo Site Clearing ~6500 OMR).
        BigDecimal raBudget = resourceAssignmentRepository.sumPlannedCostByProjectId(projectId);
        totalBudget = totalBudget.add(raBudget != null ? raBudget : BigDecimal.ZERO);

        BigDecimal totalActual = expenses.stream()
                .map(e -> e.getActualCost() != null ? e.getActualCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Add DPR-sourced actuals so a project that books cost via supervisor DPRs (rather than
        // via discrete ActivityExpense rows) still shows a non-zero totalActual.
        //
        // Important: do NOT also add resource_assignments.actual_cost here. RA.actualCost is
        // maintained in lock-step with DPR child rows by ResourceAssignmentCostRollupListener
        // (actualCost = rate × actualUnits where actualUnits is rolled up from DPR ledger), so
        // it represents the same money as `dprActual`. The earlier FIX-18 attempt added both
        // sums and produced a 2× over-count for any project that books cost purely via DPRs
        // (verified against HIGHWAY-301: DPR = ₹7,150, RA = ₹7,150, totalActual shown as
        // ₹14,300 instead of ₹7,150).
        BigDecimal dprActual = dprActualCostLookup.sumByProject(projectId);
        totalActual = totalActual.add(dprActual);

        BigDecimal totalRemaining = expenses.stream()
                .map(e -> e.getRemainingCost() != null ? e.getRemainingCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal atCompletion = expenses.stream()
                .map(e -> e.getAtCompletionCost() != null ? e.getAtCompletionCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // PMS MasterData: pull material procurement + stock value so the summary reflects the
        // procurement ledger even when it hasn't been copied into ActivityExpense rows.
        BigDecimal materialProcurement = goodsReceiptNoteRepository
                .findByProjectIdOrderByReceivedDateDesc(projectId).stream()
                .map(g -> g.getAmount() != null ? g.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openStock = materialStockRepository.findByProjectId(projectId).stream()
                .map(s -> s.getStockValue() != null ? s.getStockValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal materialIssued = materialProcurement.subtract(openStock);
        if (materialIssued.signum() < 0) materialIssued = BigDecimal.ZERO;

        // P6-style project-level budget.
        // FIX-14: when project.originalBudget is null (not set in the UI) fall back to the
        // canonical planned-cost total so the field is never null on a project that has
        // resource assignments. This matches what EVM BAC uses as its baseline.
        Project project = projectRepository.findById(projectId).orElse(null);
        // OBS-5 unit fix: project.originalBudget / currentBudget are stored in the currency's
        // "major-scale" unit (crores for INR = 1e7, millions for every other currency = 1e6) —
        // matches the Set-Budget UI, formatBudget helper, and seeders. The rest of the cost
        // summary (totalBudget, totalActual, materialProcurement, etc.) is in raw currency units,
        // so we convert the project budget to raw units here to mirror EvmService (lines 108-128).
        // Without this, /cost-summary returns projectOriginalBudget=0.02 alongside totals in lakhs.
        BigDecimal projectOriginalBudget = project != null ? project.getOriginalBudget() : null;
        BigDecimal projectCurrentBudget = project != null ? project.getCurrentBudget() : null;
        if (project != null) {
            String currency = project.getBudgetCurrency();
            BigDecimal majorUnitFactor = "INR".equalsIgnoreCase(currency)
                    ? new BigDecimal("10000000")   // 1 crore = 10^7
                    : new BigDecimal("1000000");   // 1 million = 10^6 (OMR and all others)
            if (projectOriginalBudget != null && projectOriginalBudget.signum() > 0) {
                projectOriginalBudget = projectOriginalBudget.multiply(majorUnitFactor);
            }
            if (projectCurrentBudget != null && projectCurrentBudget.signum() > 0) {
                projectCurrentBudget = projectCurrentBudget.multiply(majorUnitFactor);
            }
        }
        if (projectOriginalBudget == null) {
            projectOriginalBudget = totalBudget.compareTo(BigDecimal.ZERO) > 0 ? totalBudget : null;
        }

        return CostSummaryDto.of(totalBudget, totalActual, totalRemaining, atCompletion,
            expenses.size(), materialProcurement, openStock, materialIssued,
            projectOriginalBudget, projectCurrentBudget);
    }

    // Period Aggregation — combines two ledgers per financial period:
    //   1. ActivityExpense rows (manual "extras": permits, mobilisation, consultant fees, etc.)
    //      bucketed by actualStartDate.
    //   2. Operational DPR cost (resource-plan consumption: manpower / equipment / material)
    //      bucketed by report_date, plus its planned counterpart from ResourceAssignment.planned_cost
    //      prorated linearly across the assignment's planned start → finish window.
    // EV / PV still come from StorePeriodPerformance (manually snapshotted via the EVM tab).
    @Transactional(readOnly = true)
    public List<PeriodCostAggregationDto> aggregateByPeriod(UUID projectId) {
        financialPeriodAutoGenerator.ensureForProject(projectId);
        var periods = financialPeriodRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        var expenses = activityExpenseRepository.findByProjectId(projectId);
        var performances = storePeriodPerformanceRepository.findByProjectId(projectId);
        var assignments = resourceAssignmentRepository.findByProjectId(projectId);
        var dprDailyCost = dprActualCostLookup.sumByProjectGroupedByDate(projectId);
        Project projectForDates = projectRepository.findById(projectId).orElse(null);
        LocalDate fallbackStart = projectForDates != null ? projectForDates.getPlannedStartDate() : null;
        LocalDate fallbackFinish = projectForDates != null ? projectForDates.getPlannedFinishDate() : null;

        Map<UUID, BigDecimal> periodBudgets = computePeriodBudgets(
                periods, expenses, assignments, fallbackStart, fallbackFinish);
        Map<UUID, BigDecimal> periodActuals = computePeriodActuals(periods, expenses, dprDailyCost);

        return periods.stream().map(period -> {
            BigDecimal periodBudget = periodBudgets.getOrDefault(period.getId(), BigDecimal.ZERO);
            BigDecimal periodActual = periodActuals.getOrDefault(period.getId(), BigDecimal.ZERO);

            BigDecimal ev = BigDecimal.ZERO;
            BigDecimal pv = BigDecimal.ZERO;
            for (var perf : performances) {
                if (period.getId().equals(perf.getFinancialPeriodId())) {
                    ev = ev.add(perf.getEarnedValueCost() != null ? perf.getEarnedValueCost() : BigDecimal.ZERO);
                    pv = pv.add(perf.getPlannedValueCost() != null ? perf.getPlannedValueCost() : BigDecimal.ZERO);
                }
            }

            BigDecimal variance = periodBudget.subtract(periodActual);

            return new PeriodCostAggregationDto(
                    period.getId(), period.getName(),
                    period.getStartDate(), period.getEndDate(),
                    periodBudget, periodActual, variance,
                    ev, pv
            );
        }).collect(Collectors.toList());
    }

    // Forecast Generation
    @Transactional(readOnly = true)
    public List<CashFlowForecastDto> generateForecast(UUID projectId, CashFlowForecastEngine.ForecastMethod method) {
        financialPeriodAutoGenerator.ensureForProject(projectId);
        var periods = financialPeriodRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        var expenses = activityExpenseRepository.findByProjectId(projectId);
        var performances = storePeriodPerformanceRepository.findByProjectId(projectId);
        var assignments = resourceAssignmentRepository.findByProjectId(projectId);
        var dprDailyCost = dprActualCostLookup.sumByProjectGroupedByDate(projectId);
        Project projectForDates = projectRepository.findById(projectId).orElse(null);
        LocalDate fallbackStart = projectForDates != null ? projectForDates.getPlannedStartDate() : null;
        LocalDate fallbackFinish = projectForDates != null ? projectForDates.getPlannedFinishDate() : null;

        Map<UUID, BigDecimal> periodBudgets = computePeriodBudgets(
                periods, expenses, assignments, fallbackStart, fallbackFinish);
        Map<UUID, BigDecimal> periodActuals = computePeriodActuals(periods, expenses, dprDailyCost);

        return cashFlowForecastEngine.generateForecast(
            projectId, periods, periodBudgets, periodActuals, performances, method);
    }

    /**
     * Combine the manual ActivityExpense budget (bucketed by actualStartDate) with the prorated
     * ResourceAssignment.planned_cost (linear over the assignment's planned window) into a single
     * per-period budget map. When a resource assignment lacks its own planned dates, falls back
     * to the project's planned start/finish — common when the planner sets project dates but
     * never explicitly stamps assignment-level dates.
     */
    private Map<UUID, BigDecimal> computePeriodBudgets(
            List<FinancialPeriod> periods,
            List<ActivityExpense> expenses,
            List<com.bipros.resource.domain.model.ResourceAssignment> assignments,
            LocalDate projectFallbackStart,
            LocalDate projectFallbackFinish) {
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        for (var period : periods) {
            BigDecimal total = BigDecimal.ZERO;
            for (var e : expenses) {
                if (e.getActualStartDate() != null
                        && !e.getActualStartDate().isBefore(period.getStartDate())
                        && !e.getActualStartDate().isAfter(period.getEndDate())) {
                    total = total.add(e.getBudgetedCost() != null ? e.getBudgetedCost() : BigDecimal.ZERO);
                }
            }
            for (var ra : assignments) {
                LocalDate raStart = ra.getPlannedStartDate() != null ? ra.getPlannedStartDate() : projectFallbackStart;
                LocalDate raFinish = ra.getPlannedFinishDate() != null ? ra.getPlannedFinishDate() : projectFallbackFinish;
                total = total.add(proratePlannedCost(
                        ra.getPlannedCost(),
                        raStart,
                        raFinish,
                        period.getStartDate(),
                        period.getEndDate()));
            }
            out.put(period.getId(), total);
        }
        return out;
    }

    /**
     * Combine the manual ActivityExpense actuals (bucketed by actualStartDate) with the
     * DPR-driven daily cost (bucketed by DPR report_date) into a single per-period actual map.
     */
    private Map<UUID, BigDecimal> computePeriodActuals(
            List<FinancialPeriod> periods,
            List<ActivityExpense> expenses,
            Map<LocalDate, BigDecimal> dprDailyCost) {
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        for (var period : periods) {
            BigDecimal total = BigDecimal.ZERO;
            for (var e : expenses) {
                if (e.getActualStartDate() != null
                        && !e.getActualStartDate().isBefore(period.getStartDate())
                        && !e.getActualStartDate().isAfter(period.getEndDate())) {
                    total = total.add(e.getActualCost() != null ? e.getActualCost() : BigDecimal.ZERO);
                }
            }
            for (var entry : dprDailyCost.entrySet()) {
                LocalDate d = entry.getKey();
                if (d == null) continue;
                if (!d.isBefore(period.getStartDate()) && !d.isAfter(period.getEndDate())) {
                    total = total.add(entry.getValue());
                }
            }
            out.put(period.getId(), total);
        }
        return out;
    }

    /**
     * Linear daily proration of a ResourceAssignment's planned_cost into a financial-period
     * window: {@code planned_cost × (overlap_days ÷ activity_days)}. Returns zero when any
     * input is missing or the windows don't overlap.
     */
    private static BigDecimal proratePlannedCost(
            BigDecimal plannedCost, LocalDate raStart, LocalDate raFinish,
            LocalDate periodStart, LocalDate periodFinish) {
        if (plannedCost == null || raStart == null || raFinish == null) return BigDecimal.ZERO;
        long activityDays = ChronoUnit.DAYS.between(raStart, raFinish) + 1;
        if (activityDays <= 0) return BigDecimal.ZERO;
        LocalDate overlapStart = raStart.isAfter(periodStart) ? raStart : periodStart;
        LocalDate overlapEnd = raFinish.isBefore(periodFinish) ? raFinish : periodFinish;
        if (overlapEnd.isBefore(overlapStart)) return BigDecimal.ZERO;
        long overlapDays = ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
        return plannedCost.multiply(BigDecimal.valueOf(overlapDays))
                .divide(BigDecimal.valueOf(activityDays), 2, RoundingMode.HALF_UP);
    }
}
