package com.bipros.ai.context;

import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.security.SecurityContextHelper;
import com.bipros.security.domain.repository.ProfileRepository;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AiContextResolver {

    private final ProjectAccessGuard projectAccess;
    private final SecurityContextHelper securityContextHelper;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    public AiContext resolve(UUID projectId, String module) {
        return resolve(projectId, module, List.of());
    }

    /**
     * Resolves the AI context with an explicit HDS scope. {@code hdsVersionIds}
     * is the list of HDS document version UUIDs the user has selected for this
     * request (or for the underlying conversation when no per-request scope was
     * sent). Null is treated as empty — i.e. no HDS scope; the orchestrator
     * runs its normal tool loop.
     */
    public AiContext resolve(UUID projectId, String module, List<UUID> hdsVersionIds) {
        UUID userId;
        try {
            userId = securityContextHelper.getCurrentUserId();
        } catch (Exception e) {
            userId = null;
        }
        String role = securityContextHelper.hasRole("ADMIN") ? "ADMIN"
                : securityContextHelper.hasRole("PROJECT_MANAGER") ? "PROJECT_MANAGER" : "USER";

        String profileCode = resolveProfileCode(userId);

        List<UUID> scoped = projectAccess.getAccessibleProjectIdsForCurrentUser() != null
                ? List.copyOf(projectAccess.getAccessibleProjectIdsForCurrentUser())
                : List.of();

        // When projectId is null we INTENTIONALLY leave effectiveProjectId null —
        // even for non-admin users with a single accessible project. The caller
        // treats this as a portfolio / general session, and scopedProjectIds carries
        // the breadth. The previous auto-bind silently scoped general questions to
        // the one accessible project, which made portfolio queries impossible and
        // surprised users whose scope changed underneath them.
        UUID effectiveProjectId = projectId;

        List<UUID> hdsScope = hdsVersionIds == null ? List.of() : List.copyOf(hdsVersionIds);

        return new AiContext(userId, effectiveProjectId, module, role, profileCode, scoped, hdsScope);
    }

    private String resolveProfileCode(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> u.getProfileId())
                .flatMap(pid -> pid == null ? Optional.empty() : profileRepository.findById(pid))
                .map(p -> p.getCode())
                .orElse(null);
    }
}
