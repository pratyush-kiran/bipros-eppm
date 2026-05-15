package com.bipros.project.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.project.application.dto.CreateWbsTemplateRequest;
import com.bipros.project.application.dto.WbsTemplateResponse;
import com.bipros.project.application.service.WbsTemplateService;
import com.bipros.project.domain.model.AssetClass;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/wbs-templates")
@RequiredArgsConstructor
public class WbsTemplateController {

    private final WbsTemplateService wbsTemplateService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<WbsTemplateResponse>>> listTemplates() {
        List<WbsTemplateResponse> templates = wbsTemplateService.listTemplates();
        return ResponseEntity.ok(ApiResponse.ok(templates));
    }

    @GetMapping("/asset-class/{assetClass}")
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<List<WbsTemplateResponse>>> listTemplatesByAssetClass(
        @PathVariable AssetClass assetClass) {
        List<WbsTemplateResponse> templates = wbsTemplateService.listTemplatesByAssetClass(assetClass);
        return ResponseEntity.ok(ApiResponse.ok(templates));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PROJECT.READ')")
    public ResponseEntity<ApiResponse<WbsTemplateResponse>> getTemplate(@PathVariable UUID id) {
        WbsTemplateResponse template = wbsTemplateService.getTemplate(id);
        return ResponseEntity.ok(ApiResponse.ok(template));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'PROJECT.CREATE')")
    public ResponseEntity<ApiResponse<WbsTemplateResponse>> createTemplate(
        @Valid @RequestBody CreateWbsTemplateRequest request) {
        WbsTemplateResponse response = wbsTemplateService.createTemplate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/{templateId}/apply")
    @PreAuthorize("@projectAccess.hasProjectPermission(#projectId, 'PROJECT.UPDATE')")
    public ResponseEntity<Void> applyTemplate(
        @PathVariable UUID templateId,
        @RequestParam UUID projectId) {
        wbsTemplateService.applyTemplate(projectId, templateId);
        return ResponseEntity.noContent().build();
    }
}
