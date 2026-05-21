"use client";

import React, { useMemo } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";

import { projectApi } from "@/lib/api/projectApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { activityApi } from "@/lib/api/activityApi";
import { reportDataApi, type WbsProgressRow } from "@/lib/api/reportDataApi";
import { dailyWeatherApi } from "@/lib/api/dailyWeatherApi";
import { dprIssueApi } from "@/lib/api/dprIssueApi";
import { riskApi } from "@/lib/api/riskApi";
import type { WbsNodeResponse } from "@/lib/types";

import { KpiCard } from "@/components/ui/kpi-card";
import { MetricDelta } from "@/components/ui/metric-delta";
import { TimelinePreview, type TimelinePhase } from "@/components/dashboards/command/TimelinePreview";
import { ProjectHealthDonut } from "@/components/dashboards/command/ProjectHealthDonut";
import {
  WorkPackageStatusTable,
  type WorkPackageRow,
} from "@/components/dashboards/command/WorkPackageStatusTable";
import { ActiveAlertsPanel, type AlertItem } from "@/components/dashboards/command/ActiveAlertsPanel";
import { SiteConditionsStrip } from "@/components/dashboards/command/SiteConditionsStrip";
import { formatCrore } from "@/components/common/dashboard/primitives";

export default function ProjectCommandDashboardPage() {
  const params = useParams();
  const router = useRouter();
  const projectId = params.projectId as string;
  const base = `/projects/${projectId}`;

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });

  const { data: budgetEnv } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    enabled: !!projectId,
  });

  const { data: activitiesEnv } = useQuery({
    queryKey: ["activities-all", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 500),
    enabled: !!projectId,
  });

  const { data: wbsProgress } = useQuery({
    queryKey: ["project-wbs-progress", projectId],
    queryFn: () => reportDataApi.getWbsProgress(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: wbsTreeEnv } = useQuery({
    queryKey: ["project-wbs-tree", projectId],
    queryFn: () => projectApi.getWbsTree(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: weatherEnv } = useQuery({
    queryKey: ["daily-weather", projectId, "latest"],
    queryFn: () => dailyWeatherApi.list(projectId, {}),
    enabled: !!projectId,
    retry: false,
  });

  const { data: dprIssuesEnv } = useQuery({
    queryKey: ["dpr-issues", projectId],
    queryFn: () => dprIssueApi.list(projectId, {}),
    enabled: !!projectId,
    retry: false,
  });

  const { data: risksEnv } = useQuery({
    queryKey: ["risks", projectId],
    queryFn: () => riskApi.listRisks(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const project = projectData?.data;
  const budget = budgetEnv?.data;
  const activities = activitiesEnv?.data?.content ?? [];
  const wbsRows: WbsProgressRow[] = wbsProgress ?? [];
  const weatherLogs = (weatherEnv?.data ?? []) as Array<{
    logDate: string;
    tempMaxC: number | null;
    rainfallMm: number | null;
    windKmh: number | null;
  }>;
  const dprIssues = dprIssuesEnv?.data ?? [];
  const risks = risksEnv?.data ?? [];

  const wbsTree = wbsTreeEnv?.data ?? [];
  const flatWbs = useMemo(() => {
    const out: WbsNodeResponse[] = [];
    const walk = (nodes: WbsNodeResponse[]) => {
      for (const n of nodes) {
        out.push(n);
        if (n.children?.length) walk(n.children);
      }
    };
    walk(wbsTree);
    return out;
  }, [wbsTree]);

  const root = wbsRows.find((r) => r.level === 1) ?? wbsRows[0];
  const overallProgress = root?.actualPct ?? 0;
  const plannedProgress = root?.plannedPct ?? 0;
  const progressDelta = overallProgress - plannedProgress;

  const taskTotals = useMemo(() => {
    const total = activities.length;
    const completed = activities.filter((a) => a.status === "COMPLETED").length;
    const inProgress = activities.filter((a) => a.status === "IN_PROGRESS").length;
    const planned = total - completed - inProgress;
    return { total, completed, inProgress, planned };
  }, [activities]);

  const healthSegments = useMemo(() => {
    const onTrack = activities.filter(
      (a) => a.status === "IN_PROGRESS" && (a.percentComplete ?? 0) >= (plannedProgress || 0)
    ).length;
    const atRisk = activities.filter(
      (a) =>
        a.status === "IN_PROGRESS" &&
        (a.percentComplete ?? 0) < (plannedProgress || 0) &&
        (a.percentComplete ?? 0) > 0
    ).length;
    const delayed = activities.filter(
      (a) =>
        a.status !== "COMPLETED" &&
        a.plannedFinishDate &&
        new Date(a.plannedFinishDate) < new Date() &&
        (a.percentComplete ?? 0) < 100
    ).length;
    return [
      { label: "On Track", value: onTrack, color: "var(--emerald)" },
      { label: "At Risk", value: atRisk, color: "var(--bronze-warn)" },
      { label: "Delayed", value: delayed, color: "var(--burgundy)" },
    ];
  }, [activities, plannedProgress]);

  const timelinePhases: TimelinePhase[] = useMemo(() => {
    return flatWbs
      .filter((n) => n.wbsLevel === 2 && n.plannedStart && n.plannedFinish)
      .slice(0, 6)
      .map((n) => ({
        name: n.name,
        start: n.plannedStart!,
        end: n.plannedFinish!,
      }));
  }, [flatWbs]);

  const workPackages: WorkPackageRow[] = useMemo(() => {
    const nodeByCode = new Map(flatWbs.map((n) => [n.code, n]));
    return wbsRows
      .filter((r) => r.level === 2)
      .slice(0, 8)
      .map((r) => {
        const pct = Math.round(r.actualPct);
        const planned = r.plannedPct;
        const status =
          pct >= 100
            ? "DONE"
            : pct === 0
              ? "PLANNED"
              : pct < planned - 5
                ? "DELAYED"
                : "IN_PROGRESS";
        const node = nodeByCode.get(r.wbsCode);
        return {
          id: r.wbsCode,
          code: r.wbsCode,
          name: r.wbsName,
          contractor: undefined,
          progressPct: pct,
          status,
          due: node?.plannedFinish
            ? new Date(node.plannedFinish).toLocaleDateString("en-US", { day: "numeric", month: "short" })
            : undefined,
        };
      });
  }, [wbsRows, flatWbs]);

  const alerts: AlertItem[] = useMemo(() => {
    const list: AlertItem[] = [];
    dprIssues.slice(0, 3).forEach((i, idx) => {
      const sev = (i.severity as string | undefined)?.toUpperCase() ?? "INFO";
      const mapped: AlertItem["severity"] =
        sev === "CRITICAL" ? "CRITICAL" : sev === "HIGH" || sev === "WARNING" ? "WARNING" : "INFO";
      const status = (i.status as string | undefined)?.toUpperCase() ?? "";
      list.push({
        id: i.id ?? `dpr-${idx}`,
        severity: status === "CLOSED" || status === "RESOLVED" ? "RESOLVED" : mapped,
        message: i.title ?? "Issue",
        context: i.activityName ?? undefined,
      });
    });
    risks
      .filter((r) => r.rag === "CRIMSON" || r.rag === "RED")
      .slice(0, Math.max(0, 4 - list.length))
      .forEach((r) =>
        list.push({
          id: r.id,
          severity: r.rag === "CRIMSON" ? "CRITICAL" : "WARNING",
          message: r.title,
          context: r.code,
        })
      );
    return list;
  }, [dprIssues, risks]);

  const latestWeather = weatherLogs[0];

  const openIssuesCount = dprIssues.filter((i) => {
    const s = (i.status as string | undefined)?.toUpperCase();
    return s !== "CLOSED" && s !== "RESOLVED";
  }).length;
  const criticalIssues = dprIssues.filter(
    (i) => (i.severity as string | undefined)?.toUpperCase() === "CRITICAL"
  ).length;

  const utilisedAmount =
    budget?.currentBudget && budget?.originalBudget != null
      ? budget.currentBudget
      : null;

  return (
    <div className="space-y-6 pb-12">
      <header>
        <p className="text-xs text-text-secondary">
          {project ? (
            <>
              Welcome back · <span className="text-text-primary">{project.name}</span>
              {project.code && <span className="ml-2 text-text-secondary">{project.code}</span>}
            </>
          ) : (
            <>Project dashboard</>
          )}
        </p>
      </header>

      {/* KPI strip — each tile drills into its owning module */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard
          label="Overall Progress"
          value={overallProgress.toFixed(0)}
          unit="%"
          accent="success"
          href={`${base}/reports`}
          delta={
            <MetricDelta tone={progressDelta >= 0 ? "positive" : "negative"}>
              {progressDelta >= 0 ? "+" : ""}
              {progressDelta.toFixed(1)}% vs plan
            </MetricDelta>
          }
        />
        <KpiCard
          label="Budget Utilised"
          value={utilisedAmount != null ? formatCrore(utilisedAmount, 1) : "—"}
          accent="warning"
          href={`${base}/finance`}
          delta={
            budget?.pendingChangeCount ? (
              <MetricDelta tone="neutral">{budget.pendingChangeCount} pending</MetricDelta>
            ) : undefined
          }
        />
        <KpiCard
          label="Tasks Completed"
          value={taskTotals.completed}
          unit={`/ ${taskTotals.total}`}
          accent="info"
          href={`${base}/activities`}
          delta={
            taskTotals.inProgress > 0 ? (
              <MetricDelta tone="positive">+{taskTotals.inProgress} in progress</MetricDelta>
            ) : undefined
          }
        />
        <KpiCard
          label="Open Issues"
          value={openIssuesCount}
          unit={criticalIssues > 0 ? `${criticalIssues} critical` : undefined}
          accent="danger"
          href={`${base}/risks-dashboard`}
          delta={
            criticalIssues > 0 ? (
              <MetricDelta tone="negative">{criticalIssues} need action</MetricDelta>
            ) : undefined
          }
        />
      </div>

      {/* Row 2: Timeline + Health — section headers drill into Gantt / Reports */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <SectionHeader title="Project Timeline Preview" href={`${base}?tab=gantt`} />
          {timelinePhases.length > 0 ? (
            <TimelinePreview phases={timelinePhases} />
          ) : (
            <div className="flex items-center justify-center rounded-xl border border-dashed border-hairline p-8 text-sm text-text-secondary">
              No top-level WBS phases scheduled yet.
            </div>
          )}
        </section>
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <SectionHeader title="Project Health" href={`${base}/reports`} />
          <ProjectHealthDonut percent={overallProgress} segments={healthSegments} />
        </section>
      </div>

      {/* Row 3: Work packages + alerts/conditions */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <SectionHeader title="Work Package Status" href={`${base}/boq`} />
          <WorkPackageStatusTable
            rows={workPackages}
            onRowClick={() => router.push(`${base}/boq`)}
          />
        </section>
        <div className="space-y-4">
          <section className="rounded-2xl border border-hairline bg-ivory p-5">
            <SectionHeader title="Active Alerts" href={`${base}/issues`} />
            <ActiveAlertsPanel
              alerts={alerts}
              onAlertClick={() => router.push(`${base}/issues`)}
            />
          </section>
          <section className="rounded-2xl border border-hairline bg-ivory p-5">
            <SectionHeader title="Site Conditions" href={`${base}/weather-log`} />
            <SiteConditionsStrip
              temperature={latestWeather?.tempMaxC ?? null}
              windKmh={latestWeather?.windKmh ?? null}
              rainfallMm={latestWeather?.rainfallMm ?? null}
              aqi={null}
            />
          </section>
        </div>
      </div>
    </div>
  );
}

/** Dashboard section heading. Renders as a Link with a trailing chevron when a
 *  drill-down href is provided, so each surface signals "this is a hub tile,
 *  click to dive in." */
function SectionHeader({ title, href }: { title: string; href?: string }) {
  const inner = (
    <span className="inline-flex items-center gap-1.5">
      {title}
      {href && <span aria-hidden>›</span>}
    </span>
  );
  return (
    <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
      {href ? (
        <Link href={href} className="transition-colors hover:text-gold">
          {inner}
        </Link>
      ) : (
        inner
      )}
    </h2>
  );
}
