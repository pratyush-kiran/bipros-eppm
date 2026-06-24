import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface MonthlyProgressData {
  projectName: string;
  projectCode: string;
  period: string;
  totalActivities: number;
  completedActivities: number;
  inProgressActivities: number;
  overallPercentComplete: number;
  budgetAmount: number;
  actualCost: number;
  forecastCost: number;
  totalMilestones: number;
  achievedMilestones: number;
  openRisks: number;
  highRisks: number;
  topDelayedActivities: ActivitySummaryRow[];
}

export interface ActivitySummaryRow {
  code: string;
  name: string;
  status: string;
  delayDays: number;
  plannedFinish: string;
}

export interface EvmReportData {
  projectName: string;
  pv: number; // Planned Value
  ev: number; // Earned Value
  ac: number; // Actual Cost
  bac: number; // Budget at Completion
  spi: number; // Schedule Performance Index
  cpi: number; // Cost Performance Index
  eac: number; // Estimate at Completion
  etc: number; // Estimate to Complete
  vac: number; // Variance at Completion
  tcpi: number; // To Complete Performance Index
}

export interface CashFlowEntry {
  period: string;
  planned: number;
  actual: number;
  forecast: number;
  cumulativePlanned: number;
  cumulativeActual: number;
  cumulativeForecast: number;
}

export interface ContractStatusData {
  projectName: string;
  totalContracts: number;
  activeContracts: number;
  totalContractValue: number;
  totalVoValue: number;
  pendingMilestones: number;
  achievedMilestones: number;
  contracts: ContractSummaryRow[];
}

export interface ContractSummaryRow {
  contractNumber: string;
  contractor: string;
  value: number;
  status: string;
  milestonesPending: number;
}

export interface RiskRegisterData {
  projectName: string;
  totalRisks: number;
  highRisks: number;
  mediumRisks: number;
  lowRisks: number;
  risksByCategory: Record<string, number>;
  topRisks: RiskSummaryRow[];
}

export interface RiskSummaryRow {
  code: string;
  title: string;
  category: string;
  probability: string;
  impact: string;
  score: number;
}

export interface ResourceUtilizationData {
  projectName: string;
  totalResources: number;
  avgUtilization: number;
  resources: ResourceUtilRow[];
}

export interface ResourceUtilRow {
  code: string;
  name: string;
  type: string;
  plannedHours: number;
  actualHours: number;
  utilPct: number;
}

export const reportDataApi = {
  getMonthlyProgress: (projectId: string, period: string) =>
    apiClient
      .get<ApiResponse<MonthlyProgressData>>(
        `/v1/reports/monthly-progress?projectId=${projectId}&period=${period}`
      )
      .then((r) => r.data.data),

  getEvmReport: (projectId: string) =>
    apiClient
      .get<ApiResponse<EvmReportData>>(`/v1/reports/evm?projectId=${projectId}`)
      .then((r) => r.data.data),

  getCashFlowReport: (projectId: string) =>
    apiClient
      .get<ApiResponse<CashFlowEntry[]>>(`/v1/reports/cash-flow?projectId=${projectId}`)
      .then((r) => r.data.data),

  getContractStatus: (projectId: string) =>
    apiClient
      .get<ApiResponse<ContractStatusData>>(`/v1/reports/contract-status?projectId=${projectId}`)
      .then((r) => r.data.data),

  getRiskRegister: (projectId: string) =>
    apiClient
      .get<ApiResponse<RiskRegisterData>>(`/v1/reports/risk-register?projectId=${projectId}`)
      .then((r) => r.data.data),

  getResourceUtilization: (projectId: string) =>
    apiClient
      .get<ApiResponse<ResourceUtilizationData>>(
        `/v1/reports/resource-utilization?projectId=${projectId}`
      )
      .then((r) => r.data.data),

  getWbsProgress: (projectId: string) =>
    apiClient
      .get<ApiResponse<WbsProgressRow[]>>(`/v1/projects/${projectId}/wbs-progress`)
      .then((r) => r.data.data),
};

export interface WbsProgressRow {
  wbsCode: string;
  wbsName: string;
  level: number | null;
  plannedPct: number;
  actualPct: number;
  variancePct: number;
}
