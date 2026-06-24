import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface CurrencyBudget {
  currency: string;
  totalBudgetRaw: number;
}

export interface PortfolioEvmRow {
  projectId: string;
  projectCode: string;
  projectName: string;
  pv: number;
  ev: number;
  ac: number;
  cpi: number;
  spi: number;
  cv: number;
  sv: number;
  eac: number;
  bac: number;
  budgetCurrency: string | null;
  percentComplete: number;
}

export interface PortfolioScorecard {
  totalProjects: number;
  byStatus: Record<string, number>;
  totalBudgetCrores: number;
  totalCommittedCrores: number;
  totalSpentCrores: number;
  rag: { green: number; amber: number; red: number; grey: number };
  activeProjectsWithCriticalActivities: number;
  openRisksCritical: number;
  budgetByCurrency: CurrencyBudget[];
  spentByCurrency: CurrencyBudget[];
  committedByCurrency: CurrencyBudget[];
  avgPercentComplete: number;
}

export interface DelayedProjectRow {
  projectId: string;
  projectCode: string;
  projectName: string;
  plannedFinish: string | null;
  forecastFinish: string | null;
  daysDelayed: number;
  spi: number;
  rag: string;
}

export interface CostOverrunRow {
  projectId: string;
  projectCode: string;
  projectName: string;
  bacCrores: number;
  eacCrores: number;
  varianceCrores: number;
  cpi: number;
  budgetCurrency: string | null;
}

export interface FundingUtilizationRow {
  projectId: string;
  projectName: string;
  totalSanctionedCrores: number;
  totalReleasedCrores: number;
  totalUtilizedCrores: number;
  pendingWithTreasuryCrores: number;
  releasePct: number;
  utilizationPct: number;
  fundingStatus: string;
  currency: string | null;
}

export interface ContractorLeagueRow {
  contractorCode: string;
  contractorName: string;
  activeProjects: number;
  avgPerformance: number;
  avgSpi: number;
  avgCpi: number;
  totalContractValueCrores: number;
  totalRaBillsCrores: number;
}

export interface RiskHeatmapCell {
  probability: number;
  impact: number;
  count: number;
}

export interface RiskHeatmapTopRisk {
  riskId: string;
  projectId: string | null;
  projectCode: string;
  code: string;
  title: string;
  probability: string;
  impact: string;
  score: number;
  rag: string;
}

export interface RiskHeatmap {
  cells: RiskHeatmapCell[];
  topExposureRisks: RiskHeatmapTopRisk[];
}

export interface CashFlowOutlookPoint {
  yearMonth: string;
  plannedOutflowCrores: number;
  plannedInflowCrores: number;
  netCrores: number;
  cumulativeCrores: number;
  currency: string | null;
}

export interface ComplianceRow {
  projectId: string;
  projectCode: string;
  projectName: string;
  pfmsSanctionOk: boolean | null;
  gstnCheckOk: boolean | null;
  gemLinkedOk: boolean | null;
  cpppPublishedOk: boolean | null;
  pariveshClearanceOk: boolean | null;
  overallScore: number;
}

export interface ScheduleHealthRow {
  projectId: string;
  projectCode: string;
  projectName: string;
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
}

export const portfolioReportApi = {
  getEvmRollup: () =>
    apiClient
      .get<ApiResponse<PortfolioEvmRow[]>>("/v1/portfolio/evm-rollup")
      .then((r) => r.data),

  getScorecard: () =>
    apiClient
      .get<ApiResponse<PortfolioScorecard>>("/v1/portfolio/scorecard")
      .then((r) => r.data.data),

  getDelayedProjects: (limit = 10) =>
    apiClient
      .get<ApiResponse<DelayedProjectRow[]>>(`/v1/portfolio/delayed-projects?limit=${limit}`)
      .then((r) => r.data.data),

  getCostOverrunProjects: (limit = 10) =>
    apiClient
      .get<ApiResponse<CostOverrunRow[]>>(`/v1/portfolio/cost-overrun-projects?limit=${limit}`)
      .then((r) => r.data.data),

  getFundingUtilization: () =>
    apiClient
      .get<ApiResponse<FundingUtilizationRow[]>>("/v1/portfolio/funding-utilization")
      .then((r) => r.data.data),

  getContractorLeague: () =>
    apiClient
      .get<ApiResponse<ContractorLeagueRow[]>>("/v1/portfolio/contractor-league")
      .then((r) => r.data.data),

  getRiskHeatmap: () =>
    apiClient
      .get<ApiResponse<RiskHeatmap>>("/v1/portfolio/risk-heatmap")
      .then((r) => r.data.data),

  getCashFlowOutlook: (months = 12) =>
    apiClient
      .get<ApiResponse<CashFlowOutlookPoint[]>>(`/v1/portfolio/cash-flow-outlook?months=${months}`)
      .then((r) => r.data.data),

  getCompliance: () =>
    apiClient
      .get<ApiResponse<ComplianceRow[]>>("/v1/portfolio/compliance")
      .then((r) => r.data.data),

  getScheduleHealth: () =>
    apiClient
      .get<ApiResponse<ScheduleHealthRow[]>>("/v1/portfolio/schedule-health")
      .then((r) => r.data.data),
};
