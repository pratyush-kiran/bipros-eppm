import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type BoqStatus = "PENDING" | "ACTIVE" | "COMPLETED" | "OVERRUN" | "ON_HOLD";

export interface BoqItemResponse {
  id: string;
  projectId: string;
  itemNo: string;
  description: string;
  unit: string;
  wbsNodeId: string | null;
  boqQty: number | null;
  boqRate: number | null;
  boqAmount: number | null;
  budgetedRate: number | null;
  budgetedAmount: number | null;
  qtyExecutedToDate: number | null;
  actualRate: number | null;
  actualAmount: number | null;
  percentComplete: number | null;
  costVariance: number | null;
  costVariancePercent: number | null;
  // PMS MasterData Screen 03
  chapter: string | null;
  status: BoqStatus | null;
  /** Workstream B2: TRUE when the user has pinned actualRate manually — auto-recalc skips. */
  manualOverride: boolean | null;
}

export interface BoqSummaryResponse {
  items: BoqItemResponse[];
  boqGrandTotal: number;
  budgetedGrandTotal: number;
  actualGrandTotal: number;
  grandCostVariance: number;
  grandCostVariancePercent: number | null;
  overallPercentComplete: number | null;
}

export interface CreateBoqItemRequest {
  itemNo: string;
  description: string;
  unit: string;
  wbsNodeId?: string | null;
  boqQty?: number;
  boqRate?: number;
  budgetedRate?: number;
  qtyExecutedToDate?: number;
  actualRate?: number;
  // PMS MasterData Screen 03
  chapter?: string | null;
  status?: BoqStatus | null;
}

export interface UpdateBoqItemRequest {
  description?: string | null;
  unit?: string | null;
  wbsNodeId?: string | null;
  boqQty?: number | null;
  boqRate?: number | null;
  budgetedRate?: number | null;
  qtyExecutedToDate?: number | null;
  actualRate?: number | null;
  // PMS MasterData Screen 03
  chapter?: string | null;
  status?: BoqStatus | null;
}

export const boqApi = {
  list: (projectId: string) =>
    apiClient
      .get<ApiResponse<BoqSummaryResponse>>(`/v1/projects/${projectId}/boq`)
      .then((r) => r.data),

  get: (projectId: string, itemId: string) =>
    apiClient
      .get<ApiResponse<BoqItemResponse>>(`/v1/projects/${projectId}/boq/${itemId}`)
      .then((r) => r.data),

  create: (projectId: string, request: CreateBoqItemRequest) =>
    apiClient
      .post<ApiResponse<BoqItemResponse>>(`/v1/projects/${projectId}/boq`, request)
      .then((r) => r.data),

  createBulk: (projectId: string, requests: CreateBoqItemRequest[]) =>
    apiClient
      .post<ApiResponse<BoqItemResponse[]>>(`/v1/projects/${projectId}/boq/bulk`, requests)
      .then((r) => r.data),

  update: (projectId: string, itemId: string, request: UpdateBoqItemRequest) =>
    apiClient
      .patch<ApiResponse<BoqItemResponse>>(`/v1/projects/${projectId}/boq/${itemId}`, request)
      .then((r) => r.data),

  delete: (projectId: string, itemId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/boq/${itemId}`),

  /**
   * Workstream B1: BOQ candidates for an activity, so the DPR form can suggest / pre-select
   * a BOQ link when an activity is picked. Returns an empty array when no matches exist or
   * when the project has no BOQ defined yet.
   */
  listForActivity: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<BoqItemResponse[]>>(
        `/v1/projects/${projectId}/boq/by-activity?activityId=${activityId}`
      )
      .then((r) => r.data),
};
