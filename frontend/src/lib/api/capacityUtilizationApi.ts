import { apiClient } from "./client";
import type { ApiResponse } from "../types";

export type CapacityGroupBy = "RESOURCE_TYPE" | "RESOURCE" | "ROLE";
export type CapacityNormType = "MANPOWER" | "EQUIPMENT";
export type BudgetedSource =
  | "VARIANT"
  | "ROLE"
  | "UNSCOPED"
  | "MIXED"
  | "SPECIFIC_RESOURCE"
  | "RESOURCE_TYPE"
  | "WORK_ACTIVITY"
  | "RESOURCE_LEGACY"
  | "NONE";

/** New role-period shape — SC180 columns (Budget · Planned · Actual · %Util · Cost). */
export interface RolePeriod {
  qty: number | null;
  budgetDays: number | null;
  budgetNos: number | null;
  plannedDays: number | null;
  plannedNos: number | null;
  actualDays: number | null;
  actualNos: number | null;
  /**
   * Portion of actualDays that landed on activities whose linked Work Activity has no norm for
   * this role's type — surfaced separately so the user sees util% reflects only the tracked
   * portion, not the role's full deployment.
   */
  actualDaysUntracked: number | null;
  utilizationPct: number | null;
  costImplication: number | null;
}

export interface CapacityRoleRow {
  roleId: string;
  roleCode: string | null;
  roleName: string | null;
  ratePerDay: number | null;
  forTheDay: RolePeriod | null;
  forTheMonth: RolePeriod | null;
  cumulative: RolePeriod | null;
  normSource: BudgetedSource;
}

export interface CapacitySection {
  rows: CapacityRoleRow[];
  totalForTheDay: RolePeriod | null;
  totalForTheMonth: RolePeriod | null;
  totalCumulative: RolePeriod | null;
}

/** @deprecated Legacy flat-row shape — kept for older consumers; new code uses {@link CapacitySection}. */
export interface CapacityPeriod {
  qty: number | null;
  budgetedDays: number | null;
  actualDays: number | null;
  actualOutputPerDay: number | null;
  utilizationPct: number | null;
}

/** @deprecated Legacy row shape; new code consumes {@link CapacitySection}. */
export interface CapacityUtilizationRow {
  groupKey: {
    resourceTypeDefId: string | null;
    resourceId: string | null;
    displayLabel: string;
  };
  workActivity: {
    id: string;
    code: string;
    name: string;
    defaultUnit: string | null;
  } | null;
  budgeted: {
    outputPerDay: number | null;
    source: BudgetedSource;
  };
  forTheDay: CapacityPeriod;
  forTheMonth: CapacityPeriod;
  cumulative: CapacityPeriod;
}

export interface CapacityUtilizationReport {
  projectId: string;
  fromDate: string | null;
  toDate: string | null;
  workDays: number;
  manpower: CapacitySection | null;
  equipment: CapacitySection | null;
  /** @deprecated Legacy fields retained so the older consumers keep working. */
  groupBy: CapacityGroupBy;
  /** @deprecated. */
  normType: CapacityNormType | null;
  /** @deprecated Legacy flat rows. New code reads {@link manpower} / {@link equipment}. */
  rows: CapacityUtilizationRow[];
}

export interface GetCapacityUtilizationParams {
  projectId: string;
  fromDate?: string;
  toDate?: string;
  groupBy?: CapacityGroupBy;
  normType?: CapacityNormType;
  /**
   * User UUID (carrying a supervisor role). Sourced from
   * {@code userApi.listByRoles([...])}. Phase 091 dropped
   * {@code daily_progress_reports.supervisor_resource_id}, so this is the only
   * filter the backend accepts.
   */
  supervisorUserId?: string;
}

// ───────────────── Supervisor Performance (SC180-style rollup) ──────────────────

export interface TradeRollup {
  tradeKey: string;
  tradeLabel: string;
  mmRate: number | null;
  budgetedManDays: number | null;
  budgetedNos: number | null;
  actualManDays: number | null;
  actualNos: number | null;
  utilizationPct: number | null;
  costImplication: number | null;
  normSource: BudgetedSource;
}

export interface EquipmentRollup {
  equipmentKey: string;
  equipmentLabel: string;
  hourRate: number | null;
  budgetedDays: number | null;
  budgetedNos: number | null;
  actualDays: number | null;
  actualNos: number | null;
  utilizationPct: number | null;
  costImplication: number | null;
  normSource: BudgetedSource;
}

export interface ProductivityNorms {
  budget: number | null;
  projection: number | null;
  actualsFtm: number | null;
  normSource: BudgetedSource;
}

export interface PlannedActuals {
  qty: number | null;
  budgetDays: number | null;
  days: number | null;
  utilizationPct: number | null;
}

export interface ResourceLine {
  kind: "MANPOWER" | "EQUIPMENT";
  resourceKey: string;
  resourceLabel: string;
  norms: ProductivityNorms;
  planMonth: PlannedActuals;
  actualMonth: PlannedActuals;
}

export interface ActivityDrillDown {
  activityId: string;
  activityCode: string | null;
  activityName: string;
  unit: string | null;
  qtyForMonth: number | null;
  resources: ResourceLine[];
  remarks: string | null;
}

export interface SupervisorPerformanceReport {
  projectId: string;
  /**
   * Phase 4.4 rename — User UUID (carrying a supervisor role). The DTO field on the
   * backend was renamed from {@code supervisorResourceId}; the JSON key on the wire
   * is now {@code supervisorUserId}.
   */
  supervisorUserId: string | null;
  supervisorName: string | null;
  fromDate: string;
  toDate: string;
  workDays: number;
  summary: {
    manpower: TradeRollup[];
    equipment: EquipmentRollup[];
  };
  activities: ActivityDrillDown[];
}

