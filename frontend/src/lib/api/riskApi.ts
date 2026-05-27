import { apiClient } from "./client";
import type { ApiResponse } from "../types";

// ── Types ──────────────────────────────────────────────────────────────────

export type RiskType = "THREAT" | "OPPORTUNITY";

export type RiskRag = "CRIMSON" | "RED" | "AMBER" | "GREEN" | "OPPORTUNITY";

export type RiskTrend = "WORSENING" | "STABLE" | "IMPROVING";

export type RiskStatus =
  | "IDENTIFIED"
  | "ANALYZING"
  | "MITIGATING"
  | "RESOLVED"
  | "CLOSED"
  | "ACCEPTED"
  | "REJECTED"
  | "REALISED"
  | "OPEN_ESCALATED"
  | "OPEN_UNDER_ACTIVE_MANAGEMENT"
  | "OPEN_BEING_MANAGED"
  | "OPEN_MONITOR"
  | "OPEN_WATCH"
  | "OPEN_TARGET"
  | "OPEN_ASI_REVIEW"
  | "REALISED_PARTIALLY";

/** Statuses at which a risk leaves the active register (backend-terminal
 *  CLOSED/RESOLVED plus REJECTED). */
export const CLOSED_RISK_STATUSES: ReadonlySet<RiskStatus> = new Set([
  "CLOSED",
  "RESOLVED",
  "REJECTED",
]);

/** True while a risk is still open/active — i.e. not closed, resolved or rejected. */
export const isOpenRisk = (status: RiskStatus): boolean =>
  !CLOSED_RISK_STATUSES.has(status);

/**
 * Legacy single-axis category enum (retained for old reports). New code should use
 * the {@link RiskCategorySummary} object returned by the backend on RiskSummary.category.
 */
export type RiskCategoryLegacyCode =
  | "TECHNICAL"
  | "COMMERCIAL"
  | "ENVIRONMENTAL"
  | "REGULATORY"
  | "FINANCIAL"
  | "SCHEDULE"
  | "SAFETY"
  | "POLITICAL"
  | "SOCIAL"
  | "LAND_ACQUISITION"
  | "SUPPLY_CHAIN"
  | "DESIGN"
  | "CONSTRUCTION"
  | "GEOTECHNICAL"
  | "MONSOON_WEATHER";

/** What the backend actually returns on {@code RiskSummary.category} — see RiskCategorySummaryDto. */
export interface RiskCategorySummary {
  id: string;
  code: string;
  name: string;
  industry?: string;
  type?: { id: string; code: string; name: string } | null;
}

// PMBOK / P6 strategies — threats: AVOID/MITIGATE/TRANSFER/ACCEPT, opportunities: EXPLOIT/ENHANCE/SHARE/ACCEPT.
export type RiskResponseType =
  | "AVOID"
  | "MITIGATE"
  | "TRANSFER"
  | "ACCEPT"
  | "EXPLOIT"
  | "ENHANCE"
  | "SHARE";

export type ScoringMethod = "HIGHEST_IMPACT" | "AVERAGE_IMPACT" | "AVERAGE_INDIVIDUAL";

export type RiskAnalysisLevel = "NOT_ANALYSED" | "PARTIALLY_ANALYSED" | "WELL_ANALYSED";

export type RiskProbability = "VERY_LOW" | "LOW" | "MEDIUM" | "HIGH" | "VERY_HIGH";

/**
 * Legacy single-axis impact enum. Kept for backward compatibility with reports
 * and dashboards. Prefer the per-dimension {@code impactCost} / {@code impactSchedule}
 * fields for new code.
 */
export type RiskImpact = "VERY_LOW" | "LOW" | "MEDIUM" | "HIGH" | "VERY_HIGH";

export interface RiskAnalysisQuality {
  score: number;
  level: RiskAnalysisLevel;
  criteria: {
    hasOwner: boolean;
    hasRating: boolean;
    hasDescription: boolean;
    hasResponse: boolean;
  };
}

export interface RiskActivityAssignment {
  id: string;
  riskId: string;
  activityId: string;
  projectId: string;
  activityCode?: string;
  activityName?: string;
  activityStartDate?: string;
  activityFinishDate?: string;
}

