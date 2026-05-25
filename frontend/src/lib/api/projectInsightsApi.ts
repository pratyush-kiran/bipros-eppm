import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface ProjectStatusSnapshot {
  projectId: string;
  projectCode: string;
  projectName: string;
  status: string;
  plannedStart: string | null;
  plannedFinish: string | null;
  scheduleRag: string;
  costRag: string;
  scopeRag: string;
  riskRag: string;
  hseRag: string;
  topIssues: string[];
  nextMilestoneName: string | null;
  nextMilestoneDate: string | null;
  currentCpi: number;
  currentSpi: number;
  physicalPct: number;
  plannedPct: number;
  activeRisksCount: number;
  openHseIncidents: number;
  bacCrores: number;
  eacCrores: number;
  lastUpdatedAt: string;
}

export interface CostVarianceRow {
  wbsNodeId: string;
  wbsCode: string;
  wbsName: string;
  level: number | null;
  budgetCrores: number;
  committedCrores: number;
  actualCrores: number;
  forecastCrores: number;
  varianceCrores: number;
  variancePct: number;
}

export interface RaBillRowSummary {
  id: string;
  billNumber: string;
  billPeriodFrom: string | null;
  billPeriodTo: string | null;
  status: string;
  grossAmount: number;
  netAmount: number;
  submittedDate: string | null;
  approvedDate: string | null;
  paidDate: string | null;
}

export interface RaBillSummary {
  totalSubmittedCrores: number;
  pendingApprovalCrores: number;
  approvedCrores: number;
  paidCrores: number;
  rejectedCrores: number;
  retentionHeldCrores: number;
  ldAppliedCrores: number;
  bills: RaBillRowSummary[];
}

export interface ActivityStatusRow {
  activityId: string;
  code: string;
  name: string;
  wbsCode: string;
  wbsName: string;
  status: string;
  activityType: string;
  plannedStart: string | null;
  plannedFinish: string | null;
  actualStart: string | null;
  actualFinish: string | null;
  earlyStart: string | null;
  earlyFinish: string | null;
  totalFloat: number | null;
  freeFloat: number | null;
  isCritical: boolean;
  pctComplete: number;
  daysDelay: number;
  daysRemaining: number;
}

export interface ProjectCompliance {
  pfms: { sanctionOk: boolean; lastRelease: string | null; pendingAmount: number };
  gstn: { contractorCount: number; verifiedCount: number; expiredCount: number };
  gem: { linkedOrders: number; totalValueCrores: number };
  cppp: { publishedTenders: number; openBids: number };
  parivesh: { clearancesObtained: number; pendingClearances: number };
  hse: { last30DaysIncidents: number; openNCRs: number };
  overallScore: number;
}

export interface VariationOrderRow {
  id: string;
  contractId: string | null;
  voNumber: string;
  description: string;
  costImpactCrores: number;
  timeImpactDays: number | null;
  status: string;
  approvedBy: string | null;
  approvedDate: string | null;
}

export interface ScheduleQuality {
  missingLogicCount: number;
  leadRelationshipsCount: number;
  lagsCount: number;
  fsRelationshipPct: number;
  hardConstraintsCount: number;
  highFloatCount: number;
  negativeFloatCount: number;
  invalidDatesCount: number;
  resourceAllocationIssues: number;
  missedTasksCount: number;
  criticalPathTestOk: boolean;
  criticalPathLength: number;
  beiActual: number;
  beiRequired: number;
  overallHealthPct: number;
  failingChecks: string[];
}

export interface SnapshotDeltas {
  physicalPctDelta: number | null;
  bacCroresDelta: number | null;
  activeRisksDelta: number | null;
  tasksCompletedDelta: number | null;
}

export interface ProjectStatusSnapshotWithTrend {
  current: ProjectStatusSnapshot;
  deltas: SnapshotDeltas;
}

export interface MilestoneRow {
  milestoneId: string;
  code: string;
  name: string;
  milestoneType: string;
  plannedDate: string | null;
  forecastDate: string | null;
  actualDate: string | null;
  status: string;
  daysSlip: number;
  ldExposureCrores: number;
}

export const projectInsightsApi = {
  getStatusSnapshot: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectStatusSnapshot>>(`/v1/projects/${projectId}/status-snapshot`)
      .then((r) => r.data.data),

  getStatusSnapshotWithTrend: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectStatusSnapshotWithTrend>>(
        `/v1/projects/${projectId}/status-snapshot-with-trend`,
      )
      .then((r) => r.data.data),

  getCostVariance: (projectId: string) =>
    apiClient
      .get<ApiResponse<CostVarianceRow[]>>(`/v1/projects/${projectId}/cost-variance`)
      .then((r) => r.data.data),

  getRaBillSummary: (projectId: string) =>
    apiClient
      .get<ApiResponse<RaBillSummary>>(`/v1/projects/${projectId}/ra-bill-summary`)
      .then((r) => r.data.data),

  getActivityStatus: (
    projectId: string,
    params: { status?: string; criticalOnly?: boolean; limit?: number } = {},
  ) => {
    const qs = new URLSearchParams();
    if (params.status) qs.set("status", params.status);
    if (params.criticalOnly) qs.set("criticalOnly", "true");
    if (params.limit) qs.set("limit", String(params.limit));
    const query = qs.toString();
    return apiClient
      .get<ApiResponse<ActivityStatusRow[]>>(
        `/v1/projects/${projectId}/activity-status${query ? `?${query}` : ""}`,
      )
      .then((r) => r.data.data);
  },

  getCompliance: (projectId: string) =>
    apiClient
      .get<ApiResponse<ProjectCompliance>>(`/v1/projects/${projectId}/compliance`)
      .then((r) => r.data.data),

  getVariationOrders: (projectId: string) =>
    apiClient
      .get<ApiResponse<VariationOrderRow[]>>(`/v1/projects/${projectId}/variation-orders`)
      .then((r) => r.data.data),

  getScheduleQuality: (projectId: string) =>
    apiClient
      .get<ApiResponse<ScheduleQuality>>(`/v1/projects/${projectId}/schedule-quality`)
      .then((r) => r.data.data),

  getMilestones: (projectId: string) =>
    apiClient
      .get<ApiResponse<MilestoneRow[]>>(`/v1/projects/${projectId}/milestones`)
      .then((r) => r.data.data),
};
