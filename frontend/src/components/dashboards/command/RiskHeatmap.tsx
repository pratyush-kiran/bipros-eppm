import React from "react";
import { cn } from "@/lib/utils/cn";

export interface RiskMarker {
  id: string;
  label: string;
  /** 1–5 — 1 = rare, 5 = almost certain */
  probability: number;
  /** 1–5 — 1 = negligible, 5 = catastrophic */
  impact: number;
}

export interface RiskHeatmapProps {
  markers: RiskMarker[];
  className?: string;
}

const probLabels = ["Rare", "Unlikely", "Possible", "Likely", "Almost C"];
const impactLabels = ["Negli", "Minor", "Moder", "Major", "Catas"];

function cellColor(prob: number, impact: number): string {
  const score = prob * impact;
  if (score <= 4) return "rgba(16, 185, 129, 0.32)";
  if (score <= 8) return "rgba(245, 158, 11, 0.30)";
  if (score <= 14) return "rgba(245, 158, 11, 0.45)";
  if (score <= 19) return "rgba(239, 68, 68, 0.45)";
  return "rgba(239, 68, 68, 0.65)";
}

function markerColor(prob: number, impact: number): string {
  const score = prob * impact;
  if (score <= 4) return "var(--emerald)";
  if (score <= 8) return "var(--bronze-warn)";
  if (score <= 14) return "var(--bronze-warn)";
  if (score <= 19) return "var(--burgundy)";
  return "var(--burgundy)";
}

export function RiskHeatmap({ markers, className }: RiskHeatmapProps) {
  return (
    <div className={cn("flex gap-3", className)}>
      <div className="grid pr-2 text-[10px] text-text-secondary" style={{ gridAutoRows: "64px", rowGap: 4 }}>
        {[...probLabels].reverse().map((l) => (
          <span key={l} className="flex items-center justify-end leading-none">
            {l}
          </span>
        ))}
      </div>
      <div className="flex-1">
        <div className="grid grid-cols-5 gap-1" style={{ gridAutoRows: "64px" }}>
          {Array.from({ length: 5 })
            .map((_, rowIdx) => {
              // rows are top-down; row 0 = Almost Certain (prob 5), row 4 = Rare (prob 1)
              const prob = 5 - rowIdx;
              return Array.from({ length: 5 }).map((__, colIdx) => {
                const impact = colIdx + 1;
                const cellMarkers = markers.filter(
                  (m) => m.probability === prob && m.impact === impact
                );
                return (
                  <div
                    key={`${rowIdx}-${colIdx}`}
                    className="relative rounded-md border border-hairline/30"
                    style={{ background: cellColor(prob, impact) }}
                  >
                    <div className="absolute inset-1 flex flex-wrap items-center justify-center gap-1">
                      {cellMarkers.map((m) => (
                        <span
                          key={m.id}
                          title={m.label}
                          className="flex h-6 w-6 items-center justify-center rounded-full text-[9px] font-bold text-white shadow"
                          style={{ background: markerColor(prob, impact) }}
                        >
                          {m.label}
                        </span>
                      ))}
                    </div>
                  </div>
                );
              });
            })
            .flat()}
        </div>
        <div className="mt-2 flex justify-between text-[10px] uppercase tracking-wider text-text-secondary">
          <span className="text-text-secondary">Impact ▸</span>
          {impactLabels.map((l) => (
            <span key={l}>{l}</span>
          ))}
        </div>
      </div>
    </div>
  );
}
