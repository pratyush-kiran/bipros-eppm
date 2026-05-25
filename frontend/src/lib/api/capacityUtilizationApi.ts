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
  /** Of `actualDays`, how many were on activities where THIS role had a resolvable norm but
   *  the allocator suppressed this side (SERIES losing side or SUBSTITUTE redundant side).
   *  The frontend renders a "suppressed by other side" note instead of "not tracking". */
  actualDaysOnHiddenSides?: number | null;
  utilizationPct: number | null;
  costImplication: number | null;
  /** True when at least one of the role's activities in this period had a resolvable norm.
   *  Null on legacy / synthesised rows where the dimension isn't meaningful. */
  normResolved?: boolean | null;
  /** @deprecated Replaced by per-activity hiddenSideNotes on the Section. */
  constrainedDays?: number | null;
  /** @deprecated See {@link constrainedDays}. */
  constrainedBySide?: "MANPOWER" | "EQUIPMENT" | null;
}

/**
 * Per-activity annotation for a side that was suppressed in a SERIES/SUBSTITUTE allocation.
 * The frontend renders one banner per note in the section the activity belongs to.
 */
export interface HiddenSideNote {
  activityId: string;
  workActivityName: string | null;
  /** The side that DID govern (won). */
  governingSide: "MANPOWER" | "EQUIPMENT";
  mode: "SERIES" | "SUBSTITUTE";
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
  /** Activities where this section's side was hidden by the allocator. May be empty or absent. */
  hiddenSideNotes?: HiddenSideNote[];
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
  /** ALLOCATED qty for this trade (per-DPR CapacityAllocator share, not raw DPR qty).
   *  budgetedManDays = qtyDone ÷ productivity norm. */
  qtyDone: number | null;
  budgetedManDays: number | null;
  /** Raw sum of headcount (nos) across DPRs — DAY-basis, hours ignored. Includes tracked +
   *  suppressed + untracked. Util uses (actual − suppressed − untracked) as denominator. */
  actualManDays: number | null;
  /** Portion of actualManDays where this trade's manpower side was suppressed by the allocator
   *  (SERIES/SUBSTITUTE governed by equipment). Norm exists but this side didn't drive output. */
  actualDaysOnHiddenSides?: number | null;
  /** Portion of actualManDays where the trade's norm didn't resolve for the activity. */
  actualDaysUntracked?: number | null;
  utilizationPct: number | null;
  costImplication: number | null;
  normSource: BudgetedSource;
}

export interface EquipmentRollup {
  equipmentKey: string;
  equipmentLabel: string;
  hourRate: number | null;
  qtyDone: number | null;
  budgetedDays: number | null;
  actualDays: number | null;
  /** See {@link TradeRollup.actualDaysOnHiddenSides}. */
  actualDaysOnHiddenSides?: number | null;
  /** See {@link TradeRollup.actualDaysUntracked}. */
  actualDaysUntracked?: number | null;
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
  /** Total activity output for the window — includes sub-contractor share. */
  qtyForMonth: number | null;
  /** Σ sub-contractor qty for this activity in the window. Null when no sub-contractor rows.
   *  Frontend renders "200 Nos (170 own + 30 sub-contractor)" when present. */
  subContractorQty?: number | null;
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
    /** Activities where the manpower side was suppressed by the allocator. May be empty or absent. */
    manpowerHiddenNotes?: HiddenSideNote[];
    /** Activities where the equipment side was suppressed by the allocator. May be empty or absent. */
    equipmentHiddenNotes?: HiddenSideNote[];
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
