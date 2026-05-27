"use client";
/**
 * HeroPulse — Material-3 dark command-hub header for the project workspace.
 *
 * Renders a plain title header (status dot + name + code + date range + ⌘K
 * button) on the page background, followed by a 4-card KPI grid of glass
 * tiles. No gradient banner, no gold radial overlay. Every KPI tile renders
 * only when its underlying data is loaded or present — never a fabricated zero.
 *
 * All data is sourced exclusively from existing APIs; queries and derivations
 * below are unchanged from the prior revision.
 */
import { useQuery } from "@tanstack/react-query";
import { projectApi } from "@/lib/api/projectApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { costApi } from "@/lib/api/costApi";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { dprIssueApi } from "@/lib/api/dprIssueApi";
import { formatDate, formatBudget } from "@/lib/utils/format";
import type { ProjectStatus } from "@/lib/types";
import { MetricNumber } from "@/components/hub/mission-control/primitives/MetricNumber";
import { useCommandPalette } from "../palette/CommandPaletteProvider";
import { cardQueryOpts, rawToMajorScale } from "./cards/_shared";
import { MIcon } from "./MIcon";

interface Props {
  projectId: string;
}

/** Map ProjectStatus → status-pill text colour, theme-aware. */
const STATUS_PILL_TONE: Record<ProjectStatus, string> = {
  ACTIVE: "text-gold-deep dark:text-gold",
  PLANNED: "text-blue-700 dark:text-blue-300",
  INACTIVE: "text-text-muted dark:text-white/60",
  COMPLETED: "text-emerald-700 dark:text-emerald-300",
};

/**
 * Compact circular progress arc for the Schedule Health tile. Decorative —
 * the numeric % beside it carries the accessible value, so the SVG is
 * `aria-hidden`. Driven by the existing `scheduleHealth` scalar (0-100).
 */
