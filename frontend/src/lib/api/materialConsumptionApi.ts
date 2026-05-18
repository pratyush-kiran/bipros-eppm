import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface MaterialConsumptionLogResponse {
  id: string;
  projectId: string;
  logDate: string;
  resourceId: string | null;
  materialName: string;
  unit: string;
  openingStock: number;
  received: number;
  consumed: number;
  closingStock: number;
  wastagePercent: number | null;
  /** Legacy free-text — preserved so old rows still render. New entries should use `issuedByUserId`. */
  issuedBy: string | null;
  /** Legacy free-text — preserved so old rows still render. New entries should use `receivedByUserId`. */
  receivedBy: string | null;
  /** Phase A2: User FK for the issuer (storekeeper / supervisor). */
  issuedByUserId: string | null;
  /** Phase A2: User FK for the receiver. */
  receivedByUserId: string | null;
  /** Phase A2: role of the user who entered the log (SUPERVISOR / STOREKEEPER / etc.). */
  enteredByRole: string | null;
  /** Optional display field — backend may project the resolved issuer name. */
  issuedByUsername?: string | null;
  issuedByName?: string | null;
  /** Optional display field — backend may project the resolved receiver name. */
  receivedByUsername?: string | null;
  receivedByName?: string | null;
  wbsNodeId: string | null;
  activityId: string | null;
  unitRate: number | null;
  lineCost: number | null;
  materialRateMasterId: string | null;
  remarks: string | null;
}

export interface CreateMaterialConsumptionLogRequest {
  logDate: string;
  resourceId?: string | null;
  materialName: string;
  unit: string;
  openingStock: number;
  received: number;
  consumed: number;
  wastagePercent?: number | null;
  /** Legacy free-text — still accepted by the backend for migration compatibility. */
  issuedBy?: string | null;
  receivedBy?: string | null;
  /** Phase A2: preferred User FK for the issuer. */
  issuedByUserId?: string | null;
  /** Phase A2: preferred User FK for the receiver. */
  receivedByUserId?: string | null;
  /** Phase A2: role to record on the log (defaults to the caller's role if omitted server-side). */
  enteredByRole?: string | null;
  wbsNodeId?: string | null;
  activityId?: string | null;
  remarks?: string | null;
}

export interface MaterialConsumptionFilters {
  from?: string;
  to?: string;
  /** Phase A2 server-side filter — entered-by role (SUPERVISOR / STOREKEEPER / etc.). */
  enteredByRole?: string;
  /** Phase A2 server-side filter — issuer User id. */
  issuedByUserId?: string;
}

export const materialConsumptionApi = {
  list: (projectId: string, filters: MaterialConsumptionFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.enteredByRole) params.set("enteredByRole", filters.enteredByRole);
    if (filters.issuedByUserId) params.set("issuedByUserId", filters.issuedByUserId);
    const qs = params.toString() ? `?${params.toString()}` : "";
    return apiClient
      .get<ApiResponse<MaterialConsumptionLogResponse[]>>(`/v1/projects/${projectId}/material-consumption${qs}`)
      .then((r) => r.data);
  },

  create: (projectId: string, request: CreateMaterialConsumptionLogRequest) =>
    apiClient
      .post<ApiResponse<MaterialConsumptionLogResponse>>(
        `/v1/projects/${projectId}/material-consumption`,
        request,
      )
      .then((r) => r.data),

  delete: (projectId: string, id: string) =>
    apiClient.delete(`/v1/projects/${projectId}/material-consumption/${id}`),
};
