import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface SubContractorWorkTypeRow {
  scMasterId: string;
  scCode: string;
  scName: string;
  scWorkTypeId: string;
  workTypeName: string;
  unit: string | null;
  ratePerUnit: number | null;
  normPerDay: number | null;
  plannedQty: number;
  actualQty: number;
  distinctDays: number;
  avgQtyPerDay: number;
  productivityFactor: number | null;
  plannedCost: number;
  actualCost: number;
  costVariance: number;
  costPerformanceIndex: number | null;
  qtyCompletionPct: number;
}

export interface SubContractorKpiResponse {
  projectId: string;
  from: string;
  to: string;
  activeSubContractors: number;
  workTypesTracked: number;
  totalPlannedQty: number;
  totalActualQty: number;
  quantityCompletionPct: number;
  avgProductivityFactor: number | null;
  totalPlannedCost: number;
  totalActualCost: number;
  costVariance: number;
  costPerformanceIndex: number | null;
  daysWorked: number;
  impliedPlannedDays: number | null;
  underPerformingCount: number;
  unmatchedDprRows: number;
  perScWorkType: SubContractorWorkTypeRow[];
  bottomProductivity: SubContractorWorkTypeRow[];
  topByCost: SubContractorWorkTypeRow[];
  bottomOutputAchievement: SubContractorWorkTypeRow[];
}

export const subContractorKpiApi = {
  /**
   * Composite sub-contractor KPIs for the period [from..to]. Mirrors the manpower /
   * equipment / material KPI endpoints — single network call returns the headline
   * tile data plus side-panel tables.
   */
  getKpis: (projectId: string, from: string, to: string) =>
    apiClient
      .get<ApiResponse<SubContractorKpiResponse>>(
        `/v1/projects/${projectId}/kpis/sub-contractor`,
        { params: { from, to } },
      )
      .then((r) => r.data),
};
