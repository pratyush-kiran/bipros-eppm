"use client";
import { useQuery } from "@tanstack/react-query";
import { dashboardApi } from "@/lib/api/dashboardApi";
import { stretchApi } from "@/lib/api/stretchApi";
import { CardShell } from "./CardShell";
import { Skeleton, StatusPill, cardQueryOpts, unwrapArray } from "./_shared";

interface KpiSnap {
  kpiDefinitionId: string;
  value: number;
  status: string;
}

/**
 * Insights & Map card — a decorative location glyph (purely visual, no data
 * encoded in it) plus, if known, the count of geographic stretches associated
 * with the project. Status pill surfaces the number of AMBER / RED KPI alerts
 * so a stakeholder skimming the hub knows whether to drill in.
 */
export function InsightsMapCard({ projectId }: { projectId: string }) {
  // Share cache with useHubBadges
  const kpiSnapshotsQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "kpi-snapshots"],
    queryFn: () => dashboardApi.getProjectKpiSnapshots(projectId),
    ...cardQueryOpts,
  });

  const stretchesQuery = useQuery({
    queryKey: ["project", projectId, "hub-card", "insights", "stretches"],
    queryFn: () => stretchApi.listByProject(projectId),
    ...cardQueryOpts,
  });

  const snaps = unwrapArray<KpiSnap>(kpiSnapshotsQuery.data);
  const alertCount = snaps
    ? snaps.filter((s) => s.status === "AMBER" || s.status === "RED").length
    : null;

  const stretches = stretchesQuery.data?.data ?? [];
  const stretchCount = stretches.length;

  const statusPill =
    alertCount != null && alertCount > 0 ? (
      <StatusPill tone="warn">{alertCount} new alerts</StatusPill>
    ) : alertCount === 0 ? (
      <StatusPill tone="success">All clear</StatusPill>
    ) : undefined;

  const isLoading = kpiSnapshotsQuery.isLoading || stretchesQuery.isLoading;

  return (
    <CardShell
      groupId="insights-map"
      title="Insights & Map"
      subtitle="Where the project lives + roll-up reports"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={isLoading}
    >
      {isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-3 w-1/2" />
        </div>
      ) : (
        <div className="flex items-center gap-3">
          {/* Decorative map glyph — no data encoded here. */}
          <svg
            viewBox="0 0 48 32"
            className="h-12 w-16 text-amber-600/60"
            aria-hidden="true"
            fill="none"
          >
            <path
              d="M2 6 L16 2 L32 8 L46 4 L46 26 L32 30 L16 24 L2 28 Z"
              className="stroke-current"
              strokeWidth="1"
            />
            <path d="M16 2 V24" className="stroke-current" strokeWidth="0.75" />
            <path d="M32 8 V30" className="stroke-current" strokeWidth="0.75" />
            <circle cx="24" cy="16" r="1.5" className="fill-current" />
          </svg>
          <div className="text-xs text-text-secondary">
            {stretchCount > 0 ? (
              <>
                <span className="font-mono tabular-nums text-text-primary">
                  {stretchCount}
                </span>{" "}
                {stretchCount === 1 ? "stretch" : "stretches"} mapped
              </>
            ) : (
              <span className="text-text-muted">Geographic view available</span>
            )}
          </div>
        </div>
      )}
    </CardShell>
  );
}