export interface SupervisorPerformanceComparison {
  projectId: string;
  fromDate: string;
  toDate: string;
  workDays: number;
  reports: SupervisorPerformanceReport[];
  tradeDeltas: Array<{
    tradeKey: string;
    tradeLabel: string;
    bySupervisor: Record<string, TradeRollup>;
    bestUtilizationPct: number | null;
    bestSupervisorId: string | null;
  }>;
  equipmentDeltas: Array<{
    equipmentKey: string;
    equipmentLabel: string;
    bySupervisor: Record<string, EquipmentRollup>;
    bestUtilizationPct: number | null;
    bestSupervisorId: string | null;
  }>;
}

export interface SupervisorOption {
  /**
   * User UUID. The {@code /dpr/supervisors-used} endpoint surfaces this JSON
   * field; Phase 091 dropped the legacy Resource-FK source.
   */
  supervisorUserId: string;
  supervisorCode: string | null;
  supervisorName: string;
  dprCount: number;
}

export interface GetSupervisorPerformanceParams {
  projectId: string;
  /** User UUID. Phase 091 dropped the legacy Resource-FK supervisor filter. */
  supervisorUserId?: string;
  fromDate?: string;
  toDate?: string;
  workDays?: number;
}

export interface CompareSupervisorPerformanceParams {
  projectId: string;
  /** Phase 4.4 rename — array of User UUIDs. */
  supervisorUserIds: string[];
  fromDate?: string;
  toDate?: string;
  workDays?: number;
}

// ───────────────── Multi-period aggregate ──────────────────

export type AggregatePeriodType = "WEEKLY" | "MONTHLY";
export type AggregateGroupBy = "ROLE" | "RESOURCE_TYPE";

export interface CapacityAggregateBucket {
  from: string;
  to: string;
  label: string;
  manpower: CapacitySection | null;
  equipment: CapacitySection | null;
}

export interface CapacityUtilizationAggregateReport {
  projectId: string;
  periodType: AggregatePeriodType;
  groupBy: AggregateGroupBy;
  fromDate: string;
  toDate: string;
  buckets: CapacityAggregateBucket[];
}

export interface GetCapacityUtilizationAggregateParams {
  projectId: string;
  periodType: AggregatePeriodType;
  from?: string;
  to?: string;
  groupBy?: AggregateGroupBy;
}

export const capacityUtilizationApi = {
  get: (params: GetCapacityUtilizationParams) => {
    const qs: string[] = [`projectId=${params.projectId}`];
    if (params.fromDate) qs.push(`fromDate=${params.fromDate}`);
    if (params.toDate) qs.push(`toDate=${params.toDate}`);
    if (params.groupBy) qs.push(`groupBy=${params.groupBy}`);
    if (params.normType) qs.push(`normType=${params.normType}`);
    if (params.supervisorUserId)
      qs.push(`supervisorUserId=${params.supervisorUserId}`);
    return apiClient
      .get<ApiResponse<CapacityUtilizationReport>>(`/v1/reports/capacity-utilization?${qs.join("&")}`)
      .then((r) => r.data);
  },

  getSupervisorPerformance: (params: GetSupervisorPerformanceParams) => {
    const qs: string[] = [`projectId=${params.projectId}`];
    if (params.supervisorUserId)
      qs.push(`supervisorUserId=${params.supervisorUserId}`);
    if (params.fromDate) qs.push(`fromDate=${params.fromDate}`);
    if (params.toDate) qs.push(`toDate=${params.toDate}`);
    if (params.workDays) qs.push(`workDays=${params.workDays}`);
    return apiClient
      .get<ApiResponse<SupervisorPerformanceReport>>(
        `/v1/reports/supervisor-performance?${qs.join("&")}`,
      )
      .then((r) => r.data);
  },

  compareSupervisorPerformance: (params: CompareSupervisorPerformanceParams) => {
    const qs: string[] = [
      `projectId=${params.projectId}`,
      `supervisorUserIds=${params.supervisorUserIds.join(",")}`,
    ];
    if (params.fromDate) qs.push(`fromDate=${params.fromDate}`);
    if (params.toDate) qs.push(`toDate=${params.toDate}`);
    if (params.workDays) qs.push(`workDays=${params.workDays}`);
    return apiClient
      .get<ApiResponse<SupervisorPerformanceComparison>>(
        `/v1/reports/supervisor-performance/compare?${qs.join("&")}`,
      )
      .then((r) => r.data);
  },

  getAggregate: (params: GetCapacityUtilizationAggregateParams) => {
    const qs: string[] = [
      `projectId=${params.projectId}`,
      `periodType=${params.periodType}`,
    ];
    if (params.from) qs.push(`from=${params.from}`);
    if (params.to) qs.push(`to=${params.to}`);
    if (params.groupBy) qs.push(`groupBy=${params.groupBy}`);
    return apiClient
      .get<ApiResponse<CapacityUtilizationAggregateReport>>(
        `/v1/reports/capacity-utilization/aggregate?${qs.join("&")}`,
      )
      .then((r) => r.data);
  },

  getSupervisorsUsed: (params: {
    projectId: string;
    fromDate?: string;
    toDate?: string;
  }) => {
    const qs: string[] = [];
    if (params.fromDate) qs.push(`fromDate=${params.fromDate}`);
    if (params.toDate) qs.push(`toDate=${params.toDate}`);
    const tail = qs.length ? `?${qs.join("&")}` : "";
    return apiClient
      .get<ApiResponse<SupervisorOption[]>>(
        `/v1/projects/${params.projectId}/dpr/supervisors-used${tail}`,
      )
      .then((r) => r.data);
  },
};
