import React from "react";
import { cn } from "@/lib/utils/cn";

export type DeltaTone = "positive" | "negative" | "neutral";

export interface MetricDeltaProps {
  tone?: DeltaTone;
  children: React.ReactNode;
  className?: string;
}

const tones: Record<DeltaTone, string> = {
  positive: "bg-emerald/15 text-emerald border-emerald/30",
  negative: "bg-burgundy/15 text-burgundy border-burgundy/30",
  neutral: "bg-slate/15 text-slate border-slate/30",
};

export function MetricDelta({ tone = "neutral", children, className }: MetricDeltaProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-[11px] font-semibold tabular-nums",
        tones[tone],
        className
      )}
    >
      {children}
    </span>
  );
}
