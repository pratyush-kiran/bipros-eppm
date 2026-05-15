import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/**
 * Project-scoped role assignment — mirrors the backend `ProjectMemberRole` enum.
 * A user can hold multiple roles on the same project (e.g. PROJECT_MANAGER + SCHEDULER).
 */
export type ProjectMemberRole =
  | "PROJECT_MANAGER"
  | "SCHEDULER"
  | "RESOURCE_MANAGER"
  | "TEAM_MEMBER"
  | "CLIENT";

export const PROJECT_MEMBER_ROLES: ProjectMemberRole[] = [
  "PROJECT_MANAGER",
  "SCHEDULER",
  "RESOURCE_MANAGER",
  "TEAM_MEMBER",
  "CLIENT",
];

/**
 * Wire-shape returned by `GET /v1/projects/{projectId}/members`. The backend
 * `ProjectMemberController.MemberDto` exposes the project role as `role`
 * (not `projectRole`) — keep this field name in sync if the controller
 * renames it.
 */
export interface ProjectMemberDto {
  id: string;
  userId: string;
  projectId: string;
  role: ProjectMemberRole;
  grantedBy: string | null;
  // User display fields — populated by the backend in the list response so the
  // page renders names without needing ADMIN_USER.READ to call /v1/users.
  username?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
  grantedByUsername?: string | null;
  grantedByName?: string | null;
  /**
   * Optional — the backend DTO doesn't include audit timestamps today, but
   * `BaseEntity` does carry `createdAt`. If the controller starts projecting
   * it, the table will pick it up automatically.
   */
  createdAt?: string | null;
}

export interface AssignProjectMemberRequest {
  userId: string;
  role: ProjectMemberRole;
}

/**
 * Project member management. Backend: `ProjectMemberController`.
 *
 * Permissions enforced server-side:
 * - `PROJECT_MEMBER.READ`  — list
 * - `PROJECT_MEMBER.MANAGE` — assign / revoke
 *
 * Note: the backend exposes only POST (assign) and DELETE (revoke). There is
 * no PUT endpoint — to change a user's role, revoke the old assignment and
 * assign the new one. `updateRole` below wraps that pattern so callers don't
 * have to know the underlying mechanic.
 */
export const projectMemberApi = {
  list: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectMemberDto[]>>(`/v1/projects/${projectId}/members`)
      .then((r) => r.data),

  assign: (projectId: string, body: AssignProjectMemberRequest) =>
    apiClient
      .post<ApiResponse<ProjectMemberDto>>(`/v1/projects/${projectId}/members`, body)
      .then((r) => r.data),

  revoke: (projectId: string, memberId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/members/${memberId}`),

  /**
   * Convenience: change a member's role by revoking the existing row and
   * assigning a new one. Not atomic — if the assign step fails the caller
   * should re-fetch the list to reconcile UI state.
   */
  updateRole: async (
    projectId: string,
    memberId: string,
    userId: string,
    nextRole: ProjectMemberRole
  ) => {
    await apiClient.delete(`/v1/projects/${projectId}/members/${memberId}`);
    return apiClient
      .post<ApiResponse<ProjectMemberDto>>(`/v1/projects/${projectId}/members`, {
        userId,
        role: nextRole,
      })
      .then((r) => r.data);
  },
};
