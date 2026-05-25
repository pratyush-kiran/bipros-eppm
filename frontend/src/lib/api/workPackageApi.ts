import { apiClient } from "./client";
import type { ApiResponse, WbsPhase, WbsType } from "../types";

export type WorkPackageStatus =
  | "DONE"
  | "IN_PROGRESS"
  | "NOT_STARTED"
  | "PLANNED"
  | "DELAYED"
  | "AT_RISK";

/**
 * Flat per-work-package row returned by {@code GET /v1/projects/{id}/work-packages}.
 * Every aggregate is nullable so the UI can render "—" when the underlying activity or
 * EVM record is missing.
 */
export interface WorkPackageRowResponse {
  wbsNodeId: string;
  code: string;
  name: string;
  parentId: string | null;
  parentName: string | null;
  wbsType: WbsType | null;
  wbsLevel: number | null;
  phase: WbsPhase | null;

  contractorOrganisationId: string | null;
  contractorName: string | null;

  derivedPlannedStart: string | null;
  derivedPlannedFinish: string | null;
  derivedDurationDays: number | null;

  weightedPercentComplete: number | null;
  daysBehindSchedule: number | null;

  budgetCrores: number | null;

  activityCountTotal: number | null;
  activityCountDone: number | null;
  activityCountInProgress: number | null;
  activityCountNotStarted: number | null;
  activityCountDelayed: number | null;

  bac: number | null;
  plannedValue: number | null;
  earnedValue: number | null;
  actualCost: number | null;
  scheduleVariance: number | null;
  costVariance: number | null;
  spi: number | null;
  cpi: number | null;
  eac: number | null;
  vac: number | null;

  minTotalFloat: number | null;
  onCriticalPath: boolean | null;

  derivedStatus: WorkPackageStatus;
}

export const workPackageApi = {
  listWorkPackages: (projectId: string) =>
    apiClient
      .get<ApiResponse<WorkPackageRowResponse[]>>(
        `/v1/projects/${projectId}/work-packages`,
      )
      .then((r) => r.data),
};
