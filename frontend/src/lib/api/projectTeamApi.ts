import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/**
 * Phase A1 Project Team — the project-scoped reporting hierarchy used by DBS aggregation.
 *
 * Distinct from `projectMemberApi` (which is the older `ProjectMember` role-on-project
 * assignment used by access control). `ProjectTeam` is the **org chart** for a project:
 * one PM at the top, engineers / site managers reporting to the PM, supervisors reporting
 * to an engineer, plus QS / Safety roles attached as peers. The `reportsToUserId` column
 * carries the project-local reporting edge — it's intentionally scoped to the project so
 * the same user can be a Supervisor on project A and an Engineer on project B.
 *
 * Backend: `ProjectTeamController` under `bipros-project`, base path
 * `/v1/projects/{projectId}/team`. Every response is wrapped in `ApiResponse<T>`.
 */
export type ProjectRole =
  | "PM"
  | "CONSTRUCTION_MANAGER"
  | "SITE_MANAGER"
  | "ENGINEER"
  | "SUPERVISOR"
  | "QS"
  | "SAFETY";

export const PROJECT_TEAM_ROLES: ProjectRole[] = [
  "PM",
  "CONSTRUCTION_MANAGER",
  "SITE_MANAGER",
  "ENGINEER",
  "SUPERVISOR",
  "QS",
  "SAFETY",
];

export const PROJECT_TEAM_ROLE_LABELS: Record<ProjectRole, string> = {
  PM: "Project Manager",
  CONSTRUCTION_MANAGER: "Construction Manager",
  SITE_MANAGER: "Site Manager",
  ENGINEER: "Engineer",
  SUPERVISOR: "Supervisor",
  QS: "Quantity Surveyor",
  SAFETY: "Safety Officer",
};

export interface ProjectTeamMember {
  id: string;
  projectId: string;
  userId: string;
  role: ProjectRole;
  /** Project-local reporting edge — points to another team member's `userId`. */
  reportsToUserId: string | null;
  /** ISO date — null means "active from the beginning". */
  activeFrom: string | null;
  /** ISO date — null means "still active". */
  activeTo: string | null;
  createdAt: string | null;
  updatedAt: string | null;

  // Optional display fields the backend may project for convenience (mirrors
  // ProjectMemberDto's pattern). Treated as best-effort: the page falls back
  // to userApi.listByRoles() lookups when these are absent.
  username?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
  reportsToUsername?: string | null;
  reportsToName?: string | null;
}

export interface CreateProjectTeamMemberRequest {
  userId: string;
  role: ProjectRole;
  reportsToUserId?: string | null;
  activeFrom?: string | null;
  activeTo?: string | null;
}

export interface UpdateProjectTeamMemberRequest {
  role?: ProjectRole;
  reportsToUserId?: string | null;
  activeFrom?: string | null;
  activeTo?: string | null;
}

export const projectTeamApi = {
  list: (projectId: string, role?: ProjectRole) => {
    const qs = role ? `?role=${encodeURIComponent(role)}` : "";
    return apiClient
      .get<ApiResponse<ProjectTeamMember[]>>(`/v1/projects/${projectId}/team${qs}`)
      .then((r) => r.data);
  },

  create: (projectId: string, body: CreateProjectTeamMemberRequest) =>
    apiClient
      .post<ApiResponse<ProjectTeamMember>>(`/v1/projects/${projectId}/team`, body)
      .then((r) => r.data),

  update: (
    projectId: string,
    memberId: string,
    body: UpdateProjectTeamMemberRequest,
  ) =>
    apiClient
      .put<ApiResponse<ProjectTeamMember>>(
        `/v1/projects/${projectId}/team/${memberId}`,
        body,
      )
      .then((r) => r.data),

  delete: (projectId: string, memberId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/team/${memberId}`),

  /**
   * Resolve the Engineer that a Supervisor reports to on this project. Used by DBS
   * aggregation to walk Supervisor → Engineer → PM without re-querying the full team
   * list. Returns the engineer's team-member row.
   */
  resolveEngineer: (projectId: string, supervisorUserId: string) =>
    apiClient
      .get<ApiResponse<ProjectTeamMember | null>>(
        `/v1/projects/${projectId}/team/resolve/engineer`,
        { params: { supervisorUserId } },
      )
      .then((r) => r.data),

  /**
   * Resolve the PM for this project (single PM expected). Returns null if no PM is
   * yet assigned — the team admin page treats that as a warning rather than an error.
   */
  resolvePm: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectTeamMember | null>>(`/v1/projects/${projectId}/team/resolve/pm`)
      .then((r) => r.data),
};
