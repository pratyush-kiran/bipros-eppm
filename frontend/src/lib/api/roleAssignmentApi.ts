import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/** Role-based activity demand response — replaces the legacy resource-based row in the UI. */
export interface RoleAssignmentResponse {
  id: string;
  activityId: string;
  activityName?: string | null;
  projectId: string;
  roleId?: string | null;
  roleName?: string | null;
  roleType?: string | null;
  variantId?: string | null;
  variantLabel?: string | null;
  headcount?: number | null;
  duration?: number | null;
  quantity?: number | null;
  plannedUnits?: number | null;
  actualUnits?: number | null;
  remainingUnits?: number | null;
  plannedCost?: number | null;
  actualCost?: number | null;
  remainingCost?: number | null;
  effectiveRate?: number | null;
  unit?: string | null;
  rateType?: string | null;
  plannedStartDate?: string | null;
  plannedFinishDate?: string | null;
  // True when this row was auto-created by a DPR for a (role, variant) the planner never
  // added — planned/budgeted are zero, only actual* / remaining* carry meaning.
  unplanned?: boolean;
}

export interface RoleAssignmentRequest {
  activityId: string;
  roleId: string;
  manpowerRoleRateId?: string;
  equipmentRoleVariantId?: string;
  materialRoleVariantId?: string;
  headcount?: number;
  duration?: number;
  quantity?: number;
  plannedStartDate?: string;
  plannedFinishDate?: string;
  rateType?: string;
}

export const roleAssignmentApi = {
  listForActivity: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<RoleAssignmentResponse[]>>(
        `/v1/projects/${projectId}/activities/${activityId}/role-assignments`,
      )
      .then((r) => r.data),
  create: (projectId: string, req: RoleAssignmentRequest) =>
    apiClient
      .post<ApiResponse<RoleAssignmentResponse>>(
        `/v1/projects/${projectId}/role-assignments`,
        req,
      )
      .then((r) => r.data),
  update: (id: string, req: RoleAssignmentRequest) =>
    apiClient
      .put<ApiResponse<RoleAssignmentResponse>>(`/v1/role-assignments/${id}`, req)
      .then((r) => r.data),
  delete: (id: string) => apiClient.delete(`/v1/role-assignments/${id}`),
};
