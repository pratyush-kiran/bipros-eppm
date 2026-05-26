"use client";

import { ShieldCheck } from "lucide-react";
import { MetricTile } from "../MetricTile";
import { MetricNumber } from "../primitives/MetricNumber";
import type { DashboardSummary } from "@/lib/api/permitApi";

interface Props {
  summary: DashboardSummary | null;
}

export function PermitsTile({ summary }: Props) {
  const pending = summary?.pendingReview ?? 0;
  const active = summary?.activePermits ?? 0;
  const expiring = summary?.expiringToday ?? 0;
  const tone = pending > 0 ? "warn" : "default";

  return (
    <MetricTile
      title="Permits"
      icon={ShieldCheck}
      href="/permits"
      testid="mc-tile-permits"
      tone={tone}
    >
      <div className="flex items-baseline gap-2">
        <MetricNumber
          value={pending}
          className="font-display text-[40px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
          style={{ fontVariationSettings: "'opsz' 144" }}
        />
        <span className="text-[12px] font-medium text-slate">awaiting review</span>
      </div>

      <div className="mt-4 grid grid-cols-2 gap-3 text-[11.5px]">
        <div className="rounded-lg border border-hairline/60 bg-ivory/40 px-2.5 py-2">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ash">
            Active
          </div>
          <div className="mt-0.5 font-display text-[18px] font-semibold leading-none text-charcoal tabular-nums">
            {active}
          </div>
        </div>
        <div className="rounded-lg border border-hairline/60 bg-ivory/40 px-2.5 py-2">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-ash">
            Expiring today
          </div>
          <div className={`mt-0.5 font-display text-[18px] font-semibold leading-none tabular-nums ${expiring > 0 ? "text-burgundy" : "text-charcoal"}`}>
            {expiring}
          </div>
        </div>
      </div>
    </MetricTile>
  );
}
