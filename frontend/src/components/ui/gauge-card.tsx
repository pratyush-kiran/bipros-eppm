import React from "react";
import { cn } from "@/lib/utils/cn";

export type GaugeAccent = "success" | "warning" | "danger" | "info" | "primary" | "violet";

export interface GaugeCardProps {
  label: string;
  value: React.ReactNode;
  unit?: string;
  /** 0–100, drives the arc */
  percent: number;
  accent?: GaugeAccent;
  className?: string;
}

const colorVar: Record<GaugeAccent, string> = {
  success: "var(--emerald)",
  warning: "var(--bronze-warn)",
  danger: "var(--burgundy)",
  info: "var(--steel)",
  primary: "var(--gold)",
  violet: "var(--cmd-violet)",
};

export function GaugeCard({
  label,
  value,
  unit,
  percent,
  accent = "info",
  className,
}: GaugeCardProps) {
  const clamped = Math.max(0, Math.min(100, percent));
  const radius = 26;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (clamped / 100) * circumference;
  const stroke = colorVar[accent];

  return (
    <div
      className={cn(
        "relative overflow-hidden rounded-xl border border-hairline bg-ivory p-5",
        className
      )}
    >
      <span
        className="absolute left-0 top-0 h-full w-[3px]"
        style={{ background: stroke }}
        aria-hidden
      />
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            {label}
          </div>
          <div className="mt-2 flex items-baseline gap-1">
            <span className="font-display text-[28px] font-semibold leading-none tracking-tight text-text-primary tabular-nums">
              {value}
            </span>
            {unit && <span className="text-xs text-text-secondary">{unit}</span>}
          </div>
        </div>
        <svg width="64" height="64" viewBox="0 0 64 64" aria-hidden>
          <circle
            cx="32"
            cy="32"
            r={radius}
            fill="none"
            stroke="var(--hairline)"
            strokeWidth="5"
          />
          <circle
            cx="32"
            cy="32"
            r={radius}
            fill="none"
            stroke={stroke}
            strokeWidth="5"
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={offset}
            transform="rotate(-90 32 32)"
          />
        </svg>
      </div>
    </div>
  );
}
