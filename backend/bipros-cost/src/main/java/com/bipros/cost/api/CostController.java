package com.bipros.cost.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.cost.application.dto.*;
import com.bipros.cost.application.service.CashFlowForecastEngine;
import com.bipros.cost.application.service.CostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class CostController {

    private final CostService costService;

    // Cost Account Endpoints
    @PreAuthorize("hasPermission(null, 'COST.CREATE')")
    @PostMapping("/cost-accounts")
    public ResponseEntity<ApiResponse<CostAccountDto>> createCostAccount(
            @Valid @RequestBody CreateCostAccountRequest request) {
        CostAccountDto response = costService.createCostAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/cost-accounts")
    public ResponseEntity<ApiResponse<List<CostAccountDto>>> getCostAccountTree() {
        List<CostAccountDto> response = costService.getCostAccountTree();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/cost-accounts/{id}")
    public ResponseEntity<ApiResponse<CostAccountDto>> getCostAccount(@PathVariable UUID id) {
        CostAccountDto response = costService.getCostAccount(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.UPDATE')")
    @PutMapping("/cost-accounts/{id}")
    public ResponseEntity<ApiResponse<CostAccountDto>> updateCostAccount(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCostAccountRequest request) {
        CostAccountDto response = costService.updateCostAccount(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.DELETE')")
    @DeleteMapping("/cost-accounts/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCostAccount(@PathVariable UUID id) {
        costService.deleteCostAccount(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // Activity Expense Endpoints
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.CREATE')")
    @PostMapping("/projects/{projectId}/expenses")
    public ResponseEntity<ApiResponse<ActivityExpenseDto>> createActivityExpense(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateActivityExpenseRequest request) {
        ActivityExpenseDto response = costService.createExpense(projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.UPDATE')")
    @PutMapping("/projects/{projectId}/expenses/{expenseId}")
    public ResponseEntity<ApiResponse<ActivityExpenseDto>> updateActivityExpense(
            @PathVariable UUID projectId,
            @PathVariable UUID expenseId,
            @Valid @RequestBody UpdateActivityExpenseRequest request) {
        ActivityExpenseDto response = costService.updateExpense(expenseId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/expenses")
    public ResponseEntity<ApiResponse<PagedResponse<ActivityExpenseDto>>> getProjectExpenses(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<ActivityExpenseDto> response = costService.getExpensesByProjectPaged(projectId, page, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/activities/{activityId}/expenses")
    public ResponseEntity<ApiResponse<List<ActivityExpenseDto>>> getActivityExpenses(
            @PathVariable UUID projectId,
            @PathVariable UUID activityId) {
        List<ActivityExpenseDto> response = costService.getExpensesByActivity(activityId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.DELETE')")
    @DeleteMapping("/projects/{projectId}/expenses/{expenseId}")
    public ResponseEntity<ApiResponse<Void>> deleteActivityExpense(
            @PathVariable UUID projectId,
            @PathVariable UUID expenseId) {
        costService.deleteExpense(expenseId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // Funding Source Endpoints
    @PreAuthorize("hasPermission(null, 'COST.CREATE')")
    @PostMapping("/funding-sources")
    public ResponseEntity<ApiResponse<FundingSourceDto>> createFundingSource(
            @Valid @RequestBody CreateFundingSourceRequest request) {
        FundingSourceDto response = costService.createFundingSource(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/funding-sources")
    public ResponseEntity<ApiResponse<List<FundingSourceDto>>> getAllFundingSources() {
        List<FundingSourceDto> response = costService.getAllFundingSources();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/funding-sources/{id}")
    public ResponseEntity<ApiResponse<FundingSourceDto>> getFundingSource(@PathVariable UUID id) {
        FundingSourceDto response = costService.getFundingSource(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.DELETE')")
    @DeleteMapping("/funding-sources/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteFundingSource(@PathVariable UUID id) {
        costService.deleteFundingSource(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // Project Funding Endpoints
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.CREATE')")
    @PostMapping("/projects/{projectId}/funding")
    public ResponseEntity<ApiResponse<ProjectFundingDto>> assignFundingToProject(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectFundingRequest request) {
        ProjectFundingDto response = costService.assignFundingToProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/funding")
    public ResponseEntity<ApiResponse<List<ProjectFundingDto>>> getProjectFunding(
            @PathVariable UUID projectId) {
        List<ProjectFundingDto> response = costService.getProjectFunding(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Alias for {@code /projects/{projectId}/funding}. Dashboards and third-party consumers
     * reach for the longer "funding-sources" suffix; both forms resolve to the same allocations.
     */
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/funding-sources")
    public ResponseEntity<ApiResponse<List<ProjectFundingDto>>> getProjectFundingSources(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(costService.getProjectFunding(projectId)));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.DELETE')")
    @DeleteMapping("/projects/{projectId}/funding/{fundingId}")
    public ResponseEntity<ApiResponse<Void>> deleteProjectFunding(
            @PathVariable UUID projectId,
            @PathVariable UUID fundingId) {
        costService.deleteProjectFunding(fundingId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // Financial Period Endpoints
    @PreAuthorize("hasPermission(null, 'COST.CREATE')")
    @PostMapping("/financial-periods")
    public ResponseEntity<ApiResponse<FinancialPeriodDto>> createFinancialPeriod(
            @Valid @RequestBody CreateFinancialPeriodRequest request) {
        FinancialPeriodDto response = costService.createFinancialPeriod(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/financial-periods")
    public ResponseEntity<ApiResponse<List<FinancialPeriodDto>>> getAllFinancialPeriods() {
        List<FinancialPeriodDto> response = costService.getAllFinancialPeriods();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/financial-periods/open")
    public ResponseEntity<ApiResponse<List<FinancialPeriodDto>>> getOpenFinancialPeriods() {
        List<FinancialPeriodDto> response = costService.getOpenFinancialPeriods();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/financial-periods/{id}")
    public ResponseEntity<ApiResponse<FinancialPeriodDto>> getFinancialPeriod(@PathVariable UUID id) {
        FinancialPeriodDto response = costService.getFinancialPeriod(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.UPDATE')")
    @PutMapping("/financial-periods/{id}/close")
    public ResponseEntity<ApiResponse<FinancialPeriodDto>> closePeriod(@PathVariable UUID id) {
        FinancialPeriodDto response = costService.closePeriod(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.DELETE')")
    @DeleteMapping("/financial-periods/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteFinancialPeriod(@PathVariable UUID id) {
        costService.deleteFinancialPeriod(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // Store Period Performance Endpoints
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.CREATE')")
    @PostMapping("/projects/{projectId}/spp")
    public ResponseEntity<ApiResponse<StorePeriodPerformanceDto>> createStorePeriodPerformance(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateStorePeriodPerformanceRequest request) {
        StorePeriodPerformanceDto response = costService.storePeriodPerformance(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/spp")
    public ResponseEntity<ApiResponse<List<StorePeriodPerformanceDto>>> getProjectPeriodPerformance(
            @PathVariable UUID projectId) {
        List<StorePeriodPerformanceDto> response = costService.getProjectPeriodPerformance(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/spp/{periodId}")
    public ResponseEntity<ApiResponse<StorePeriodPerformanceDto>> getProjectLevelPerformance(
            @PathVariable UUID projectId,
            @PathVariable UUID periodId) {
        StorePeriodPerformanceDto response = costService.getProjectLevelPerformance(projectId, periodId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.DELETE')")
    @DeleteMapping("/projects/{projectId}/spp/{sppId}")
    public ResponseEntity<ApiResponse<Void>> deleteStorePeriodPerformance(
            @PathVariable UUID projectId,
            @PathVariable UUID sppId) {
        costService.deleteStorePeriodPerformance(sppId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // RA Bill Endpoints
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.CREATE')")
    @PostMapping("/projects/{projectId}/ra-bills")
    public ResponseEntity<ApiResponse<RaBillDto>> createRaBill(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateRaBillRequest request) {
        RaBillDto response = costService.createRaBill(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/ra-bills")
    public ResponseEntity<ApiResponse<List<RaBillDto>>> getRaBillsByProject(
            @PathVariable UUID projectId) {
        List<RaBillDto> response = costService.getRaBillsByProject(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/ra-bills/{raBillId}")
    public ResponseEntity<ApiResponse<RaBillDto>> getRaBill(@PathVariable UUID raBillId) {
        RaBillDto response = costService.getRaBill(raBillId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.UPDATE')")
    @PutMapping("/ra-bills/{raBillId}")
    public ResponseEntity<ApiResponse<RaBillDto>> updateRaBill(
            @PathVariable UUID raBillId,
            @Valid @RequestBody CreateRaBillRequest request) {
        RaBillDto response = costService.updateRaBill(raBillId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // RA Bill Item Endpoints
    @PreAuthorize("hasPermission(null, 'COST.UPDATE')")
    @PostMapping("/ra-bills/{raBillId}/items")
    public ResponseEntity<ApiResponse<RaBillItemDto>> addRaBillItem(
            @PathVariable UUID raBillId,
            @Valid @RequestBody CreateRaBillItemRequest request) {
        RaBillItemDto response = costService.addRaBillItem(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("hasPermission(null, 'COST.READ')")
    @GetMapping("/ra-bills/{raBillId}/items")
    public ResponseEntity<ApiResponse<List<RaBillItemDto>>> getRaBillItems(
            @PathVariable UUID raBillId) {
        List<RaBillItemDto> response = costService.getRaBillItems(raBillId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // DPR Estimate Endpoints
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.CREATE')")
    @PostMapping("/projects/{projectId}/dpr-estimates")
    public ResponseEntity<ApiResponse<DprEstimateDto>> createDprEstimate(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateDprEstimateRequest request) {
        DprEstimateDto response = costService.createDprEstimate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/dpr-estimates")
    public ResponseEntity<ApiResponse<List<DprEstimateDto>>> getDprEstimatesByProject(
            @PathVariable UUID projectId) {
        List<DprEstimateDto> response = costService.getDprEstimatesByProject(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // Cost Summary Endpoint
    @GetMapping("/projects/{projectId}/cost-summary")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    public ResponseEntity<ApiResponse<CostSummaryDto>> getCostSummary(
            @PathVariable UUID projectId) {
        CostSummaryDto response = costService.getCostSummary(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // Period Cost Aggregation
    @GetMapping("/projects/{projectId}/cost-periods")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    public ResponseEntity<ApiResponse<List<PeriodCostAggregationDto>>> aggregateByPeriod(
            @PathVariable UUID projectId) {
        List<PeriodCostAggregationDto> response = costService.aggregateByPeriod(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // Forecast Generation
    @GetMapping("/projects/{projectId}/cost-forecast")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    public ResponseEntity<ApiResponse<List<CashFlowForecastDto>>> generateForecast(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "LINEAR") CashFlowForecastEngine.ForecastMethod method) {
        List<CashFlowForecastDto> response = costService.generateForecast(projectId, method);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // Cash Flow Forecast Endpoints
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.UPDATE')")
    @PostMapping("/projects/{projectId}/cash-flow")
    public ResponseEntity<ApiResponse<CashFlowForecastDto>> createCashFlowForecast(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateCashFlowForecastRequest request) {
        CashFlowForecastDto response = costService.createCashFlowForecast(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'COST.READ')")
    @GetMapping("/projects/{projectId}/cash-flow")
    public ResponseEntity<ApiResponse<List<CashFlowForecastDto>>> getCashFlowForecastByProject(
            @PathVariable UUID projectId) {
        List<CashFlowForecastDto> response = costService.getCashFlowForecastByProject(projectId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
