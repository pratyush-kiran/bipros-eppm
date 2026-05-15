package com.bipros.security.application.service;

import com.bipros.security.application.dto.RoleResponse;
import com.bipros.security.domain.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only catalog operations on global Roles. Today this exposes the role
 * list with enabled-user member counts to back {@code GET /v1/roles}; write
 * operations (create / delete role) are not in scope — the canonical role set
 * is seeded by {@code RolePermissionMatrix.DEFAULTS} at boot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;

    /**
     * Lists every Role with the number of <em>enabled</em> users currently
     * assigned to it. Ordered by role name (alphabetical) to match the
     * frontend's stable display order.
     */
    @Transactional(readOnly = true)
    public List<RoleResponse> listAllWithMemberCounts() {
        List<Object[]> rows = roleRepository.findAllWithMemberCount();
        return rows.stream()
                .map(r -> new RoleResponse(
                        (UUID) r[0],
                        (String) r[1],
                        (String) r[2],
                        ((Number) r[3]).longValue()))
                .toList();
    }
}
