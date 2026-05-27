"use client";
import { useQuery } from "@tanstack/react-query";
import { dashboardApi, isUtilisationKpiCode } from "@/lib/api/dashboardApi";
import { projectResourceApi } from "@/lib/api/projectResourceApi";
import { CardShell } from "./CardShell";
import { MetricNumber } from "@/components/hub/mission-control/primitives/MetricNumber";
import { Skeleton, StatusPill, cardQueryOpts, unwrapArray } from "./_shared";

interface KpiDef {
  id: string;
  code: string;
}
interface KpiSnap {
  kpiDefinitionId: string;
  value: number;
  status: string;
}

/**
 * People & Capacity card — capacity utilisation donut + team headcount /
 * distinct roles. Capacity comes from the Resource Utilisation KPI snapshot
 * (definition code RESOURCE_UTIL); team comes from the resource pool.
 */
export function PeopleCapacityCard({ projectId }: { projectId: string }) {
  // Same keys as useHubBadges — shared cache
  const teamQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "team"],
    queryFn: () => projectResourceApi.listPool(projectId),
    ...cardQueryOpts,
  });

  const kpiSnapshotsQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "kpi-snapshots"],
    queryFn: () => dashboardApi.getProjectKpiSnapshots(projectId),
    ...cardQueryOpts,
  });
  const kpiDefinitionsQuery = useQuery({
    queryKey: ["hub-badge", "kpi-definitions"],
    queryFn: () => dashboardApi.getKpiDefinitions(),
    ...cardQueryOpts,
  });

  const members = teamQuery.data?.data ?? [];
  const memberCount = members.length;
  const roleCount = new Set(
    members.map((m) => m.roleName?.trim()).filter((n): n is string => Boolean(n)),
  ).size;

  // Capacity utilisation %: locate the Resource Utilisation KPI (code RESOURCE_UTIL)
  const defs = unwrapArray<KpiDef>(kpiDefinitionsQuery.data);
  const snaps = unwrapArray<KpiSnap>(kpiSnapshotsQuery.data);
  let utilisationPct: number | null = null;
  if (defs && snaps) {
    const capacityDef = defs.find((d) => isUtilisationKpiCode(d.code));
    if (capacityDef) {
      const snap = snaps.find((s) => s.kpiDefinitionId === capacityDef.id);
      if (snap) {
        utilisationPct = Math.round(snap.value);
      }
    }
  }

  const isLoading =
    teamQuery.isLoading || kpiSnapshotsQuery.isLoading || kpiDefinitionsQuery.isLoading;

  const statusPill =
    utilisationPct != null ? (
      <StatusPill
        tone={
          utilisationPct >= 85 ? "danger" : utilisationPct >= 70 ? "warn" : "success"
        }
      >
        {utilisationPct}% used
      </StatusPill>
    ) : undefined;

  // Donut geometry — 32×32 px viewBox with stroke-based ring.
  const radius = 14;
  const circumference = 2 * Math.PI * radius;
  const dashOffset =
    utilisationPct != null
      ? circumference * (1 - Math.max(0, Math.min(100, utilisationPct)) / 100)
      : circumference;

  return (
    <CardShell
      groupId="people"
      title="People & Capacity"
      subtitle="Who is doing what, and how fully"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={isLoading}
    >
      {isLoading ? (
        <div className="flex items-center gap-3">
          <Skeleton className="h-14 w-14 rounded-full" />
          <div className="flex-1 space-y-1.5">
            <Skeleton className="h-3 w-2/3" />
            <Skeleton className="h-3 w-1/2" />
          </div>
        </div>
      ) : (
        <div className="flex items-center gap-3.5">
          <div className="relative h-[72px] w-[72px] shrink-0">
            <svg viewBox="0 0 32 32" className="h-[72px] w-[72px] -rotate-90" aria-hidden="true">
              <circle
                cx="16"
                cy="16"
                r={radius}
                fill="none"
                className="stroke-parchment"
                strokeWidth="3"
              />
              {utilisationPct != null ? (
                <circle
                  cx="16"
                  cy="16"
                  r={radius}
                  fill="none"
                  className="stroke-cyan-600"
                  strokeWidth="3"
                  strokeLinecap="round"
                  strokeDasharray={circumference}
                  strokeDashoffset={dashOffset}
                />
              ) : null}
            </svg>
            {utilisationPct != null ? (
              <div className="absolute inset-0 flex items-center justify-center">
                <MetricNumber
                  value={utilisationPct}
                  format={(n) => `${Math.round(n)}%`}
                  className="font-mono text-sm font-semibold text-text-primary tabular-nums"
                />
              </div>
            ) : null}
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
              {utilisationPct != null ? "Utilisation" : "Utilisation unavailable"}
            </div>
            {memberCount > 0 ? (
              <div className="mt-1.5 grid grid-cols-2 gap-2">
                <div className="rounded-lg border border-border bg-ivory p-2.5">
                  <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                    Members
                  </div>
                  <div className="mt-0.5 font-mono text-base text-text-primary tabular-nums">
                    {memberCount}
                  </div>
                </div>
                {roleCount > 0 ? (
                  <div className="rounded-lg border border-border bg-ivory p-2.5">
                    <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                      Roles
                    </div>
                    <div className="mt-0.5 font-mono text-base text-text-primary tabular-nums">
                      {roleCount}
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>
      )}
    </CardShell>
  );
}
