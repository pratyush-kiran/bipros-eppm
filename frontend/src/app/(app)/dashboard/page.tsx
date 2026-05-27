"use client";

import {
  AlertTriangle,
  Banknote,
  BarChart3,
  Briefcase,
  Calendar as CalendarIcon,
  Clock,
  Flame,
  FolderTree,
  Gauge,
  Layers3,
  PauseCircle,
  PlayCircle,
  Plus,
  RefreshCw,
  ShieldAlert,
  Sparkles,
  TrendingUp,
  Users,
  Wallet,
  Zap,
} from "lucide-react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { apiClient } from "@/lib/api/client";
import { KpiTile } from "@/components/common/KpiTile";
import {
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  formatCrore,
  formatPct,
} from "@/components/common/dashboard/primitives";
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";

interface ProjectData {
  id: string;
  code?: string;
  name: string;
  status: string;
  createdAt: string;
}
interface ActivityData {
  totalActivities: number;
  criticalActivities: number;
  overdueCount: number;
}
interface MetricsData {
  plannedCount: number;
  activeCount: number;
  completedCount: number;
  onHoldCount: number;
  cancelledCount: number;
  resourceCount: number;
  recentProjects: ProjectData[];
  activities: ActivityData;
}

async function fetchMetrics(): Promise<MetricsData> {
  try {
    const projectsResponse = await apiClient.get("/v1/projects?page=0&size=100");
    const projects: ProjectData[] = projectsResponse.data.data?.content || [];
    const plannedCount = projects.filter((p) => p.status === "PLANNED").length;
    const activeCount = projects.filter((p) => p.status === "ACTIVE").length;
    const completedCount = projects.filter((p) => p.status === "COMPLETED").length;
    const onHoldCount = projects.filter((p) => p.status === "ON_HOLD").length;
    const cancelledCount = projects.filter((p) => p.status === "CANCELLED").length;
    const recentProjects = [...projects]
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(0, 5);

    const resourcesResponse = await apiClient.get("/v1/resources");
    const resourcesList = resourcesResponse.data.data || [];
    const resourceCount = Array.isArray(resourcesList) ? resourcesList.length : 0;

    let totalActivities = 0;
    let criticalActivities = 0;
    let overdueCount = 0;
    const today = new Date();
    for (const proj of projects.slice(0, 10)) {
      try {
        const actResp = await apiClient.get(`/v1/projects/${proj.id}/activities?page=0&size=500`);
        const actData = actResp.data.data;
        totalActivities += actData?.pagination?.totalElements || 0;
        const list: Array<{
          isCritical?: boolean;
          totalFloat?: number;
          plannedFinishDate?: string | null;
          percentComplete?: number | null;
        }> = actData?.content || [];
        criticalActivities += list.filter((a) => a.isCritical === true || a.totalFloat === 0).length;
        overdueCount += list.filter((a) => {
          const finish = a.plannedFinishDate ? new Date(a.plannedFinishDate) : null;
          const pct = a.percentComplete ?? 0;
          return finish && finish < today && pct < 100;
        }).length;
      } catch { /* skip */ }
    }

    return {
      plannedCount, activeCount, completedCount, onHoldCount, cancelledCount,
      resourceCount, recentProjects,
      activities: { totalActivities, criticalActivities, overdueCount },
    };
  } catch (error) {
    console.error("Failed to fetch metrics:", error);
    return {
      plannedCount: 0, activeCount: 0, completedCount: 0, onHoldCount: 0, cancelledCount: 0,
      resourceCount: 0, recentProjects: [],
      activities: { totalActivities: 0, criticalActivities: 0, overdueCount: 0 },
    };
  }
}

const STATUS_META: Record<
  string,
  { label: string; icon: React.ReactNode; tone: string; ring: string; dot: string }
