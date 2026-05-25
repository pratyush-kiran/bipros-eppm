package com.bipros.ai.context;

import java.util.List;
import java.util.UUID;

/**
 * Context carried through every AI request: user identity, project scope, module.
 * Used for RBAC injection and tool scoping.
 *
 * <p>{@code role} retains the legacy role string ("ADMIN", "PROJECT_MANAGER", "USER")
 * for backward-compat with tools that already read it. {@code profile} carries the
 * fine-grained Profile.code (e.g. "SITE_MANAGER") used for tool filtering and persona
 * selection.
 *
 * <p>{@code hdsVersionIds} carries the HDS document version UUIDs the user has
 * selected as the retrieval scope for this request (HDS = Highway Design Standard).
 * When non-empty, the orchestrator routes deterministically to the HDS search
 * tool — the LLM-driven tool-selection loop is bypassed. Empty/null means no
 * HDS scope; the orchestrator runs its normal ReAct loop over the project
 * tools.
 */
public record AiContext(
    UUID userId,
    UUID projectId,
    String module,
    String role,
    String profile,
    List<UUID> scopedProjectIds,
    List<UUID> hdsVersionIds
) {

    /**
     * Backward-compatible constructor that defaults {@code hdsVersionIds} to
     * an empty list. Keeps existing callers (tests, fixtures, older code paths)
     * compiling without change.
     */
    public AiContext(UUID userId, UUID projectId, String module, String role, String profile,
                     List<UUID> scopedProjectIds) {
        this(userId, projectId, module, role, profile, scopedProjectIds, List.of());
    }
}
