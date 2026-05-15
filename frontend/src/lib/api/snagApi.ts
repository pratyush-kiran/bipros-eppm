import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type SnagSeverity = "LOW" | "MEDIUM" | "HIGH";
export type SnagStatus = "OPEN" | "IN_PROGRESS" | "CLOSED";

export interface SnagResponse {
  id: string;
  projectId: string;
  activityId: string | null;
  locationCode: string | null;
  description: string;
  severity: SnagSeverity;
  status: SnagStatus;
  raisedBy: string;
  raisedAt: string;
  closedBy: string | null;
  closedAt: string | null;
  closureNote: string | null;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreateSnagRequest {
  activityId?: string;
  locationCode?: string;
  description: string;
  severity?: SnagSeverity;
}

export interface UpdateSnagRequest {
  activityId?: string;
  locationCode?: string;
  description?: string;
  severity?: SnagSeverity;
  status?: SnagStatus;
}

export interface CloseSnagRequest {
  closureNote?: string;
}

export const snagApi = {
  list: (projectId: string, status?: SnagStatus) =>
    apiClient
      .get<ApiResponse<SnagResponse[]>>(
        `/v1/projects/${projectId}/snags`,
        { params: status ? { status } : {} }
      )
      .then((r) => r.data),

  create: (projectId: string, data: CreateSnagRequest) =>
    apiClient
      .post<ApiResponse<SnagResponse>>(
        `/v1/projects/${projectId}/snags`,
        data
      )
      .then((r) => r.data),

  update: (projectId: string, id: string, data: UpdateSnagRequest) =>
    apiClient
      .put<ApiResponse<SnagResponse>>(
        `/v1/projects/${projectId}/snags/${id}`,
        data
      )
      .then((r) => r.data),

  close: (projectId: string, id: string, data?: CloseSnagRequest) =>
    apiClient
      .post<ApiResponse<SnagResponse>>(
        `/v1/projects/${projectId}/snags/${id}/close`,
        data ?? {}
      )
      .then((r) => r.data),
};