> = {
  ACTIVE: {
    label: "Active",
    icon: <PlayCircle size={11} />,
    tone: "text-gold-deep",
    ring: "border-gold/40 bg-gold-tint/40",
    dot: "bg-gold-deep",
  },
  PLANNED: {
    label: "Planned",
    icon: <Clock size={11} />,
    tone: "text-steel",
    ring: "border-hairline bg-paper",
    dot: "bg-steel",
  },
  COMPLETED: {
    label: "Completed",
    icon: <Sparkles size={11} />,
    tone: "text-slate",
    ring: "border-hairline bg-ivory",
    dot: "bg-slate",
  },
  ON_HOLD: {
    label: "On hold",
    icon: <PauseCircle size={11} />,
    tone: "text-amber-flame",
    ring: "border-amber-flame/35 bg-amber-flame/10",
    dot: "bg-amber-flame",
  },
  CANCELLED: {
    label: "Cancelled",
    icon: <AlertTriangle size={11} />,
    tone: "text-burgundy",
    ring: "border-burgundy/30 bg-burgundy/8",
    dot: "bg-burgundy",
  },
};

const RAG_TONE: Record<string, { label: string; color: string; band: string }> = {
  GREEN: { label: "On track", color: "text-gold-deep", band: "bg-gold-deep" },
  AMBER: { label: "At risk", color: "text-amber-flame", band: "bg-amber-flame" },
  RED: { label: "Critical", color: "text-burgundy", band: "bg-burgundy" },
  CRIMSON: { label: "Critical", color: "text-burgundy", band: "bg-burgundy" },
};

const QUICK_ACTIONS = [
  {
    title: "Projects",
    href: "/projects",
    icon: FolderTree,
    blurb: "Portfolio, baselines, programme hierarchy.",
  },
  {
    title: "Resources",
    href: "/resources",
    icon: Users,
    blurb: "People, equipment, rate cards, allocations.",
  },
  {
    title: "Calendars",
    href: "/admin/calendars",
    icon: CalendarIcon,
    blurb: "Working days, holidays, shift patterns.",
  },
  {
    title: "Reports",
    href: "/reports",
    icon: BarChart3,
    blurb: "Earned-value, variance, executive summaries.",
  },
  {
    title: "Risk register",
    href: "/risk",
    icon: ShieldAlert,
    blurb: "Identification, scoring, mitigation log.",
  },
  {
    title: "Portfolios",
    href: "/portfolios",
    icon: Briefcase,
    blurb: "Multi-programme rollups & sponsor views.",
  },
  {
    title: "Schedule",
    href: "/dashboards/portfolio",
    icon: TrendingUp,
    blurb: "Cross-project Gantt and critical path.",
  },
  {
    title: "Compliance",
    href: "/dashboards/portfolio",
    icon: Gauge,
    blurb: "PFMS, GSTN, GeM, CPPP, PARIVESH checks.",
  },
];

