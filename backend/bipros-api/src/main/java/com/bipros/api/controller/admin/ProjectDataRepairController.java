package com.bipros.api.controller.admin;

import com.bipros.api.dto.BudgetCorrectionRequest;
import com.bipros.api.dto.BudgetCorrectionResponse;
import com.bipros.api.dto.DataHealthResponse;
import com.bipros.api.dto.RepairReport;
import com.bipros.api.dto.RepairRequest;
import com.bipros.api.service.ProjectBudgetCorrectionService;
import com.bipros.api.service.ProjectDataRepairService;
import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/projects/{projectId}")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class ProjectDataRepairController {

  private final ProjectDataRepairService service;
  private final ProjectBudgetCorrectionService budgetCorrectionService;

  @GetMapping("/data-health")
  public ResponseEntity<ApiResponse<DataHealthResponse>> dataHealth(@PathVariable UUID projectId) {
    log.info("GET /v1/admin/projects/{}/data-health", projectId);
    return ResponseEntity.ok(ApiResponse.ok(service.diagnose(projectId)));
  }

  @PostMapping("/repair")
  public ResponseEntity<ApiResponse<RepairReport>> repair(
      @PathVariable UUID projectId,
      @RequestBody(required = false) RepairRequest request) {
    RepairRequest req = request == null ? new RepairRequest() : request;
    log.info("POST /v1/admin/projects/{}/repair dryRun={} phases={}", projectId, req.isDryRun(), req.getPhases());
    return ResponseEntity.ok(ApiResponse.ok(service.repair(projectId, req)));
  }

  @PostMapping("/budget-correction")
  public ResponseEntity<ApiResponse<BudgetCorrectionResponse>> correctBudget(
      @PathVariable UUID projectId,
      @RequestBody BudgetCorrectionRequest req) {
    log.info("POST /v1/admin/projects/{}/budget-correction correctedBudget={} recomputeEvm={}",
        projectId, req.getCorrectedBudget(), req.isRecomputeEvm());
    return ResponseEntity.ok(ApiResponse.ok(budgetCorrectionService.correctBudget(projectId, req)));
  }
}
