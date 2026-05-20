import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface DbsManualExpense {
  id: string;
  projectId: string;
  reportDate: string;
  activityId: string | null;
  categoryId: string;
  categoryCode: string | null;
  categoryName: string | null;
  dbsSection: string | null;
  supervisorUserId: string | null;
  description: string;
  amount: number;
  currency: string;
  vendorName: string | null;
  receiptNo: string | null;
  attachmentUrl: string | null;
  taxAmount: number | null;
  taxRatePct: number | null;
  notes: string | null;
  approvalStatus: "PENDING" | "APPROVED" | "REJECTED";
  approvalThresholdBreached: boolean;
  reversalOfId: string | null;
}

export interface DbsManualExpenseRequest {
  reportDate: string;
  activityId?: string | null;
  categoryId: string;
  supervisorUserId?: string | null;
  description: string;
  amount: number;
  currency?: string | null;
  vendorName?: string | null;
  receiptNo?: string | null;
  attachmentUrl?: string | null;
  taxAmount?: number | null;
  taxRatePct?: number | null;
  notes?: string | null;
  reversalOfId?: string | null;
}

export const dbsExpenseApi = {
  listForDate: (projectId: string, date: string) =>
    apiClient
      .get<ApiResponse<DbsManualExpense[]>>(
        `/v1/projects/${projectId}/dbs/expenses?date=${date}`,
      )
      .then((r) => r.data),

  listForActivity: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<DbsManualExpense[]>>(
        `/v1/projects/${projectId}/dbs/expenses/by-activity/${activityId}`,
      )
      .then((r) => r.data),

  create: (projectId: string, req: DbsManualExpenseRequest) =>
    apiClient
      .post<ApiResponse<DbsManualExpense>>(
        `/v1/projects/${projectId}/dbs/expenses`,
        req,
      )
      .then((r) => r.data),

  update: (projectId: string, id: string, req: DbsManualExpenseRequest) =>
    apiClient
      .put<ApiResponse<DbsManualExpense>>(
        `/v1/projects/${projectId}/dbs/expenses/${id}`,
        req,
      )
      .then((r) => r.data),

  remove: (projectId: string, id: string) =>
    apiClient
      .delete<ApiResponse<void>>(`/v1/projects/${projectId}/dbs/expenses/${id}`)
      .then((r) => r.data),

  approve: (projectId: string, id: string) =>
    apiClient
      .post<ApiResponse<DbsManualExpense>>(
        `/v1/projects/${projectId}/dbs/expenses/${id}/approve`,
        {},
      )
      .then((r) => r.data),

  reject: (projectId: string, id: string) =>
    apiClient
      .post<ApiResponse<DbsManualExpense>>(
        `/v1/projects/${projectId}/dbs/expenses/${id}/reject`,
        {},
      )
      .then((r) => r.data),

  listPending: (projectId: string) =>
    apiClient
      .get<ApiResponse<DbsManualExpense[]>>(
        `/v1/projects/${projectId}/dbs/expenses/pending`,
      )
      .then((r) => r.data),

  getApprovalThreshold: (projectId: string) =>
    apiClient
      .get<ApiResponse<number>>(
        `/v1/projects/${projectId}/dbs/expenses/approval-threshold`,
      )
      .then((r) => r.data),

  setApprovalThreshold: (projectId: string, threshold: number | null) =>
    apiClient
      .put<ApiResponse<number>>(
        `/v1/projects/${projectId}/dbs/expenses/approval-threshold`,
        { threshold },
      )
      .then((r) => r.data),
};
