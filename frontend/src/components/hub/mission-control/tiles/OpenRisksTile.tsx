"use client";

import { ShieldAlert } from "lucide-react";
import { MetricTile } from "../MetricTile";
import { MetricNumber } from "../primitives/MetricNumber";
import type {
  PortfolioScorecard,
  RiskHeatmap,
} from "@/lib/api/portfolioReportApi";

interface Props {
  scorecard: PortfolioScorecard | null;
  heatmap: RiskHeatmap | null;
}

const RAG_DOT: Record<string, string> = {
  RED: "bg-burgundy",
  AMBER: "bg-bronze-warn",
  GREEN: "bg-emerald",
  YELLOW: "bg-bronze-warn",
};

export function OpenRisksTile({ scorecard: _scorecard, heatmap }: Props) {
  // Count = top exposure risks length so the headline matches the rows shown.
  // scorecard.openRisksCritical only counts CRITICAL-severity risks and would be
  // 0 even when the heatmap surfaces HIGH/MEDIUM exposure items below it.
  const top = (heatmap?.topExposureRisks ?? []).slice(0, 3);
  const count = top.length;
  const tone = count > 0 ? "danger" : "ok";

  return (
    <MetricTile
      title="Top exposure risks"
      icon={ShieldAlert}
      href="/reports/risk-register"
      testid="mc-tile-risks"
      tone={tone}
    >
      <div className="flex items-baseline gap-2">
        <MetricNumber
          value={count}
          className="font-display text-[40px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
          style={{ fontVariationSettings: "'opsz' 144" }}
        />
        <span className="text-[12px] font-medium text-slate">
          {count === 1 ? "high-exposure risk" : "high-exposure risks"}
        </span>
      </div>

      {top.length > 0 ? (
        <ul className="mt-4 space-y-1.5 text-[12px]">
          {top.map((r) => (
            <li key={r.riskId} className="flex items-center gap-2">
              <span
                aria-hidden
                className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full ${RAG_DOT[r.rag] ?? "bg-ash"}`}
              />
              <span className="truncate text-charcoal">{r.title}</span>
              <span className="ml-auto shrink-0 font-mono text-[10.5px] text-ash tabular-nums">
                {r.score}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-[12px] text-slate">No critical risks open.</p>
      )}
    </MetricTile>
  );
}
