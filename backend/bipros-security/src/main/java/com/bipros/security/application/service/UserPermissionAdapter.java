package com.bipros.security.application.service;

import com.bipros.common.security.UserPermissionPort;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Adapter that wires {@link UserPermissionPort} (declared in bipros-common) to the
 * security module's {@link CurrentUserService#permissionsFor(com.bipros.security.domain.model.User)}.
 *
 * <p>Allows modules such as bipros-project to resolve an arbitrary user's effective permission
 * codes without depending on bipros-security (which already depends on bipros-project,
 * so the reverse would create a cycle).
 */
@Service
@RequiredArgsConstructor
public class UserPermissionAdapter implements UserPermissionPort {

    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    @Override
    public Set<String> permissionsFor(UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        return userRepository.findById(userId)
                .map(currentUserService::permissionsFor)
                .orElse(Set.of());
    }
}
