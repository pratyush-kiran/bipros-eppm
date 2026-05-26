"use client";
/**
 * HeroPulse — Phase D brand-asserting hero banner for the project workspace.
 *
 * Renders a theme-adaptive gradient cover above the QuickAccess + Bento grid.
 * Surfaces project identity (status pill + name + code + date range) and four
 * KPI stats sourced exclusively from existing APIs. Each tile renders only
 * when its underlying data is loaded — never a fabricated zero.
 *
 * Light theme: soft cream / parchment gradient with a gentle gold radial
 * accent. Dark theme: dramatic charcoal gradient anchored to literal hex
 * stops (#1C1C1C / #1F1F1F / #2A2A2A) — chosen because `--charcoal` flips
 * to a light cream under `.dark` and would invert the gradient if used here.
 */
import { useQuery } from "@tanstack/react-query";
import { projectApi } from "@/lib/api/projectApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { costApi } from "@/lib/api/costApi";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { dprIssueApi } from "@/lib/api/dprIssueApi";
import { formatDate, formatBudget } from "@/lib/utils/format";
import type { ProjectStatus } from "@/lib/types";
import type { ReactNode } from "react";
import { DashboardsMenu } from "../nav/DashboardsMenu";
import { useCommandPalette } from "../palette/CommandPaletteProvider";
import { cardQueryOpts, rawToMajorScale } from "./cards/_shared";

interface Props {
  projectId: string;
}

/**
 * Feature flag: hide the in-hero Dashboards menu. The Section Context Bar
 * still has its own Dashboards menu so users can reach dashboards from any
 * section page. Flip to `true` to restore the in-hero button.
 */
const SHOW_DASHBOARDS_IN_HERO = false;

/** Map ProjectStatus → status-pill text colour, theme-aware. */
const STATUS_PILL_TONE: Record<ProjectStatus, string> = {
  ACTIVE: "text-gold-deep dark:text-gold",
  PLANNED: "text-blue-700 dark:text-blue-300",
  INACTIVE: "text-text-muted dark:text-white/60",
  COMPLETED: "text-emerald-700 dark:text-emerald-300",
};

function StatTile({
  label,
  value,
  trend,
  loading,
}: {
  label: string;
  value: ReactNode;
  trend?: { delta: number; suffix?: string } | null;
  loading: boolean;
}) {
  return (
    <div className="flex flex-col gap-1 px-4 first:pl-0" aria-busy={loading}>
      <div className="text-[10px] font-bold uppercase tracking-[0.18em] text-text-secondary dark:text-white/50">
        {label}
      </div>
      {loading ? (
        <div className="h-8 w-16 rounded bg-parchment dark:bg-white/10 motion-safe:animate-pulse" />
      ) : (
        <div className="flex items-baseline gap-2">
          <span className="font-serif text-3xl text-text-primary dark:text-white tabular-nums">
            {value}
          </span>
          {trend && trend.delta !== 0 ? (
            <span
              className={`text-xs tabular-nums ${
                trend.delta > 0
                  ? "text-emerald-700 dark:text-emerald-300"
                  : "text-red-700 dark:text-red-300"
              }`}
            >
              {trend.delta > 0 ? "▲" : "▼"} {Math.abs(trend.delta).toFixed(1)}
              {trend.suffix ?? ""}
            </span>
          ) : null}
        </div>
      )}
    </div>
  );
}

