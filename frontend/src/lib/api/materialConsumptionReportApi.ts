import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/**
 * Material Consumption Report (Phase E). Read-only — wraps the
 * {@code MaterialConsumptionReportController} backend endpoint plus an Excel export.
 * Mirrors {@code MaterialConsumptionRow} and {@code MaterialConsumptionReportResponse}
 * from {@code bipros-reporting/.../materialconsumption/}.
 */
export type MaterialConsumptionGroupBy =
  | "DAY"
  | "MATERIAL"
  | "ACTIVITY"
  | "SUPERVISOR";

export type MaterialConsumptionAlertCode =
  | "EXCESS_CONSUMPTION"
  | "NEGATIVE_BALANCE"
  | "BUDGET_OVERCONSUMPTION"
  | "MISSING_UNIT_RATE";

export interface MaterialConsumptionRow {
  projectId: string;
  fromDate: string | null;
  toDate: string | null;
  wbsNodeId: string | null;
  wbsName: string | null;
  activityId: string | null;
  activityName: string | null;
  supervisorUserId: string | null;
  supervisorName: string | null;
  storekeeperUserId: string | null;
  storekeeperName: string | null;
  materialRateMasterId: string | null;
  materialName: string | null;
  unit: string | null;
  plannedQty: number | null;
  issuedQty: number | null;
  consumedQty: number | null;
  balanceQty: number | null;
  wastagePercent: number | null;
  unitRate: number | null;
  plannedCost: number | null;
  actualCost: number | null;
  variance: number | null;
  variancePercent: number | null;
  alerts: string[];
}

export interface MaterialConsumptionReportResponse {
  from: string | null;
  to: string | null;
  groupBy: MaterialConsumptionGroupBy | null;
  rows: MaterialConsumptionRow[];
  totals: Record<string, number>;
  alertCounts: Record<string, number>;
}

export interface MaterialConsumptionFilters {
  from?: string;
  to?: string;
  wbsNodeId?: string;
  activityId?: string;
  supervisorUserId?: string;
  storekeeperUserId?: string;
  materialRateMasterId?: string;
  groupBy?: MaterialConsumptionGroupBy | "";
}

function buildQuery(filters: MaterialConsumptionFilters): string {
  const params = new URLSearchParams();
  if (filters.from) params.set("from", filters.from);
  if (filters.to) params.set("to", filters.to);
  if (filters.wbsNodeId) params.set("wbsNodeId", filters.wbsNodeId);
  if (filters.activityId) params.set("activityId", filters.activityId);
  if (filters.supervisorUserId) params.set("supervisorUserId", filters.supervisorUserId);
  if (filters.storekeeperUserId) params.set("storekeeperUserId", filters.storekeeperUserId);
  if (filters.materialRateMasterId)
    params.set("materialRateMasterId", filters.materialRateMasterId);
  if (filters.groupBy) params.set("groupBy", filters.groupBy);
  const qs = params.toString();
  return qs ? `?${qs}` : "";
}

export const materialConsumptionReportApi = {
  generate: (projectId: string, filters: MaterialConsumptionFilters = {}) =>
    apiClient
      .get<ApiResponse<MaterialConsumptionReportResponse>>(
        `/v1/projects/${projectId}/reports/material-consumption${buildQuery(filters)}`,
      )
      .then((r) => r.data),

  downloadExcel: async (projectId: string, filters: MaterialConsumptionFilters = {}) => {
    const response = await apiClient.get<Blob>(
      `/v1/projects/${projectId}/reports/material-consumption/export.xlsx${buildQuery(filters)}`,
      { responseType: "blob" },
    );
    return response.data;
  },
};
