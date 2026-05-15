package com.bipros.portfolio.presentation;

import com.bipros.common.dto.ApiResponse;
import com.bipros.portfolio.application.dto.AddScoringCriterionRequest;
import com.bipros.portfolio.application.dto.CreateScoringModelRequest;
import com.bipros.portfolio.application.dto.ProjectRankingResponse;
import com.bipros.portfolio.application.dto.ScoringCriterionResponse;
import com.bipros.portfolio.application.dto.ScoringModelResponse;
import com.bipros.portfolio.application.service.ScoringService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/scoring-models")
@RequiredArgsConstructor
public class ScoringController {

  private final ScoringService scoringService;

  @PostMapping
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<ApiResponse<ScoringModelResponse>> createScoringModel(
      @Valid @RequestBody CreateScoringModelRequest request) {
    ScoringModelResponse response = scoringService.createScoringModel(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<List<ScoringModelResponse>>> listScoringModels() {
    List<ScoringModelResponse> response = scoringService.listScoringModels();
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<ScoringModelResponse>> getScoringModel(@PathVariable UUID id) {
    ScoringModelResponse response = scoringService.getScoringModel(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<Void> deleteScoringModel(@PathVariable UUID id) {
    scoringService.deleteScoringModel(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/criteria")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<ApiResponse<ScoringCriterionResponse>> addCriterion(
      @PathVariable UUID id, @Valid @RequestBody AddScoringCriterionRequest request) {
    ScoringCriterionResponse response = scoringService.addCriterion(id, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping("/{id}/criteria")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<List<ScoringCriterionResponse>>> getModelCriteria(
      @PathVariable UUID id) {
    List<ScoringCriterionResponse> response = scoringService.getModelCriteria(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PostMapping("/{modelId}/projects/{projectId}/score")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<Void> scoreProject(
      @PathVariable UUID modelId,
      @PathVariable UUID projectId,
      @RequestParam UUID criterionId,
      @RequestParam Double score) {
    scoringService.scoreProject(projectId, modelId, criterionId, score);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/{modelId}/ranking")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<List<ProjectRankingResponse>>> getProjectRanking(
      @PathVariable UUID modelId) {
    List<ProjectRankingResponse> response = scoringService.prioritizeProjects(modelId);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
