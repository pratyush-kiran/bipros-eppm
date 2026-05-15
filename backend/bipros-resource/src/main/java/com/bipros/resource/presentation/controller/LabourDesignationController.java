package com.bipros.resource.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.resource.application.dto.LabourCategoryReference;
import com.bipros.resource.application.dto.LabourDesignationRequest;
import com.bipros.resource.application.dto.LabourDesignationResponse;
import com.bipros.resource.application.dto.LabourGradeReference;
import com.bipros.resource.application.service.LabourDesignationService;
import com.bipros.resource.domain.model.LabourCategory;
import com.bipros.resource.domain.model.LabourGrade;
import com.bipros.resource.domain.model.LabourStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/v1/labour-designations")
@PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
@RequiredArgsConstructor
@Slf4j
public class LabourDesignationController {

    private final LabourDesignationService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<PagedResponse<LabourDesignationResponse>>> list(
            @RequestParam(required = false) LabourCategory category,
            @RequestParam(required = false) LabourGrade grade,
            @RequestParam(required = false) LabourStatus status,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        log.info("GET /v1/labour-designations category={} grade={} status={} q={}",
            category, grade, status, q);
        return ResponseEntity.ok(ApiResponse.ok(service.search(category, grade, status, q, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<LabourDesignationResponse>> get(@PathVariable UUID id) {
        log.info("GET /v1/labour-designations/{} - Fetching designation", id);
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @GetMapping("/by-code/{code}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<LabourDesignationResponse>> getByCode(@PathVariable String code) {
        log.info("GET /v1/labour-designations/by-code/{} - Fetching designation", code);
        return ResponseEntity.ok(ApiResponse.ok(service.getByCode(code)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LabourDesignationResponse>> create(
            @Valid @RequestBody LabourDesignationRequest req) {
        log.info("POST /v1/labour-designations code={}", req.code());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<LabourDesignationResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody LabourDesignationRequest req) {
        log.info("PUT /v1/labour-designations/{} - Updating designation", id);
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        log.info("DELETE /v1/labour-designations/{} - Deleting designation", id);
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/categories")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<LabourCategoryReference>>> categories() {
        return ResponseEntity.ok(ApiResponse.ok(service.listCategories()));
    }

    @GetMapping("/grades")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<LabourGradeReference>>> grades() {
        return ResponseEntity.ok(ApiResponse.ok(service.listGrades()));
    }
}
