package com.bipros.reporting.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.reporting.application.dto.PredictionDto;
import com.bipros.reporting.application.service.PredictionService;
import com.bipros.reporting.domain.model.Prediction;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/projects/{projectId}/predictions")
@RequiredArgsConstructor
public class PredictionController {

  private final PredictionService predictionService;

  /**
   * Run all predictions for a project
   */
  @PostMapping("/run")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<List<PredictionDto>>> runPredictions(
      @PathVariable UUID projectId) {
    List<PredictionDto> predictions = predictionService.runAllPredictions(projectId);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(predictions));
  }

  /**
   * Get latest predictions for a project
   */
  @GetMapping
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<List<PredictionDto>>> getPredictions(
      @PathVariable UUID projectId) {
    List<PredictionDto> predictions = predictionService.getProjectPredictions(projectId);
    return ResponseEntity.ok(ApiResponse.ok(predictions));
  }

  /**
   * Get latest prediction by type
   */
  @GetMapping("/{type}")
  @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'REPORT.READ')")
  public ResponseEntity<ApiResponse<PredictionDto>> getPredictionByType(
      @PathVariable UUID projectId,
      @PathVariable String type) {
    Prediction.PredictionType predictionType = Prediction.PredictionType.valueOf(type);
    PredictionDto prediction = predictionService.getLatestPredictionByType(projectId,
        predictionType);
    return ResponseEntity.ok(ApiResponse.ok(prediction));
  }
}
