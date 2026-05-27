"use client";

import { useQuery } from "@tanstack/react-query";
import {
  Briefcase,
  CheckCircle2,
  Clock,
  Coins,
  Flame,
  PauseCircle,
  PlayCircle,
  ShieldAlert,
  Sparkles,
  TrendingUp,
  Wallet,
  XCircle,
  Zap,
} from "lucide-react";
import { KpiTile } from "@/components/common/KpiTile";
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
import {
  EmptyBlock,
  LoadingBlock,
  formatCrore,
  formatPct,
} from "@/components/common/dashboard/primitives";

const STATUS_META: Record<
  string,
  { label: string; icon: React.ReactNode; color: string }
> = {
  PLANNED: { label: "Planned", icon: <Clock size={14} />, color: "text-steel" },
  ACTIVE: { label: "Active", icon: <PlayCircle size={14} />, color: "text-emerald" },
  COMPLETED: {
    label: "Completed",
    icon: <CheckCircle2 size={14} />,
    color: "text-slate",
  },
  ON_HOLD: {
    label: "On hold",
    icon: <PauseCircle size={14} />,
    color: "text-bronze-warn",
  },
  CANCELLED: { label: "Cancelled", icon: <XCircle size={14} />, color: "text-burgundy" },
};

export function PortfolioKpiRow() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-scorecard"],
    queryFn: () => portfolioReportApi.getScorecard(),
    staleTime: 60_000,
  });

  if (isLoading) return <LoadingBlock label="Loading portfolio insights…" />;
  if (isError || !data) return <EmptyBlock label="Portfolio KPIs unavailable" />;

  const planned = data.byStatus?.PLANNED ?? 0;
  const active = data.byStatus?.ACTIVE ?? 0;
  const completed = data.byStatus?.COMPLETED ?? 0;
  const onHold = data.byStatus?.ON_HOLD ?? 0;
  const cancelled = data.byStatus?.CANCELLED ?? 0;

  const budget = data.totalBudgetCrores ?? 0;
  const committed = data.totalCommittedCrores ?? 0;
  const spent = data.totalSpentCrores ?? 0;
  const utilizationPct = budget > 0 ? (spent / budget) * 100 : 0;
  const commitmentPct = budget > 0 ? (committed / budget) * 100 : 0;
  const remainingCr = Math.max(budget - spent, 0);

  return (
    <div className="space-y-5">
      {/* HERO ROW — Portfolio Value + supporting KPIs */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        {/* Hero card — gold gradient */}
        <div className="lg:col-span-5">
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
                    Portfolio value
                  </div>
                  <div className="text-xs text-slate">Approved budget across all projects</div>
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
                {data.totalProjects} project{data.totalProjects === 1 ? "" : "s"} · committed{" "}
                <span className="font-semibold text-charcoal">{formatPct(commitmentPct)}</span>{" "}
                · spent{" "}
                <span className="font-semibold text-charcoal">{formatPct(utilizationPct)}</span>
              </div>
            </div>

            {/* Budget burn meter */}
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
                  Remaining {formatCrore(remainingCr, 1)}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Supporting KPIs — 4 in a 2×2 grid on lg, single row on xl */}
        <div className="grid grid-cols-2 gap-3 lg:col-span-7 lg:grid-cols-4">
          <KpiTile
            label="Spent"
            value={formatCrore(spent, 1)}
            hint={`${formatPct(utilizationPct)} of budget`}
            icon={<Coins size={14} />}
            tone="success"
          />
          <KpiTile
            label="Committed"
            value={formatCrore(committed, 1)}
            hint={`${formatPct(commitmentPct)} of budget`}
            icon={<TrendingUp size={14} />}
          />
          <KpiTile
            label="At-risk active"
            value={data.activeProjectsWithCriticalActivities}
            hint="With critical activities"
            tone={data.activeProjectsWithCriticalActivities > 0 ? "warning" : "default"}
            icon={<Zap size={14} />}
          />
          <KpiTile
            label="Critical risks"
            value={data.openRisksCritical}
            hint={data.openRisksCritical > 0 ? "Open & escalating" : "All clear"}
            tone={data.openRisksCritical > 0 ? "danger" : "success"}
            icon={
              data.openRisksCritical > 0 ? (
                <Flame size={14} />
              ) : (
                <ShieldAlert size={14} />
              )
            }
          />
        </div>
      </div>

      {/* LIFECYCLE ROW — total + status pills */}
      <div className="rounded-2xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.03)]">
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
              {data.totalProjects}
            </span>
            <span className="text-[10px] font-semibold uppercase tracking-wider text-slate">
              total
            </span>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 md:grid-cols-5">
          <StatusPill code="PLANNED" count={planned} total={data.totalProjects} />
          <StatusPill code="ACTIVE" count={active} total={data.totalProjects} />
          <StatusPill code="COMPLETED" count={completed} total={data.totalProjects} />
          <StatusPill code="ON_HOLD" count={onHold} total={data.totalProjects} />
          <StatusPill code="CANCELLED" count={cancelled} total={data.totalProjects} />
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
          className={`flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.1em] ${meta.color}`}
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
      >
        {count}
      </div>
      {/* Mini progress sparkbar */}
      <div className="mt-2.5 h-1 w-full overflow-hidden rounded-full bg-ivory">
        <div
          className={`h-full rounded-full transition-all duration-500 ${
            code === "ACTIVE"
              ? "bg-gradient-to-r from-emerald to-emerald/70"
              : code === "ON_HOLD"
                ? "bg-gradient-to-r from-bronze-warn to-bronze-warn/70"
                : code === "CANCELLED"
                  ? "bg-gradient-to-r from-burgundy to-burgundy/70"
                  : code === "COMPLETED"
                    ? "bg-gradient-to-r from-slate to-slate/60"
                    : "bg-gradient-to-r from-steel to-steel/60"
          }`}
          style={{ width: isZero ? "0%" : `${Math.max(pct, 4)}%` }}
        />
      </div>
    </div>
  );
}

