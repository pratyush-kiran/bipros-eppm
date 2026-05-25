"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { dprIssueApi } from "@/lib/api/dprIssueApi";
import { dailyWeatherApi } from "@/lib/api/dailyWeatherApi";
import type { ProjectResponse } from "@/lib/types";
import { KpiRow } from "./KpiRow";
import { ProjectTimelinePreview } from "./ProjectTimelinePreview";
import { ProjectHealthDonut } from "./ProjectHealthDonut";
import { WorkPackageTable } from "./WorkPackageTable";
import { ActiveAlertsPanel } from "./ActiveAlertsPanel";
import { SiteConditionsPanel } from "./SiteConditionsPanel";
import {
  bucketActivities,
  criticalIssueCount,
  tasksCompleted,
} from "./dashboardDerivations";

interface ProjectDashboardTabProps {
  project: ProjectResponse;
  projectId: string;
}

const STALE = 60_000;

export function ProjectDashboardTab({
  project,
  projectId,
}: ProjectDashboardTabProps) {
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
  const activityRows = activityStatusQ.data ?? [];
  const issues = issuesQ.data ?? [];
  const weatherRows = weatherQ.data ?? [];

  const buckets = useMemo(() => bucketActivities(activityRows), [activityRows]);
  const tasks = useMemo(() => tasksCompleted(activityRows), [activityRows]);
  const critical = useMemo(() => criticalIssueCount(issues), [issues]);

  const latestWeather = useMemo(() => {
    if (weatherRows.length === 0) return null;
    return [...weatherRows].sort((a, b) =>
      b.logDate.localeCompare(a.logDate),
    )[0];
  }, [weatherRows]);

  return (
    <div className="space-y-6">
      <KpiRow
        snapshot={snapshot}
        deltas={deltas}
        tasks={tasks}
        tasksDelta={deltas?.tasksCompletedDelta ?? null}
        criticalIssueCount={critical}
      />

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[1.6fr_1fr]">
        <ProjectTimelinePreview activities={activityRows} />
        <ProjectHealthDonut
          buckets={{
            onTrack: buckets.onTrack,
            atRisk: buckets.atRisk,
            delayed: buckets.delayed,
          }}
          physicalPct={snapshot?.physicalPct ?? 0}
        />
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[1.6fr_1fr]">
        <WorkPackageTable activities={activityRows} />
        <div className="space-y-6">
          <ActiveAlertsPanel issues={issues} />
          <SiteConditionsPanel weather={latestWeather} />
        </div>
      </div>
    </div>
  );
}
