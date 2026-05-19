import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface FinancialPeriod {
  id: string;
  name: string;
  startDate: string;
  endDate: string;
  periodType: string | null;
  isClosed: boolean;
  sortOrder: number | null;
}

export interface StorePeriodPerformance {
  id: string;
  projectId: string;
  financialPeriodId: string;
  activityId: string | null;
  actualLaborCost: number | null;
  actualNonlaborCost: number | null;
  actualMaterialCost: number | null;
  actualExpenseCost: number | null;
  actualLaborUnits: number | null;
  actualNonlaborUnits: number | null;
  actualMaterialUnits: number | null;
  earnedValueCost: number | null;
  plannedValueCost: number | null;
}

export interface CreateStorePeriodPerformanceRequest {
  projectId: string;
  financialPeriodId: string;
  activityId?: string | null;
  actualLaborCost?: number | null;
  actualNonlaborCost?: number | null;
  actualMaterialCost?: number | null;
  actualExpenseCost?: number | null;
  actualLaborUnits?: number | null;
  actualNonlaborUnits?: number | null;
  actualMaterialUnits?: number | null;
  earnedValueCost?: number | null;
  plannedValueCost?: number | null;
}

export interface PeriodPerformanceRollup {
  periodId: string;
  periodName: string;
  periodType: string | null;
  startDate: string;
  endDate: string;
  actualCost: number;
  plannedValue: number;
  earnedValue: number;
  cv: number;
  sv: number;
  cpi: number | null;
  spi: number | null;
}

export const periodPerformanceApi = {
  getAllFinancialPeriods: () =>
    apiClient
      .get<ApiResponse<FinancialPeriod[]>>("/v1/financial-periods")
      .then((r) => r.data),

  getOpenFinancialPeriods: () =>
    apiClient
      .get<ApiResponse<FinancialPeriod[]>>("/v1/financial-periods/open")
      .then((r) => r.data),

  getProjectPeriodPerformance: (projectId: string) =>
    apiClient
      .get<ApiResponse<StorePeriodPerformance[]>>(`/v1/projects/${projectId}/spp`)
      .then((r) => r.data),

  getProjectLevelPerformance: (projectId: string, periodId: string) =>
    apiClient
      .get<ApiResponse<StorePeriodPerformance>>(`/v1/projects/${projectId}/spp/${periodId}`)
      .then((r) => r.data),

  createStorePeriodPerformance: (
    projectId: string,
    data: CreateStorePeriodPerformanceRequest
  ) =>
    apiClient
      .post<ApiResponse<StorePeriodPerformance>>(`/v1/projects/${projectId}/spp`, data)
      .then((r) => r.data),

  deleteStorePeriodPerformance: (projectId: string, sppId: string) =>
    apiClient
      .delete<ApiResponse<void>>(`/v1/projects/${projectId}/spp/${sppId}`)
      .then((r) => r.data),

  getPerformanceRollup: (projectId: string, periodType: "D" | "W" | "M") =>
    apiClient
      .get<ApiResponse<PeriodPerformanceRollup[]>>(
        `/v1/projects/${projectId}/performance`,
        { params: { periodType } },
      )
      .then((r) => r.data),
};
