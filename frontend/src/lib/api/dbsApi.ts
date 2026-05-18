import { apiClient } from "./client";
import type { ApiResponse } from "../types";

/**
 * Phase C — Daily Balance Sheet (DBS) API client.
 *
 * DBS rolls every operational source (DPR, Daily Resource Deployment, Material
 * Consumption) up the project-team chain: Supervisor → Engineer → PM. The backend
 * persists incremental aggregates in three tables (`dbs_daily_supervisor`,
 * `dbs_daily_engineer`, `dbs_daily_project`) and exposes them through a single
 * `DbsController` under `/v1/projects/{projectId}/dbs`. Period rollups (WEEK /
 * MONTH) are computed on the fly by SUM over the daily rows so late edits stay
 * consistent without cascade.
 *
 * Every response is wrapped in `ApiResponse<T>` (see `bipros-common` envelope).
 */

export type DbsPeriodType = "DAY" | "WEEK" | "MONTH";

export interface DbsSectionLine {
  description: string;
  unit?: string;
  rate?: number;
  quantity?: number;
  totalAmount: number;
}

export interface DbsSupervisorDayResponse {
  id?: string;
  projectId: string;
  supervisorUserId?: string;
  supervisorName?: string;
  engineerUserId?: string;
  reportDate: string;
  materialAmount: number;
  manpowerAmount: number;
  adminAmount: number;
  machineryAmount: number;
  fuelAmount: number;
  subcontractAmount: number;
  boqForTheDayAmount: number;
  boqPlannedAmount: number;
  boqAchievedAmount: number;
  totalExpense: number;
  totalIncome: number;
  contribution: number;
  contributionPct: number;
  materialLines: DbsSectionLine[];
  manpowerLines: DbsSectionLine[];
  adminLines: DbsSectionLine[];
  machineryLines: DbsSectionLine[];
  fuelLines: DbsSectionLine[];
  boqLines: DbsSectionLine[];
  subcontractLines: DbsSectionLine[];
  recomputedAt?: string;
}

export interface DbsEngineerDayResponse {
  id?: string;
  projectId: string;
  engineerUserId?: string;
  reportDate: string;
  supervisorIds: string[];
  materialAmount: number;
  manpowerAmount: number;
  adminAmount: number;
  machineryAmount: number;
  fuelAmount: number;
  subcontractAmount: number;
  boqForTheDayAmount: number;
  boqPlannedAmount: number;
  boqAchievedAmount: number;
  totalExpense: number;
  totalIncome: number;
  contribution: number;
  contributionPct: number;
  recomputedAt?: string;
}

export interface DbsProjectDayResponse {
  id?: string;
  projectId: string;
  reportDate: string;
  engineerIds: string[];
  supervisorCount: number;
  dprCount: number;
  materialAmount: number;
  manpowerAmount: number;
  adminAmount: number;
  machineryAmount: number;
  fuelAmount: number;
  subcontractAmount: number;
  boqForTheDayAmount: number;
  boqPlannedAmount: number;
  boqAchievedAmount: number;
  totalExpense: number;
  totalIncome: number;
  contribution: number;
  contributionPct: number;
  cumulativeExpense?: number;
  cumulativeIncome?: number;
  cumulativeContribution?: number;
  recomputedAt?: string;
  alerts?: DbsAlertCode[];
}

/**
 * Soft health-check codes evaluated by `DbsAlertEvaluator`. Surfaced on the PM
 * tab as a coloured banner above the totals panel. Backend returns plain strings;
 * we narrow to the known set so the renderer can map to colour tokens.
 */
export type DbsAlertCode =
  | "LOW_CONTRIBUTION_PCT"
  | "NEGATIVE_CONTRIBUTION"
  | "RUNAWAY_FUEL"
  | "MISSING_RATE_DATA";

export interface DbsSupervisorSummaryDto {
  supervisorUserId?: string;
  supervisorName?: string;
  totalExpense: number;
  totalIncome: number;
  contribution: number;
  contributionPct: number;
  dprCount: number;
}

export interface DbsSupervisorPeriodResponse {
  periodType: DbsPeriodType;
  from: string;
  to: string;
  totals: DbsSupervisorDayResponse;
  dailyRows: DbsSupervisorDayResponse[];
}

export interface DbsEngineerPeriodResponse {
  periodType: DbsPeriodType;
  from: string;
  to: string;
  totals: DbsEngineerDayResponse;
  dailyRows: DbsEngineerDayResponse[];
}

export interface DbsProjectPeriodResponse {
  periodType: DbsPeriodType;
  from: string;
  to: string;
  totals: DbsProjectDayResponse;
  dailyRows: DbsProjectDayResponse[];
}

const base = (projectId: string) => `/v1/projects/${projectId}/dbs`;

