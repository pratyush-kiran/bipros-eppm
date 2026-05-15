import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface WorkActivityResponse {
  id: string;
  code: string;
  name: string;
  defaultUnit: string | null;
  discipline: string | null;
  description: string | null;
  sortOrder: number | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  createdBy: string | null;
  updatedBy: string | null;
}

export interface ProductivityCoverageNorm {
  scope: "VARIANT" | "ROLE" | "UNSCOPED";
  label: string;
  outputPerDay: number | null;
  outputPerManPerDay: number | null;
  workingHoursPerDay: number | null;
}

export interface ProductivityCoverageSide {
  configured: boolean;
  norms: ProductivityCoverageNorm[];
}

export type ProductivityCoverageSummary =
  | "MANPOWER_ONLY"
  | "EQUIPMENT_ONLY"
  | "BOTH"
  | "NONE";

export interface ProductivityCoverageResponse {
  workActivityId: string;
  workActivityName: string;
  defaultUnit: string | null;
  manpower: ProductivityCoverageSide;
  equipment: ProductivityCoverageSide;
  summary: ProductivityCoverageSummary;
}

export interface CreateWorkActivityRequest {
  code?: string;
  name: string;
  defaultUnit?: string | null;
  discipline?: string | null;
  description?: string | null;
  sortOrder?: number | null;
  active?: boolean;
}

export const workActivityApi = {
  list: (active?: boolean) => {
    const qs = active === undefined ? "" : `?active=${active}`;
    return apiClient
      .get<ApiResponse<WorkActivityResponse[]>>(`/v1/work-activities${qs}`)
      .then((r) => r.data);
  },

  get: (id: string) =>
    apiClient.get<ApiResponse<WorkActivityResponse>>(`/v1/work-activities/${id}`).then((r) => r.data),

  productivityCoverage: (id: string) =>
    apiClient
      .get<ApiResponse<ProductivityCoverageResponse>>(
        `/v1/work-activities/${id}/productivity-coverage`,
      )
      .then((r) => r.data),

  create: (request: CreateWorkActivityRequest) =>
    apiClient
      .post<ApiResponse<WorkActivityResponse>>("/v1/work-activities", request)
      .then((r) => r.data),

  update: (id: string, request: CreateWorkActivityRequest) =>
    apiClient
      .put<ApiResponse<WorkActivityResponse>>(`/v1/work-activities/${id}`, request)
      .then((r) => r.data),

  delete: (id: string) => apiClient.delete(`/v1/work-activities/${id}`),

  deleteAll: () => apiClient.delete("/v1/work-activities"),
};
