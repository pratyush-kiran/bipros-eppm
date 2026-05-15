import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type IndentStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "APPROVED"
  | "REJECTED"
  | "PARTIALLY_FULFILLED"
  | "FULFILLED";

export interface MaterialIndentItemDto {
  id?: string | null;
  materialName: string;
  quantity: number | string;
  uom: string;
  remarks?: string | null;
}

export interface MaterialIndentResponse {
  id: string;
  projectId: string;
  indentNo: string;
  requestedBy: string | null;
  requestedAt: string | null;
  requiredBy: string;
  status: IndentStatus;
  notes: string | null;
  decisionBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  itemsCount: number;
  items: MaterialIndentItemDto[];
  createdAt: string;
  updatedAt: string;
}

export interface CreateMaterialIndentRequest {
  requiredBy: string;
  notes?: string | null;
  items: MaterialIndentItemDto[];
}

export interface UpdateMaterialIndentRequest {
  requiredBy?: string | null;
  notes?: string | null;
  items?: MaterialIndentItemDto[] | null;
}

export interface IndentDecisionRequest {
  decisionNote?: string | null;
}

export const materialIndentApi = {
  list: (projectId: string, status?: IndentStatus) => {
    const qs = status ? `?status=${status}` : "";
    return apiClient
      .get<ApiResponse<MaterialIndentResponse[]>>(`/v1/projects/${projectId}/material-indents${qs}`)
      .then((r) => r.data);
  },

  get: (projectId: string, id: string) =>
    apiClient
      .get<ApiResponse<MaterialIndentResponse>>(`/v1/projects/${projectId}/material-indents/${id}`)
      .then((r) => r.data),

  create: (projectId: string, request: CreateMaterialIndentRequest) =>
    apiClient
      .post<ApiResponse<MaterialIndentResponse>>(
        `/v1/projects/${projectId}/material-indents`,
        request
      )
      .then((r) => r.data),

  update: (projectId: string, id: string, request: UpdateMaterialIndentRequest) =>
    apiClient
      .put<ApiResponse<MaterialIndentResponse>>(
        `/v1/projects/${projectId}/material-indents/${id}`,
        request
      )
      .then((r) => r.data),

  submit: (projectId: string, id: string) =>
    apiClient
      .post<ApiResponse<MaterialIndentResponse>>(
        `/v1/projects/${projectId}/material-indents/${id}/submit`
      )
      .then((r) => r.data),

  approve: (projectId: string, id: string, request: IndentDecisionRequest = {}) =>
    apiClient
      .post<ApiResponse<MaterialIndentResponse>>(
        `/v1/projects/${projectId}/material-indents/${id}/approve`,
        request
      )
      .then((r) => r.data),

  reject: (projectId: string, id: string, request: IndentDecisionRequest = {}) =>
    apiClient
      .post<ApiResponse<MaterialIndentResponse>>(
        `/v1/projects/${projectId}/material-indents/${id}/reject`,
        request
      )
      .then((r) => r.data),
};
