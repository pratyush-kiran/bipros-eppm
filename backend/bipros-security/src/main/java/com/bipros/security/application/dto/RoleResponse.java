package com.bipros.security.application.dto;

import java.util.UUID;

/**
 * Read-only projection of a global Role with its enabled-user member count.
 *
 * <p>Backs {@code GET /v1/roles} so the frontend can drop the hardcoded
 * {@code CANONICAL_ROLES} constant in {@code roleApi.ts}. {@code memberCount}
 * counts only users where {@code users.enabled = true} — locked / disabled
 * users are excluded.
 */
public record RoleResponse(
        UUID id,
        String name,
        String description,
        long memberCount
) {
}
