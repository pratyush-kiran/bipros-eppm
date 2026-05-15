package com.bipros.security.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.security.application.dto.RoleResponse;
import com.bipros.security.application.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only global Role catalog. Replaces the frontend's hardcoded
 * {@code CANONICAL_ROLES} constant in {@code roleApi.ts} so the UI can pull
 * the live list (with member counts) from the server instead of shipping a
 * stale copy of {@code RolePermissionMatrix.DEFAULTS}.
 *
 * <p>Authorised for anyone with {@code ADMIN_USER.READ} — admins and any
 * profile-driven role that can read the user admin module. ADMIN is
 * short-circuited by {@code CustomPermissionEvaluator}, so no explicit ADMIN
 * escape hatch is required.
 */
@Slf4j
@RestController
@RequestMapping("/v1/roles")
@Tag(name = "Roles", description = "Read-only global role catalog")
@PreAuthorize("hasPermission(null, 'ADMIN_USER.READ')")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @Operation(summary = "List roles",
            description = "Lists every global Role with the count of enabled users currently assigned to it. Ordered by role name.")
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.ok(roleService.listAllWithMemberCounts());
    }
}
