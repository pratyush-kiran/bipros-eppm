import { apiClient } from "./client";
import type {
  ApiResponse,
  Department,
  IcpmsModule,
  ModuleAccessLevel,
  PagedResponse,
  PresenceStatus,
  UserResponse,
} from "../types";

export interface UpdateUserRolesRequest {
  roles: string[];
}

export interface CreateUserRequest {
  username: string;
  email: string;
  password: string;
  firstName?: string;
  lastName?: string;
  profileId?: string | null;
  enabled?: boolean;
}

export interface UpdateUserProfileRequest {
  firstName?: string | null;
  lastName?: string | null;
  email?: string | null;
  mobile?: string | null;
  designation?: string | null;
  department?: Department | null;
  organisationId?: string | null;
  joiningDate?: string | null;
  contractEndDate?: string | null;
  presenceStatus?: PresenceStatus | null;
  enabled?: boolean | null;
}

export interface UserAccessApiResponse {
  userId: string;
  moduleAccess: Partial<Record<IcpmsModule, ModuleAccessLevel>>;
  corridorScopes: string[];
  allCorridors: boolean;
}

/**
 * Light-weight projection of {@link UserResponse} for supervisor/role pickers. Carries only what
 * the picker UI actually renders (name + identifier columns) so consumers don't have to know
 * about IC-PMS personnel fields. Phase 4.4 RBAC.
 */
export interface UserSummary {
  id: string;
  username: string;
  /** Display name — "{firstName} {lastName}" when present, otherwise the username. */
  name: string;
  email: string;
  /** Personnel master code (Screen 07). Optional; absent for legacy users. */
  employeeCode?: string | null;
}

export const userApi = {
  listUsers: (page = 0, size = 50, roles?: string[]) =>
    apiClient
      .get<ApiResponse<PagedResponse<UserResponse>>>("/v1/users", {
        params: {
          page,
          size,
          ...(roles && roles.length > 0 ? { roles: roles.join(",") } : {}),
        },
      })
      .then((r) => r.data),

  getUser: (id: string) =>
    apiClient.get<ApiResponse<UserResponse>>(`/v1/users/${id}`).then((r) => r.data),

  getAccess: (id: string) =>
    apiClient
      .get<ApiResponse<UserAccessApiResponse>>(`/v1/users/${id}/access`)
      .then((r) => r.data),

  updateUserRoles: (userId: string, data: UpdateUserRolesRequest) =>
    apiClient
      .put<ApiResponse<UserResponse>>(`/v1/users/${userId}/roles`, data)
      .then((r) => r.data),

  /**
   * Phase 5.1 — alias for {@link updateUserRoles} that accepts a bare role-name
   * list. Used by the admin Edit drawer + bulk-assign modal so the call site
   * doesn't have to wrap the array in `{ roles: ... }`.
   */
  assignRoles: (userId: string, roleNames: string[]) =>
    apiClient
      .put<ApiResponse<UserResponse>>(`/v1/users/${userId}/roles`, { roles: roleNames })
      .then((r) => r.data),

  /**
   * Phase 5.1 — convenience alias for {@link updateProfile} (the IC-PMS
   * Personnel Master fields). The backend has no `PUT /v1/users/{id}` separate
   * from this; the admin Edit drawer calls it after the role/profile diff
   * mutations so a single Save runs as a small pipeline of one-shot calls.
   */
  update: (userId: string, body: UpdateUserProfileRequest) =>
    apiClient
      .put<ApiResponse<UserResponse>>(`/v1/users/${userId}`, body)
      .then((r) => r.data),

  /**
   * Phase 5.1 — no DELETE endpoint exists; deactivation is a soft toggle via
   * `PUT /v1/users/{id}/status` with `enabled = false`. The bulk-deactivate UI
   * calls this once per selected user inside `Promise.all`.
   */
  deactivate: (userId: string) =>
    apiClient
      .put<ApiResponse<UserResponse>>(`/v1/users/${userId}/status`, { enabled: false })
      .then((r) => r.data),

  toggleUserEnabled: (userId: string, enabled: boolean) =>
    apiClient
      .put<ApiResponse<UserResponse>>(`/v1/users/${userId}/status`, { enabled })
      .then((r) => r.data),

  updateProfile: (userId: string, body: UpdateUserProfileRequest) =>
    apiClient
      .put<ApiResponse<UserResponse>>(`/v1/users/${userId}`, body)
      .then((r) => r.data),

  createUser: (body: CreateUserRequest) =>
    apiClient.post<ApiResponse<UserResponse>>("/v1/users", body).then((r) => r.data),

  assignProfile: (userId: string, profileId: string | null) =>
    apiClient
      .put<ApiResponse<UserResponse>>(`/v1/users/${userId}/profile`, { profileId })
      .then((r) => r.data),

  /**
   * Fetch users whose `roles` contains ANY of the given role codes. Backed by the Phase 4.6
   * server-side filter on {@code GET /v1/users?roles=...}. The supervisor picker calls this
   * with {@code ['SUPERVISOR','FOREMAN','SITE_ENGINEER','SITE_MANAGER']} — the eligibility
   * gate now lives on the backend, the frontend just renders what comes back.
   *
   * Page size is capped at 200; if an org grows past that the picker would need
   * search-as-you-type (out of scope for Phase 4.4).
   */
  listByRoles: async (roles: string[]): Promise<UserSummary[]> => {
    const csv = roles.join(",");
    const { data } = await apiClient.get<ApiResponse<PagedResponse<UserResponse>>>(
      "/v1/users",
      { params: { roles: csv, size: 200 } },
    );
    return (data.data?.content ?? []).map((u) => ({
      id: u.id,
      username: u.username,
      name: [u.firstName, u.lastName].filter(Boolean).join(" ") || u.username,
      email: u.email,
      employeeCode: u.employeeCode ?? null,
    }));
  },
};
