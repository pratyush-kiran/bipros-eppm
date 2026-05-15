package com.bipros.admin.presentation.controller;

import com.bipros.common.dto.ApiResponse;
import com.bipros.admin.application.dto.CreateGlobalSettingRequest;
import com.bipros.admin.application.dto.GlobalSettingDto;
import com.bipros.admin.application.service.GlobalSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/v1/admin/settings")
@RequiredArgsConstructor
public class GlobalSettingController {

    private final GlobalSettingService globalSettingService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<GlobalSettingDto>> createSetting(
        @Valid @RequestBody CreateGlobalSettingRequest request) {
        GlobalSettingDto setting = globalSettingService.createSetting(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(setting));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<ApiResponse<GlobalSettingDto>> getSetting(@PathVariable UUID id) {
        GlobalSettingDto setting = globalSettingService.getSetting(id);
        return ResponseEntity.ok(ApiResponse.ok(setting));
    }

    @GetMapping("/key/{key}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<ApiResponse<GlobalSettingDto>> getSettingByKey(@PathVariable String key) {
        GlobalSettingDto setting = globalSettingService.getSettingByKey(key);
        return ResponseEntity.ok(ApiResponse.ok(setting));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.READ')")
    public ResponseEntity<ApiResponse<List<GlobalSettingDto>>> listSettings(
        @RequestParam(required = false) String category) {
        List<GlobalSettingDto> settings;
        if (category != null) {
            settings = globalSettingService.getSettingsByCategory(category);
        } else {
            settings = globalSettingService.getAllSettings();
        }
        return ResponseEntity.ok(ApiResponse.ok(settings));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<GlobalSettingDto>> updateSetting(
        @PathVariable UUID id,
        @Valid @RequestBody CreateGlobalSettingRequest request) {
        GlobalSettingDto setting = globalSettingService.updateSetting(id, request);
        return ResponseEntity.ok(ApiResponse.ok(setting));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deleteSetting(@PathVariable UUID id) {
        globalSettingService.deleteSetting(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
