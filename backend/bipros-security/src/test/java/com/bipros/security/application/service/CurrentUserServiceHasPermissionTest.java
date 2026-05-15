package com.bipros.security.application.service;

import com.bipros.security.domain.model.PermissionCatalog;
import com.bipros.security.domain.model.Profile;
import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.ProfileRepository;
import com.bipros.security.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CurrentUserService#getEffectivePermissions()} and
 * {@link CurrentUserService#hasPermission(String)} — Phase 1.2 of the RBAC overhaul.
 *
 * <p>Pins the union semantics between {@link com.bipros.security.domain.model.RolePermissionMatrix}
 * defaults and the user's assigned {@link Profile} permissions.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserServiceHasPermissionTest {

    private static final String USERNAME = "alice";

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileRepository profileRepository;

    @InjectMocks
    private CurrentUserService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Cases ────────────────────────────────────────────────────────────────

    @Test
    void noAuth_returnsEmpty() {
        SecurityContextHolder.clearContext();

        assertThat(service.getEffectivePermissions()).isEmpty();
        assertThat(service.hasPermission("PROJECT.READ")).isFalse();
    }

    @Test
    void userWithRoleOnly_returnsMatrixPerms() {
        User user = fixture(USERNAME, null, "SITE_ENGINEER");
        stubAuthenticatedUser(user);

        Set<String> perms = service.getEffectivePermissions();

        assertThat(perms).contains("DPR.CREATE", "ACTIVITY.UPDATE");
        assertThat(perms).doesNotContain("PROJECT.DELETE");
    }

    @Test
    void userWithProfileOnly_returnsProfilePerms() {
        UUID profileId = UUID.randomUUID();
        User user = fixture(USERNAME, profileId /* no roles */);
        Profile profile = profileWithPermissions("PROJECT.READ", "REPORT.READ");
        stubAuthenticatedUser(user);
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(profile));

        Set<String> perms = service.getEffectivePermissions();

        assertThat(perms).containsExactlyInAnyOrder("PROJECT.READ", "REPORT.READ");
    }

    @Test
    void userWithRoleAndProfile_returnsUnion() {
        UUID profileId = UUID.randomUUID();
        User user = fixture(USERNAME, profileId, "SUPERVISOR");
        Profile profile = profileWithPermissions("REPORT.EXPORT");
        stubAuthenticatedUser(user);
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(profile));

        Set<String> perms = service.getEffectivePermissions();

        // Matrix contributes (SUPERVISOR -> DPR.CREATE among others) and profile contributes REPORT.EXPORT.
        assertThat(perms).contains("DPR.CREATE", "REPORT.EXPORT");
    }

    @Test
    void adminRole_returnsAllCatalogPermissions() {
        User user = fixture(USERNAME, null, "ADMIN");
        stubAuthenticatedUser(user);

        Set<String> perms = service.getEffectivePermissions();

        assertThat(perms).isEqualTo(PermissionCatalog.ALL_CODES);
    }

    @Test
    void hasPermissionReturnsFalseForUnknownCode() {
        User user = fixture(USERNAME, null, "SITE_ENGINEER");
        stubAuthenticatedUser(user);

        assertThat(service.hasPermission("MADE_UP.CODE")).isFalse();
    }

    @Test
    void unknownRole_contributesZeroPermissions() {
        User user = fixture(USERNAME, null, "WEIRD_LEGACY");
        stubAuthenticatedUser(user);

        assertThat(service.getEffectivePermissions()).isEmpty();
    }

    // ── Fixtures / helpers ───────────────────────────────────────────────────

    /** Build a {@link User} with the given role names and (nullable) profileId. */
    private static User fixture(String username, UUID profileId, String... roleNames) {
        User user = new User(username, username + "@example.com", "{noop}hashed");
        user.setEnabled(true);
        user.setAccountLocked(false);
        user.setProfileId(profileId);

        Set<UserRole> roles = new HashSet<>();
        for (String name : roleNames) {
            Role role = new Role(name);
            UserRole ur = new UserRole();
            ur.setRole(role);
            roles.add(ur);
        }
        user.setRoles(roles);
        return user;
    }

    private static Profile profileWithPermissions(String... codes) {
        Set<String> perms = new HashSet<>();
        for (String c : codes) {
            perms.add(c);
        }
        return new Profile("TEST_PROFILE", "Test Profile", "fixture",
                "TESTER", false, perms);
    }

    /**
     * Plant {@code user} into the {@link SecurityContextHolder} via a username-based
     * authentication token, and stub {@link UserRepository#findByUsername(String)} to return it.
     * Mirrors how {@code CurrentUserService.getCurrentUser()} resolves the principal.
     */
    private void stubAuthenticatedUser(User user) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(user.getUsername(), "n/a", java.util.List.of());
        SecurityContextHolder.setContext(new SecurityContextImpl(token));
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
    }
}
