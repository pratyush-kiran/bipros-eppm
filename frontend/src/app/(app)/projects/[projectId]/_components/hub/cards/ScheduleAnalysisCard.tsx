"use client";
import { useQuery } from "@tanstack/react-query";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { CardShell } from "./CardShell";
import { Skeleton, StatusPill, cardQueryOpts } from "./_shared";

/**
 * Schedule Analysis card — three mini-tiles fed by the schedule-quality
 * endpoint: overall health %, missing-logic count, hard-constraint count.
 *
 * If the endpoint returns nothing the body collapses to chips only — we never
 * fabricate.
 */
export function ScheduleAnalysisCard({ projectId }: { projectId: string }) {
  const qualityQuery = useQuery({
    queryKey: ["project", projectId, "hub-card", "schedule", "quality"],
    queryFn: () => projectInsightsApi.getScheduleQuality(projectId),
    ...cardQueryOpts,
  });

  const quality = qualityQuery.data;
  const healthPct = quality?.overallHealthPct;

  const statusPill =
    healthPct != null ? (
      <StatusPill
        tone={healthPct >= 85 ? "success" : healthPct >= 70 ? "warn" : "danger"}
      >
        Health {Math.round(healthPct)}%
      </StatusPill>
    ) : undefined;

  return (
    <CardShell
      groupId="schedule"
      title="Schedule Analysis"
      subtitle="How the network holds together"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={qualityQuery.isLoading}
    >
      {qualityQuery.isLoading ? (
        <div className="grid grid-cols-3 gap-2">
          <Skeleton className="h-14" />
          <Skeleton className="h-14" />
          <Skeleton className="h-14" />
        </div>
      ) : quality ? (
        <div className="grid grid-cols-3 gap-2">
          {healthPct != null ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Health
              </div>
              <div className="mt-1 font-mono text-lg text-text-primary tabular-nums">
                {Math.round(healthPct)}%
              </div>
            </div>
          ) : null}
          {quality.missingLogicCount != null ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Logic gaps
              </div>
              <div className="mt-1 font-mono text-lg text-text-primary tabular-nums">
                {quality.missingLogicCount}
              </div>
            </div>
          ) : null}
          {quality.hardConstraintsCount != null ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Hard constraints
              </div>
              <div className="mt-1 font-mono text-lg text-text-primary tabular-nums">
                {quality.hardConstraintsCount}
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
    </CardShell>
  );
}
