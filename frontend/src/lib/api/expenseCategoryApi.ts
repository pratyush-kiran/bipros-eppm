import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/**
 * Expense Category — admin-configurable master data driving the DBS ad-hoc expense
 * capture. Each category maps to a single DBS section letter (A/B/C/D/E/F/G), which
 * buckets the manually-entered expense into the right section at read time.
 */
export interface ExpenseCategory {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  /** Single uppercase letter A–G. */
  dbsSection: string;
  defaultTaxable: boolean;
  defaultTaxRatePct?: number | null;
  sortOrder: number;
  active: boolean;
}

export interface ExpenseCategoryRequest {
  code: string;
  name: string;
  description?: string | null;
  dbsSection: string;
  defaultTaxable?: boolean | null;
  defaultTaxRatePct?: number | null;
  sortOrder?: number | null;
  active?: boolean | null;
}

export const expenseCategoryApi = {
  list: (activeOnly = false) =>
    apiClient
      .get<ApiResponse<ExpenseCategory[]>>(
        `/v1/expense-categories?activeOnly=${activeOnly}`,
      )
      .then((r) => r.data),

  listBySection: (section: string) =>
    apiClient
      .get<ApiResponse<ExpenseCategory[]>>(
        `/v1/expense-categories/by-section/${section}`,
      )
      .then((r) => r.data),

  get: (id: string) =>
    apiClient
      .get<ApiResponse<ExpenseCategory>>(`/v1/expense-categories/${id}`)
      .then((r) => r.data),

  create: (request: ExpenseCategoryRequest) =>
    apiClient
      .post<ApiResponse<ExpenseCategory>>("/v1/expense-categories", request)
      .then((r) => r.data),

  update: (id: string, request: ExpenseCategoryRequest) =>
    apiClient
      .put<ApiResponse<ExpenseCategory>>(
        `/v1/expense-categories/${id}`,
        request,
      )
      .then((r) => r.data),

  remove: (id: string) =>
    apiClient
      .delete<ApiResponse<void>>(`/v1/expense-categories/${id}`)
      .then((r) => r.data),
};
