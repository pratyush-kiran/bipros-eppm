package com.bipros.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Port for resolving an arbitrary user's effective permission codes from outside the security
 * module. Implemented in bipros-security (UserPermissionAdapter) over CurrentUserService.
 * Lets modules that must NOT depend on bipros-security (e.g. bipros-project) check permissions.
 */
public interface UserPermissionPort {

    /** Effective permission codes for the user (role-matrix ∪ assigned profile). Empty if unknown. */
    Set<String> permissionsFor(UUID userId);

    /** True when {@code userId} holds {@code permissionCode}. Null-safe (false on null code/user). */
    default boolean hasPermission(UUID userId, String permissionCode) {
        if (userId == null || permissionCode == null) return false;
        return permissionsFor(userId).contains(permissionCode);
    }
}
