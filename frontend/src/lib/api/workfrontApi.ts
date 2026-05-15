import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type WorkfrontStatus = "PLANNED" | "READY" | "RELEASED" | "HANDED_OVER";

export interface WorkfrontResponse {
  id: string;
  projectId: string;
  wbsCode: string | null;
  locationCode: string | null;
  status: WorkfrontStatus;
  readyAt: string | null;
  releasedBy: string | null;
  releasedAt: string | null;
  blockers: string | null;
  notes: string | null;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreateWorkfrontRequest {
  wbsCode?: string;
  locationCode?: string;
  status?: WorkfrontStatus;
  blockers?: string;
  notes?: string;
}

export interface UpdateWorkfrontRequest {
  wbsCode?: string;
  locationCode?: string;
  status?: WorkfrontStatus;
  blockers?: string;
  notes?: string;
}

export const workfrontApi = {
  list: (projectId: string) =>
    apiClient
      .get<ApiResponse<WorkfrontResponse[]>>(
        `/v1/projects/${projectId}/workfronts`
      )
      .then((r) => r.data),

  detail: (projectId: string, id: string) =>
    apiClient
      .get<ApiResponse<WorkfrontResponse>>(
        `/v1/projects/${projectId}/workfronts/${id}`
      )
      .then((r) => r.data),

  create: (projectId: string, data: CreateWorkfrontRequest) =>
    apiClient
      .post<ApiResponse<WorkfrontResponse>>(
        `/v1/projects/${projectId}/workfronts`,
        data
      )
      .then((r) => r.data),

  update: (projectId: string, id: string, data: UpdateWorkfrontRequest) =>
    apiClient
      .put<ApiResponse<WorkfrontResponse>>(
        `/v1/projects/${projectId}/workfronts/${id}`,
        data
      )
      .then((r) => r.data),

  release: (projectId: string, id: string) =>
    apiClient
      .post<ApiResponse<WorkfrontResponse>>(
        `/v1/projects/${projectId}/workfronts/${id}/release`,
        {}
      )
      .then((r) => r.data),
};
