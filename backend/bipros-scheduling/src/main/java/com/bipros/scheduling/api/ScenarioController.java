package com.bipros.scheduling.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.scheduling.application.dto.CreateScenarioRequest;
import com.bipros.scheduling.application.dto.ScenarioComparisonResponse;
import com.bipros.scheduling.application.dto.ScheduleScenarioResponse;
import com.bipros.scheduling.application.service.ScenarioService;
import com.bipros.scheduling.domain.model.ScheduleScenario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/schedule-scenarios")
@RequiredArgsConstructor
@Slf4j
public class ScenarioController {

  private final ScenarioService scenarioService;

  @PostMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.UPDATE')")
  public ResponseEntity<ApiResponse<ScheduleScenarioResponse>> createScenario(
      @PathVariable UUID projectId,
      @RequestBody CreateScenarioRequest request) {
    log.info("Creating scenario for project: {}", projectId);
    ScheduleScenarioResponse result = scenarioService.createScenario(projectId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(result));
  }

  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ResponseEntity<ApiResponse<List<ScheduleScenarioResponse>>> listScenarios(
      @PathVariable UUID projectId) {
    log.info("Listing scenarios for project: {}", projectId);
    List<ScheduleScenarioResponse> result = scenarioService.listScenarios(projectId);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @GetMapping("/{scenarioId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ResponseEntity<ApiResponse<ScheduleScenarioResponse>> getScenario(
      @PathVariable UUID projectId,
      @PathVariable UUID scenarioId) {
    log.info("Getting scenario: {} for project: {}", scenarioId, projectId);
    ScheduleScenarioResponse result = scenarioService.getScenario(projectId, scenarioId);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PutMapping("/{scenarioId}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.UPDATE')")
  public ResponseEntity<ApiResponse<ScheduleScenarioResponse>> updateScenario(
      @PathVariable UUID projectId,
      @PathVariable UUID scenarioId,
      @RequestBody ScheduleScenario updates) {
    log.info("Updating scenario: {} for project: {}", scenarioId, projectId);
    ScheduleScenarioResponse result = scenarioService.updateScenario(projectId, scenarioId, updates);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PostMapping("/compare")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'SCHEDULE.READ')")
  public ResponseEntity<ApiResponse<ScenarioComparisonResponse>> compareScenarios(
      @PathVariable UUID projectId,
      @RequestParam UUID scenario1,
      @RequestParam UUID scenario2) {
    log.info("Comparing scenarios: {} and {} for project: {}", scenario1, scenario2, projectId);
    ScenarioComparisonResponse result = scenarioService.compareScenarios(projectId, scenario1, scenario2);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
