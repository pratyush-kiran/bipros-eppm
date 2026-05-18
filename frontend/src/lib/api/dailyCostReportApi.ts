import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface DailyCostReportRow {
  dprId: string;
  date: string;
  activity: string;
  qtyExecuted: number;
  unit: string;
  /** Workstream B1: new FK linkage. Null on legacy rows that resolve via boqItemNo only. */
  boqItemId: string | null;
  boqItemNo: string | null;
  budgetedUnitRate: number | null;
  actualUnitRate: number | null;
  budgetedCost: number | null;
  actualCost: number | null;
  variance: number | null;
  variancePercent: number | null;
  /** Workstream B3: projected ETC for this row (null when no EvmCalculation exists). */
  etc: number | null;
  /** Workstream B3: projected EAC for this row (null when no EvmCalculation exists). */
  eac: number | null;
  supervisor: string;
}

export interface DailyCostReportResponse {
  from: string | null;
  to: string | null;
  rows: DailyCostReportRow[];
  periodBudgetedCost: number;
  periodActualCost: number;
  periodVariance: number;
  periodVariancePercent: number | null;
}

export interface DailyCostReportFilters {
  from?: string;
  to?: string;
}

export const dailyCostReportApi = {
  generate: (projectId: string, filters: DailyCostReportFilters = {}) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    const qs = params.toString() ? `?${params.toString()}` : "";
    return apiClient
      .get<ApiResponse<DailyCostReportResponse>>(`/v1/projects/${projectId}/daily-cost-report${qs}`)
      .then((r) => r.data);
  },

  /**
   * Workstream B3 drilldown: returns the DPR rows that contributed to a single BOQ item's
   * actual cost in the same window, in the same shape as the main report.
   */
  drilldown: (
    projectId: string,
    boqItemId: string,
    filters: DailyCostReportFilters = {}
  ) => {
    const params = new URLSearchParams();
    params.set("boqItemId", boqItemId);
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    return apiClient
      .get<ApiResponse<DailyCostReportResponse>>(
        `/v1/projects/${projectId}/daily-cost-report/drilldown?${params.toString()}`
      )
      .then((r) => r.data);
  },
};
