import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface ProjectBudgetResponse {
  originalBudget: number | null;
  currentBudget: number | null;
  pendingAdditions: number;
  pendingReductions: number;
  approvedAdditions: number;
  approvedReductions: number;
  pendingChangeCount: number;
  approvedChangeCount: number;
  budgetCurrency: string;
  budgetUpdatedAt: string | null;
}

export type BudgetChangeType = "ADDITION" | "REDUCTION" | "TRANSFER";
export type BudgetChangeStatus = "PENDING" | "APPROVED" | "REJECTED";

export interface BudgetChangeLogResponse {
  id: string;
  projectId: string;
  fromWbsNodeId: string | null;
  fromWbsNodeCode: string | null;
  toWbsNodeId: string | null;
  toWbsNodeCode: string | null;
  amount: number;
  changeType: BudgetChangeType;
  status: BudgetChangeStatus;
  reason: string;
  requestedBy: string;
  requestedByName: string | null;
  decidedBy: string | null;
  decidedByName: string | null;
  requestedAt: string;
  decidedAt: string | null;
}

export interface CreateBudgetChangeRequest {
  fromWbsNodeId?: string | null;
  toWbsNodeId?: string | null;
  amount: number;
  changeType: BudgetChangeType;
  reason: string;
}

export interface WbsBudgetNode {
  wbsNodeId: string;
  code: string;
  name: string;
  wbsLevel: number | null;
  budgetCrores: number;
  childrenBudgetCrores: number;
  unallocatedCrores: number;
  warning: boolean;
}

export interface WbsBudgetSummary {
  totalBudgetCrores: number;
  nodes: WbsBudgetNode[];
}

export const budgetApi = {
  setInitialBudget: (projectId: string, amount: number) =>
    apiClient
      .post<ApiResponse<ProjectBudgetResponse>>(`/v1/projects/${projectId}/budget`, { amount })
      .then((r) => r.data),

  getBudgetSummary: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectBudgetResponse>>(`/v1/projects/${projectId}/budget`)
      .then((r) => r.data),

  requestChange: (projectId: string, data: CreateBudgetChangeRequest) =>
    apiClient
      .post<ApiResponse<BudgetChangeLogResponse>>(`/v1/projects/${projectId}/budget/changes`, data)
      .then((r) => r.data),

  getChangeLog: (projectId: string) =>
    apiClient
      .get<ApiResponse<BudgetChangeLogResponse[]>>(`/v1/projects/${projectId}/budget/changes`)
      .then((r) => r.data),

  approveChange: (projectId: string, changeId: string) =>
    apiClient
      .patch<ApiResponse<BudgetChangeLogResponse>>(
        `/v1/projects/${projectId}/budget/changes/${changeId}/approve`
      )
      .then((r) => r.data),

  rejectChange: (projectId: string, changeId: string, reason?: string) =>
    apiClient
      .patch<ApiResponse<BudgetChangeLogResponse>>(
        `/v1/projects/${projectId}/budget/changes/${changeId}/reject`,
        reason ? { reason } : undefined
      )
      .then((r) => r.data),

  getWbsBudgetSummary: (projectId: string) =>
    apiClient
      .get<ApiResponse<WbsBudgetSummary>>(`/v1/projects/${projectId}/wbs/budget-summary`)
      .then((r) => r.data),
};
