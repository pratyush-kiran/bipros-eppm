package com.bipros.security.application.service;

import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserPermissionAdapter} — pure Mockito, no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
class UserPermissionAdapterTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private UserPermissionAdapter adapter;

    // ── Case A: known user ────────────────────────────────────────────────────

    @Test
    void knownUser_hasMatchingPermission_returnsTrue() {
        UUID userId = UUID.randomUUID();
        User user = new User("alice", "alice@example.com", "{noop}hashed");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(currentUserService.permissionsFor(user)).thenReturn(Set.of("DPR.APPROVE"));

        assertThat(adapter.hasPermission(userId, "DPR.APPROVE")).isTrue();
    }

    @Test
    void knownUser_doesNotHavePermission_returnsFalse() {
        UUID userId = UUID.randomUUID();
        User user = new User("alice", "alice@example.com", "{noop}hashed");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(currentUserService.permissionsFor(user)).thenReturn(Set.of("DPR.APPROVE"));

        assertThat(adapter.hasPermission(userId, "DPR.DELETE")).isFalse();
    }

    // ── Case B: unknown user ──────────────────────────────────────────────────

    @Test
    void unknownUser_permissionsForIsEmpty() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(adapter.permissionsFor(userId)).isEmpty();
    }

    @Test
    void unknownUser_hasPermissionReturnsFalse() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(adapter.hasPermission(userId, "DPR.APPROVE")).isFalse();
    }

    // ── Case C: null-safety ───────────────────────────────────────────────────

    @Test
    void nullUserId_hasPermissionReturnsFalse() {
        assertThat(adapter.hasPermission(null, "X")).isFalse();
        verifyNoInteractions(userRepository, currentUserService);
    }

    @Test
    void nullPermissionCode_hasPermissionReturnsFalse() {
        UUID userId = UUID.randomUUID();
        // The default short-circuits on null code before calling permissionsFor
        assertThat(adapter.hasPermission(userId, null)).isFalse();
        verifyNoInteractions(userRepository, currentUserService);
    }

    @Test
    void nullUserId_permissionsForReturnsEmpty() {
        assertThat(adapter.permissionsFor(null)).isEmpty();
        verifyNoInteractions(userRepository, currentUserService);
    }
}
