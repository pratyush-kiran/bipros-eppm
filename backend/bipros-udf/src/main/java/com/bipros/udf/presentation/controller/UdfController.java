package com.bipros.udf.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.udf.application.dto.CreateUserDefinedFieldRequest;
import com.bipros.udf.application.dto.SetUdfValueRequest;
import com.bipros.udf.application.dto.UdfValueResponse;
import com.bipros.udf.application.dto.UserDefinedFieldDto;
import com.bipros.udf.application.service.UdfService;
import com.bipros.udf.domain.model.UdfScope;
import com.bipros.udf.domain.model.UdfSubject;
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

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/v1/udf")
@RequiredArgsConstructor
public class UdfController {

    private final UdfService udfService;

    /**
     * Discovery endpoint. Lists the supported UDF subjects and scopes so clients can build the
     * drop-downs that drive {@link #listFields} without having to hard-code enum values.
     */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> catalog() {
        java.util.Map<String, Object> body = java.util.Map.of(
            "subjects", java.util.Arrays.stream(UdfSubject.values()).map(Enum::name).toList(),
            "scopes", java.util.Arrays.stream(UdfScope.values()).map(Enum::name).toList(),
            "endpoints", java.util.List.of(
                "GET /v1/udf/fields?subject={subject}&scope={scope}&projectId={optional uuid}",
                "POST /v1/udf/fields",
                "PUT /v1/udf/fields/{fieldId}",
                "DELETE /v1/udf/fields/{fieldId}",
                "GET /v1/udf/values/{entityId}",
                "PUT /v1/udf/values/{fieldId}/{entityId}"));
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /** Legacy alias (pluralised path). Same payload as {@link #catalog()}. */
    @GetMapping("-definitions")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<UserDefinedFieldDto>>> listAllDefinitions(
        @RequestParam(required = false) UdfSubject subject) {
        List<UserDefinedFieldDto> fields = subject != null
            ? udfService.getFieldsBySubject(subject, UdfScope.GLOBAL, null)
            : udfService.listAllFields();
        return ResponseEntity.ok(ApiResponse.ok(fields));
    }

    @PostMapping("/fields")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<UserDefinedFieldDto>> createField(
        @Valid @RequestBody CreateUserDefinedFieldRequest request) {
        UserDefinedFieldDto field = udfService.createField(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(field));
    }

    @GetMapping("/fields")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<UserDefinedFieldDto>>> listFields(
        @RequestParam(required = false) UdfSubject subject,
        @RequestParam(required = false) UdfScope scope,
        @RequestParam(required = false) UUID projectId) {
        UdfScope resolvedScope = scope != null ? scope : UdfScope.GLOBAL;
        List<UserDefinedFieldDto> fields = subject != null
            ? udfService.getFieldsBySubject(subject, resolvedScope, projectId)
            : udfService.listAllFields();
        return ResponseEntity.ok(ApiResponse.ok(fields));
    }

    @PutMapping("/fields/{fieldId}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<UserDefinedFieldDto>> updateField(
        @PathVariable UUID fieldId,
        @Valid @RequestBody CreateUserDefinedFieldRequest request) {
        UserDefinedFieldDto field = udfService.updateField(fieldId, request);
        return ResponseEntity.ok(ApiResponse.ok(field));
    }

    @DeleteMapping("/fields/{fieldId}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deleteField(@PathVariable UUID fieldId) {
        udfService.deleteField(fieldId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/values/{fieldId}/{entityId}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<UdfValueResponse>> setValue(
        @PathVariable UUID fieldId,
        @PathVariable UUID entityId,
        @Valid @RequestBody SetUdfValueRequest request) {
        UdfValueResponse value = udfService.setValue(fieldId, entityId, request);
        return ResponseEntity.ok(ApiResponse.ok(value));
    }

    @GetMapping("/values/{fieldId}/{entityId}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<UdfValueResponse>> getValue(
        @PathVariable UUID fieldId,
        @PathVariable UUID entityId) {
        UdfValueResponse value = udfService.getValue(fieldId, entityId);
        return ResponseEntity.ok(ApiResponse.ok(value));
    }

    @GetMapping("/values/{entityId}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<UdfValueResponse>>> getValuesForEntity(
        @PathVariable UUID entityId) {
        List<UdfValueResponse> values = udfService.getValuesForEntity(entityId);
        return ResponseEntity.ok(ApiResponse.ok(values));
    }

    @GetMapping("/fields/{fieldId}/evaluate/{entityId}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<String>> evaluateFormula(
        @PathVariable UUID fieldId,
        @PathVariable UUID entityId) {
        String result = udfService.evaluateFormula(fieldId, entityId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
