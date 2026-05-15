package com.bipros.security.infrastructure.security;

import com.bipros.security.application.service.CurrentUserService;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Resolves {@code hasPermission(...)} in SpEL expressions against the current user's effective
 * permission set (matrix defaults for their roles + their assigned profile's permissions).
 *
 * <p>Supports the two-arg form {@code hasPermission(target, permission)} used by Spring's
 * MethodSecurityExpressionHandler — the {@code target} is ignored (we don't do object-ACL today),
 * only the permission code matters. The three-arg form {@code hasPermission(targetId, targetType,
 * permission)} also delegates to the same check.
 *
 * <p>ADMIN escape hatch: a user with the ROLE_ADMIN authority always passes. This mirrors how the
 * existing @PreAuthorize("hasRole('ADMIN')") expressions implicitly behave today — admin sees
 * everything regardless of their assigned profile.
 */
@Component
public class CustomPermissionEvaluator implements PermissionEvaluator {

    private final CurrentUserService currentUserService;

    public CustomPermissionEvaluator(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object target, Object permission) {
        return check(authentication, permission);
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId,
                                 String targetType, Object permission) {
        return check(authentication, permission);
    }

    private boolean check(Authentication authentication, Object permission) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null) {
            return false;
        }
        // ADMIN escape hatch — short-circuit the permission set lookup.
        boolean isAdmin = authentication.getAuthorities() != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) return true;
        return currentUserService.hasPermission(permission.toString());
    }
}
