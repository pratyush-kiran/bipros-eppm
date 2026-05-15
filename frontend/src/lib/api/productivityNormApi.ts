import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type ProductivityNormType = "MANPOWER" | "EQUIPMENT";

export type ProductivityNormSource =
  | "VARIANT"
  | "ROLE"
  | "UNSCOPED"
  | "SPECIFIC_RESOURCE"
  | "RESOURCE_TYPE"
  | "RESOURCE_LEGACY"
  | "NONE";

export interface ProductivityNormResponse {
  id: string;
  normType: ProductivityNormType;

  workActivityId: string | null;
  workActivityName: string | null;
  workActivityCode: string | null;

  resourceTypeId: string | null;
  resourceTypeName: string | null;

  resourceId: string | null;
  resourceCode: string | null;
  resourceName: string | null;

  /** @deprecated use {@link workActivityId} / {@link workActivityName}. */
  activityName: string | null;

  unit: string;
  outputPerManPerDay: number | null;
  outputPerHour: number | null;
  crewSize: number | null;
  outputPerDay: number | null;
  workingHoursPerDay: number | null;
  fuelLitresPerHour: number | null;
  equipmentSpec: string | null;
  remarks: string | null;

  /**
   * Role-keyed scope fields (new model). Null on legacy Type/Resource/Unscoped rows.
   * Resolution chain: (role + variant) → role → unscoped.
   */
  roleId: string | null;
  categoryId: string | null;
  gradeId: string | null;
  make: string | null;
  model: string | null;
}

export interface CreateProductivityNormRequest {
  normType: ProductivityNormType;
  workActivityId?: string | null;
  resourceTypeId?: string | null;
  resourceId?: string | null;
  /** @deprecated use {@link workActivityId}. Server will resolve by name when id is omitted. */
  activityName?: string | null;
  unit: string;
  outputPerManPerDay?: number | null;
  outputPerHour?: number | null;
  crewSize?: number | null;
  outputPerDay?: number | null;
  workingHoursPerDay?: number | null;
  fuelLitresPerHour?: number | null;
  equipmentSpec?: string | null;
  remarks?: string | null;

  /** Role-keyed scope (new). Pick exactly one scope (legacy Type/Resource, role-keyed, or unscoped). */
  roleId?: string | null;
  categoryId?: string | null;
  gradeId?: string | null;
  make?: string | null;
  model?: string | null;
}

export interface ResolvedNormResponse {
  workActivityId: string | null;
  resourceId: string | null;
  outputPerDay: number | null;
  unit: string | null;
  source: ProductivityNormSource;
  productivityNormId: string | null;
}

export const productivityNormApi = {
  list: (normType?: ProductivityNormType, workActivityId?: string) => {
    const qs: string[] = [];
    if (normType) qs.push(`normType=${normType}`);
    if (workActivityId) qs.push(`workActivityId=${workActivityId}`);
    const suffix = qs.length ? `?${qs.join("&")}` : "";
    return apiClient
      .get<ApiResponse<ProductivityNormResponse[]>>(`/v1/productivity-norms${suffix}`)
      .then((r) => r.data);
  },

  get: (id: string) =>
    apiClient
      .get<ApiResponse<ProductivityNormResponse>>(`/v1/productivity-norms/${id}`)
      .then((r) => r.data),

  create: (request: CreateProductivityNormRequest) =>
    apiClient
      .post<ApiResponse<ProductivityNormResponse>>(`/v1/productivity-norms`, request)
      .then((r) => r.data),

  createBulk: (requests: CreateProductivityNormRequest[]) =>
    apiClient
      .post<ApiResponse<ProductivityNormResponse[]>>(`/v1/productivity-norms/bulk`, requests)
      .then((r) => r.data),

  update: (id: string, request: CreateProductivityNormRequest) =>
    apiClient
      .put<ApiResponse<ProductivityNormResponse>>(`/v1/productivity-norms/${id}`, request)
      .then((r) => r.data),

  delete: (id: string) => apiClient.delete(`/v1/productivity-norms/${id}`),

  lookup: (workActivityId: string, resourceId?: string) => {
    const qs: string[] = [`workActivityId=${workActivityId}`];
    if (resourceId) qs.push(`resourceId=${resourceId}`);
    return apiClient
      .get<ApiResponse<ResolvedNormResponse>>(`/v1/productivity-norms/lookup?${qs.join("&")}`)
      .then((r) => r.data);
  },
};
