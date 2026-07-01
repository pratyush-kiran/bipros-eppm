package com.bipros.reporting.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.reporting.application.dto.*;
import com.bipros.reporting.application.service.CapacityUtilizationReportService;
import com.bipros.reporting.application.service.DailyDeploymentReportService;
import com.bipros.reporting.application.service.DprCostingReportService;
import com.bipros.reporting.application.service.DprReportService;
import com.bipros.reporting.application.service.ReportService;
import com.bipros.reporting.application.service.SupervisorPerformanceReportService;
import com.bipros.reporting.domain.model.ReportFormat;
import com.bipros.reporting.domain.model.ReportType;
import com.bipros.reporting.infrastructure.export.CapacityUtilizationExcelWriter;
import com.bipros.reporting.infrastructure.export.DprCostingExcelWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
public class ReportController {

  private final ReportService reportService;
  private final CapacityUtilizationReportService capacityUtilizationReportService;
  private final DailyDeploymentReportService dailyDeploymentReportService;
  private final DprReportService dprReportService;
  private final CapacityUtilizationExcelWriter capacityUtilizationExcelWriter;
  private final DprCostingReportService dprCostingReportService;
  private final DprCostingExcelWriter dprCostingExcelWriter;
  private final SupervisorPerformanceReportService supervisorPerformanceReportService;

  @PersistenceContext private EntityManager em;

  /** Alias for {@code /reports/definitions}. Dashboards and links that expect the bare
   * {@code /v1/reports} collection endpoint land here instead of receiving a 404. */
  @GetMapping
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<List<ReportDefinitionResponse>> listReports(
      @RequestParam(required = false) ReportType type) {
    return ApiResponse.ok(reportService.listReportDefinitions(type));
  }

  @PostMapping("/definitions")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<ReportDefinitionResponse> createReportDefinition(
      @Valid @RequestBody CreateReportDefinitionRequest request) {
    return ApiResponse.ok(reportService.createReportDefinition(request));
  }

  @GetMapping("/definitions")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<List<ReportDefinitionResponse>> listReportDefinitions(
      @RequestParam(required = false) ReportType type) {
    return ApiResponse.ok(reportService.listReportDefinitions(type));
  }

  @GetMapping("/definitions/{id}")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<ReportDefinitionResponse> getReportDefinition(@PathVariable UUID id) {
    return ApiResponse.ok(reportService.getReportDefinition(id));
  }

  @DeleteMapping("/definitions/{id}")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<Void> deleteReportDefinition(@PathVariable UUID id) {
    reportService.deleteReportDefinition(id);
    return ApiResponse.ok(null);
  }

  @PostMapping("/execute")
  @PreAuthorize("hasPermission(null, 'REPORT.EXPORT')")
  public ApiResponse<ReportExecutionResponse> executeReport(
      @Valid @RequestBody ExecuteReportRequest request) {
    return ApiResponse.ok(
        reportService.executeReport(
            request.reportDefinitionId(),
            request.projectId(),
            request.format(),
            request.parameters()));
  }

  @GetMapping("/executions/{id}")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<ReportExecutionResponse> getExecution(@PathVariable UUID id) {
    return ApiResponse.ok(reportService.getExecution(id));
  }

  @GetMapping("/executions")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<List<ReportExecutionResponse>> listExecutions(
      @RequestParam UUID projectId) {
    return ApiResponse.ok(reportService.listExecutions(projectId));
  }

  @GetMapping("/projects/{projectId}/s-curve")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ApiResponse<List<SCurveDataPoint>> getSCurveData(
      @PathVariable UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
      @RequestParam(defaultValue = "DAILY") String interval) {
    return ApiResponse.ok(reportService.generateSCurveData(projectId, start, end, interval));
  }