export function HeroPulse({ projectId }: Props) {
  const { openPalette } = useCommandPalette();

  // Project metadata — shares cache with ProjectHub root.
  const projectQuery = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    ...cardQueryOpts,
  });
  const project = projectQuery.data?.data;
  const currency = project?.budgetCurrency ?? null;

  // Hero stats — one query per KPI tile. All use the shared cardQueryOpts.
  const snapshotQuery = useQuery({
    queryKey: ["project", projectId, "hero", "status-snapshot-trend"],
    queryFn: () => projectInsightsApi.getStatusSnapshotWithTrend(projectId),
    ...cardQueryOpts,
  });

  const budgetQuery = useQuery({
    queryKey: ["project", projectId, "hub-card", "cost", "budget-summary"],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    ...cardQueryOpts,
  });

  const costSummaryQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "cost-summary"],
    queryFn: () => costApi.getCostSummary(projectId),
    ...cardQueryOpts,
  });

  const scheduleQualityQuery = useQuery({
    queryKey: ["project", projectId, "hero", "schedule-quality"],
    queryFn: () => projectInsightsApi.getScheduleQuality(projectId),
    ...cardQueryOpts,
  });

  const criticalIssuesQuery = useQuery({
    queryKey: ["project", projectId, "hero", "critical-issues"],
    queryFn: () => dprIssueApi.list(projectId, { severity: "CRITICAL" }),
    ...cardQueryOpts,
  });

  // ── Derive stats ──────────────────────────────────────────────────────────
  const snapshot = snapshotQuery.data?.current;
  const deltas = snapshotQuery.data?.deltas;
  const progressPct = snapshot?.physicalPct ?? null;
  const progressDelta = deltas?.physicalPctDelta ?? null;

  const budget = budgetQuery.data?.data;
  const costSummary = costSummaryQuery.data?.data;
  // Both endpoints return values in raw project-currency units (rupees / rials).
  // `WbsBudgetNode.budgetCrores` is the only field already in major-scale; the
  // top-level `currentBudget` / `originalBudget` are NOT pre-scaled.
  const rawTotalBudget = budget?.currentBudget ?? budget?.originalBudget ?? null;
  const totalBudgetMajor =
    rawTotalBudget != null ? rawToMajorScale(rawTotalBudget, currency) : null;
  const actualSpendMajor =
    costSummary?.totalActual != null
      ? rawToMajorScale(costSummary.totalActual, currency)
      : null;

  const scheduleHealth = scheduleQualityQuery.data?.overallHealthPct ?? null;

  const issuesRaw = criticalIssuesQuery.data?.data;
  const criticalIssuesCount = Array.isArray(issuesRaw)
    ? issuesRaw.filter(
        (i) => i.severity === "CRITICAL" && (i.status === "OPEN" || i.status === "IN_PROGRESS"),
      ).length
    : null;

  const status = project?.status;
  const statusToneClass = status
    ? STATUS_PILL_TONE[status]
    : "text-text-muted dark:text-white/60";

  const dateRange =
    project?.plannedStartDate && project?.plannedFinishDate
      ? `${formatDate(project.plannedStartDate)} → ${formatDate(project.plannedFinishDate)}`
      : null;

  return (
    <section
      aria-label="Project hero"
      // Light: soft cream/parchment gradient via theme tokens.
      // Dark: hard-coded hex stops (#1C1C1C → #1F1F1F → #2A2A2A) — `--charcoal`
      // inverts under `.dark`, so we anchor the dark gradient with literals.
      className="relative mb-6 overflow-hidden rounded-2xl border border-border
                 bg-gradient-to-br from-parchment via-ivory to-gold-tint/40
                 dark:border-transparent
                 dark:from-[#1C1C1C] dark:via-[#1F1F1F] dark:to-[#2A2A2A]"
    >
      {/* Gold radial overlay — present in both themes at different opacity */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0
                   bg-[radial-gradient(800px_320px_at_100%_0%,rgba(212,175,55,0.15),transparent_60%)]
                   dark:bg-[radial-gradient(800px_320px_at_100%_0%,rgba(212,175,55,0.22),transparent_60%)]"
      />
      <div className="relative p-6 sm:p-8">
        {/* Top row: status pill + (optional) dashboards menu + ⌘K hint */}
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-[0.18em]">
            <span className={statusToneClass} aria-hidden>
              ●
            </span>
            <span className={statusToneClass}>{status ?? "—"}</span>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {SHOW_DASHBOARDS_IN_HERO ? (
              <DashboardsMenu projectId={projectId} variant="hero" />
            ) : null}
            <button
              type="button"
              onClick={openPalette}
              aria-label="Open command palette"
              className="inline-flex items-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-medium transition-colors
                         bg-gold-tint/40 text-gold-deep border-gold/40 hover:bg-gold-tint
                         dark:bg-white/10 dark:text-white/90 dark:border-white/10 dark:hover:bg-white/15"
            >
              ⌘K Jump to anything
              <kbd className="rounded border px-1 py-0.5 text-[0.62rem] font-mono
                              border-gold/40 bg-gold-tint/60 text-gold-deep
                              dark:border-white/15 dark:bg-white/10 dark:text-white/70">
                ⌘K
              </kbd>
            </button>
          </div>
        </div>

        {/* Identity */}
        <div className="mt-5">
          <h1 className="font-serif text-4xl text-text-primary dark:text-white sm:text-5xl">
            {project?.name ?? "Loading project…"}
          </h1>
          <p className="mt-2 font-mono text-xs tracking-[0.12em] text-gold-deep dark:text-gold">
            {project?.code ?? ""}
            {dateRange ? (
              <span className="text-gold-deep/80 dark:text-gold/80"> · {dateRange}</span>
            ) : null}
          </p>
        </div>

        {/* KPI strip — only tiles with real data render */}
        <div className="mt-6 flex flex-wrap items-stretch gap-y-3 divide-x divide-border dark:divide-white/10">
          {progressPct != null || snapshotQuery.isLoading ? (
            <StatTile
              label="Overall Progress"
              loading={snapshotQuery.isLoading}
              value={progressPct != null ? `${progressPct.toFixed(0)}%` : "—"}
              trend={
                progressDelta != null && progressDelta !== 0
                  ? { delta: progressDelta, suffix: "%" }
                  : null
              }
            />
          ) : null}

          {(totalBudgetMajor != null && actualSpendMajor != null) ||
          budgetQuery.isLoading ||
          costSummaryQuery.isLoading ? (
            <StatTile
              label="Budget Utilised"
              loading={budgetQuery.isLoading || costSummaryQuery.isLoading}
              value={
                totalBudgetMajor != null && actualSpendMajor != null ? (
                  <span className="text-2xl sm:text-3xl">
                    {formatBudget(actualSpendMajor, currency)}
                    <span className="text-text-muted dark:text-white/60"> / </span>
                    {formatBudget(totalBudgetMajor, currency)}
                  </span>
                ) : (
                  "—"
                )
              }
            />
          ) : null}

          {scheduleHealth != null || scheduleQualityQuery.isLoading ? (
            <StatTile
              label="Schedule Health"
              loading={scheduleQualityQuery.isLoading}
              value={
                scheduleHealth != null ? (
                  <span className="text-emerald-700 dark:text-emerald-300">
                    {scheduleHealth.toFixed(0)}%
                  </span>
                ) : (
                  "—"
                )
              }
            />
          ) : null}

          {criticalIssuesCount != null || criticalIssuesQuery.isLoading ? (
            <StatTile
              label="Critical Issues"
              loading={criticalIssuesQuery.isLoading}
              value={
                criticalIssuesCount != null ? (
                  <span className="text-red-700 dark:text-red-300">
                    {criticalIssuesCount}
                  </span>
                ) : (
                  "—"
                )
              }
            />
          ) : null}
        </div>
      </div>
    </section>
  );
}
