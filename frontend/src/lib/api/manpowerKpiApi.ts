import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface WorkforceUtilization {
  actualNos: number;
  plannedNos: number;
  utilizationPct: number;
  rawUtilizationPct: number;
  overflow: boolean;
  laborResourceCount: number;
  activeResourceCount: number;
  missingAttendanceCount: number;
}

export interface ProductivityFactorRow {
  activityId: string;
  activityName: string;
  darUnit: string | null;
  normUnit: string | null;
  actualOutputPerManPerDay: number;
  normOutputPerManPerDay: number;
  factor: number;
  unitMismatch: boolean;
}

export interface LabourCostPerUnitRow {
  boqItemId: string;
  itemNo: string;
  description: string;
  unit: string | null;
  labourCost: number;
  qtyExecuted: number;
  costPerUnit: number;
}

export interface CrewOutputRow {
  activityId: string;
  activityName: string;
  crewSize: number | null;
  actualOutputPerDay: number;
  normOutputPerDay: number;
  deviationPct: number;
}

export interface OutputAchievementRow {
  activityId: string;
  activityName: string;
  actualDailyOutput: number;
  plannedDailyOutput: number;
  achievementPct: number;
}

export interface ManpowerDataQuality {
  missingRateResourceCount: number;
  missingRateResourceCodes: string[];
  missingAttendanceResourceCount: number;
  missingAttendanceResourceCodes: string[];
  unitMismatchActivityCount: number;
  noNormActivityCount: number;
  noBoqBaselineActivityCount: number;
}

/**
 * KPI 3.1 / 3.3 / 3.4 / 3.7 cost block.
 * Variance positive = under budget; LCPI ≥ 1.0 = on budget.
 * OT premium uses 2.0× per Indian Factories Act §59 (locked 2026-05-08).
 */
export interface LabourCostSummary {
  plannedLabourCost: number;
  actualLabourCost: number;
  labourCostVariance: number;
  lcpi: number;
  otCostPct: number;
  activityCoverageCount: number;
  missingPlanCount: number;
}

export interface ManpowerKpiResponse {
  projectId: string;
  from: string;
  to: string;
  workforceUtilization: WorkforceUtilization;
  productivityFactor: ProductivityFactorRow[];
  headlineProductivityFactor: number;
  labourCostPerUnit: LabourCostPerUnitRow[];
  weightedAvgCostPerUnit: number;
  crewOutput: CrewOutputRow[];
  idleTimeRatioPct: number;
  overtimeRatioPct: number;
  outputAchievement: OutputAchievementRow[];
  labourCostSummary: LabourCostSummary;
  /** KPI 2.7 — linear-interpolation fallback in Phase 2A. */
  cumulativeProgressPct: number;
  dataQuality: ManpowerDataQuality;
}

export const manpowerKpiApi = {
  /**
   * Composite manpower KPIs for the period [from..to]. Single network call returns all
   * the metrics the dashboards need; the components pick which slices to render.
   */
  getKpis: (projectId: string, from: string, to: string) =>
    apiClient
      .get<ApiResponse<ManpowerKpiResponse>>(
        `/v1/projects/${projectId}/kpis/manpower`,
        { params: { from, to } },
      )
      .then((r) => r.data),
};
