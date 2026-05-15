package com.bipros.security.application.service;

import com.bipros.security.application.dto.UserResponse;
import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.ProfileRepository;
import com.bipros.security.domain.repository.RoleRepository;
import com.bipros.security.domain.repository.UserRepository;
import com.bipros.security.domain.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService#listUsers(Pageable, String)} — Phase 4.6 of the RBAC overhaul.
 *
 * <p>The {@code ?roles=COMMA,SEPARATED} query param on {@code GET /v1/users} powers the
 * upcoming supervisor / staff picker. These tests pin:
 * <ul>
 *   <li>blank / null {@code roles} falls back to the unfiltered listing,</li>
 *   <li>a populated {@code roles} CSV is split, normalised to upper case, and forwarded to
 *       {@link UserRepository#findByRoleNamesAndEnabled(Collection, Pageable)} — and ONLY that
 *       method (the unfiltered {@code findAll} is not invoked),</li>
 *   <li>users whose role-name doesn't match are filtered out (i.e. only matching users are
 *       returned in the response page).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceListUsersRolesFilterTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private ProfileRepository profileRepository;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private com.bipros.common.util.AuditService auditService;
    @Mock private CurrentUserService currentUserService;

    @InjectMocks
    private UserService service;

    @Test
    void listUsers_nullRoles_callsFindAll() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

        Page<UserResponse> result = service.listUsers(pageable, null);

        assertThat(result.getContent()).isEmpty();
        verify(userRepository).findAll(pageable);
        verify(userRepository, never()).findByRoleNamesAndEnabled(anyCollection(), any(Pageable.class));
    }

    @Test
    void listUsers_blankRoles_callsFindAll() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

        service.listUsers(pageable, "   ");

        verify(userRepository).findAll(pageable);
        verify(userRepository, never()).findByRoleNamesAndEnabled(anyCollection(), any(Pageable.class));
    }

    @Test
    void listUsers_rolesFilter_onlyReturnsMatchingUsers() {
        // ── Arrange ──
        Pageable pageable = PageRequest.of(0, 20);

        User siteEngineer = userWithRole("site_eng", "SITE_ENGINEER");
        User supervisor = userWithRole("supervisor1", "SUPERVISOR");

        // The repo only returns users matching the requested role names; here only the
        // supervisor matches the SUPERVISOR,FOREMAN filter — site_eng must NOT come back.
        when(userRepository.findByRoleNamesAndEnabled(anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(supervisor), pageable, 1));
        when(currentUserService.permissionsFor(any(User.class))).thenReturn(Set.of());

        // ── Act ──
        Page<UserResponse> result = service.listUsers(pageable, "SUPERVISOR,FOREMAN");

        // ── Assert: only the SUPERVISOR user is returned ──
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).username()).isEqualTo("supervisor1");
        assertThat(result.getContent())
                .extracting(UserResponse::username)
                .doesNotContain(siteEngineer.getUsername());

        // ── Assert: the unfiltered listing path was NOT used ──
        verify(userRepository, never()).findAll(any(Pageable.class));

        // ── Assert: role names were forwarded correctly (split + uppercased) ──
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> rolesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository).findByRoleNamesAndEnabled(rolesCaptor.capture(), any(Pageable.class));
        assertThat(rolesCaptor.getValue()).containsExactlyInAnyOrder("SUPERVISOR", "FOREMAN");
    }

    @Test
    void listUsers_rolesFilter_normalisesToUpperCaseAndDeduplicates() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findByRoleNamesAndEnabled(anyCollection(), any(Pageable.class)))
                .thenReturn(Page.empty(pageable));

        service.listUsers(pageable, " supervisor , Foreman ,supervisor ");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> rolesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository).findByRoleNamesAndEnabled(rolesCaptor.capture(), any(Pageable.class));
        assertThat(rolesCaptor.getValue()).containsExactlyInAnyOrder("SUPERVISOR", "FOREMAN");
    }

    // ── Fixtures ──

    private static User userWithRole(String username, String roleName) {
        User u = new User(username, username + "@example.com", "{noop}x");
        u.setEnabled(true);
        Role role = new Role(roleName);
        UserRole ur = new UserRole();
        ur.setRole(role);
        Set<UserRole> roles = new HashSet<>();
        roles.add(ur);
        u.setRoles(roles);
        // Force a stable id so toResponse doesn't NPE on getId in downstream mappers.
        try {
            java.lang.reflect.Field f = com.bipros.common.model.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, UUID.randomUUID());
        } catch (ReflectiveOperationException ignored) {
            // Best-effort — UserResponse.from tolerates a null id in unit tests.
        }
        return u;
    }
}
