package com.bipros.security.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.common.dto.PagedResponse;
import com.bipros.security.application.dto.AssignProfileRequest;
import com.bipros.security.application.dto.CreateUserRequest;
import com.bipros.security.application.dto.SetPasswordRequest;
import com.bipros.security.application.dto.UpdateUserProfileRequest;
import com.bipros.security.application.dto.UpdateUserRolesRequest;
import com.bipros.security.application.dto.UpdateUserStatusRequest;
import com.bipros.security.application.dto.UserAccessResponse;
import com.bipros.security.application.dto.UserResponse;
import com.bipros.security.application.service.UserAccessService;
import com.bipros.security.application.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/users")
// Class-level guard is just authentication; per-method @PreAuthorize narrows the privileged
// endpoints (list, getById, updateProfile, access matrix) below. /me is open to every signed-in
// user — they're allowed to see their own profile regardless of role.
@PreAuthorize("isAuthenticated()")
@Tag(name = "Users", description = "User management endpoints")
@Slf4j
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserAccessService userAccessService;

    @GetMapping("/me")
    @Operation(summary = "Get current user", description = "Retrieve the currently authenticated user details")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser() {
        try {
            UserResponse response = userService.getCurrentUser();
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (Exception e) {
            log.error("Error retrieving current user", e);
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("NOT_FOUND", e.getMessage()));
        }
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.READ') "
            + "or (#roles != null and #roles.length() > 0 and hasPermission(null, 'DPR.CREATE'))")
    @Operation(summary = "List users",
            description = "Retrieve a paginated list of users. Unfiltered listing requires "
                    + "ADMIN_USER.READ. The role-filtered form (?roles=COMMA,SEPARATED,NAMES) "
                    + "is also reachable by DPR.CREATE callers — it powers the DPR supervisor "
                    + "picker on the project pages.")
    public ResponseEntity<ApiResponse<PagedResponse<UserResponse>>> listUsers(
            Pageable pageable,
            @RequestParam(name = "roles", required = false) String roles) {
        try {
            Page<UserResponse> page = userService.listUsers(pageable, roles);
            PagedResponse<UserResponse> response = PagedResponse.of(
                    page.getContent(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.getNumber(),
                    page.getSize()
            );
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (Exception e) {
            log.error("Error listing users", e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("INTERNAL_SERVER_ERROR", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.READ')")
    @Operation(summary = "Get user by ID", description = "Retrieve a specific user by their ID")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        try {
            UserResponse response = userService.getUserById(id);
            return ResponseEntity.ok(ApiResponse.ok(response));
        } catch (Exception e) {
            log.error("Error retrieving user: {}", id, e);
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("NOT_FOUND", e.getMessage()));
        }
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.CREATE')")
    @Operation(summary = "Create user", description = "Create a new user (admin only).")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.createUser(request)));
    }

    @PostMapping("/set-password")
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.UPDATE')")
    @Operation(summary = "Admin set/reset a user's password by username",
        description = "Sets the given user's password (BCrypt-hashed, same as login). Admin "
            + "override — no current password required. User identified by exact username.")
    public ResponseEntity<ApiResponse<String>> setPassword(
            @Valid @RequestBody SetPasswordRequest request) {
        userService.setPasswordByUsername(request.username(), request.password());
        return ResponseEntity.ok(ApiResponse.ok("Password updated for user '" + request.username() + "'"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.UPDATE')")
    @Operation(summary = "Update personnel profile",
        description = "Update mobile, department, joining dates, presence status and other "
            + "Personnel Master (Screen 07) fields for a user.")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateProfile(id, request)));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.UPDATE')")
    @Operation(summary = "Enable or disable a user")
    public ResponseEntity<ApiResponse<UserResponse>> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.setEnabled(id, request.enabled())));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.UPDATE')")
    @Operation(summary = "Replace the user's role set")
    public ResponseEntity<ApiResponse<UserResponse>> updateRoles(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRolesRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.setRoles(id, request)));
    }

    @PutMapping("/{id}/profile")
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.UPDATE')")
    @Operation(summary = "Assign (or clear with null) a permission profile to a user")
    public ResponseEntity<ApiResponse<UserResponse>> assignProfile(
            @PathVariable UUID id,
            @RequestBody AssignProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userService.setProfile(id, request.profileId())));
    }

    @GetMapping("/{id}/access")
    @PreAuthorize("hasPermission(null, 'ADMIN_USER.READ') or #id == @currentUserService.getCurrentUserId()")
    @Operation(summary = "Get IC-PMS module access & corridor scope for a user")
    public ResponseEntity<ApiResponse<UserAccessResponse>> getUserAccess(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(userAccessService.getAccess(id)));
        } catch (Exception e) {
            log.error("Error retrieving access for user: {}", id, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("INTERNAL_SERVER_ERROR", e.getMessage()));
        }
    }
}
