package com.bipros.reporting.portfolio;

import com.bipros.common.dto.ApiResponse;
import com.bipros.reporting.portfolio.dto.CashFlowOutlookPoint;
import com.bipros.reporting.portfolio.dto.ComplianceRow;
import com.bipros.reporting.portfolio.dto.ContractorLeagueRow;
import com.bipros.reporting.portfolio.dto.CostOverrunRow;
import com.bipros.reporting.portfolio.dto.DelayedProjectRow;
import com.bipros.reporting.portfolio.dto.FundingUtilizationRow;
import com.bipros.reporting.portfolio.dto.PortfolioEvmRow;
import com.bipros.reporting.portfolio.dto.PortfolioScorecardDto;
import com.bipros.reporting.portfolio.dto.RiskHeatmapDto;
import com.bipros.reporting.portfolio.dto.ScheduleHealthRow;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/portfolio")
@PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
@RequiredArgsConstructor
public class PortfolioReportController {

  private final PortfolioReportService portfolioReportService;

  @GetMapping("/evm-rollup")
  public ApiResponse<List<PortfolioEvmRow>> getEvmRollup() {
    return ApiResponse.ok(portfolioReportService.getEvmRollup());
  }

  @GetMapping("/scorecard")
  public ApiResponse<PortfolioScorecardDto> getScorecard() {
    return ApiResponse.ok(portfolioReportService.getScorecard());
  }

  @GetMapping("/delayed-projects")
  public ApiResponse<List<DelayedProjectRow>> getDelayedProjects(
      @RequestParam(defaultValue = "10") int limit) {
    return ApiResponse.ok(portfolioReportService.getDelayedProjects(limit));
  }

  @GetMapping("/cost-overrun-projects")
  public ApiResponse<List<CostOverrunRow>> getCostOverrunProjects(
      @RequestParam(defaultValue = "10") int limit) {
    return ApiResponse.ok(portfolioReportService.getCostOverrunProjects(limit));
  }

  @GetMapping("/funding-utilization")
  public ApiResponse<List<FundingUtilizationRow>> getFundingUtilization() {
    return ApiResponse.ok(portfolioReportService.getFundingUtilization());
  }

  @GetMapping("/contractor-league")
  public ApiResponse<List<ContractorLeagueRow>> getContractorLeague() {
    return ApiResponse.ok(portfolioReportService.getContractorLeague());
  }

  @GetMapping("/risk-heatmap")
  public ApiResponse<RiskHeatmapDto> getRiskHeatmap() {
    return ApiResponse.ok(portfolioReportService.getRiskHeatmap());
  }

  @GetMapping("/cash-flow-outlook")
  public ApiResponse<List<CashFlowOutlookPoint>> getCashFlowOutlook(
      @RequestParam(defaultValue = "12") int months) {
    return ApiResponse.ok(portfolioReportService.getCashFlowOutlook(months));
  }

  @GetMapping("/compliance")
  public ApiResponse<List<ComplianceRow>> getCompliance() {
    return ApiResponse.ok(portfolioReportService.getCompliance());
  }

  @GetMapping("/schedule-health")
  public ApiResponse<List<ScheduleHealthRow>> getScheduleHealth() {
    return ApiResponse.ok(portfolioReportService.getScheduleHealth());
  }
}
