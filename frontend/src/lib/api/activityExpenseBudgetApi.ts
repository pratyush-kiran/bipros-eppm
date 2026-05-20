import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface ActivityExpenseBudget {
  id: string;
  projectId: string;
  activityId: string;
  categoryId: string;
  categoryCode: string | null;
  categoryName: string | null;
  dbsSection: string | null;
  plannedAmount: number;
  notes: string | null;
}

export interface ActivityExpenseBudgetRequest {
  categoryId: string;
  plannedAmount: number;
  notes?: string | null;
}

export const activityExpenseBudgetApi = {
  list: (projectId: string, activityId: string) =>
    apiClient
      .get<ApiResponse<ActivityExpenseBudget[]>>(
        `/v1/projects/${projectId}/activities/${activityId}/expense-budgets`,
      )
      .then((r) => r.data),

  upsert: (
    projectId: string,
    activityId: string,
    req: ActivityExpenseBudgetRequest,
  ) =>
    apiClient
      .put<ApiResponse<ActivityExpenseBudget>>(
        `/v1/projects/${projectId}/activities/${activityId}/expense-budgets`,
        req,
      )
      .then((r) => r.data),

  remove: (projectId: string, activityId: string, budgetId: string) =>
    apiClient
      .delete<ApiResponse<void>>(
        `/v1/projects/${projectId}/activities/${activityId}/expense-budgets/${budgetId}`,
      )
      .then((r) => r.data),
};