export interface RiskResponse {
  id: string;
  code: string;
  title: string;
  description: string;
  /** Backend returns the full category object; null when the risk is uncategorised. */
  category?: RiskCategorySummary | null;
  riskType: RiskType;
  probability: RiskProbability;
  /** Legacy single-axis impact (back-compat only). Use impactCost / impactSchedule for new code. */
  impact?: RiskImpact;
  impactCost: number;
  impactSchedule: number;
  riskScore: number;
  residualRiskScore: number;
  rag: RiskRag;
  trend: RiskTrend;
  isOpportunity: boolean;
  status: RiskStatus;
  owner: string;
  ownerId: string;
  identifiedDate: string;
  identifiedById: string;
  dueDate: string;
  createdAt: string;
  updatedAt: string;
  analysisQuality?: RiskAnalysisQuality;
  sortOrder: number;

  // P6 Exposure
  exposureStartDate?: string;
  exposureFinishDate?: string;
  preResponseExposureCost?: number;
  postResponseExposureCost?: number;

  // P6 Response strategy
  responseType?: RiskResponseType;
  responseDescription?: string;

  // P6 Post-response
  postResponseProbability?: RiskProbability;
  postResponseImpactCost?: number;
  postResponseImpactSchedule?: number;
  postResponseRiskScore?: number;

  // P6 Descriptive
  cause?: string;
  effect?: string;
  notes?: string;

  // Assigned activities
  assignedActivities?: RiskActivityAssignment[];
}

export interface CreateRiskRequest {
  code?: string;
  title: string;
  description?: string;
  categoryId?: string;
  legacyCategoryCode?: string;
  riskType?: RiskType;
  probability?: number;
  /** Legacy single-axis impact (back-compat only). Prefer impactCost/impactSchedule. */
  impact?: RiskImpact;
  impactCost?: number;
  impactSchedule?: number;
  status?: RiskStatus;
  ownerId?: string;
  identifiedDate?: string;
  identifiedById?: string;
  dueDate?: string;
  affectedActivities?: string;
  costImpact?: number;
  scheduleImpactDays?: number;
  sortOrder?: number;
  responseType?: RiskResponseType;
  responseDescription?: string;
  postResponseProbability?: number;
  postResponseImpactCost?: number;
  postResponseImpactSchedule?: number;
  cause?: string;
  effect?: string;
  notes?: string;
}

export interface UpdateRiskRequest {
  title?: string;
  description?: string;
  categoryId?: string;
  riskType?: RiskType;
  status?: RiskStatus;
  probability?: number;
  impactCost?: number;
  impactSchedule?: number;
  ownerId?: string;
  identifiedDate?: string;
  identifiedById?: string;
  dueDate?: string;
  affectedActivities?: string;
  costImpact?: number;
  scheduleImpactDays?: number;
  sortOrder?: number;
  responseType?: RiskResponseType;
  responseDescription?: string;
  postResponseProbability?: number;
  postResponseImpactCost?: number;
  postResponseImpactSchedule?: number;
  cause?: string;
  effect?: string;
  notes?: string;
}

export interface RiskScoringMatrixCell {
  id?: string;
  projectId?: string;
  probabilityValue: number;
  impactValue: number;
  score: number;
}

export interface RiskScoringConfig {
  id: string;
  projectId: string;
  scoringMethod: ScoringMethod;
  active: boolean;
}

// ── API Methods ────────────────────────────────────────────────────────────

