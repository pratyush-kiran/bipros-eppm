import { apiClient } from "./client";
import type { ApiResponse } from "../types";
import type {
  QcTestType,
  QcTestTypeRequest,
  QcSession,
  QcSessionRequest,
  QcDashboardResponse,
  QcOutcome,
} from "../types/qc";

export interface QcSessionFilters {
  activityId?: string;
  outcome?: QcOutcome;
  from?: string;
  to?: string;
}

export const qcApi = {
  // ─── Test Types ──────────────────────────────────────────────────────────────
  listTestTypes: (projectId: string) =>
    apiClient
      .get<ApiResponse<QcTestType[]>>(`/v1/projects/${projectId}/qc/test-types`)
      .then((r) => r.data),

  createTestType: (projectId: string, req: QcTestTypeRequest) =>
    apiClient
      .post<ApiResponse<QcTestType>>(`/v1/projects/${projectId}/qc/test-types`, req)
      .then((r) => r.data),

  updateTestType: (projectId: string, id: string, req: QcTestTypeRequest) =>
    apiClient
      .put<ApiResponse<QcTestType>>(`/v1/projects/${projectId}/qc/test-types/${id}`, req)
      .then((r) => r.data),

  deleteTestType: (projectId: string, id: string) =>
    apiClient.delete(`/v1/projects/${projectId}/qc/test-types/${id}`),

  // ─── Sessions ────────────────────────────────────────────────────────────────
  listSessions: (projectId: string, filters: QcSessionFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.activityId) params.set("activityId", filters.activityId);
    if (filters.outcome) params.set("outcome", filters.outcome);
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    const qs = params.toString();
    return apiClient
      .get<ApiResponse<QcSession[]>>(
        `/v1/projects/${projectId}/qc${qs ? `?${qs}` : ""}`
      )
      .then((r) => r.data);
  },

  getSession: (projectId: string, id: string) =>
    apiClient
      .get<ApiResponse<QcSession>>(`/v1/projects/${projectId}/qc/${id}`)
      .then((r) => r.data),

  createSession: (projectId: string, req: QcSessionRequest) =>
    apiClient
      .post<ApiResponse<QcSession>>(`/v1/projects/${projectId}/qc`, req)
      .then((r) => r.data),

  updateSession: (projectId: string, id: string, req: QcSessionRequest) =>
    apiClient
      .put<ApiResponse<QcSession>>(`/v1/projects/${projectId}/qc/${id}`, req)
      .then((r) => r.data),

  deleteSession: (projectId: string, id: string) =>
    apiClient.delete(`/v1/projects/${projectId}/qc/${id}`),

  // ─── Dashboard ───────────────────────────────────────────────────────────────
  getDashboard: (projectId: string, filters: QcSessionFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.activityId) params.set("activityId", filters.activityId);
    if (filters.outcome)    params.set("outcome", filters.outcome);
    if (filters.from)       params.set("from", filters.from);
    if (filters.to)         params.set("to", filters.to);
    const qs = params.toString();
    return apiClient
      .get<ApiResponse<QcDashboardResponse>>(
        `/v1/projects/${projectId}/qc/dashboard${qs ? `?${qs}` : ""}`
      )
      .then((r) => r.data);
  },
};