  @GetMapping("/projects/{projectId}/resource-histogram")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ApiResponse<List<ResourceHistogramEntry>> getResourceHistogram(
      @PathVariable UUID projectId,
      @RequestParam UUID resourceId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
    return ApiResponse.ok(reportService.generateResourceHistogram(projectId, resourceId, start, end));
  }

  @GetMapping("/executions/{id}/download")
  @PreAuthorize("hasPermission(null, 'REPORT.EXPORT')")
  public ResponseEntity<byte[]> downloadReportExecution(@PathVariable UUID id) {
    var execution = reportService.getExecution(id);

    MediaType contentType =
        execution.format() == ReportFormat.EXCEL
            ? MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            : MediaType.APPLICATION_PDF;

    String fileName = execution.filePath() != null ? execution.filePath() : "report.bin";

    return ResponseEntity.ok()
        .contentType(contentType)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
        .body(new byte[0]); // Placeholder: actual bytes would come from file storage
  }

  @GetMapping("/monthly-progress")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<MonthlyProgressData> getMonthlyProgress(
      @RequestParam UUID projectId,
      @RequestParam String period) {
    return ApiResponse.ok(reportService.getMonthlyProgress(projectId, period));
  }

  @GetMapping("/evm")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<EvmReportData> getEvmReport(@RequestParam UUID projectId) {
    return ApiResponse.ok(reportService.getEvmReport(projectId));
  }

  @GetMapping("/cash-flow")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<List<CashFlowEntry>> getCashFlowReport(@RequestParam UUID projectId) {
    return ApiResponse.ok(reportService.getCashFlowReport(projectId));
  }

  @GetMapping("/contract-status")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<ContractStatusData> getContractStatus(@RequestParam UUID projectId) {
    return ApiResponse.ok(reportService.getContractStatus(projectId));
  }

  @GetMapping("/risk-register")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<RiskRegisterData> getRiskRegister(@RequestParam UUID projectId) {
    return ApiResponse.ok(reportService.getRiskRegister(projectId));
  }

  @GetMapping("/resource-utilization")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<ResourceUtilizationData> getResourceUtilization(
      @RequestParam UUID projectId) {
    return ApiResponse.ok(reportService.getResourceUtilization(projectId));
  }

  @GetMapping("/capacity-utilization")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<CapacityUtilizationReport> getCapacityUtilization(
      @RequestParam UUID projectId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @RequestParam(required = false, defaultValue = "RESOURCE_TYPE") String groupBy,
      @RequestParam(required = false) String normType,
      @RequestParam(required = false) UUID supervisorUserId) {
    return ApiResponse.ok(
        capacityUtilizationReportService.build(projectId, fromDate, toDate, groupBy, normType,
            supervisorUserId));
  }

  /**
   * Multi-period aggregate. Slices [fromDate, toDate] into weekly or monthly buckets and returns
   * the per-role section for each bucket. Frontend renders a pivot table (buckets along the top,
   * roles down the left).
   */
  @GetMapping("/capacity-utilization/aggregate")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<com.bipros.reporting.application.dto.CapacityUtilizationAggregateReport>
      getCapacityUtilizationAggregate(
          @RequestParam UUID projectId,
          @RequestParam(required = false, defaultValue = "MONTHLY") String periodType,
          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
          @RequestParam(required = false, defaultValue = "ROLE") String groupBy) {
    return ApiResponse.ok(
        capacityUtilizationReportService.aggregate(projectId, periodType, from, to, groupBy));
  }

  /**
   * Per-supervisor (or project-wide when {@code supervisorUserId} is null) productivity
   * rollup mirroring the SC180 Resource Productivity Report — Manpower Utilization by trade,
   * Equipment Utilization by equipment-type, and a per-activity drill-down with productivity
   * norms. Reads {@code project.dpr_manpower}/{@code project.dpr_equipment} directly.
   */
  @GetMapping("/supervisor-performance")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<SupervisorPerformanceReport> getSupervisorPerformance(
      @RequestParam UUID projectId,
      @RequestParam(required = false) UUID supervisorUserId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @RequestParam(required = false, defaultValue = "26") int workDays) {
    return ApiResponse.ok(supervisorPerformanceReportService.build(
        projectId, supervisorUserId, fromDate, toDate, workDays));
  }