export const dbsApi = {
  getSupervisorDay: (projectId: string, supervisorUserId: string, date: string) =>
    apiClient
      .get<ApiResponse<DbsSupervisorDayResponse>>(
        `${base(projectId)}/supervisor/${supervisorUserId}`,
        { params: { date } },
      )
      .then((r) => r.data),

  getSupervisorPeriod: (
    projectId: string,
    supervisorUserId: string,
    date: string,
    periodType: DbsPeriodType,
  ) =>
    apiClient
      .get<ApiResponse<DbsSupervisorPeriodResponse>>(
        `${base(projectId)}/supervisor/${supervisorUserId}`,
        { params: { date, periodType } },
      )
      .then((r) => r.data),

  getEngineerDay: (projectId: string, engineerUserId: string, date: string) =>
    apiClient
      .get<ApiResponse<DbsEngineerDayResponse>>(
        `${base(projectId)}/engineer/${engineerUserId}`,
        { params: { date } },
      )
      .then((r) => r.data),

  getEngineerPeriod: (
    projectId: string,
    engineerUserId: string,
    date: string,
    periodType: DbsPeriodType,
  ) =>
    apiClient
      .get<ApiResponse<DbsEngineerPeriodResponse>>(
        `${base(projectId)}/engineer/${engineerUserId}`,
        { params: { date, periodType } },
      )
      .then((r) => r.data),

  getProjectDay: (projectId: string, date: string) =>
    apiClient
      .get<ApiResponse<DbsProjectDayResponse>>(`${base(projectId)}/project`, {
        params: { date },
      })
      .then((r) => r.data),

  getProjectPeriod: (
    projectId: string,
    date: string,
    periodType: DbsPeriodType,
  ) =>
    apiClient
      .get<ApiResponse<DbsProjectPeriodResponse>>(`${base(projectId)}/project`, {
        params: { date, periodType },
      })
      .then((r) => r.data),

  listSupervisorsForDay: (projectId: string, date: string) =>
    apiClient
      .get<ApiResponse<DbsSupervisorSummaryDto[]>>(
        `${base(projectId)}/supervisors`,
        { params: { date } },
      )
      .then((r) => r.data),

  /**
   * Fetches the active alert codes (e.g. NEGATIVE_CONTRIBUTION, RUNAWAY_FUEL) for
   * the project rollup on a given date. Returns an empty array when no alerts fire.
   * Alerts are also embedded on `DbsProjectDayResponse.alerts`, but the dedicated
   * endpoint lets the banner render without re-fetching the full DBS payload.
   */
  getAlerts: (projectId: string, date: string) =>
    apiClient
      .get<ApiResponse<DbsAlertCode[]>>(`${base(projectId)}/alerts`, {
        params: { date },
      })
      .then((r) => r.data),

  recompute: (projectId: string, date: string) =>
    apiClient
      .post<ApiResponse<void>>(`${base(projectId)}/recompute`, null, {
        params: { date },
      })
      .then((r) => r.data),

  recomputeRange: (projectId: string, from: string, to: string) =>
    apiClient
      .post<ApiResponse<void>>(`${base(projectId)}/recompute-range`, null, {
        params: { from, to },
      })
      .then((r) => r.data),

  /**
   * Trigger a browser download of the DBS Excel workbook.
   *
   * The backend export endpoint returns the raw XLSX bytes (no `ApiResponse` envelope),
   * so we fetch as a blob and synthesize a temporary anchor click to push the file
   * into the user's downloads folder. Filename is server-suggested via
   * `Content-Disposition`, but axios doesn't expose that header reliably across CORS
   * so we recompute a sensible filename on the client.
   */
  downloadExcel: async (
    projectId: string,
    date: string,
    level: "PM" | "SUPERVISOR" = "PM",
    supervisorUserId?: string,
  ) => {
    const params: Record<string, string> = { date, level };
    if (level === "SUPERVISOR" && supervisorUserId) {
      params.supervisorUserId = supervisorUserId;
    }
    const res = await apiClient.get<Blob>(`${base(projectId)}/export.xlsx`, {
      params,
      responseType: "blob",
    });
    triggerBlobDownload(res.data, `dbs-${date}-${level}.xlsx`);
  },

  /**
   * Trigger a browser download of the DBS PDF.
   * Same shape as {@link dbsApi.downloadExcel}, but produces a PDF blob.
   */
  downloadPdf: async (
    projectId: string,
    date: string,
    level: "PM" | "SUPERVISOR" = "PM",
    supervisorUserId?: string,
  ) => {
    const params: Record<string, string> = { date, level };
    if (level === "SUPERVISOR" && supervisorUserId) {
      params.supervisorUserId = supervisorUserId;
    }
    const res = await apiClient.get<Blob>(`${base(projectId)}/export.pdf`, {
      params,
      responseType: "blob",
    });
    triggerBlobDownload(res.data, `dbs-${date}-${level}.pdf`);
  },
};

/**
 * Synthesizes an anchor click to push a blob into the user's download manager.
 * Revokes the object URL on the next tick so we don't leak the blob.
 */
function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Defer revoke so the browser has a chance to start the download stream.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
