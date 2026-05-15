package com.bipros.admin.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.admin.application.dto.AdminCategoryDto;
import com.bipros.admin.application.dto.CreateAdminCategoryRequest;
import com.bipros.admin.application.service.AdminCategoryService;
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
@RequestMapping("/v1/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<AdminCategoryDto>> createCategory(
        @Valid @RequestBody CreateAdminCategoryRequest request) {
        AdminCategoryDto category = adminCategoryService.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(category));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<AdminCategoryDto>> getCategory(@PathVariable UUID id) {
        AdminCategoryDto category = adminCategoryService.getCategory(id);
        return ResponseEntity.ok(ApiResponse.ok(category));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<AdminCategoryDto>>> listCategories(
        @RequestParam(required = false) String categoryType) {
        List<AdminCategoryDto> categories = adminCategoryService.listCategories(categoryType);
        return ResponseEntity.ok(ApiResponse.ok(categories));
    }

    @GetMapping("/{id}/children")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.READ')")
    public ResponseEntity<ApiResponse<List<AdminCategoryDto>>> getChildCategories(@PathVariable UUID id) {
        List<AdminCategoryDto> children = adminCategoryService.getChildCategories(id);
        return ResponseEntity.ok(ApiResponse.ok(children));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<AdminCategoryDto>> updateCategory(
        @PathVariable UUID id,
        @Valid @RequestBody CreateAdminCategoryRequest request) {
        AdminCategoryDto category = adminCategoryService.updateCategory(id, request);
        return ResponseEntity.ok(ApiResponse.ok(category));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_MASTER.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(@PathVariable UUID id) {
        adminCategoryService.deleteCategory(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