export default function DashboardPage() {
  const [isClient, setIsClient] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const qc = useQueryClient();

  const { data: metrics, isLoading: metricsLoading } = useQuery<MetricsData>({
    queryKey: ["dashboard-metrics"],
    queryFn: fetchMetrics,
    staleTime: 60_000,
  });

  const { data: scorecard } = useQuery({
    queryKey: ["portfolio-scorecard"],
    queryFn: () => portfolioReportApi.getScorecard(),
    staleTime: 60_000,
  });

  const { data: delayedRows } = useQuery({
    queryKey: ["portfolio-delayed", 5],
    queryFn: () => portfolioReportApi.getDelayedProjects(5),
    staleTime: 60_000,
  });

  const { data: riskHeatmap } = useQuery({
    queryKey: ["portfolio-risk-heatmap"],
    queryFn: () => portfolioReportApi.getRiskHeatmap(),
    staleTime: 60_000,
  });

  const { data: cashFlow } = useQuery({
    queryKey: ["portfolio-cash-flow", 6],
    queryFn: () => portfolioReportApi.getCashFlowOutlook(6),
    staleTime: 60_000,
  });

  useEffect(() => setIsClient(true), []);

  const handleRefresh = async () => {
    setRefreshing(true);
    await qc.invalidateQueries();
    setTimeout(() => setRefreshing(false), 600);
  };

  const today = useMemo(
    () =>
      new Date().toLocaleDateString("en-US", {
        weekday: "long",
        year: "numeric",
        month: "long",
        day: "numeric",
      }),
    [],
  );

  const nowTime = useMemo(
    () =>
      new Date().toLocaleTimeString("en-IN", {
        hour: "2-digit",
        minute: "2-digit",
      }),
    [],
  );

  if (!isClient) return null;

  // Derived values
  const budget = scorecard?.totalBudgetCrores ?? 0;
  const committed = scorecard?.totalCommittedCrores ?? 0;
  const spent = scorecard?.totalSpentCrores ?? 0;
  const utilizationPct = budget > 0 ? (spent / budget) * 100 : 0;
  const commitmentPct = budget > 0 ? (committed / budget) * 100 : 0;
  const remaining = Math.max(budget - spent, 0);

  const ragGreen = scorecard?.rag.green ?? 0;
  const ragAmber = scorecard?.rag.amber ?? 0;
  const ragRed = scorecard?.rag.red ?? 0;
  const ragTotal = ragGreen + ragAmber + ragRed || 1;
  const healthScore = Math.round(
    (ragGreen * 100 + ragAmber * 60 + ragRed * 20) / ragTotal,
  );

  return (
    <div>
      {/* HERO HEADER */}
      <div className="relative mb-6 rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-parchment/40 p-6 shadow-[0_2px_4px_rgba(28,28,28,0.04),0_20px_50px_-30px_rgba(28,28,28,0.18)]">
        <div className="pointer-events-none absolute inset-0 overflow-hidden rounded-2xl">
          <div className="absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gold/10 blur-3xl" />
          <div className="absolute -bottom-24 left-1/3 h-56 w-56 rounded-full bg-gold-tint/40 blur-3xl" />
        </div>

        <div className="relative flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
          <div className="flex-1">
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/40 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
                <Sparkles size={11} />
                Today · {today}
              </span>
              <span className="inline-flex items-center gap-1.5 rounded-full border border-hairline bg-paper px-2.5 py-0.5 text-[10px] font-semibold text-slate">
                <Clock size={10} /> {nowTime} IST
              </span>
            </div>
            <h1
              className="font-display text-[36px] font-semibold leading-[1.05] tracking-tight text-charcoal"
              style={{ fontVariationSettings: "'opsz' 144" }}
            >
              Programme command centre
            </h1>
            <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
              Portfolio health, financial burn, schedule pressure and emerging risks —
              one glance from the chair, one click from the canvas.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={handleRefresh}
              className="inline-flex items-center gap-2 rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep"
            >
              <RefreshCw size={14} strokeWidth={1.75} className={refreshing ? "animate-spin" : ""} />
              Refresh
            </button>
            <Link
              href="/dashboards/portfolio"
              className="inline-flex items-center gap-2 rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep"
            >
              <BarChart3 size={14} strokeWidth={1.75} />
              Portfolio scorecard
            </Link>
            <Link
              href="/projects/new"
              className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-br from-gold to-gold-deep px-3.5 py-2 text-xs font-semibold text-paper shadow-[0_4px_12px_-2px_rgba(0,88,202,0.45)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_18px_-4px_rgba(0,88,202,0.55)]"
            >
              <Plus size={14} strokeWidth={2.5} />
              New project
            </Link>
          </div>
        </div>
      </div>

      {/* HERO KPI STRIP */}
      <div className="mb-6 grid grid-cols-1 gap-4 lg:grid-cols-12">
        {/* Hero — Portfolio value */}
        <div className="lg:col-span-5">
          {scorecard ? (
            <div className="group relative h-full overflow-hidden rounded-2xl border border-gold/30 bg-gradient-to-br from-gold-tint/60 via-paper to-paper p-6 shadow-[0_4px_20px_-8px_rgba(0,88,202,0.25)] transition-all duration-300 hover:shadow-[0_12px_40px_-12px_rgba(0,88,202,0.35)]">
              <div className="pointer-events-none absolute -right-16 -top-16 h-48 w-48 rounded-full bg-gold/10 blur-3xl" />
              <div className="pointer-events-none absolute -bottom-8 -left-8 h-32 w-32 rounded-full bg-gold-deep/8 blur-2xl" />
              <div className="relative flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-gold to-gold-deep text-paper shadow-[0_4px_12px_-2px_rgba(0,88,202,0.45)]">
                    <Wallet size={18} strokeWidth={2} />
                  </div>
                  <div>
                    <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
                      Total project value
                    </div>
                    <div className="text-xs text-slate">
                      Approved across {scorecard.totalProjects} project
                      {scorecard.totalProjects === 1 ? "" : "s"}
                    </div>
                  </div>
                </div>
                <span className="hidden items-center gap-1 rounded-full border border-gold/30 bg-paper/80 px-2.5 py-1 text-[10px] font-semibold text-gold-deep backdrop-blur md:inline-flex">
                  <Sparkles size={11} />
                  Live
                </span>
              </div>
              <div className="relative mt-5">
                <div
                  className="font-display text-[42px] font-semibold leading-none tracking-tight text-charcoal"
                  style={{ fontVariationSettings: "'opsz' 144" }}
                >
                  {formatCrore(budget)}
                </div>
                <div className="mt-1.5 text-xs text-slate">
                  Spent <span className="font-semibold text-charcoal">{formatPct(utilizationPct)}</span>
                  {" · "}
                  Committed <span className="font-semibold text-charcoal">{formatPct(commitmentPct)}</span>
                </div>
              </div>
              <div className="relative mt-5">
                <div className="relative h-2 w-full overflow-hidden rounded-full bg-ivory">
                  <div
                    className="absolute inset-y-0 left-0 rounded-full bg-gradient-to-r from-emerald to-emerald/70"
                    style={{ width: `${Math.min(utilizationPct, 100)}%` }}
                  />
                  <div
                    className="absolute inset-y-0 rounded-full bg-gradient-to-r from-gold to-gold-deep opacity-70"
                    style={{
                      left: `${Math.min(utilizationPct, 100)}%`,
                      width: `${Math.max(Math.min(commitmentPct - utilizationPct, 100 - utilizationPct), 0)}%`,
                    }}
                  />
                </div>
                <div className="mt-2.5 flex items-center justify-between text-[10px] font-medium text-slate">
                  <span className="inline-flex items-center gap-1.5">
                    <span className="h-2 w-2 rounded-full bg-emerald" />
                    Spent {formatCrore(spent, 1)}
                  </span>
                  <span className="inline-flex items-center gap-1.5">
                    <span className="h-2 w-2 rounded-full bg-gold" />
                    Committed {formatCrore(committed, 1)}
                  </span>
                  <span className="inline-flex items-center gap-1.5">
                    <span className="h-2 w-2 rounded-full bg-ivory ring-1 ring-hairline" />
                    Remaining {formatCrore(remaining, 1)}
                  </span>
                </div>
              </div>
            </div>
          ) : (
            <LoadingBlock label="Loading portfolio value…" />
          )}
        </div>

        {/* Supporting KPIs */}
        <div className="grid grid-cols-2 gap-3 lg:col-span-7 lg:grid-cols-4">
          <KpiTile
            label="Active projects"
            value={metricsLoading ? "—" : metrics?.activeCount ?? 0}
            hint={metricsLoading ? "Loading…" : "Currently in execution"}
            tone="accent"
            icon={<PlayCircle size={14} />}
          />
          <KpiTile
            label="At-risk active"
            value={scorecard?.activeProjectsWithCriticalActivities ?? 0}
            hint="With critical activities"
            tone={
              (scorecard?.activeProjectsWithCriticalActivities ?? 0) > 0
                ? "warning"
                : "default"
            }
            icon={<Zap size={14} />}
          />
          <KpiTile
            label="Critical risks"
            value={scorecard?.openRisksCritical ?? 0}
            hint={
              (scorecard?.openRisksCritical ?? 0) > 0
                ? "Open & escalating"
                : "All clear"
            }
            tone={(scorecard?.openRisksCritical ?? 0) > 0 ? "danger" : "success"}
            icon={(scorecard?.openRisksCritical ?? 0) > 0 ? <Flame size={14} /> : <ShieldAlert size={14} />}
          />
          <KpiTile
            label="Overdue tasks"
            value={metrics?.activities.overdueCount ?? 0}
            hint={
              (metrics?.activities.overdueCount ?? 0) > 0
                ? "Past planned finish"
                : "All on schedule"
            }
            tone={(metrics?.activities.overdueCount ?? 0) > 0 ? "warning" : "default"}
            icon={<AlertTriangle size={14} />}
          />
        </div>
      </div>

      {/* SECONDARY KPI ROW */}
      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <KpiTile
          label="Total activities"
          value={metricsLoading ? "—" : metrics?.activities.totalActivities ?? 0}
          icon={<Layers3 size={14} />}
        />
        <KpiTile
          label="Critical path"
          value={metrics?.activities.criticalActivities ?? 0}
          tone={(metrics?.activities.criticalActivities ?? 0) > 0 ? "warning" : "default"}
          icon={<TrendingUp size={14} />}
        />
        <KpiTile
          label="Resources"
          value={metrics?.resourceCount ?? 0}
          icon={<Users size={14} />}
        />
        <KpiTile
          label="Spent"
          value={formatCrore(spent, 1)}
          hint={`${formatPct(utilizationPct)} of budget`}
          tone="success"
          icon={<Banknote size={14} />}
        />
        <KpiTile
          label="Committed"
          value={formatCrore(committed, 1)}
          hint={`${formatPct(commitmentPct)} of budget`}
          icon={<TrendingUp size={14} />}
        />
        <KpiTile
          label="Health score"
          value={`${healthScore}/100`}
          hint={
            healthScore >= 80
              ? "Healthy portfolio"
              : healthScore >= 60
                ? "Watch list"
                : "Needs attention"
          }
          tone={healthScore >= 80 ? "accent" : healthScore >= 60 ? "warning" : "danger"}
          icon={<Gauge size={14} />}
        />
      </div>

      {/* LIFECYCLE STATUS PILLS */}
      {!metricsLoading && metrics && (
        <div className="mb-6 rounded-2xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.03)]">
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg border border-hairline bg-ivory text-gold-deep">
                <Briefcase size={14} />
              </div>
              <div>
                <div className="text-sm font-semibold text-charcoal">Project lifecycle</div>
                <div className="text-[11px] text-slate">
                  Distribution by status across the portfolio
                </div>
              </div>
            </div>
            <div className="flex items-baseline gap-1.5">
              <span
                className="font-display text-2xl font-semibold leading-none text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {(metrics.plannedCount + metrics.activeCount + metrics.completedCount + metrics.onHoldCount + metrics.cancelledCount)}
              </span>
              <span className="text-[10px] font-semibold uppercase tracking-wider text-slate">
                total
              </span>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 md:grid-cols-5">
            <StatusPill code="PLANNED" count={metrics.plannedCount} total={metrics.plannedCount + metrics.activeCount + metrics.completedCount + metrics.onHoldCount + metrics.cancelledCount} />
            <StatusPill code="ACTIVE" count={metrics.activeCount} total={metrics.plannedCount + metrics.activeCount + metrics.completedCount + metrics.onHoldCount + metrics.cancelledCount} />
            <StatusPill code="COMPLETED" count={metrics.completedCount} total={metrics.plannedCount + metrics.activeCount + metrics.completedCount + metrics.onHoldCount + metrics.cancelledCount} />
            <StatusPill code="ON_HOLD" count={metrics.onHoldCount} total={metrics.plannedCount + metrics.activeCount + metrics.completedCount + metrics.onHoldCount + metrics.cancelledCount} />
            <StatusPill code="CANCELLED" count={metrics.cancelledCount} total={metrics.plannedCount + metrics.activeCount + metrics.completedCount + metrics.onHoldCount + metrics.cancelledCount} />
          </div>
        </div>
      )}

      {/* TWO-COLUMN INSIGHTS */}
      <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Recent projects (spans 2) */}
        <div className="lg:col-span-2">
          <SectionCard
            title="Recent projects"
            subtitle="Latest activity across the portfolio"
            icon={<Briefcase size={16} />}
            actions={
              <Link
                href="/projects"
                className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-semibold text-gold-deep transition-colors hover:bg-gold-tint/40"
              >
                View all <span aria-hidden>→</span>
              </Link>
            }
          >
            {metrics?.recentProjects && metrics.recentProjects.length > 0 ? (
              <ul className="divide-y divide-hairline rounded-xl border border-hairline bg-ivory/30 overflow-hidden">
                {metrics.recentProjects.map((p) => {
                  const meta = STATUS_META[p.status] ?? STATUS_META.PLANNED;
                  return (
                    <li key={p.id}>
                      <Link
                        href={`/projects/${p.id}`}
                        className="group flex items-center justify-between gap-3 px-4 py-3 transition-colors hover:bg-paper"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className={`inline-flex h-1.5 w-1.5 shrink-0 rounded-full ${meta.dot}`} />
                            <span className="font-mono text-[10px] uppercase tracking-wide text-slate">
                              {p.code ?? "—"}
                            </span>
                          </div>
                          <div className="mt-0.5 truncate text-sm font-medium text-charcoal group-hover:text-gold-deep">
                            {p.name}
                          </div>
                        </div>
                        <span
                          className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[10px] font-semibold ${meta.ring} ${meta.tone}`}
                        >
                          {meta.icon}
                          {meta.label}
                        </span>
                        <span className="hidden text-[11px] font-medium text-slate sm:inline-block">
                          {new Date(p.createdAt).toLocaleDateString("en-IN", {
                            day: "numeric",
                            month: "short",
                            year: "numeric",
                          })}
                        </span>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            ) : (
              <EmptyBlock label="No recent projects" />
            )}
          </SectionCard>
        </div>

        {/* RAG mix card */}
        <div>
          <SectionCard
            title="RAG mix"
            subtitle="Schedule + cost banding"
            icon={<Gauge size={16} />}
            accent
            badge={
              <span
                className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold ${
                  healthScore >= 80
                    ? "border-gold/30 bg-gold-tint/40 text-gold-deep"
                    : healthScore >= 60
                      ? "border-amber-flame/35 bg-amber-flame/10 text-amber-flame"
                      : "border-burgundy/30 bg-burgundy/8 text-burgundy"
                }`}
              >
                {healthScore}/100
              </span>
            }
          >
            <div className="relative h-12 w-full overflow-hidden rounded-xl border border-hairline bg-paper">
              <div className="flex h-full w-full">
                {[
                  { label: "On track", count: ragGreen, color: "var(--gold-deep)" },
                  { label: "At risk", count: ragAmber, color: "var(--amber-flame)" },
                  { label: "Critical", count: ragRed, color: "var(--burgundy)" },
                ].map((seg) => {
                  const pct = (seg.count / ragTotal) * 100;
                  if (pct === 0) return null;
                  return (
                    <div
                      key={seg.label}
                      className="relative flex items-center justify-center"
                      style={{
                        width: `${pct}%`,
                        background: `linear-gradient(180deg, ${seg.color} 0%, ${seg.color}dd 100%)`,
                      }}
                      title={`${seg.label}: ${seg.count}`}
                    >
                      <div className="text-[11px] font-semibold text-white drop-shadow-sm">
                        {pct >= 18 ? `${seg.label} · ${seg.count}` : seg.count}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
            <div className="mt-3 grid grid-cols-3 gap-2 text-center">
              <RagMini label="On track" count={ragGreen} color="text-gold-deep" />
              <RagMini label="At risk" count={ragAmber} color="text-amber-flame" />
              <RagMini label="Critical" count={ragRed} color="text-burgundy" />
            </div>
          </SectionCard>
        </div>
      </div>

      {/* THREE INSIGHT CARDS */}
      <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Top delayed */}
        <SectionCard
          title="Top delayed projects"
          subtitle="Ranked by schedule slip (days)"
          icon={<Clock size={16} />}
          actions={
            <Link
              href="/dashboards/portfolio"
              className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-semibold text-gold-deep transition-colors hover:bg-gold-tint/40"
            >
              See all <span aria-hidden>→</span>
            </Link>
          }
        >
          {delayedRows && delayedRows.length > 0 ? (
            <ul className="space-y-2">
              {delayedRows.slice(0, 4).map((r) => {
                const tone = RAG_TONE[r.rag] ?? RAG_TONE.AMBER;
                return (
                  <li
                    key={r.projectId}
                    className="flex items-center gap-3 rounded-lg border border-hairline bg-ivory/30 p-3 transition-all hover:border-gold/30 hover:bg-paper"
                  >
                    <span className={`h-2 w-2 shrink-0 rounded-full ${tone.band}`} />
                    <div className="min-w-0 flex-1">
                      <div className="font-mono text-[10px] uppercase tracking-wide text-slate">
                        {r.projectCode}
                      </div>
                      <div className="truncate text-xs font-medium text-charcoal">
                        {r.projectName}
                      </div>
                    </div>
                    <div className="text-right">
                      <div
                        className={`font-display text-base font-semibold leading-none ${tone.color}`}
                        style={{ fontVariationSettings: "'opsz' 144" }}
                      >
                        {r.daysDelayed > 0 ? `+${r.daysDelayed}d` : `${r.daysDelayed}d`}
                      </div>
                      <div className="mt-0.5 text-[10px] text-slate">
                        SPI {r.spi > 0 ? r.spi.toFixed(2) : "—"}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          ) : (
            <EmptyBlock label="No delayed projects" />
          )}
        </SectionCard>

        {/* Top critical risks */}
        <SectionCard
          title="Top critical risks"
          subtitle="Ranked by exposure score"
          icon={<Flame size={16} />}
          actions={
            <Link
              href="/reports/risk-register"
              className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-semibold text-gold-deep transition-colors hover:bg-gold-tint/40"
            >
              Risk register <span aria-hidden>→</span>
            </Link>
          }
        >
          {riskHeatmap && riskHeatmap.topExposureRisks.length > 0 ? (
            <ul className="space-y-2">
              {riskHeatmap.topExposureRisks.slice(0, 4).map((r) => {
                const tone = RAG_TONE[r.rag] ?? RAG_TONE.AMBER;
                return (
                  <li
                    key={r.riskId}
                    className="flex items-start gap-3 rounded-lg border border-hairline bg-ivory/30 p-3 transition-all hover:border-gold/30 hover:bg-paper"
                  >
                    <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${tone.band}`} />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="font-mono text-[10px] uppercase tracking-wide text-slate">
                          {r.code}
                        </span>
                        <span className={`font-semibold text-[10px] uppercase tracking-wide ${tone.color}`}>
                          · {tone.label}
                        </span>
                      </div>
                      <div className="mt-0.5 text-xs leading-snug text-charcoal line-clamp-2">
                        {r.title}
                      </div>
                    </div>
                    <div
                      className={`shrink-0 font-display text-base font-semibold leading-none ${tone.color}`}
                      style={{ fontVariationSettings: "'opsz' 144" }}
                    >
                      {r.score.toFixed(0)}
                    </div>
                  </li>
                );
              })}
            </ul>
          ) : (
            <EmptyBlock label="No critical risks" />
          )}
        </SectionCard>

        {/* Cash flow next 6 months */}
        <SectionCard
          title="Next 6 months · cash"
          subtitle="Net monthly position (₹ Cr)"
          icon={<Banknote size={16} />}
          actions={
            <Link
              href="/dashboards/portfolio"
              className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-semibold text-gold-deep transition-colors hover:bg-gold-tint/40"
            >
              Detail <span aria-hidden>→</span>
            </Link>
          }
        >
          {cashFlow && cashFlow.length > 0 ? (
            <CashFlowSparkline data={cashFlow} />
          ) : (
            <EmptyBlock label="No cash forecast yet" />
          )}
        </SectionCard>
      </div>

      {/* QUICK ACTIONS */}
      <div className="mb-6">
        <h2
          className="mb-3.5 font-display text-xl font-semibold tracking-tight text-charcoal"
          style={{ fontVariationSettings: "'opsz' 144" }}
        >
          Jump back in
        </h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {QUICK_ACTIONS.map((card) => (
            <Link
              key={card.title}
              href={card.href}
              className="group relative overflow-hidden rounded-2xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.04)] transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_12px_32px_-14px_rgba(0,88,202,0.25)]"
            >
              <div className="pointer-events-none absolute -right-12 -top-12 h-28 w-28 rounded-full bg-gold/8 opacity-0 blur-2xl transition-opacity duration-300 group-hover:opacity-100" />
              <div className="mb-3.5 flex h-10 w-10 items-center justify-center rounded-xl border border-gold/25 bg-gradient-to-br from-gold-tint/60 to-paper text-gold-deep shadow-sm">
                <card.icon size={18} strokeWidth={1.5} />
              </div>
              <div
                className="font-display text-lg font-semibold leading-tight tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {card.title}
              </div>
              <p className="mt-1 text-xs leading-relaxed text-slate">{card.blurb}</p>
              <span
                aria-hidden
                className="absolute right-5 top-5 text-sm text-gold-deep opacity-40 transition-all duration-200 group-hover:translate-x-0.5 group-hover:opacity-100"
              >
                →
              </span>
            </Link>
          ))}
        </div>
      </div>
    </div>
  );
}

function StatusPill({
  code,
  count,
  total,
}: {
  code: string;
  count: number;
  total: number;
}) {
  const meta = STATUS_META[code];
  if (!meta) return null;
  const pct = total > 0 ? (count / total) * 100 : 0;
  const isZero = count === 0;

  return (
    <div
      className={`group relative overflow-hidden rounded-xl border p-3 transition-all duration-200 hover:-translate-y-0.5 ${
        isZero
          ? "border-hairline bg-ivory/50"
          : "border-hairline bg-paper hover:border-gold/30 hover:shadow-[0_6px_16px_-10px_rgba(0,88,202,0.25)]"
      }`}
    >
      <div className="flex items-center justify-between">
        <div
          className={`flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.1em] ${meta.tone}`}
        >
          {meta.icon}
          {meta.label}
        </div>
        {!isZero && (
          <span className="text-[10px] font-semibold text-slate">
            {pct.toFixed(0)}%
          </span>
        )}
      </div>
      <div
        className={`mt-1.5 font-display text-2xl font-semibold leading-none tracking-tight ${
          isZero ? "text-ash" : "text-charcoal"
        }`}
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {count}
      </div>
      <div className="mt-2.5 h-1 w-full overflow-hidden rounded-full bg-ivory">
        <div
          className={`h-full rounded-full transition-all duration-500 ${meta.dot}`}
          style={{ width: isZero ? "0%" : `${Math.max(pct, 4)}%` }}
        />
      </div>
    </div>
  );
}

function RagMini({
  label,
  count,
  color,
}: {
  label: string;
  count: number;
  color: string;
}) {
  return (
    <div className="rounded-lg border border-hairline bg-paper p-2.5 transition-all hover:-translate-y-0.5 hover:border-gold/25 hover:shadow-[0_6px_16px_-10px_rgba(0,88,202,0.25)]">
      <div className="text-[10px] font-semibold uppercase tracking-[0.1em] text-slate">
        {label}
      </div>
      <div
        className={`mt-1 font-display text-xl font-semibold leading-none ${color}`}
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {count}
      </div>
    </div>
  );
}

function CashFlowSparkline({
  data,
}: {
  data: Array<{
    yearMonth: string;
    plannedOutflowCrores: number;
    plannedInflowCrores: number;
    netCrores: number;
  }>;
}) {
  const points = data.slice(0, 6);
  const maxAbs = Math.max(
    ...points.map((p) => Math.abs(p.netCrores)),
    Math.abs(points[points.length - 1]?.netCrores ?? 0),
    1,
  );

  return (
    <div>
      <div className="flex h-32 items-end gap-1.5">
        {points.map((p) => {
          const heightPct = (Math.abs(p.netCrores) / maxAbs) * 100;
          const isPositive = p.netCrores >= 0;
          return (
            <div
              key={p.yearMonth}
              className="group relative flex flex-1 flex-col items-center justify-end"
              title={`${p.yearMonth}: net ₹${p.netCrores.toFixed(1)} Cr`}
            >
              <div
                className={`w-full rounded-t-md transition-all duration-300 ${
                  isPositive
                    ? "bg-gradient-to-t from-gold to-gold-deep"
                    : "bg-gradient-to-t from-burgundy/70 to-burgundy"
                } group-hover:opacity-90`}
                style={{ height: `${Math.max(heightPct, 6)}%` }}
              />
              <div className="mt-1.5 text-[9px] font-medium uppercase tracking-wide text-slate">
                {p.yearMonth.slice(5)}
              </div>
            </div>
          );
        })}
      </div>
      <div className="mt-3 flex items-center justify-between border-t border-hairline pt-3 text-[11px] text-slate">
        <span>
          Net {formatCrore(points.reduce((s, p) => s + p.netCrores, 0), 1)}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-gold-deep" /> Surplus
          <span className="ml-2 h-2 w-2 rounded-full bg-burgundy" /> Burn
        </span>
      </div>
    </div>
  );
}
