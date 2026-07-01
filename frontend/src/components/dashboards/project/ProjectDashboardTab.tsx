"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { format } from "date-fns";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import type { ActivityStatusRow } from "@/lib/api/projectInsightsApi";
import { dprIssueApi } from "@/lib/api/dprIssueApi";
import { dailyWeatherApi } from "@/lib/api/dailyWeatherApi";
import type { DprIssueRow } from "@/lib/types/dpr";
import type { ProjectResponse } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { StatusBadge } from "@/components/common/StatusBadge";
import { KpiRow } from "./KpiRow";
import { BudgetBand } from "./BudgetBand";
import { ProjectTimelinePreview } from "./ProjectTimelinePreview";
import { ProjectHealthDonut } from "./ProjectHealthDonut";
import { WorkPackageTable } from "./WorkPackageTable";
import { ActiveAlertsPanel } from "./ActiveAlertsPanel";
import { SiteConditionsPanel } from "./SiteConditionsPanel";
import { OverviewDrillDownDrawer } from "./OverviewDrillDownDrawer";
import {
  bucketActivities,
  criticalIssueCount,
  tasksCompleted,
  openIssueCount,
  completedActivities,
  activitiesByHealth,
  openIssues,
  mapWorkPackageStatus,
} from "./dashboardDerivations";
import {
  SEVERITY_VARIANT,
  STATUS_VARIANT,
  categoryLabel,
} from "@/components/dpr/IssueBadges";

type DrillState =
  | { kind: "tasks" }
  | { kind: "issues" }
  | { kind: "health"; bucket: "onTrack" | "atRisk" | "delayed" }
  | null;

const HEALTH_LABEL: Record<"onTrack" | "atRisk" | "delayed", string> = {
  onTrack: "On Track",
  atRisk: "At Risk",
  delayed: "Delayed",
};

function ActivityDrillRow({ row }: { row: ActivityStatusRow }) {
  const code = row.wbsCode || row.code;
  const due = row.plannedFinish
    ? format(new Date(row.plannedFinish), "d MMM")
    : "—";
  return (
    <div className="flex items-center gap-3 rounded-lg border border-hairline bg-paper px-3 py-2 text-sm">
      <span className="w-20 shrink-0 font-mono text-[11px] text-slate">{code}</span>
      <span className="min-w-0 flex-1 truncate text-charcoal">{row.name}</span>
      <span className="shrink-0 text-[11px] text-slate">{row.pctComplete.toFixed(0)}%</span>
      <StatusBadge status={mapWorkPackageStatus(row)} variant="compact" />
      <span className="w-14 shrink-0 text-right text-[11px] text-slate">{due}</span>
    </div>
  );
}

function IssueDrillRow({ row }: { row: DprIssueRow }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-hairline bg-paper px-3 py-2 text-sm">
      <Badge variant={SEVERITY_VARIANT[row.severity]}>{row.severity}</Badge>
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium text-charcoal">{row.title}</div>
        <div className="mt-0.5 text-[11px] text-slate">
          {categoryLabel(row.category)}
          {row.assignedToName ? ` · ${row.assignedToName}` : ""}
        </div>
      </div>
      <Badge variant={STATUS_VARIANT[row.status]}>
        {row.status.replace("_", " ")}
      </Badge>
    </div>
  );
}

interface ProjectDashboardTabProps {
  project: ProjectResponse;
  projectId: string;
}

const STALE = 60_000;

