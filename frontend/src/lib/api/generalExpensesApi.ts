import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/**
 * Section G — General Expenses (monthly overheads). One file per project under
 * /v1/projects/{id}/general-expenses. Plan items are seeded with the 20
 * standard PRE-sheet rows on project creation; PM edits qty/amount per item.
 * Monthly actuals are logged once per yearMonth (encoded as YYYYMM, e.g.
 * 202605 for May 2026). Backend prorates the monthly total across days for the
 * Section G entry on the PM DBS rollup.
 */

export type GeneralExpenseUnit = "MONTH" | "LS";
export type GeneralExpenseFormulaType = "NONE" | "PCT_CONTRACT_VALUE";

export interface GeneralExpensePlanItem {
  id: string;
  projectId: string;
  description: string;
  unit: GeneralExpenseUnit;
  rate?: number | null;
  planQty?: number | null;
  planAmount?: number | null;
  formulaType: GeneralExpenseFormulaType;
  formulaPct?: number | null;
  sortOrder: number;
  active: boolean;
}

export interface GeneralExpenseMonthlyEntry {
  id: string;
  projectId: string;
  planItemId: string;
  yearMonth: number;
  achievedQty?: number | null;
  achievedAmount?: number | null;
  notes?: string | null;
  loggedByUserId?: string | null;
  updatedAt?: string;
}

export interface MonthlyActualsRow {
  planItem: GeneralExpensePlanItem;
  actual: GeneralExpenseMonthlyEntry | null;
}

export interface MonthlyActualsResponse {
  yearMonth: number;
  monthlyTotal: number;
  rows: MonthlyActualsRow[];
}

export interface PlanItemUpsertRequest {
  description?: string;
  unit?: GeneralExpenseUnit;
  rate?: number | null;
  planQty?: number | null;
  planAmount?: number | null;
  formulaType?: GeneralExpenseFormulaType;
  formulaPct?: number | null;
  sortOrder?: number;
  active?: boolean;
}

export interface MonthlyEntryUpsertRequest {
  achievedQty?: number | null;
  achievedAmount?: number | null;
  notes?: string | null;
}

const base = (projectId: string) => `/v1/projects/${projectId}/general-expenses`;

/** {@code yearMonth} encoding used by the backend. */
export const toYearMonth = (year: number, month: number) => year * 100 + month;

/** Parse "YYYY-MM" (the value of an <input type="month">) into the YYYYMM integer. */
export const parseInputMonth = (value: string): number => {
  const [y, m] = value.split("-").map(Number);
  return toYearMonth(y, m);
};

/** Inverse of {@link parseInputMonth} — format the integer for the input element. */
export const formatInputMonth = (yearMonth: number): string => {
  const y = Math.floor(yearMonth / 100);
  const m = yearMonth % 100;
  return `${y.toString().padStart(4, "0")}-${m.toString().padStart(2, "0")}`;
};

export const generalExpensesApi = {
  listPlanItems: (projectId: string) =>
    apiClient
      .get<ApiResponse<GeneralExpensePlanItem[]>>(`${base(projectId)}/plan-items`)
      .then((r) => r.data),

  createPlanItem: (projectId: string, body: PlanItemUpsertRequest) =>
    apiClient
      .post<ApiResponse<GeneralExpensePlanItem>>(`${base(projectId)}/plan-items`, body)
      .then((r) => r.data),

  updatePlanItem: (projectId: string, itemId: string, body: PlanItemUpsertRequest) =>
    apiClient
      .put<ApiResponse<GeneralExpensePlanItem>>(`${base(projectId)}/plan-items/${itemId}`, body)
      .then((r) => r.data),

  deletePlanItem: (projectId: string, itemId: string) =>
    apiClient
      .delete<ApiResponse<void>>(`${base(projectId)}/plan-items/${itemId}`)
      .then((r) => r.data),

  getActuals: (projectId: string, yearMonth: number) =>
    apiClient
      .get<ApiResponse<MonthlyActualsResponse>>(`${base(projectId)}/actuals`, {
        params: { yearMonth },
      })
      .then((r) => r.data),

  upsertActual: (
    projectId: string,
    planItemId: string,
    yearMonth: number,
    body: MonthlyEntryUpsertRequest,
  ) =>
    apiClient
      .put<ApiResponse<GeneralExpenseMonthlyEntry>>(
        `${base(projectId)}/actuals/${planItemId}`,
        body,
        { params: { yearMonth } },
      )
      .then((r) => r.data),

  deleteActual: (projectId: string, planItemId: string, yearMonth: number) =>
    apiClient
      .delete<ApiResponse<void>>(`${base(projectId)}/actuals/${planItemId}`, {
        params: { yearMonth },
      })
      .then((r) => r.data),
};
