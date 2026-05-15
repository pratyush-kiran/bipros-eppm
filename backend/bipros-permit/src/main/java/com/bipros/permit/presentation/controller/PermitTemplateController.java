package com.bipros.permit.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.permit.application.dto.ApprovalStepTemplateDto;
import com.bipros.permit.application.dto.PermitPackDto;
import com.bipros.permit.application.dto.PermitTypeTemplateDto;
import com.bipros.permit.application.dto.PpeItemTemplateDto;
import com.bipros.permit.application.service.PermitTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PermitTemplateController {

    private final PermitTemplateService templateService;

    @GetMapping("/v1/permit-packs")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<PermitPackDto>>> listPacks() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listPacks()));
    }

    @GetMapping("/v1/permit-packs/{code}/types")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<PermitTypeTemplateDto>>> typesForPack(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listTypesForPack(code)));
    }

    @GetMapping("/v1/permit-types/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<PermitTypeTemplateDto>> getType(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.getType(id)));
    }

    @GetMapping("/v1/permit-types/{id}/ppe-items")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<PpeItemTemplateDto>>> ppeForType(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listPpeItemsForType(id)));
    }

    @GetMapping("/v1/permit-types/{id}/approval-steps")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<ApprovalStepTemplateDto>>> stepsForType(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listApprovalStepsForType(id)));
    }

    // ── Admin endpoints ────────────────────────────────────────────────────

    @GetMapping("/v1/admin/permit-packs")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<PermitPackDto>>> adminListPacks() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listPacks()));
    }

    @PostMapping("/v1/admin/permit-packs")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<PermitPackDto>> adminCreatePack(@Valid @RequestBody PermitPackDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(templateService.createPack(dto)));
    }

    @PutMapping("/v1/admin/permit-packs/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<PermitPackDto>> adminUpdatePack(@PathVariable UUID id,
                                                                      @Valid @RequestBody PermitPackDto dto) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.updatePack(id, dto)));
    }

    @GetMapping("/v1/admin/permit-types")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<PermitTypeTemplateDto>>> adminListTypes() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listAllTypes()));
    }

    @PostMapping("/v1/admin/permit-types")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<PermitTypeTemplateDto>> adminCreateType(@Valid @RequestBody PermitTypeTemplateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(templateService.createType(dto)));
    }

    @PutMapping("/v1/admin/permit-types/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<PermitTypeTemplateDto>> adminUpdateType(@PathVariable UUID id,
                                                                              @Valid @RequestBody PermitTypeTemplateDto dto) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.updateType(id, dto)));
    }

    @GetMapping("/v1/admin/ppe-items")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<PpeItemTemplateDto>>> adminListPpe() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listAllPpeItems()));
    }

    @PostMapping("/v1/admin/ppe-items")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<PpeItemTemplateDto>> adminCreatePpe(@Valid @RequestBody PpeItemTemplateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(templateService.createPpeItem(dto)));
    }

    @PutMapping("/v1/admin/ppe-items/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<PpeItemTemplateDto>> adminUpdatePpe(@PathVariable UUID id,
                                                                          @Valid @RequestBody PpeItemTemplateDto dto) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.updatePpeItem(id, dto)));
    }
}
