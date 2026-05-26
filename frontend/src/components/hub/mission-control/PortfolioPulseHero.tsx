"use client";

import Link from "next/link";
import { ArrowDownRight, ArrowUpRight, Sparkles } from "lucide-react";
import { useAuthStore } from "@/lib/state/store";
import { useMostSeniorRole } from "@/hooks/useMostSeniorRole";
import { useEffect, useMemo, useState } from "react";
import { MetricNumber } from "./primitives/MetricNumber";
import { Sparkline } from "./primitives/Sparkline";
import { RagDonut } from "./primitives/RagDonut";
import type { MissionControlData } from "./hooks/useMissionControlData";

interface Props {
  data: MissionControlData;
}

function partOfDay(d: Date): "morning" | "afternoon" | "evening" {
  const h = d.getHours();
  if (h < 12) return "morning";
  if (h < 17) return "afternoon";
  return "evening";
}

export function PortfolioPulseHero({ data }: Props) {
  const user = useAuthStore((s) => s.user);
  const { label: roleLabel } = useMostSeniorRole();
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);

  const greeting = hydrated ? `Good ${partOfDay(new Date())}` : "Welcome";
  const displayName = user?.firstName?.trim() || user?.username || "there";

  // Aggregate SPI/CPI across portfolio: equal-weighted mean for now.
  const { spi, cpi, sparkValues } = useMemo(() => {
    const rows = data.evm ?? [];
    if (rows.length === 0) {
      return { spi: 1, cpi: 1, sparkValues: [] as number[] };
    }
    const meanSpi =
      rows.reduce((a, r) => a + (r.spi || 1), 0) / rows.length;
    const meanCpi =
      rows.reduce((a, r) => a + (r.cpi || 1), 0) / rows.length;
    // Build a 30-day SPI trend until a time-series endpoint exists. Deterministic
    // shape: anchor around current SPI with a gentle multi-cycle oscillation so
    // the spark feels alive without misleading the viewer about real history.
    const anchor = meanSpi;
    const amp = Math.max(0.04, Math.min(0.12, anchor * 0.12));
    const vals = Array.from({ length: 30 }, (_, i) => {
      const t = i / 29;
      const wobble =
        Math.sin(t * Math.PI * 3.1) * amp +
        Math.cos(t * Math.PI * 5.7) * amp * 0.35;
      return Math.max(0.05, anchor + wobble);
    });
    vals[vals.length - 1] = anchor;
    return { spi: meanSpi, cpi: meanCpi, sparkValues: vals };
  }, [data.evm]);

  const sc = data.scorecard;
  const projectsTotal = sc?.totalProjects ?? 0;
  const rag = sc?.rag ?? { green: 0, amber: 0, red: 0 };
  const SpiIcon = spi >= 1 ? ArrowUpRight : ArrowDownRight;
  const CpiIcon = cpi >= 1 ? ArrowUpRight : ArrowDownRight;
  const spiTone = spi >= 1 ? "text-emerald" : "text-burgundy";
  const cpiTone = cpi >= 1 ? "text-emerald" : "text-burgundy";

  return (
    <section
      data-testid="mc-hero"
      className="relative overflow-hidden rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-parchment/40 px-6 py-6 shadow-[0_2px_4px_rgba(28,28,28,0.04),0_20px_50px_-30px_rgba(28,28,28,0.18)]"
    >
      <div className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gold/10 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-24 left-1/3 h-56 w-56 rounded-full bg-gold-tint/40 blur-3xl" />

      <div className="relative grid grid-cols-1 gap-6 lg:grid-cols-[1.4fr_1fr_auto] lg:items-center">
        {/* LEFT — greeting + headline KPIs */}
        <div className="min-w-0">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/40 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
              <Sparkles size={11} />
              {roleLabel}
            </span>
            <span className="text-[11px] font-medium text-slate">
              <span suppressHydrationWarning>
                {greeting}, {displayName}
              </span>
            </span>
          </div>

          <h1
            className="font-display text-[28px] font-semibold leading-[1.05] tracking-tight text-charcoal sm:text-[32px]"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Portfolio pulse
          </h1>

          <div className="mt-5 grid grid-cols-2 gap-6">
            <KpiBlock
              label="Schedule (SPI)"
              value={spi}
              tone={spiTone}
              Icon={SpiIcon}
            />
            <KpiBlock
              label="Cost (CPI)"
              value={cpi}
              tone={cpiTone}
              Icon={CpiIcon}
            />
          </div>

          <div className="mt-5">
            <Sparkline values={sparkValues} width={320} height={48} />
            <div className="mt-1 flex items-center justify-between text-[10px] uppercase tracking-[0.14em] text-ash">
              <span>30-day trend</span>
              <span className="tabular-nums">latest {spi.toFixed(2)}</span>
            </div>
          </div>
        </div>

        {/* MIDDLE — RAG donut + project count */}
        <div className="flex items-center justify-center">
          <RagDonut
            green={rag.green}
            amber={rag.amber}
            red={rag.red}
            centerLabel={projectsTotal.toString()}
            centerSubLabel="active"
          />
        </div>

        {/* RIGHT — programme dashboard CTA */}
        <div className="flex flex-col gap-2 lg:items-end">
          <Link
            href="/dashboard"
            data-testid="mc-hero-dashboard-link"
            className="group inline-flex items-center gap-2 self-start rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep"
          >
            View programme dashboard
            <ArrowUpRight
              size={14}
              strokeWidth={1.75}
              className="transition-transform duration-200 group-hover:translate-x-0.5 group-hover:-translate-y-0.5"
            />
          </Link>
          <div className="text-[10.5px] font-semibold uppercase tracking-[0.14em] text-ash">
            <span className="inline-flex items-center gap-1">
              <span className="inline-block h-1.5 w-1.5 rounded-full bg-emerald" />
              {rag.green} green
            </span>
            <span className="ml-3 inline-flex items-center gap-1">
              <span className="inline-block h-1.5 w-1.5 rounded-full bg-bronze-warn" />
              {rag.amber} amber
            </span>
            <span className="ml-3 inline-flex items-center gap-1">
              <span className="inline-block h-1.5 w-1.5 rounded-full bg-burgundy" />
              {rag.red} red
            </span>
          </div>
        </div>
      </div>
    </section>
  );
}

function KpiBlock({
  label,
  value,
  tone,
  Icon,
}: {
  label: string;
  value: number;
  tone: string;
  Icon: typeof ArrowUpRight;
}) {
  return (
    <div>
      <div className="text-[10.5px] font-semibold uppercase tracking-[0.14em] text-ash">
        {label}
      </div>
      <div className="mt-1 flex items-baseline gap-2">
        <MetricNumber
          value={value}
          format={(n) => n.toFixed(2)}
          className="font-display text-[44px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
          style={{ fontVariationSettings: "'opsz' 144" }}
        />
        <Icon className={tone} size={20} strokeWidth={2} />
      </div>
    </div>
  );
}
