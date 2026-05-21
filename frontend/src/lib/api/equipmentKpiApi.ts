import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export interface UtilizationRow {
  resourceId: string;
  resourceCode: string;
  resourceName: string;
  operatingHours: number;
  idleHours: number;
  breakdownHours: number;
  utilizationPct: number;
  mechanicalAvailabilityPct: number;
  /** KPI 7.1 — idle_hours × Resource.cost_per_unit. Single rate across OWNED/HIRED in Phase 2A. */
  idleCost: number;
}

export interface IdleAlertRow {
  resourceId: string;
  resourceCode: string;
  resourceName: string;
  logDate: string;
  idleHours: number;
}

export interface FuelPerOutputRow {
  resourceId: string;
  resourceCode: string;
  resourceName: string;
  fuelConsumed: number;
  qtyExecuted: number;
  fuelPerOutput: number;
}

export interface AvailabilityPerformanceRow {
  resourceId: string;
  resourceCode: string;
  resourceName: string;
  availability: number;
  performance: number;
  /** KPI 6.2 — attributed_qty ÷ Σ working_hours per machine. */
  outputRatePerHour: number;
}

export interface OwnedRentedSlice {
  ownershipType: string;
  operatingHours: number;
  cost: number;
}

export interface ServiceDueRow {
  resourceId: string;
  resourceCode: string;
  resourceName: string;
  nextServiceDate: string;
  daysUntilService: number;
}

export interface EquipmentKpiResponse {
  projectId: string;
  from: string;
  to: string;
  utilization: UtilizationRow[];
  idleAlerts: IdleAlertRow[];
  fuelPerOutput: FuelPerOutputRow[];
  availabilityPerformance: AvailabilityPerformanceRow[];
  ownedVsRented: OwnedRentedSlice[];
  serviceDue: ServiceDueRow[];
  /** Always 0 in nos × rate model — kept for back-compat. */
  mechanicalAvailabilityPct: number;
  equipmentProductivityIndexPct: number;
  /** Always 0 in nos × rate model — kept for back-compat. */
  idleMachineCostTotal: number;
  /** Σ DPR nos for equipment in window. */
  actualNos: number;
  /** Σ headcount across equipment assignments. */
  plannedNos: number;
  /** actualNos ÷ plannedNos, capped at 1.0. Replaces the old hours-based Avg Utilisation %. */
  nosUtilizationPct: number;
}

export const equipmentKpiApi = {
  getKpis: (projectId: string, from: string, to: string) =>
    apiClient
      .get<ApiResponse<EquipmentKpiResponse>>(
        `/v1/projects/${projectId}/kpis/equipment`,
        { params: { from, to } },
      )
      .then((r) => r.data),
};
