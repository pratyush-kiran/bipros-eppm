import { apiClient } from '@/lib/api/client'
import type { ApiResponse } from '@/lib/types'

export interface DashboardConfig {
  id: string
  tier: 'EXECUTIVE' | 'PROGRAMME' | 'OPERATIONAL' | 'FIELD'
  layoutConfig: Record<string, unknown>
}

export interface KpiDefinition {
  id: string
  name: string
  code: string
  formula: string
  unit: string
  greenThreshold?: number
  amberThreshold?: number
  redThreshold?: number
  moduleSource: string
  isActive: boolean
}

export interface KpiSnapshot {
  id: string
  kpiDefinitionId: string
  projectId: string
  value: number
  status: 'GREEN' | 'AMBER' | 'RED'
  calculatedAt: string
}

/** Matches the Resource Utilisation KPI definition by code
 *  (RESOURCE_UTIL / RESOURCE_UTILIZATION). The hub People & Capacity card and
 *  the Quick Access "capacity" badge read its snapshot for the utilisation %. */
export const isUtilisationKpiCode = (code?: string): boolean =>
  !!code && code.toUpperCase().includes('RESOURCE_UTIL')

export interface CreateKpiDefinitionRequest {
  name: string
  code: string
  formula: string
  unit: string
  greenThreshold?: number
  amberThreshold?: number
  redThreshold?: number
  moduleSource: string
  isActive: boolean
}

export interface FieldActiveSite {
  activityId: string
  activityName: string
  status: string
  workers: number
  equipment: number
  safetyIncidents: number
}

export interface FieldDailyWorklog {
  date: string
  headCount: number
  equipmentCount: number
  operatingHours: number
}

export interface FieldSummary {
  projectId: string
  asOfDate: string | null
  workersOnSite: number
  equipmentDeployed: number
  operatingHours4d: number
  safetyIncidents: number
  activeSites: FieldActiveSite[]
  dailyWorklogs: FieldDailyWorklog[]
  /** Stock Availability % = OK rows ÷ total tracked materials. */
  stockAvailabilityPct: number
  /** Re-Order Level Breach Count = LOW + CRITICAL stock rows. */
  reorderBreachCount: number
  stockTrackedMaterialCount: number
}

export const dashboardApi = {
  // Dashboard Config
  getDashboardByTier: (tier: string) =>
    apiClient.get<DashboardConfig>(`/v1/dashboards/${tier}`),

  getFieldSummary: (projectId: string, asOfDate?: string) => {
    const url = asOfDate
      ? `/v1/projects/${projectId}/dashboards/field/summary?asOfDate=${asOfDate}`
      : `/v1/projects/${projectId}/dashboards/field/summary`
    // Backend wraps payloads in ApiResponse<T>; unwrap once here so callers don't
    // double-unwrap. Pattern matches manpowerKpiApi / equipmentKpiApi.
    return apiClient
      .get<ApiResponse<FieldSummary>>(url)
      .then((r) => r.data)
  },

  // KPI Snapshots by Tier
  getKpisByTierAndProject: (tier: string, projectId?: string) => {
    const url = projectId
      ? `/v1/dashboards/${tier}/kpis?projectId=${projectId}`
      : `/v1/dashboards/${tier}/kpis`
    return apiClient.get<KpiSnapshot[]>(url)
  },

  // KPI Definitions
  getKpiDefinitions: () =>
    apiClient.get<KpiDefinition[]>(`/v1/dashboards/kpi-definitions`),

  createKpiDefinition: (request: CreateKpiDefinitionRequest) =>
    apiClient.post<KpiDefinition>(`/v1/dashboards/kpi-definitions`, request),

  // Project-specific KPIs
  getProjectKpiSnapshots: (projectId: string) =>
    apiClient.get<KpiSnapshot[]>(`/v1/dashboards/projects/${projectId}/kpi-snapshots`),

  calculateProjectKpis: (projectId: string) =>
    apiClient.post<KpiSnapshot[]>(`/v1/dashboards/projects/${projectId}/kpi-snapshots/calculate`, {}),
}
