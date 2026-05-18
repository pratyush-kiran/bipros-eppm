package com.bipros.reporting.materialconsumption;

import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * REST API for the Material Consumption Report. JSON endpoint returns
 * {@link MaterialConsumptionReportResponse}; Excel endpoint returns a single-sheet .xlsx.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/reports/material-consumption")
@RequiredArgsConstructor
@Slf4j
public class MaterialConsumptionReportController {

  private final MaterialConsumptionReportService service;
  private final MaterialConsumptionExcelWriter excelWriter;

  @GetMapping
  public ApiResponse<MaterialConsumptionReportResponse> generate(
      @PathVariable UUID projectId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) UUID wbsNodeId,
      @RequestParam(required = false) UUID activityId,
      @RequestParam(required = false) UUID supervisorUserId,
      @RequestParam(required = false) UUID storekeeperUserId,
      @RequestParam(required = false) UUID materialRateMasterId,
      @RequestParam(required = false) String groupBy) {
    MaterialConsumptionFilter filter = new MaterialConsumptionFilter(
        projectId, from, to, wbsNodeId, activityId, supervisorUserId,
        storekeeperUserId, materialRateMasterId, groupBy);
    return ApiResponse.ok(service.generate(filter));
  }

  @GetMapping("/export.xlsx")
  public ResponseEntity<byte[]> exportExcel(
      @PathVariable UUID projectId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) UUID wbsNodeId,
      @RequestParam(required = false) UUID activityId,
      @RequestParam(required = false) UUID supervisorUserId,
      @RequestParam(required = false) UUID storekeeperUserId,
      @RequestParam(required = false) UUID materialRateMasterId,
      @RequestParam(required = false) String groupBy) {
    MaterialConsumptionFilter filter = new MaterialConsumptionFilter(
        projectId, from, to, wbsNodeId, activityId, supervisorUserId,
        storekeeperUserId, materialRateMasterId, groupBy);
    MaterialConsumptionReportResponse report = service.generate(filter);
    byte[] bytes;
    try {
      bytes = excelWriter.write(report);
    } catch (Exception e) {
      log.error("Material Consumption Excel export failed for project {}", projectId, e);
      return ResponseEntity.internalServerError().build();
    }
    String filename = "material-consumption-" + projectId + "-"
        + report.from() + "_" + report.to() + ".xlsx";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + filename + "\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(bytes);
  }
}
