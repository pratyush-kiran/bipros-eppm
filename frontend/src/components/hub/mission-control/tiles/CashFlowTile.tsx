"use client";

import { Wallet, TrendingDown, TrendingUp } from "lucide-react";
import { MetricTile } from "../MetricTile";
import { MetricNumber } from "../primitives/MetricNumber";
import { Sparkline } from "../primitives/Sparkline";
import type { CashFlowOutlookPoint } from "@/lib/api/portfolioReportApi";

interface Props {
  points: CashFlowOutlookPoint[] | null;
}

function formatCrores(n: number): string {
  if (Math.abs(n) >= 100) return `${n.toFixed(0)} Cr`;
  return `${n.toFixed(1)} Cr`;
}

export function CashFlowTile({ points }: Props) {
  const data = points ?? [];
  const hasMovement = data.some((p) => p.netCrores !== 0);
  const latest = data[data.length - 1] ?? null;
  const prev = data[data.length - 2] ?? null;
  const values = data.map((p) => p.netCrores);
  const net = latest?.netCrores ?? 0;
  const delta = latest && prev ? latest.netCrores - prev.netCrores : 0;
  const TrendIcon = delta >= 0 ? TrendingUp : TrendingDown;
  const trendTone = delta >= 0 ? "text-emerald" : "text-burgundy";

  return (
    <MetricTile
      title="Cash flow · this month"
      icon={Wallet}
      href="/reports/cash-flow"
      testid="mc-tile-cashflow"
    >
      {hasMovement ? (
        <>
          <div className="flex items-baseline gap-2">
            <MetricNumber
              value={net}
              format={formatCrores}
              className="font-display text-[34px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
              style={{ fontVariationSettings: "'opsz' 144" }}
            />
            <span
              className={`inline-flex items-center gap-1 text-[12px] font-semibold tabular-nums ${trendTone}`}
            >
              <TrendIcon size={14} strokeWidth={2} />
              {delta >= 0 ? "+" : ""}
              {delta.toFixed(1)} Cr
            </span>
          </div>
          <div className="mt-4">
            <Sparkline values={values} width={240} height={36} />
            <div className="mt-1.5 flex justify-between text-[10px] font-medium uppercase tracking-[0.14em] text-ash">
              <span>{data[0]?.yearMonth ?? "—"}</span>
              <span>{latest?.yearMonth ?? "—"}</span>
            </div>
          </div>
        </>
      ) : (
        <>
          <div className="flex items-baseline gap-2">
            <span
              className="font-display text-[34px] font-semibold leading-none tracking-tight text-ash tabular-nums"
              style={{ fontVariationSettings: "'opsz' 144" }}
            >
              —
            </span>
            <span className="text-[12px] font-medium text-slate">no movement yet</span>
          </div>
          <p className="mt-4 text-[12px] text-slate">
            Cash flow will appear once the first RA bills are released.
          </p>
        </>
      )}
    </MetricTile>
  );
}