export const riskApi = {
  // Risk CRUD
  listRisks: (projectId: string, status?: string) => {
    const params = status ? `?status=${status}` : "";
    return apiClient
      .get<ApiResponse<RiskResponse[]>>(`/v1/projects/${projectId}/risks${params}`)
      .then((r) => r.data);
  },

  getRisk: (projectId: string, riskId: string) =>
    apiClient
      .get<ApiResponse<RiskResponse>>(`/v1/projects/${projectId}/risks/${riskId}`)
      .then((r) => r.data),

  createRisk: (projectId: string, data: CreateRiskRequest) =>
    apiClient
      .post<ApiResponse<RiskResponse>>(`/v1/projects/${projectId}/risks`, data)
      .then((r) => r.data),

  updateRisk: (projectId: string, riskId: string, data: UpdateRiskRequest) =>
    apiClient
      .put<ApiResponse<RiskResponse>>(`/v1/projects/${projectId}/risks/${riskId}`, data)
      .then((r) => r.data),

  deleteRisk: (projectId: string, riskId: string) =>
    apiClient.delete(`/v1/projects/${projectId}/risks/${riskId}`),

  // Activity Assignment
  addActivityToRisk: (projectId: string, riskId: string, activityId: string) =>
    apiClient
      .post<ApiResponse<RiskActivityAssignment>>(
        `/v1/projects/${projectId}/risks/${riskId}/activities/${activityId}`
      )
      .then((r) => r.data),

  removeActivityFromRisk: (projectId: string, riskId: string, activityId: string) =>
    apiClient.delete(
      `/v1/projects/${projectId}/risks/${riskId}/activities/${activityId}`
    ),

  getAssignedActivities: (projectId: string, riskId: string) =>
    apiClient
      .get<ApiResponse<RiskActivityAssignment[]>>(
        `/v1/projects/${projectId}/risks/${riskId}/activities`
      )
      .then((r) => r.data),

  // Scoring Matrix
  getScoringMatrix: (projectId: string) =>
    apiClient
      .get<ApiResponse<RiskScoringMatrixCell[]>>(
        `/v1/projects/${projectId}/risk-scoring/matrix`
      )
      .then((r) => r.data),

  updateScoringMatrix: (projectId: string, cells: RiskScoringMatrixCell[]) =>
    apiClient
      .put<ApiResponse<RiskScoringMatrixCell[]>>(
        `/v1/projects/${projectId}/risk-scoring/matrix`,
        cells
      )
      .then((r) => r.data),

  getScoringConfig: (projectId: string) =>
    apiClient
      .get<ApiResponse<RiskScoringConfig>>(
        `/v1/projects/${projectId}/risk-scoring/config`
      )
      .then((r) => r.data),

  updateScoringConfig: (projectId: string, scoringMethod: ScoringMethod) =>
    apiClient
      .put<ApiResponse<RiskScoringConfig>>(
        `/v1/projects/${projectId}/risk-scoring/config`,
        { scoringMethod }
      )
      .then((r) => r.data),

  // Summary & Matrix
  getRisksSummary: (projectId: string) =>
    apiClient
      .get<ApiResponse<{
        totalRisks: number;
        exposure: number;
        byStatus: Record<string, number>;
        matrix: Record<string, RiskResponse[]>;
        risksNotAnalysed: number;
      }>>(`/v1/projects/${projectId}/risks/summary`)
      .then((r) => r.data),

  getRiskMatrix: (projectId: string) =>
    apiClient
      .get<ApiResponse<Record<string, RiskResponse[]>>>(
        `/v1/projects/${projectId}/risks/matrix`
      )
      .then((r) => r.data),

  getRiskExposure: (projectId: string) =>
    apiClient
      .get<ApiResponse<number>>(`/v1/projects/${projectId}/risks/exposure`)
      .then((r) => r.data),

  // Responses
  addResponse: (projectId: string, riskId: string, data: unknown) =>
    apiClient
      .post<ApiResponse<unknown>>(
        `/v1/projects/${projectId}/risks/${riskId}/responses`,
        data
      )
      .then((r) => r.data),

  getResponses: (projectId: string, riskId: string) =>
    apiClient
      .get<ApiResponse<unknown[]>>(
        `/v1/projects/${projectId}/risks/${riskId}/responses`
      )
      .then((r) => r.data),

  // Template copy
  copyFromTemplates: (projectId: string, templateIds: string[]) =>
    apiClient
      .post<ApiResponse<RiskResponse[]>>(
        `/v1/projects/${projectId}/risks/copy-from-templates`,
        { templateIds }
      )
      .then((r) => r.data),

  // Legacy compatibility
  getRisksByProject: (projectId: string) =>
    apiClient
      .get<ApiResponse<RiskResponse[]>>(`/v1/projects/${projectId}/risks`)
      .then((r) => r.data),
};