export function ProjectDashboardTab({
  projectId,
}: ProjectDashboardTabProps) {
  const [drill, setDrill] = useState<DrillState>(null);

  const snapshotQ = useQuery({
    queryKey: ["project-dashboard-snapshot-trend", projectId],
    queryFn: () => projectInsightsApi.getStatusSnapshotWithTrend(projectId),
    staleTime: STALE,
  });

  const activityStatusQ = useQuery({
    queryKey: ["project-dashboard-activity-status", projectId],
    queryFn: () => projectInsightsApi.getActivityStatus(projectId, { limit: 200 }),
    staleTime: STALE,
  });

  const issuesQ = useQuery({
    queryKey: ["project-dashboard-issues", projectId],
    queryFn: () => dprIssueApi.list(projectId).then((r) => r.data ?? []),
    staleTime: STALE,
  });

  const weatherQ = useQuery({
    queryKey: ["project-dashboard-weather", projectId],
    queryFn: () => dailyWeatherApi.list(projectId).then((r) => r.data ?? []),
    staleTime: STALE,
  });

  const snapshot = snapshotQ.data?.current ?? null;
  const deltas = snapshotQ.data?.deltas ?? null;
  // Memoize the array fallbacks so a `?? []` doesn't mint a new reference each render and
  // re-fire every dependent useMemo below (the drill-down content, buckets, counts).
  const activityRows = useMemo(() => activityStatusQ.data ?? [], [activityStatusQ.data]);
  const issues = useMemo(() => issuesQ.data ?? [], [issuesQ.data]);
  const weatherRows = useMemo(() => weatherQ.data ?? [], [weatherQ.data]);

  const buckets = useMemo(() => bucketActivities(activityRows), [activityRows]);
  const tasks = useMemo(() => tasksCompleted(activityRows), [activityRows]);
  const critical = useMemo(() => criticalIssueCount(issues), [issues]);
  const openCount = useMemo(() => openIssueCount(issues), [issues]);

  const latestWeather = useMemo(() => {
    if (weatherRows.length === 0) return null;
    return [...weatherRows].sort((a, b) =>
      b.logDate.localeCompare(a.logDate),
    )[0];
  }, [weatherRows]);

  // Derive drawer title / viewAll / children from drill state.
  // When drill is null the drawer is closed (open=false) so the empty fallbacks are off-screen.
  const drawerContent = useMemo(() => {
    if (!drill) {
      return { title: "", viewAll: undefined, children: null };
    }
    if (drill.kind === "tasks") {
      const done = completedActivities(activityRows);
      return {
        title: `Completed Activities (${done.length})`,
        viewAll: { href: `/projects/${projectId}/activities`, label: "View all in Activities →" },
        children:
          done.length === 0 ? (
            <p className="text-sm text-slate">No completed activities.</p>
          ) : (
            <div className="flex flex-col gap-1.5">
              {done.map((r) => (
                <ActivityDrillRow key={r.activityId} row={r} />
              ))}
            </div>
          ),
      };
    }
    if (drill.kind === "health") {
      const rows = activitiesByHealth(activityRows, drill.bucket);
      const label = HEALTH_LABEL[drill.bucket];
      return {
        title: `${label} Activities (${rows.length})`,
        viewAll: { href: `/projects/${projectId}/activities`, label: "View all in Activities →" },
        children:
          rows.length === 0 ? (
            <p className="text-sm text-slate">No {label.toLowerCase()} activities.</p>
          ) : (
            <div className="flex flex-col gap-1.5">
              {rows.map((r) => (
                <ActivityDrillRow key={r.activityId} row={r} />
              ))}
            </div>
          ),
      };
    }
    // kind === "issues"
    const open = openIssues(issues);
    return {
      title: `Open Issues (${open.length})`,
      viewAll: { href: `/projects/${projectId}/issues`, label: "View all in Issue Log →" },
      children:
        open.length === 0 ? (
          <p className="text-sm text-slate">No open issues.</p>
        ) : (
          <div className="flex flex-col gap-1.5">
            {open.map((r, i) => (
              <IssueDrillRow key={r.id ?? i} row={r} />
            ))}
          </div>
        ),
    };
  }, [drill, activityRows, issues, projectId]);

  return (
    <div className="space-y-6">
      <KpiRow
        snapshot={snapshot}
        deltas={deltas}
        tasks={tasks}
        tasksDelta={deltas?.tasksCompletedDelta ?? null}
        openIssueCount={openCount}
        criticalIssueCount={critical}
        onTasksClick={() => setDrill({ kind: "tasks" })}
        onIssuesClick={() => setDrill({ kind: "issues" })}
      />

      <BudgetBand projectId={projectId} />

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[1.6fr_1fr]">
        <ProjectTimelinePreview activities={activityRows} />
        <ProjectHealthDonut
          buckets={{
            onTrack: buckets.onTrack,
            atRisk: buckets.atRisk,
            delayed: buckets.delayed,
          }}
          physicalPct={snapshot?.physicalPct ?? 0}
          onSelect={(bucket) => setDrill({ kind: "health", bucket })}
        />
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[1.6fr_1fr]">
        <WorkPackageTable activities={activityRows} />
        <div className="space-y-6">
          <ActiveAlertsPanel issues={issues} />
          <SiteConditionsPanel weather={latestWeather} />
        </div>
      </div>

      <OverviewDrillDownDrawer
        open={drill !== null}
        onClose={() => setDrill(null)}
        title={drawerContent.title}
        viewAll={drawerContent.viewAll}
      >
        {drawerContent.children}
      </OverviewDrillDownDrawer>
    </div>
  );
}
