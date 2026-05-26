"use client";
import { useQuery } from "@tanstack/react-query";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { CardShell } from "./CardShell";
import { Skeleton, StatusPill, cardQueryOpts } from "./_shared";

/**
 * Plan card — surfaces the structural backbone of the project: total activity
 * count, completion ratio and a small breakdown of top WBS phases (grouped
 * by {@code wbsName}). Shares the activity-status query key with
 * {@code useHubBadges} so React Query dedupes the fetch.
 */
export function PlanCard({ projectId }: { projectId: string }) {
  const activitiesQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "activities"],
    queryFn: () => projectInsightsApi.getActivityStatus(projectId),
    ...cardQueryOpts,
  });

  const rows = activitiesQuery.data;
  const totalCount = rows?.length ?? 0;

  // Group activities by WBS phase, derive average % complete per phase.
  // We only show this if we actually have rows with non-empty wbsName.
  const phases: Array<{ name: string; pct: number; count: number }> = (() => {
    if (!rows || rows.length === 0) return [];
    const bucket = new Map<string, { sumPct: number; count: number }>();
    for (const r of rows) {
      const key = (r.wbsName ?? "").trim();
      if (!key) continue;
      const cur = bucket.get(key) ?? { sumPct: 0, count: 0 };
      cur.sumPct += r.pctComplete ?? 0;
      cur.count += 1;
      bucket.set(key, cur);
    }
    return [...bucket.entries()]
      .map(([name, v]) => ({
        name,
        pct: v.count > 0 ? Math.round(v.sumPct / v.count) : 0,
        count: v.count,
      }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 4);
  })();

  const completedCount = rows?.filter((r) => r.status === "COMPLETED").length ?? 0;

  const statusPill =
    rows && totalCount > 0 ? (
      <StatusPill tone="info">
        {totalCount} {totalCount === 1 ? "activity" : "activities"}
      </StatusPill>
    ) : undefined;

  return (
    <CardShell
      groupId="plan"
      title="Plan"
      subtitle="WBS, activities, BOQ — the structural backbone"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={activitiesQuery.isLoading}
    >
      {activitiesQuery.isLoading ? (
        <div className="space-y-2.5">
          <Skeleton className="h-3 w-full" />
          <Skeleton className="h-3 w-5/6" />
          <Skeleton className="h-3 w-4/6" />
          <Skeleton className="h-3 w-3/6" />
        </div>
      ) : phases.length > 0 ? (
        <ul className="space-y-2.5">
          {phases.map((p) => (
            <li key={p.name} className="text-xs">
              <div className="flex items-center justify-between gap-3">
                <span className="truncate text-text-secondary">{p.name}</span>
                <span className="font-mono text-text-muted text-[10px] tabular-nums">
                  {p.pct}%
                </span>
              </div>
              <div className="mt-1 h-1.5 rounded-full bg-parchment overflow-hidden">
                <div
                  className="h-full bg-blue-500"
                  style={{ width: `${Math.max(0, Math.min(100, p.pct))}%` }}
                />
              </div>
            </li>
          ))}
        </ul>
      ) : rows && totalCount > 0 ? (
        <dl className="grid grid-cols-3 gap-2">
          <div>
            <dt className="text-[10px] uppercase tracking-[0.1em] text-text-muted">Total</dt>
            <dd className="font-mono text-lg text-text-primary tabular-nums">{totalCount}</dd>
          </div>
          <div>
            <dt className="text-[10px] uppercase tracking-[0.1em] text-text-muted">Done</dt>
            <dd className="font-mono text-lg text-emerald-700 tabular-nums">{completedCount}</dd>
          </div>
          <div>
            <dt className="text-[10px] uppercase tracking-[0.1em] text-text-muted">Critical</dt>
            <dd className="font-mono text-lg text-red-700 tabular-nums">
              {rows.filter((r) => r.isCritical).length}
            </dd>
          </div>
        </dl>
      ) : null}
    </CardShell>
  );
}
