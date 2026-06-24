"use client";

import React from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  Banknote,
  CalendarClock,
  ClipboardList,
  Coins,
  Flag,
  Gauge,
  HardHat,
  Layers3,
  Receipt,
  ShieldAlert,
  Target,
  TrendingUp,
} from "lucide-react";
import { KpiTile } from "@/components/common/KpiTile";
import {
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  formatPct,
} from "@/components/common/dashboard/primitives";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";

type RagBand = "good" | "amber" | "red" | "neutral";

const RAG_BAND: Record<string, RagBand> = {
  GREEN: "good",
  AMBER: "amber",
  RED: "red",
  CRIMSON: "red",
  GREY: "neutral",
};

const RAG_LABEL: Record<string, string> = {
  GREEN: "On track",
  AMBER: "At risk",
  RED: "Critical",
  CRIMSON: "Critical",
  GREY: "No data",
};

const RAG_TONE: Record<RagBand, {
  bg: string;
  ring: string;
  dot: string;
  label: string;
  glow: string;
}> = {
  good: {
    bg: "from-gold-tint/70 via-paper to-paper",
    ring: "border-gold/55",
    dot: "bg-gold-deep",
    label: "text-gold-deep",
    glow: "bg-gold/20",
  },
  amber: {
    bg: "from-amber-flame/20 via-paper to-paper",
    ring: "border-amber-flame/55",
    dot: "bg-amber-flame",
    label: "text-amber-flame",
    glow: "bg-amber-flame/20",
  },
  red: {
    bg: "from-burgundy/22 via-paper to-paper",
    ring: "border-burgundy/55",
    dot: "bg-burgundy",
    label: "text-burgundy",
    glow: "bg-burgundy/22",
  },
  neutral: {
    bg: "from-ivory via-paper to-paper",
    ring: "border-hairline",
    dot: "bg-ash",
    label: "text-slate",
    glow: "bg-ivory",
  },
};

