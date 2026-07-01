import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type NcrCategory = "QUALITY" | "SAFETY" | "MATERIAL" | "WORKMANSHIP" | "OTHER";
export type NcrSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type NcrStatus = "OPEN" | "IN_REVIEW" | "CLOSED" | "REJECTED";
export type NcrSourceType = "MANUAL" | "QC_TEST_FAIL" | "INSPECTION" | "SNAG" | "OTHER";

export interface NcrResponse {
  id: string;
  projectId: string;
  ncrNo: string;
  title: string;
  description: string | null;
  category: NcrCategory;
  severity: NcrSeverity;
  status: NcrStatus;
  raisedBy: string | null;
  raisedAt: string | null;
  assignedTo: string | null;
  rootCause: string | null;
  correctiveAction: string | null;
  closedBy: string | null;
  closedAt: string | null;
  sourceType: NcrSourceType;
  sourceRefId: string | null;
  activityId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateNcrRequest {
  title: string;
  description?: string | null;
  category?: NcrCategory | null;
  severity?: NcrSeverity | null;
  assignedTo?: string | null;
  sourceType?: NcrSourceType | null;
  sourceRefId?: string | null;
  activityId?: string | null;
}

export interface UpdateNcrRequest {
  title?: string | null;
  description?: string | null;
  category?: NcrCategory | null;
  severity?: NcrSeverity | null;
  assignedTo?: string | null;
  rootCause?: string | null;
  correctiveAction?: string | null;
}

export interface CloseNcrRequest {
  rootCause: string;
  correctiveAction: string;
}

export const ncrApi = {
  list: (projectId: string, status?: NcrStatus) => {
    const qs = status ? `?status=${status}` : "";
    return apiClient
      .get<ApiResponse<NcrResponse[]>>(`/v1/projects/${projectId}/ncrs${qs}`)
      .then((r) => r.data);
  },

  get: (projectId: string, id: string) =>
    apiClient
      .get<ApiResponse<NcrResponse>>(`/v1/projects/${projectId}/ncrs/${id}`)
      .then((r) => r.data),

  create: (projectId: string, request: CreateNcrRequest) =>
    apiClient
      .post<ApiResponse<NcrResponse>>(`/v1/projects/${projectId}/ncrs`, request)
      .then((r) => r.data),

  update: (projectId: string, id: string, request: UpdateNcrRequest) =>
    apiClient
      .put<ApiResponse<NcrResponse>>(`/v1/projects/${projectId}/ncrs/${id}`, request)
      .then((r) => r.data),

  approveClosure: (projectId: string, id: string, request: CloseNcrRequest) =>
    apiClient
      .post<ApiResponse<NcrResponse>>(
        `/v1/projects/${projectId}/ncrs/${id}/approve-closure`,
        request
      )
      .then((r) => r.data),

  reject: (projectId: string, id: string, note?: string) =>
    apiClient
      .post<ApiResponse<NcrResponse>>(`/v1/projects/${projectId}/ncrs/${id}/reject`, {
        note: note ?? null,
      })
      .then((r) => r.data),
};
