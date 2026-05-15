package com.bipros.udf.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.udf.application.dto.*;
import com.bipros.udf.application.service.FormulaConfigurationService;
import com.bipros.udf.application.service.FormulaEngine;
import com.bipros.udf.domain.model.FormulaCategory;
import com.bipros.udf.domain.model.FormulaVersion;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/formulas")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
public class FormulaController {

    private final FormulaEngine formulaEngine;
    private final FormulaConfigurationService formulaConfigurationService;

    // ---- Master Formulas ----

    @PostMapping
    public ResponseEntity<ApiResponse<FormulaDto>> createMasterFormula(
            @Valid @RequestBody CreateFormulaRequest request) {
        FormulaDto formula = formulaConfigurationService.createMasterFormula(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(formula));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FormulaDto>> updateMasterFormula(
            @PathVariable UUID id,
            @Valid @RequestBody CreateFormulaRequest request) {
        FormulaDto formula = formulaConfigurationService.updateMasterFormula(id, request);
        return ResponseEntity.ok(ApiResponse.ok(formula));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FormulaDto>>> listAllFormulas() {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.listAllMasterFormulas()));
    }

    @GetMapping("/by-category")
    public ResponseEntity<ApiResponse<List<FormulaCategoryDto>>> listFormulasByCategory() {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.listFormulasByCategory()));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<ApiResponse<List<FormulaDto>>> listFormulasByCategory(
            @PathVariable FormulaCategory category) {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.listFormulasByCategory(category)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FormulaDto>> getFormula(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.getMasterFormula(id)));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<ApiResponse<FormulaDto>> getFormulaByCode(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.getMasterFormulaByCode(code)));
    }

    // ---- Evaluation ----

    @PostMapping("/evaluate")
    public ResponseEntity<ApiResponse<FormulaResultDto>> evaluateFormula(
            @Valid @RequestBody EvaluateFormulaRequest request) {
        Map<String, BigDecimal> context = request.getVariables() != null
                ? request.getVariables().entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    try {
                                        return new BigDecimal(e.getValue());
                                    } catch (NumberFormatException ex) {
                                        return BigDecimal.ZERO;
                                    }
                                }))
                : Map.of();

        FormulaResultDto result = formulaEngine.evaluate(
                request.getFormulaCode(), request.getProjectId(), context);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/evaluate/{formulaCode}")
    public ResponseEntity<ApiResponse<FormulaResultDto>> evaluateFormulaByCode(
            @PathVariable String formulaCode,
            @RequestParam(required = false) UUID projectId,
            @RequestBody Map<String, String> variables) {
        Map<String, BigDecimal> context = variables != null
                ? variables.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    try {
                                        return new BigDecimal(e.getValue());
                                    } catch (NumberFormatException ex) {
                                        return BigDecimal.ZERO;
                                    }
                                }))
                : Map.of();

        FormulaResultDto result = formulaEngine.evaluate(formulaCode, projectId, context);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ---- Overrides ----

    @PostMapping("/overrides")
    public ResponseEntity<ApiResponse<FormulaOverrideDto>> createOverride(
            @Valid @RequestBody CreateFormulaOverrideRequest request) {
        FormulaOverrideDto dto = formulaConfigurationService.createOverride(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(dto));
    }

    @PutMapping("/overrides/{id}")
    public ResponseEntity<ApiResponse<FormulaOverrideDto>> updateOverride(
            @PathVariable UUID id,
            @Valid @RequestBody CreateFormulaOverrideRequest request) {
        FormulaOverrideDto dto = formulaConfigurationService.updateOverride(id, request);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @DeleteMapping("/overrides/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteOverride(@PathVariable UUID id) {
        formulaConfigurationService.deleteOverride(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/overrides/project/{projectId}")
    public ResponseEntity<ApiResponse<List<FormulaOverrideDto>>> listOverridesByProject(
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.listOverridesByProject(projectId)));
    }

    @GetMapping("/overrides/formula/{formulaCode}")
    public ResponseEntity<ApiResponse<List<FormulaOverrideDto>>> listOverridesByFormula(
            @PathVariable String formulaCode) {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.listOverridesByFormula(formulaCode)));
    }

    // ---- Versions ----

    @GetMapping("/{formulaCode}/versions")
    public ResponseEntity<ApiResponse<List<FormulaVersion>>> listVersions(
            @PathVariable String formulaCode,
            @RequestParam(required = false) UUID projectId) {
        return ResponseEntity.ok(ApiResponse.ok(formulaConfigurationService.listVersions(formulaCode, projectId)));
    }

    @PostMapping("/versions/{versionId}/revert")
    public ResponseEntity<ApiResponse<FormulaOverrideDto>> revertToVersion(
            @PathVariable UUID versionId) {
        FormulaOverrideDto dto = formulaConfigurationService.revertToVersion(versionId);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }
}