function RagTile({
  label,
  rag,
  icon,
}: {
  label: string;
  rag: string;
  icon: React.ReactNode;
}) {
  const band: RagBand = RAG_BAND[rag] ?? "neutral";
  const tone = RAG_TONE[band];
  const niceLabel = RAG_LABEL[rag] ?? rag;

  return (
    <div
      className={`group relative overflow-hidden rounded-2xl border-2 bg-gradient-to-br ${tone.bg} ${tone.ring} p-5 shadow-[0_2px_4px_rgba(28,28,28,0.05)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_14px_32px_-14px_rgba(28,28,28,0.22)]`}
    >
      {/* Top status bar — solid colour stripe makes the band readable from across the room */}
      <div className={`pointer-events-none absolute inset-x-0 top-0 h-1 ${tone.dot}`} />
      <div
        className={`pointer-events-none absolute -right-10 -top-10 h-28 w-28 rounded-full ${tone.glow} blur-2xl`}
      />
      <div className="relative flex items-start justify-between">
        <div
          className={`flex h-9 w-9 items-center justify-center rounded-xl border-2 ${tone.ring} bg-paper ${tone.label} shadow-sm`}
        >
          {icon}
        </div>
        <span
          className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[10px] font-semibold text-paper shadow-[0_2px_6px_-2px_rgba(28,28,28,0.2)] ${tone.dot}`}
        >
          <span className="h-1.5 w-1.5 rounded-full bg-paper/90" />
          {niceLabel}
        </span>
      </div>
      <div className="relative mt-4">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-slate">
          {label}
        </div>
        <div
          className={`mt-1 font-display text-[28px] font-semibold leading-none tracking-tight ${tone.label}`}
          style={{ fontVariationSettings: "'opsz' 144" }}
        >
          {rag === "GREY" ? "N/A" : rag}
        </div>
      </div>
    </div>
  );
}

function indexBand(v: number): "good" | "amber" | "red" | "neutral" {
  if (!Number.isFinite(v) || v === 0) return "neutral";
  if (v >= 0.95) return "good";
  if (v >= 0.85) return "amber";
  return "red";
}

const TILE_TONE_TO_KPI: Record<
  "good" | "amber" | "red" | "neutral",
  "default" | "success" | "warning" | "danger" | "accent"
> = {
  good: "accent",
  amber: "warning",
  red: "danger",
  neutral: "default",
};

export function ProjectStatusSnapshot({ projectId }: { projectId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["project-status-snapshot", projectId],
    queryFn: () => projectInsightsApi.getStatusSnapshot(projectId),
  });

  // BAC/EAC arrive in CRORE units (raw ÷ 1e7). Recover the raw amount and render
  // per-currency (k/L/Cr for INR, K/M/B otherwise). Optional hook + INR fallback
  // so it is safe if ever rendered outside a project route. Display-only.
  const cur = useProjectCurrencyOptional();
  const moneyCompact = cur
    ? cur.moneyCompact
    : (v: number | null | undefined) => formatMoney(v, { code: "INR" }, { compact: true });

  if (isLoading)
    return (
      <SectionCard title="Project status" icon={<Gauge size={16} />}>
        <LoadingBlock label="Loading status snapshot…" />
      </SectionCard>
    );
  if (isError || !data)
    return (
      <SectionCard title="Project status" icon={<Gauge size={16} />}>
        <EmptyBlock label="Status snapshot unavailable" />
      </SectionCard>
    );

  const s = data;
  const cpiBand = indexBand(s.currentCpi);
  const spiBand = indexBand(s.currentSpi);
  const physBand =
    s.physicalPct >= s.plannedPct - 5
      ? "good"
      : s.physicalPct >= s.plannedPct - 15
        ? "amber"
        : "red";
  const eacOverrunPct =
    s.bacCrores > 0 ? ((s.eacCrores - s.bacCrores) / s.bacCrores) * 100 : 0;
  const eacBand =
    eacOverrunPct <= 2 ? "good" : eacOverrunPct <= 8 ? "amber" : "red";

  return (
    <div className="space-y-6">
      {/* RAG header card with project meta */}
      <div className="relative overflow-hidden rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-parchment/40 p-6 shadow-[0_2px_4px_rgba(28,28,28,0.04),0_20px_50px_-30px_rgba(28,28,28,0.18)]">
        <div className="pointer-events-none absolute -right-20 -top-20 h-56 w-56 rounded-full bg-gold/10 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-24 left-1/4 h-44 w-44 rounded-full bg-gold-tint/40 blur-3xl" />

        <div className="relative mb-5 flex flex-wrap items-end justify-between gap-3">
          <div>
            <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
              {s.projectCode} · status snapshot
            </div>
            <h2
              className="mt-0.5 font-display text-2xl font-semibold leading-tight tracking-tight text-charcoal"
              style={{ fontVariationSettings: "'opsz' 144" }}
            >
              Health across five dimensions
            </h2>
            <p className="mt-1 text-xs text-slate">
              {s.plannedStart && s.plannedFinish
                ? `Planned ${s.plannedStart} → ${s.plannedFinish}`
                : "Planning window not set"}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-hairline bg-paper px-2.5 py-1">
              <CalendarClock size={11} />
              Data date {new Date(s.lastUpdatedAt).toLocaleString("en-IN", {
                dateStyle: "medium",
                timeStyle: "short",
              })}
            </span>
          </div>
        </div>

        <div className="relative grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <RagTile label="Schedule" rag={s.scheduleRag} icon={<CalendarClock size={16} />} />
          <RagTile label="Cost" rag={s.costRag} icon={<Banknote size={16} />} />
          <RagTile label="Scope" rag={s.scopeRag} icon={<Layers3 size={16} />} />
          <RagTile label="Risk" rag={s.riskRag} icon={<ShieldAlert size={16} />} />
          <RagTile label="HSE" rag={s.hseRag} icon={<HardHat size={16} />} />
        </div>
      </div>

      {/* Performance metrics */}
      <SectionCard
        title="Performance metrics"
        subtitle="Earned-value indices and progress against plan"
        icon={<TrendingUp size={16} />}
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
          <KpiTile
            label="CPI"
            value={s.currentCpi.toFixed(2)}
            hint={
              cpiBand === "good"
                ? "On budget"
                : cpiBand === "amber"
                  ? "Watch for overrun"
                  : cpiBand === "red"
                    ? "Cost overrun"
                    : "No EVM data"
            }
            tone={TILE_TONE_TO_KPI[cpiBand]}
            icon={<Coins size={14} />}
          />
          <KpiTile
            label="SPI"
            value={s.currentSpi.toFixed(2)}
            hint={
              spiBand === "good"
                ? "On schedule"
                : spiBand === "amber"
                  ? "Slipping"
                  : spiBand === "red"
                    ? "Behind plan"
                    : "No EVM data"
            }
            tone={TILE_TONE_TO_KPI[spiBand]}
            icon={<Gauge size={14} />}
          />
          <KpiTile
            label="Physical %"
            value={formatPct(s.physicalPct, 1)}
            hint={
              physBand === "good"
                ? "Tracking plan"
                : physBand === "amber"
                  ? `${(s.plannedPct - s.physicalPct).toFixed(1)}% behind`
                  : "Significantly behind"
            }
            tone={TILE_TONE_TO_KPI[physBand]}
            icon={<Target size={14} />}
          />
          <KpiTile
            label="Planned %"
            value={formatPct(s.plannedPct, 1)}
            hint="As of data date"
            icon={<ClipboardList size={14} />}
          />
          <KpiTile
            label="BAC"
            value={moneyCompact(s.bacCrores * 1e7)}
            hint="Budget at completion"
            tone="accent"
            icon={<Banknote size={14} />}
          />
          <KpiTile
            label="EAC"
            value={moneyCompact(s.eacCrores * 1e7)}
            hint={
              eacBand === "good"
                ? "Within budget"
                : eacBand === "amber"
                  ? `${eacOverrunPct.toFixed(1)}% over BAC`
                  : eacBand === "red"
                    ? `${eacOverrunPct.toFixed(1)}% over BAC`
                    : "Estimate at completion"
            }
            tone={TILE_TONE_TO_KPI[eacBand]}
            icon={<Receipt size={14} />}
          />
        </div>

        {/* Plan vs actual progress meter */}
        <div className="mt-5 rounded-xl border border-hairline bg-ivory/40 p-4">
          <div className="mb-2 flex items-center justify-between">
            <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-slate">
              Progress against plan
            </div>
            <div className="text-[11px] font-medium text-slate">
              <span className="text-charcoal font-semibold">
                {formatPct(s.physicalPct, 1)}
              </span>{" "}
              actual ·{" "}
              <span className="text-charcoal font-semibold">
                {formatPct(s.plannedPct, 1)}
              </span>{" "}
              planned
            </div>
          </div>
          <div className="relative h-2.5 w-full overflow-hidden rounded-full bg-paper ring-1 ring-hairline">
            <div
              className="absolute inset-y-0 left-0 rounded-full bg-gradient-to-r from-gold to-gold-deep"
              style={{ width: `${Math.min(s.physicalPct, 100)}%` }}
            />
            {/* planned marker */}
            <div
              className="absolute inset-y-0 w-[2px] bg-charcoal/70"
              style={{ left: `${Math.min(s.plannedPct, 100)}%` }}
              title={`Planned: ${formatPct(s.plannedPct, 1)}`}
            />
          </div>
        </div>
      </SectionCard>

      {/* Issues + Next milestone */}
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        <SectionCard
          title="Top issues"
          icon={<AlertTriangle size={16} />}
          badge={
            s.topIssues.length > 0 ? (
              <span className="inline-flex items-center gap-1 rounded-full border border-burgundy/25 bg-burgundy/8 px-2 py-0.5 text-[10px] font-semibold text-burgundy">
                {s.topIssues.length} open
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 rounded-full border border-gold/25 bg-gold-tint/40 px-2 py-0.5 text-[10px] font-semibold text-gold-deep">
                All clear
              </span>
            )
          }
        >
          {s.topIssues.length === 0 ? (
            <EmptyBlock label="No open issues" />
          ) : (
            <ul className="space-y-2">
              {s.topIssues.map((t, idx) => (
                <li
                  key={`${idx}-${t.slice(0, 24)}`}
                  className="flex items-start gap-3 rounded-lg border border-hairline bg-ivory/40 px-3 py-2.5 transition-colors hover:border-gold/30 hover:bg-paper"
                >
                  <span className="mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-md border border-burgundy/25 bg-burgundy/8 text-[10px] font-bold text-burgundy">
                    {idx + 1}
                  </span>
                  <span className="text-sm leading-relaxed text-charcoal">{t}</span>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>

        <SectionCard
          title="Next milestone"
          icon={<Flag size={16} />}
          badge={
            s.nextMilestoneDate ? (
              <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-paper px-2 py-0.5 text-[10px] font-semibold text-slate">
                <CalendarClock size={10} />
                {s.nextMilestoneDate}
              </span>
            ) : null
          }
        >
          {s.nextMilestoneName ? (
            <div className="rounded-xl border border-hairline bg-ivory/40 p-4">
              <p
                className="font-display text-lg font-semibold leading-tight tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {s.nextMilestoneName}
              </p>
              <p className="mt-1 text-xs text-slate">
                Planned for {s.nextMilestoneDate ?? "—"}
              </p>
            </div>
          ) : (
            <EmptyBlock label="No upcoming milestones" />
          )}

          <div className="mt-3 grid grid-cols-2 gap-3">
            <div className="rounded-xl border border-hairline bg-paper p-3 transition-all hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_6px_16px_-10px_rgba(0,88,202,0.25)]">
              <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                <ShieldAlert size={11} className="text-bronze-warn" />
                Active risks
              </div>
              <div
                className={`mt-1 font-display text-2xl font-semibold leading-none ${
                  s.activeRisksCount > 0 ? "text-bronze-warn" : "text-charcoal"
                }`}
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {s.activeRisksCount}
              </div>
            </div>
            <div className="rounded-xl border border-hairline bg-paper p-3 transition-all hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_6px_16px_-10px_rgba(0,88,202,0.25)]">
              <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                <HardHat size={11} className="text-burgundy" />
                Open HSE
              </div>
              <div
                className={`mt-1 font-display text-2xl font-semibold leading-none ${
                  s.openHseIncidents > 0 ? "text-burgundy" : "text-charcoal"
                }`}
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {s.openHseIncidents}
              </div>
            </div>
          </div>
        </SectionCard>
      </div>
    </div>
  );
}
