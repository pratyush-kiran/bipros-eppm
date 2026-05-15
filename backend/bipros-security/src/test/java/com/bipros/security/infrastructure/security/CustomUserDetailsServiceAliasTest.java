package com.bipros.security.infrastructure.security;

import com.bipros.security.domain.model.Role;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.model.UserRole;
import com.bipros.security.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the legacy/rename role aliasing logic in
 * {@link CustomUserDetailsService}. See ROLE_ALIASES constant — these tests
 * pin the transitional aliasing behavior introduced in Phase 0.2 of the RBAC
 * overhaul. Remove together with the alias map once Phase 3 lands.
 */
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceAliasTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService service;

    private static final String USERNAME = "alice";

    // ── Bidirectional renames ────────────────────────────────────────────────

    @Test
    void qcManager_emitsQaQcEngineerAlias() {
        stubUserWithRole("QC_MANAGER");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).contains("ROLE_QC_MANAGER", "ROLE_QA_QC_ENGINEER");
    }

    @Test
    void qaQcEngineer_emitsQcManagerAlias() {
        stubUserWithRole("QA_QC_ENGINEER");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).contains("ROLE_QA_QC_ENGINEER", "ROLE_QC_MANAGER");
    }

    @Test
    void hseOfficer_emitsSafetyOfficerAlias() {
        stubUserWithRole("HSE_OFFICER");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).contains("ROLE_HSE_OFFICER", "ROLE_SAFETY_OFFICER");
    }

    @Test
    void safetyOfficer_emitsHseOfficerAlias() {
        stubUserWithRole("SAFETY_OFFICER");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).contains("ROLE_SAFETY_OFFICER", "ROLE_HSE_OFFICER");
    }

    // ── One-way legacy strings (canonical -> legacy authority only) ──────────

    @Test
    void supervisor_emitsSiteSupervisorAlias() {
        stubUserWithRole("SUPERVISOR");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).contains("ROLE_SUPERVISOR", "ROLE_SITE_SUPERVISOR");
    }

    @Test
    void finance_emitsCostEngineerAlias() {
        stubUserWithRole("FINANCE");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).contains("ROLE_FINANCE", "ROLE_COST_ENGINEER");
    }

    @Test
    void storeManager_emitsStoreKeeperAlias() {
        stubUserWithRole("STORE_MANAGER");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).contains("ROLE_STORE_MANAGER", "ROLE_STORE_KEEPER");
    }

    // ── Negative: unrelated role gets no aliases ─────────────────────────────

    @Test
    void projectManager_doesNotPickUpAnyAlias() {
        stubUserWithRole("PROJECT_MANAGER");

        Set<String> authorities = authoritiesOf(service.loadUserByUsername(USERNAME));

        assertThat(authorities).containsExactly("ROLE_PROJECT_MANAGER");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubUserWithRole(String roleName) {
        User user = new User(USERNAME, USERNAME + "@example.com", "{noop}hashed");
        user.setEnabled(true);
        user.setAccountLocked(false);

        Role role = new Role(roleName);
        UserRole userRole = new UserRole();
        userRole.setRole(role);

        Set<UserRole> roles = new HashSet<>();
        roles.add(userRole);
        user.setRoles(roles);

        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
    }

    private static Set<String> authoritiesOf(UserDetails details) {
        return details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
