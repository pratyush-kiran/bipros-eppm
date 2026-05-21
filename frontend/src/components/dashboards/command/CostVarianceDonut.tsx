import React from "react";
import { cn } from "@/lib/utils/cn";

export interface CostVarianceDonutProps {
  /** Net variance — negative = under budget, positive = over. */
  variancePct: number;
  segments: { label: string; value: number; color: string }[];
  className?: string;
}

export function CostVarianceDonut({ variancePct, segments, className }: CostVarianceDonutProps) {
  const total = segments.reduce((s, x) => s + x.value, 0) || 1;
  const radius = 56;
  const stroke = 12;
  const circumference = 2 * Math.PI * radius;

  // Precompute the running offset for each arc without reassigning during render.
  const offsets: number[] = [];
  segments.reduce((acc, seg) => {
    offsets.push(acc);
    return acc + seg.value / total;
  }, 0);
  const arcs = segments.map((seg, i) => {
    const pct = seg.value / total;
    return {
      ...seg,
      dashArray: `${pct * circumference} ${circumference}`,
      dashOffset: -offsets[i] * circumference,
    };
  });

  const isUnder = variancePct < 0;

  return (
    <div className={cn("flex items-center justify-between gap-6", className)}>
      <div className="relative">
        <svg width="160" height="160" viewBox="0 0 160 160" aria-hidden>
          <circle cx="80" cy="80" r={radius} fill="none" stroke="var(--hairline)" strokeWidth={stroke} />
          {arcs.map((a, i) => (
            <circle
              key={`${a.label}-${i}`}
              cx="80"
              cy="80"
              r={radius}
              fill="none"
              stroke={a.color}
              strokeWidth={stroke}
              strokeLinecap="butt"
              strokeDasharray={a.dashArray}
              strokeDashoffset={a.dashOffset}
              transform="rotate(-90 80 80)"
            />
          ))}
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span
            className="font-display text-2xl font-semibold leading-none tabular-nums"
            style={{ color: isUnder ? "var(--emerald)" : "var(--burgundy)" }}
          >
            {variancePct.toFixed(1)}%
          </span>
          <span className="mt-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            {isUnder ? "Under Budget" : "Over Budget"}
          </span>
        </div>
      </div>
      <ul className="flex flex-col gap-3 text-sm">
        {segments.map((s) => (
          <li key={s.label} className="flex items-center gap-3">
            <span
              className="inline-block h-2.5 w-2.5 rounded-full"
              style={{ background: s.color }}
              aria-hidden
            />
            <span className="flex-1 text-text-secondary">{s.label}</span>
            <span className="font-semibold tabular-nums text-text-primary">
              {((s.value / total) * 100).toFixed(0)}%
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
