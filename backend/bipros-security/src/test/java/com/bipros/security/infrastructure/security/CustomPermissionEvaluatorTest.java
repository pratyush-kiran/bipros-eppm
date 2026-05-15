package com.bipros.security.infrastructure.security;

import com.bipros.security.application.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomPermissionEvaluator}. Covers null/unauthenticated guards, the
 * ROLE_ADMIN short-circuit, positive/negative delegation to {@link CurrentUserService}, and that
 * the three-arg form routes through the same logic as the two-arg form.
 */
@ExtendWith(MockitoExtension.class)
class CustomPermissionEvaluatorTest {

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private CustomPermissionEvaluator evaluator;

    private Authentication nonAdminAuth;
    private Authentication adminAuth;

    @BeforeEach
    void setUp() {
        nonAdminAuth = new UsernamePasswordAuthenticationToken(
                "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_PROJECT_MANAGER")));
        adminAuth = new UsernamePasswordAuthenticationToken(
                "root", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void nullAuthentication_returnsFalse() {
        assertThat(evaluator.hasPermission(null, new Object(), "PROJECT.UPDATE")).isFalse();
        verifyNoInteractions(currentUserService);
    }

    @Test
    void unauthenticatedAuth_returnsFalse() {
        Authentication unauth = new UsernamePasswordAuthenticationToken("alice", "n/a"); // no authorities => not authenticated
        // Sanity guard: this constructor leaves isAuthenticated() == false.
        assertThat(unauth.isAuthenticated()).isFalse();

        assertThat(evaluator.hasPermission(unauth, new Object(), "PROJECT.UPDATE")).isFalse();
        verifyNoInteractions(currentUserService);
    }

    @Test
    void nullPermission_returnsFalse() {
        assertThat(evaluator.hasPermission(nonAdminAuth, new Object(), null)).isFalse();
        verifyNoInteractions(currentUserService);
    }

    @Test
    void adminAuthority_returnsTrueRegardlessOfHasPermission() {
        boolean result = evaluator.hasPermission(adminAuth, new Object(), "PROJECT.DELETE");

        assertThat(result).isTrue();
        // ADMIN must short-circuit before consulting the permission set.
        verifyNoInteractions(currentUserService);
    }

    @Test
    void userWithPermission_returnsTrue() {
        when(currentUserService.hasPermission("DPR.CREATE")).thenReturn(true);

        boolean result = evaluator.hasPermission(nonAdminAuth, new Object(), "DPR.CREATE");

        assertThat(result).isTrue();
        verify(currentUserService).hasPermission("DPR.CREATE");
    }

    @Test
    void userWithoutPermission_returnsFalse() {
        when(currentUserService.hasPermission("DPR.CREATE")).thenReturn(false);

        boolean result = evaluator.hasPermission(nonAdminAuth, new Object(), "DPR.CREATE");

        assertThat(result).isFalse();
        verify(currentUserService).hasPermission("DPR.CREATE");
    }

    @Test
    void threeArgFormDelegatesToSameLogic() {
        when(currentUserService.hasPermission("PROJECT.UPDATE")).thenReturn(true);

        boolean granted = evaluator.hasPermission(nonAdminAuth, 42L, "Project", "PROJECT.UPDATE");
        boolean adminGranted = evaluator.hasPermission(adminAuth, 99L, "Project", "PROJECT.DELETE");
        boolean nullAuthDenied = evaluator.hasPermission(null, 1L, "Project", "PROJECT.UPDATE");

        assertThat(granted).isTrue();
        assertThat(adminGranted).isTrue();
        assertThat(nullAuthDenied).isFalse();
        // Exactly one delegation: the non-admin call. Admin short-circuits, null-auth guards.
        verify(currentUserService).hasPermission("PROJECT.UPDATE");
        verify(currentUserService, never()).hasPermission("PROJECT.DELETE");
    }
}
