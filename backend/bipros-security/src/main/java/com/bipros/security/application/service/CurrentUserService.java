package com.bipros.security.application.service;

import com.bipros.security.domain.model.Profile;
import com.bipros.security.domain.model.RolePermissionMatrix;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.ProfileRepository;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the currently-authenticated {@link User} from the Spring {@code SecurityContext}.
 *
 * <p>The {@code SecurityContextHelper.getCurrentUserId()} in {@code bipros-common} casts the
 * username string straight to UUID, which is wrong for this app — Spring's principal carries
 * the literal username (e.g. {@code "admin"}), not the user's primary key. This service does the
 * proper lookup via {@link UserRepository}.
 *
 * <p>Holds no state; safe to inject anywhere.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    /** @return the current user's ID, or {@code null} if no authenticated user is in scope. */
    public UUID getCurrentUserId() {
        return getCurrentUser().map(User::getId).orElse(null);
    }

    public Optional<User> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return Optional.empty();
        }
        return userRepository.findByUsername(auth.getName());
    }

    public boolean isAdmin() {
        return currentRoles().contains("ROLE_ADMIN");
    }

    /**
     * @return {@code true} when there's no Spring {@code Authentication} at all — i.e. we're
     *         running inside a CommandLineRunner / startup seeder / scheduled job, NOT an HTTP
     *         request. ProjectAccessService treats this as "system mode" and bypasses RBAC.
     *         Anonymous authenticated requests (set by Spring's anonymous filter) are NOT system.
     */
    public boolean isSystemContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null;
    }

    public boolean hasRole(String role) {
        Set<String> roles = currentRoles();
        return roles.contains(role) || roles.contains("ROLE_" + role);
    }

    public Set<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns the union of every fine-grained permission code the current user can exercise.
     *
     * <p>Two sources are combined:
     * <ol>
     *   <li>{@link RolePermissionMatrix} — the <em>default</em> permission set hard-coded for each
     *       role the user holds (via {@code user_roles}). This is the platform's shipped baseline
     *       and is identical for every user in that role.</li>
     *   <li>The user's directly-assigned {@link Profile} (via {@code User.profileId}) —
     *       <em>admin-customised</em> permissions layered on top. A profile may grant codes the
     *       role's matrix omits; it never revokes (the union widens, it does not narrow).</li>
     * </ol>
     *
     * <p>Precedence: both sources are additive — {@code effective = matrix(roles) ∪ profile}. There
     * is no subtraction. To restrict a user, remove a role or assign a narrower profile.
     *
     * <p>If there is no authenticated user (system context, anonymous, or unknown principal) an
     * empty immutable set is returned. The returned set is always immutable.
     */
    public Set<String> getEffectivePermissions() {
        return getCurrentUser().map(this::permissionsFor).orElse(Set.of());
    }

    /**
     * Compute the effective permission union for an arbitrary {@link User} reference, independent
     * of the {@code SecurityContext}. Used at JWT-issue time (login / refresh) before any
     * authentication has been planted into the context, and anywhere else a permission set is
     * needed for a user other than the current principal.
     *
     * <p>Semantics mirror {@link #getEffectivePermissions()}: union of
     * {@link RolePermissionMatrix#permissionsForAll(java.util.Collection)} over the user's role
     * names plus the user's assigned {@link Profile#getPermissions()}. Returns an immutable set;
     * empty if {@code user} is {@code null}.
     */
    public Set<String> permissionsFor(User user) {
        if (user == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        Set<String> roleNames = user.getRoles().stream()
                .map(ur -> ur.getRole() == null ? null : ur.getRole().getName())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        result.addAll(RolePermissionMatrix.permissionsForAll(roleNames));
        if (user.getProfileId() != null) {
            profileRepository.findById(user.getProfileId())
                    .map(Profile::getPermissions)
                    .ifPresent(result::addAll);
        }
        return Set.copyOf(result);
    }

    /**
     * Convenience predicate over {@link #getEffectivePermissions()}.
     *
     * <p>Returns {@code true} when {@code code} appears in either the user's role-matrix defaults
     * or the user's assigned profile. See {@link #getEffectivePermissions()} for the union /
     * precedence rules — the same additive semantics apply here.
     *
     * @param code a fine-grained permission code (e.g. {@code "PROJECT.READ"})
     * @return {@code true} when the current user holds the permission via role default or profile
     */
    public boolean hasPermission(String code) {
        return getEffectivePermissions().contains(code);
    }
}