  /**
   * Side-by-side comparison of N supervisors over the same window. Returns each supervisor's
   * full report plus pivoted trade/equipment deltas.
   */
  @GetMapping("/supervisor-performance/compare")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<SupervisorPerformanceComparison> compareSupervisorPerformance(
      @RequestParam UUID projectId,
      @RequestParam List<UUID> supervisorUserIds,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
      @RequestParam(required = false, defaultValue = "26") int workDays) {
    return ApiResponse.ok(supervisorPerformanceReportService.compare(
        projectId, supervisorUserIds, fromDate, toDate, workDays));
  }

  /**
   * Streams the 5-sheet Capacity Utilisation .xlsx workbook (Plant utilization, Manpower
   * utilization, SUMMARY, Daily Deployment, DPR) for a single project and calendar month.
   * {@code month} accepts ISO {@code YYYY-MM}; {@code workDays} populates the highlighted Work
   * days cell (defaults to 26 — typical 6-day-week construction month).
   */
  @GetMapping("/capacity-utilization/excel")
  @PreAuthorize("hasPermission(null, 'REPORT.EXPORT')")
  public ResponseEntity<byte[]> downloadCapacityUtilizationExcel(
      @RequestParam UUID projectId,
      @RequestParam String month,
      @RequestParam(required = false, defaultValue = "26") int workDays,
      @RequestParam(required = false) UUID supervisorUserId) {
    YearMonth ym = YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyy-MM"));
    LocalDate from = ym.atDay(1);
    LocalDate to = ym.atEndOfMonth();
    var plant = capacityUtilizationReportService.build(
        projectId, from, to, "RESOURCE_TYPE", "EQUIPMENT", supervisorUserId);
    var manpower = capacityUtilizationReportService.build(
        projectId, from, to, "RESOURCE_TYPE", "MANPOWER", supervisorUserId);
    var daily = dailyDeploymentReportService.build(projectId, ym);
    var dpr = dprReportService.build(projectId, ym);
    String projectName = lookupProjectName(projectId);

    byte[] bytes = capacityUtilizationExcelWriter.generate(
        plant, manpower, daily, dpr, ym, workDays, projectName);

    String fileName = "capacity-utilization-" + ym + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + fileName + "\"")
        .body(bytes);
  }

  /**
   * Streams the "Daily Activity Costing" .xlsx for a project and date range — one sheet per
   * calendar month, APPROVED DPRs only. Mirrors the site teams' DPR-monthwise template.
   */
  @GetMapping("/dpr/excel")
  @PreAuthorize("hasPermission(null, 'DPR.READ')")
  public ResponseEntity<byte[]> downloadDprCostingExcel(
      @RequestParam UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    var report = dprCostingReportService.build(projectId, from, to);
    byte[] bytes = dprCostingExcelWriter.generate(report);

    String fileName = "dpr-costing-" + from + "_" + to + ".xlsx";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + fileName + "\"")
        .body(bytes);
  }

  private String lookupProjectName(UUID projectId) {
    @SuppressWarnings("unchecked")
    List<Object> rows = em.createNativeQuery(
            "SELECT name FROM project.projects WHERE id = :id")
        .setParameter("id", projectId)
        .setMaxResults(1)
        .getResultList();
    return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
  }

  @GetMapping("/trend-analysis")
  @PreAuthorize("hasPermission(null, 'REPORT.READ')")
  public ApiResponse<TrendAnalysisData> getTrendAnalysis(
      @RequestParam UUID projectId,
      @RequestParam(defaultValue = "6") int months) {
    return ApiResponse.ok(reportService.getTrendAnalysis(projectId, months));
  }
}
