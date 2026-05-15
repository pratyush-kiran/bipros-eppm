package com.bipros.portfolio.presentation;

import com.bipros.common.dto.ApiResponse;
import com.bipros.portfolio.application.dto.CreatePortfolioRequest;
import com.bipros.portfolio.application.dto.OptimizationResultResponse;
import com.bipros.portfolio.application.dto.OptimizePortfolioRequest;
import com.bipros.portfolio.application.dto.PortfolioProjectResponse;
import com.bipros.portfolio.application.dto.PortfolioResponse;
import com.bipros.portfolio.application.dto.ScenarioComparisonRequest;
import com.bipros.portfolio.application.dto.ScenarioComparisonResponse;
import com.bipros.portfolio.application.dto.UpdatePortfolioRequest;
import com.bipros.portfolio.application.dto.WhatIfRequest;
import com.bipros.portfolio.application.dto.WhatIfResponse;
import com.bipros.portfolio.application.service.PortfolioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/portfolios")
@RequiredArgsConstructor
public class PortfolioController {

  private final PortfolioService portfolioService;

  @PostMapping
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<ApiResponse<PortfolioResponse>> createPortfolio(
      @Valid @RequestBody CreatePortfolioRequest request) {
    PortfolioResponse response = portfolioService.createPortfolio(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<List<PortfolioResponse>>> listPortfolios() {
    List<PortfolioResponse> response = portfolioService.listPortfolios();
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<PortfolioResponse>> getPortfolio(@PathVariable UUID id) {
    PortfolioResponse response = portfolioService.getPortfolio(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<ApiResponse<PortfolioResponse>> updatePortfolio(
      @PathVariable UUID id,
      @Valid @RequestBody UpdatePortfolioRequest request) {
    PortfolioResponse updated = portfolioService.update(id, request);
    return ResponseEntity.ok(ApiResponse.ok(updated));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<Void> deletePortfolio(@PathVariable UUID id) {
    portfolioService.deletePortfolio(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/projects")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<ApiResponse<PortfolioProjectResponse>> addProject(
      @PathVariable UUID id, @RequestParam UUID projectId) {
    PortfolioProjectResponse response = portfolioService.addProject(id, projectId);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @DeleteMapping("/{id}/projects/{projectId}")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<Void> removeProject(@PathVariable UUID id, @PathVariable UUID projectId) {
    portfolioService.removeProject(id, projectId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/projects")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<List<PortfolioProjectResponse>>> getPortfolioProjects(
      @PathVariable UUID id) {
    List<PortfolioProjectResponse> response = portfolioService.getPortfolioProjects(id);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PostMapping("/{id}/optimize")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.UPDATE')")
  public ResponseEntity<ApiResponse<OptimizationResultResponse>> optimizePortfolio(
      @PathVariable UUID id,
      @Valid @RequestBody OptimizePortfolioRequest request) {
    OptimizationResultResponse response = portfolioService.optimizePortfolio(id, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PostMapping("/{id}/what-if")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<WhatIfResponse>> whatIfAnalysis(
      @PathVariable UUID id,
      @Valid @RequestBody WhatIfRequest request) {
    WhatIfResponse response = portfolioService.whatIfAnalysis(id, request);
    return ResponseEntity.ok(ApiResponse.ok(response));
  }

  @PostMapping("/{id}/scenarios/compare")
  @PreAuthorize("hasPermission(null, 'PORTFOLIO.READ')")
  public ResponseEntity<ApiResponse<ScenarioComparisonResponse>> compareScenarios(
      @PathVariable UUID id,
      @Valid @RequestBody ScenarioComparisonRequest request) {
    ScenarioComparisonResponse response = portfolioService.compareScenarios(id, request.scenarioIds());
    return ResponseEntity.ok(ApiResponse.ok(response));
  }
}
