package com.bipros.reporting.presentation.controller;

import com.bipros.baseline.application.dto.BaselineResponse;
import com.bipros.baseline.application.dto.ScheduleComparisonResponse;
import com.bipros.baseline.application.service.BaselineService;
import com.bipros.common.dto.ApiResponse;
import com.bipros.reporting.application.dto.CashFlowEntry;
import com.bipros.reporting.application.dto.ReportDefinitionResponse;
import com.bipros.reporting.application.dto.ResourceHistogramEntry;
import com.bipros.reporting.application.dto.SCurveDataPoint;
import com.bipros.reporting.application.dto.CostVarianceReport;
import com.bipros.reporting.application.dto.ScheduleVarianceReport;
import com.bipros.reporting.application.service.CostVarianceReportService;
import com.bipros.reporting.application.service.ReportDataService;
import com.bipros.reporting.application.service.ReportService;
import com.bipros.reporting.application.service.ScheduleVarianceReportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wraps the existing report services behind the POST contract the Classic Reports UI expects:
 * {@code POST /v1/projects/{id}/reports/*}. The frontend sends no/minimal parameters, so the
 * controller fills in sensible defaults (MONTHLY interval, project date window, latest baseline).
 * Shapes are transformed to match {@code frontend/src/lib/api/reportApi.ts}.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/reports")
@RequiredArgsConstructor
@Slf4j
public class ProjectReportController {

  private final ReportService reportService;
  private final ReportDataService reportDataService;
  private final BaselineService baselineService;
  private final ScheduleVarianceReportService scheduleVarianceReportService;
  private final CostVarianceReportService costVarianceReportService;

  @PersistenceContext private EntityManager em;

  public record SCurveResponse(
      List<String> periods,
      List<BigDecimal> plangedCumulativeValue,
      List<BigDecimal> earnedCumulativeValue,
      List<BigDecimal> actualCumulativeValue) {}

  public record ResourceHistogramAllocation(String date, Map<String, Double> allocated) {}

  public record ResourceHistogramResponse(
      List<String> resources, List<ResourceHistogramAllocation> allocations) {}

  public record CashFlowResponse(
      List<String> periods,
      List<BigDecimal> outflows,
      List<BigDecimal> inflows,
      List<BigDecimal> netCashFlow) {}

  public record ScheduleComparisonRow(
      String activityCode,
      String activityName,
      LocalDate baselineStart,
      LocalDate baselineFinish,
      LocalDate currentStart,
      LocalDate currentFinish,
      long startVarianceDays,
      long finishVarianceDays) {}

  public record ScheduleComparisonResponsePayload(
      UUID baselineId, String baselineName, List<ScheduleComparisonRow> activities) {}

  public record ScheduleComparisonRequest(UUID baselineId) {}

  public record ResourceHistogramRequest(UUID resourceId) {}

  @PostMapping("/s-curve")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.EXPORT')")
  public ApiResponse<SCurveResponse> generateSCurve(@PathVariable UUID projectId) {
    LocalDate[] window = resolveProjectWindow(projectId);
    List<SCurveDataPoint> raw =
        reportService.generateSCurveData(projectId, window[0], window[1], "MONTHLY");

    List<String> periods = new ArrayList<>();
    List<BigDecimal> pv = new ArrayList<>();
    List<BigDecimal> ev = new ArrayList<>();
    List<BigDecimal> ac = new ArrayList<>();
    for (SCurveDataPoint p : raw) {
      periods.add(p.date() != null ? p.date().toString() : "");
      pv.add(p.plannedValue() != null ? p.plannedValue() : BigDecimal.ZERO);
      ev.add(p.earnedValue() != null ? p.earnedValue() : BigDecimal.ZERO);
      ac.add(p.actualCost() != null ? p.actualCost() : BigDecimal.ZERO);
    }
    return ApiResponse.ok(new SCurveResponse(periods, pv, ev, ac));
  }

  @PostMapping("/resource-histogram")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.EXPORT')")
  public ApiResponse<ResourceHistogramResponse> generateResourceHistogram(
      @PathVariable UUID projectId,
      @RequestBody(required = false) ResourceHistogramRequest body) {
    LocalDate[] window = resolveProjectWindow(projectId);

    List<UUID> resourceIds = new ArrayList<>();
    List<String> resourceNames = new ArrayList<>();
    if (body != null && body.resourceId() != null) {
      resourceIds.add(body.resourceId());
      resourceNames.add(resourceNameOrId(body.resourceId()));
    } else {
      collectProjectResources(projectId, resourceIds, resourceNames);
    }

    if (resourceIds.isEmpty()) {
      return ApiResponse.ok(new ResourceHistogramResponse(List.of(), List.of()));
    }

    Map<LocalDate, Map<String, Double>> daily = new LinkedHashMap<>();
    for (int i = 0; i < resourceIds.size(); i++) {
      UUID rid = resourceIds.get(i);
      String rname = resourceNames.get(i);
      List<ResourceHistogramEntry> entries =
          reportService.generateResourceHistogram(projectId, rid, window[0], window[1]);
      for (ResourceHistogramEntry e : entries) {
        if (e.date() == null) continue;
        Map<String, Double> row = daily.computeIfAbsent(e.date(), k -> new LinkedHashMap<>());
        row.merge(rname, e.plannedUnits() != null ? e.plannedUnits() : 0.0, Double::sum);
      }
    }

    List<ResourceHistogramAllocation> allocations = new ArrayList<>();
    for (Map.Entry<LocalDate, Map<String, Double>> entry : daily.entrySet()) {
      allocations.add(new ResourceHistogramAllocation(entry.getKey().toString(), entry.getValue()));
    }

    return ApiResponse.ok(new ResourceHistogramResponse(resourceNames, allocations));
  }

  @PostMapping("/cash-flow")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.EXPORT')")
  public ApiResponse<CashFlowResponse> generateCashFlow(@PathVariable UUID projectId) {
    List<CashFlowEntry> entries = reportDataService.getCashFlowReport(projectId);

    List<String> periods = new ArrayList<>();
    List<BigDecimal> outflows = new ArrayList<>();
    List<BigDecimal> inflows = new ArrayList<>();
    List<BigDecimal> net = new ArrayList<>();
    for (CashFlowEntry e : entries) {
      periods.add(e.period() != null ? e.period() : "");
      BigDecimal planned = e.planned() != null ? e.planned() : BigDecimal.ZERO;
      BigDecimal actual = e.actual() != null ? e.actual() : BigDecimal.ZERO;
      outflows.add(planned);
      inflows.add(actual);
      net.add(actual.subtract(planned));
    }
    return ApiResponse.ok(new CashFlowResponse(periods, outflows, inflows, net));
  }

  @PostMapping("/schedule-comparison")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.EXPORT')")
  public ApiResponse<ScheduleComparisonResponsePayload> generateScheduleComparison(
      @PathVariable UUID projectId,
      @RequestBody(required = false) ScheduleComparisonRequest body) {
    UUID baselineId = body != null ? body.baselineId() : null;
    String baselineName = null;

    if (baselineId == null) {
      List<BaselineResponse> baselines = baselineService.listBaselines(projectId);
      BaselineResponse chosen =
          baselines.stream()
              .filter(b -> Boolean.TRUE.equals(b.isActive()))
              .findFirst()
              .orElseGet(
                  () ->
                      baselines.stream()
                          .max(Comparator.comparing(BaselineResponse::createdAt))
                          .orElse(null));
      if (chosen == null) {
        return ApiResponse.ok(
            new ScheduleComparisonResponsePayload(null, null, Collections.emptyList()));
      }
      baselineId = chosen.id();
      baselineName = chosen.name();
    }

    List<ScheduleComparisonResponse> rows =
        baselineService.getScheduleComparison(projectId, baselineId);

    List<ScheduleComparisonRow> activities = new ArrayList<>();
    for (ScheduleComparisonResponse r : rows) {
      activities.add(
          new ScheduleComparisonRow(
              r.activityId() != null ? r.activityId().toString() : "",
              r.activityName(),
              r.baselineStart(),
              r.baselineFinish(),
              r.currentStart(),
              r.currentFinish(),
              r.startVarianceDays() != null ? r.startVarianceDays() : 0L,
              r.finishVarianceDays() != null ? r.finishVarianceDays() : 0L));
    }

    return ApiResponse.ok(
        new ScheduleComparisonResponsePayload(baselineId, baselineName, activities));
  }

  @GetMapping("/custom")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ApiResponse<List<ReportDefinitionResponse>> listCustom(@PathVariable UUID projectId) {
    return ApiResponse.ok(reportService.listReportDefinitions(null));
  }

  /**
   * P6-style Schedule Variance Report. {@code baselineId} is optional — when omitted, falls
   * back to {@code Project.activeBaselineId}. 404 if neither is set.
   */
  @GetMapping("/schedule-variance")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ApiResponse<ScheduleVarianceReport> getScheduleVarianceReport(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID baselineId) {
    return ApiResponse.ok(scheduleVarianceReportService.getReport(projectId, baselineId));
  }

  /**
   * P6-style Cost Variance Report. Same baseline-resolution rules as schedule-variance.
   */
  @GetMapping("/cost-variance")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ApiResponse<CostVarianceReport> getCostVarianceReport(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID baselineId) {
    return ApiResponse.ok(costVarianceReportService.getReport(projectId, baselineId));
  }

  private LocalDate[] resolveProjectWindow(UUID projectId) {
    LocalDate start = null;
    LocalDate finish = null;
    try {
      Object row =
          em.createNativeQuery(
                  "SELECT planned_start_date, planned_finish_date FROM project.projects WHERE id = ?1")
              .setParameter(1, projectId)
              .getSingleResult();
      if (row instanceof Object[] cols) {
        if (cols[0] != null) start = LocalDate.parse(cols[0].toString());
        if (cols[1] != null) finish = LocalDate.parse(cols[1].toString());
      }
    } catch (Exception e) {
      log.debug("Project window lookup failed for {}: {}", projectId, e.getMessage());
    }
    LocalDate today = LocalDate.now();
    if (start == null) start = today.minusMonths(6);
    if (finish == null) finish = today.plusMonths(12);
    if (!finish.isAfter(start)) finish = start.plusMonths(1);
    return new LocalDate[] {start, finish};
  }

  private void collectProjectResources(
      UUID projectId, List<UUID> ids, List<String> names) {
    try {
      @SuppressWarnings("unchecked")
      List<Object> rows =
          em.createNativeQuery(
                  "SELECT DISTINCT r.id, COALESCE(r.name, r.code, 'Resource') "
                      + "FROM resource.resources r "
                      + "JOIN resource.resource_assignments a ON a.resource_id = r.id "
                      + "WHERE a.project_id = ?1 "
                      + "ORDER BY 2")
              .setParameter(1, projectId)
              .getResultList();
      for (Object row : rows) {
        Object[] cols = (Object[]) row;
        if (cols[0] == null) continue;
        ids.add(UUID.fromString(cols[0].toString()));
        names.add(cols[1] != null ? cols[1].toString() : "Resource");
      }
    } catch (Exception e) {
      log.debug("Project resource lookup failed for {}: {}", projectId, e.getMessage());
    }
  }

  private String resourceNameOrId(UUID resourceId) {
    try {
      Object name =
          em.createNativeQuery(
                  "SELECT COALESCE(name, code, 'Resource') FROM resource.resources WHERE id = ?1")
              .setParameter(1, resourceId)
              .getSingleResult();
      return name != null ? name.toString() : resourceId.toString();
    } catch (Exception e) {
      return resourceId.toString();
    }
  }
}
