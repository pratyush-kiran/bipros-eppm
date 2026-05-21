import React from "react";
import { cn } from "@/lib/utils/cn";

export interface ProjectHealthDonutProps {
  /** 0–100 — the central percent. */
  percent: number;
  centralLabel?: string;
  /** Status buckets shown to the right with a colored dot + count. */
  segments: { label: string; value: number; color: string }[];
  className?: string;
}

export function ProjectHealthDonut({
  percent,
  centralLabel = "Complete",
  segments,
  className,
}: ProjectHealthDonutProps) {
  const clamped = Math.max(0, Math.min(100, percent));
  const radius = 56;
  const stroke = 12;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (clamped / 100) * circumference;
  return (
    <div className={cn("flex items-center justify-between gap-6", className)}>
      <div className="relative">
        <svg width="160" height="160" viewBox="0 0 160 160" aria-hidden>
          <circle
            cx="80"
            cy="80"
            r={radius}
            fill="none"
            stroke="var(--hairline)"
            strokeWidth={stroke}
          />
          <circle
            cx="80"
            cy="80"
            r={radius}
            fill="none"
            stroke="var(--emerald)"
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={offset}
            transform="rotate(-90 80 80)"
          />
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="font-display text-3xl font-semibold leading-none text-text-primary tabular-nums">
            {clamped.toFixed(0)}%
          </span>
          <span className="mt-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            {centralLabel}
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
            <span className="font-semibold tabular-nums text-text-primary">{s.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
