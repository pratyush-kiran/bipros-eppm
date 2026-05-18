"use client";

import { useQuery } from "@tanstack/react-query";
import {
  workActivityApi,
  type ProductivityCoverageResponse,
  type WorkActivityResponse,
} from "@/lib/api/workActivityApi";

export type ActivityMasterStatusState = "OK" | "LINKED_NO_NORMS" | "UNLINKED" | "LOADING";

export interface ActivityMasterStatus {
  state: ActivityMasterStatusState;
  master: WorkActivityResponse | null;
  coverage: ProductivityCoverageResponse | null;
}

/**
 * Resolves the productivity-readiness of a Project Activity:
 *   UNLINKED        — no master Work Activity is mapped; productivity norms cannot resolve at DPR
 *                     time and Capacity Utilization will skip the activity.
 *   LINKED_NO_NORMS — master is mapped but `productivity-coverage` summary is NONE (or the
 *                     coverage endpoint failed for this id, which we treat the same so the
 *                     planner still sees a remediation path).
 *   OK              — master is mapped and at least one productivity norm exists.
 *
 * Query keys are deliberately the same ones the existing `WorkActivityCoverageChip` and the
 * activity create form already use — picks up their warm caches.
 */
export function useActivityMasterStatus(
  workActivityId: string | null | undefined,
): ActivityMasterStatus {
  const { data: workActivitiesData, isLoading: loadingMasters } = useQuery({
    queryKey: ["work-activities", "active"],
    queryFn: () => workActivityApi.list(true),
  });

  const {
    data: coverageData,
    isLoading: loadingCoverage,
    isError: coverageErrored,
  } = useQuery({
    queryKey: ["work-activity-coverage", workActivityId],
    queryFn: () => workActivityApi.productivityCoverage(workActivityId!),
    enabled: !!workActivityId,
    // Don't retry forever on a 404 (stale workActivityId pointing at a deleted master) —
    // surface the "linked but unhealthy" state to the UI quickly.
    retry: 1,
  });

  if (!workActivityId) {
    return { state: "UNLINKED", master: null, coverage: null };
  }

  const master =
    workActivitiesData?.data?.find((w) => w.id === workActivityId) ?? null;
  const coverage = coverageData?.data ?? null;

  // Coverage query failed (e.g. workActivityId is stale / master was deleted) — treat as
  // LINKED_NO_NORMS so the planner gets the red card with a "Configure / Re-link" path
  // rather than a perpetually-loading panel.
  if (coverageErrored) {
    return { state: "LINKED_NO_NORMS", master, coverage: null };
  }

  if (loadingMasters || loadingCoverage || !coverage) {
    return { state: "LOADING", master, coverage };
  }

  if (coverage.summary === "NONE") {
    return { state: "LINKED_NO_NORMS", master, coverage };
  }
  return { state: "OK", master, coverage };
}
