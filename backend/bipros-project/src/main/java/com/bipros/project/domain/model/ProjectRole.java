package com.bipros.project.domain.model;

/**
 * Roles a user can hold on the project-scoped reporting line (see {@link ProjectTeamMember}).
 * Deliberately narrower than the global RBAC role list — only the roles that participate in
 * the Daily Balance Sheet rollup (Supervisor → Engineer → Site Manager → PM) plus QS/Safety.
 */
public enum ProjectRole {
    PM,
    SITE_MANAGER,
    ENGINEER,
    SUPERVISOR,
    QS,
    SAFETY
}
