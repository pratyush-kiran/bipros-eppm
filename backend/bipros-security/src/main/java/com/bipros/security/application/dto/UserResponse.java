package com.bipros.security.application.dto;

import com.bipros.security.domain.model.AuthMethod;
import com.bipros.security.domain.model.Department;
import com.bipros.security.domain.model.PresenceStatus;
import com.bipros.security.domain.model.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * User + Personnel response. Per PMS MasterData Screen 07 the same entity backs the Personnel
 * Master, so we surface mobile / department / employee code / joining dates / presence status
 * alongside the core auth fields.
 */
public record UserResponse(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        List<String> roles,
        UUID profileId,
        String profileName,
        UUID organisationId,
        String designation,
        String primaryIcpmsRole,
        Set<AuthMethod> authMethods,
        // ── Personnel Master (Screen 07) ──
        String employeeCode,
        String mobile,
        Department department,
        LocalDate joiningDate,
        LocalDate contractEndDate,
        PresenceStatus presenceStatus,
        List<UUID> assignedStretchIds,
        /** Effective fine-grained permission codes (role-matrix ∪ profile); sorted ascending. */
        List<String> permissions
) {
    public static UserResponse from(User user, List<String> roles) {
        return from(user, roles, null, null, List.of(), List.of());
    }

    public static UserResponse from(User user, List<String> roles, List<UUID> stretchIds) {
        return from(user, roles, null, null, stretchIds, List.of());
    }

    public static UserResponse from(User user, List<String> roles, UUID profileId, String profileName,
                                    List<UUID> stretchIds) {
        return from(user, roles, profileId, profileName, stretchIds, List.of());
    }

    public static UserResponse from(User user, List<String> roles, UUID profileId, String profileName,
                                    List<UUID> stretchIds, List<String> permissions) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.isEnabled(),
                roles,
                profileId,
                profileName,
                user.getOrganisationId(),
                user.getDesignation(),
                user.getPrimaryIcpmsRole(),
                user.getAuthMethods(),
                user.getEmployeeCode(),
                user.getMobile(),
                user.getDepartment(),
                user.getJoiningDate(),
                user.getContractEndDate(),
                user.getPresenceStatus(),
                stretchIds != null ? stretchIds : List.of(),
                permissions != null ? permissions : List.of()
        );
    }
}
