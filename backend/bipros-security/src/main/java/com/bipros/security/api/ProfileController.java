package com.bipros.security.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.security.application.dto.CreateProfileRequest;
import com.bipros.security.application.dto.ProfileResponse;
import com.bipros.security.application.dto.UpdateProfileRequest;
import com.bipros.security.application.service.ProfileService;
import com.bipros.security.domain.model.PermissionCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/profiles")
@Tag(name = "Profiles", description = "Permission profile management (Admin only)")
@Slf4j
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping("/permissions")
    @PreAuthorize("hasPermission(null, 'ADMIN_PROFILE.READ')")
    @Operation(summary = "List the static catalog of permission codes")
    public ResponseEntity<ApiResponse<List<PermissionCatalog.Permission>>> listPermissions() {
        return ResponseEntity.ok(ApiResponse.ok(PermissionCatalog.ALL));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_PROFILE.READ')")
    @Operation(summary = "List all profiles")
    public ResponseEntity<ApiResponse<List<ProfileResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(profileService.listProfiles()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_PROFILE.READ')")
    @Operation(summary = "Get a profile by id")
    public ResponseEntity<ApiResponse<ProfileResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(profileService.getProfile(id)));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_PROFILE.CREATE')")
    @Operation(summary = "Create a new profile")
    public ResponseEntity<ApiResponse<ProfileResponse>> create(@Valid @RequestBody CreateProfileRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(profileService.createProfile(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_PROFILE.UPDATE')")
    @Operation(summary = "Update a profile (system defaults are editable but never deletable)")
    public ResponseEntity<ApiResponse<ProfileResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProfileRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(profileService.updateProfile(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_PROFILE.DELETE')")
    @Operation(summary = "Delete a custom profile (system defaults reject with 409)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        profileService.deleteProfile(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