function ScheduleRing({ pct }: { pct: number }) {
  const clamped = Math.max(0, Math.min(100, pct));
  const size = 44;
  const stroke = 4;
  const r = (size - stroke) / 2;
  const circumference = 2 * Math.PI * r;
  const offset = circumference * (1 - clamped / 100);
  return (
    <svg
      aria-hidden
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      className="shrink-0 -rotate-90"
    >
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        className="stroke-outline-variant"
        strokeWidth={stroke}
      />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke="var(--secondary)"
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={circumference}
        strokeDashoffset={offset}
        className="motion-safe:transition-[stroke-dashoffset] motion-safe:duration-700"
      />
    </svg>
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
  // Status dot + label colour reflects real status; default secondary/green
  // (ACTIVE) when status is unknown/loading.
  const statusToneClass = status
    ? STATUS_PILL_TONE[status]
    : "text-secondary";

  const dateRange =
    project?.plannedStartDate && project?.plannedFinishDate
      ? `${formatDate(project.plannedStartDate)} → ${formatDate(project.plannedFinishDate)}`
      : null;

  // Budget burn ratio (0-100), clamped — drives the bar width + over-budget tone.
  const budgetReady =
    totalBudgetMajor != null && actualSpendMajor != null && totalBudgetMajor > 0;
  const burnPct = budgetReady
    ? Math.max(0, Math.min(100, (actualSpendMajor! / totalBudgetMajor!) * 100))
    : 0;
  const overBudget = budgetReady
    ? actualSpendMajor! / totalBudgetMajor! > 1
    : false;

  return (
    <section aria-label="Project hero">
      {/* (1) Plain title header on the page background */}
      <header className="mb-6 flex flex-col md:flex-row justify-between items-start md:items-end gap-4">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <span
              aria-hidden
              className={`w-2 h-2 rounded-full bg-current shadow-[0_0_8px_rgba(78,222,163,0.6)] ${statusToneClass}`}
            />
            <span
              className={`text-[10px] font-bold tracking-widest uppercase ${statusToneClass}`}
            >
              {status ?? "ACTIVE"}
            </span>
          </div>
          <h1 className="font-sans font-bold text-[clamp(2rem,4vw,3rem)] leading-tight tracking-tight text-on-surface">
            {project?.name ?? "Loading project…"}
          </h1>
          <div className="flex items-center gap-4 text-text-secondary text-xs mt-1 font-mono">
            {project?.code ? <span>{project.code}</span> : null}
            {project?.code && dateRange ? (
              <span aria-hidden className="w-1 h-1 bg-white/20 rounded-full" />
            ) : null}
            {dateRange ? <span>{dateRange}</span> : null}
          </div>
        </div>
        <button
          type="button"
          onClick={openPalette}
          aria-label="Open command palette"
          className="bg-surface-container-high border border-white/10 px-4 py-2.5 rounded-xl flex items-center gap-2 hover:bg-surface-container-highest transition-colors shadow-lg text-sm font-medium text-on-surface"
        >
          ⌘K Jump to anything
          <span className="text-[10px] bg-white/5 px-1.5 py-0.5 rounded ml-2">⌘K</span>
        </button>
      </header>

      {/* (2) 4-card KPI grid. Only tiles with real (or loading) data render. */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
        {/* Overall Progress */}
        {progressPct != null || snapshotQuery.isLoading ? (
          <div aria-busy={snapshotQuery.isLoading} className="glass-card p-4 rounded-2xl">
            <div className="text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">
              Overall Progress
            </div>
            {snapshotQuery.isLoading ? (
              <div className="h-10 w-20 rounded bg-white/10 motion-safe:animate-pulse" />
            ) : (
              <>
                <div className="flex items-baseline gap-2">
                  <span className="text-[40px] font-bold text-on-surface leading-none tabular-nums">
                    {progressPct != null ? (
                      <MetricNumber
                        value={progressPct}
                        format={(n) => `${Math.round(n)}%`}
                      />
                    ) : (
                      "—"
                    )}
                  </span>
                  {progressDelta != null && progressDelta !== 0 ? (
                    <span
                      className={`text-xs tabular-nums ${
                        progressDelta > 0 ? "text-secondary" : "text-error"
                      }`}
                    >
                      {progressDelta > 0 ? "▲" : "▼"} {Math.abs(progressDelta).toFixed(1)}%
                    </span>
                  ) : null}
                </div>
                {progressPct != null ? (
                  <div
                    aria-hidden
                    className="mt-3 w-full h-1 bg-white/5 rounded-full overflow-hidden"
                  >
                    <div
                      className="h-full bg-primary motion-safe:transition-[width] motion-safe:duration-700"
                      style={{ width: `${Math.max(0, Math.min(100, progressPct))}%` }}
                    />
                  </div>
                ) : null}
              </>
            )}
          </div>
        ) : null}

        {/* Budget Utilised */}
        {(totalBudgetMajor != null && actualSpendMajor != null) ||
        budgetQuery.isLoading ||
        costSummaryQuery.isLoading ? (
          <div
            aria-busy={budgetQuery.isLoading || costSummaryQuery.isLoading}
            className="glass-card p-4 rounded-2xl"
          >
            <div className="text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">
              Budget Utilised
            </div>
            {budgetQuery.isLoading || costSummaryQuery.isLoading ? (
              <div className="h-10 w-28 rounded bg-white/10 motion-safe:animate-pulse" />
            ) : (
              <>
                <div className="text-2xl font-bold text-on-surface leading-tight tabular-nums">
                  {totalBudgetMajor != null && actualSpendMajor != null ? (
                    <>
                      {formatBudget(actualSpendMajor, currency)}
                      <span className="text-text-secondary"> / </span>
                      {formatBudget(totalBudgetMajor, currency)}
                    </>
                  ) : (
                    "—"
                  )}
                </div>
                {budgetReady ? (
                  <div
                    aria-hidden
                    className="mt-3 w-full h-1 bg-white/5 rounded-full overflow-hidden"
                  >
                    <div
                      className={`h-full motion-safe:transition-[width] motion-safe:duration-700 ${
                        overBudget ? "bg-error" : "bg-primary"
                      }`}
                      style={{ width: `${burnPct}%` }}
                    />
                  </div>
                ) : null}
              </>
            )}
          </div>
        ) : null}

        {/* Schedule Health */}
        {scheduleHealth != null || scheduleQualityQuery.isLoading ? (
          <div
            aria-busy={scheduleQualityQuery.isLoading}
            className="glass-card p-4 rounded-2xl"
          >
            <div className="text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">
              Schedule Health
            </div>
            {scheduleQualityQuery.isLoading ? (
              <div className="h-11 w-28 rounded bg-white/10 motion-safe:animate-pulse" />
            ) : scheduleHealth != null ? (
              <div className="flex items-center gap-3">
                <ScheduleRing pct={scheduleHealth} />
                <span className="text-[40px] font-bold text-secondary leading-none tabular-nums">
                  <MetricNumber
                    value={scheduleHealth}
                    format={(n) => `${Math.round(n)}%`}
                  />
                </span>
              </div>
            ) : (
              <span className="text-[40px] font-bold text-secondary leading-none">—</span>
            )}
          </div>
        ) : null}

        {/* Critical Issues */}
        {criticalIssuesCount != null || criticalIssuesQuery.isLoading ? (
          <div
            aria-busy={criticalIssuesQuery.isLoading}
            className="glass-card p-4 rounded-2xl"
          >
            <div className="text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">
              Critical Issues
            </div>
            {criticalIssuesQuery.isLoading ? (
              <div className="h-10 w-16 rounded bg-white/10 motion-safe:animate-pulse" />
            ) : (
              <div className="flex items-center gap-3">
                <span className="text-[40px] font-bold text-error leading-none tabular-nums">
                  {criticalIssuesCount != null ? (
                    <MetricNumber
                      value={criticalIssuesCount}
                      format={(n) => `${Math.round(n)}`}
                    />
                  ) : (
                    "—"
                  )}
                </span>
                <MIcon name="warning" className="text-error animate-pulse" />
              </div>
            )}
          </div>
        ) : null}
      </div>
    </section>
  );
}
