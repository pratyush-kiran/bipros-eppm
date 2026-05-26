"use client";

import { AlertTriangle } from "lucide-react";
import { MetricTile } from "../MetricTile";
import { MetricNumber } from "../primitives/MetricNumber";
import type {
  DelayedProjectRow,
  PortfolioScorecard,
} from "@/lib/api/portfolioReportApi";

interface Props {
  scorecard: PortfolioScorecard | null;
  delayed: DelayedProjectRow[] | null;
}

export function AtRiskTile({ scorecard: _scorecard, delayed }: Props) {
  // Count = delayed list length, so the headline number always matches the rows
  // shown below. scorecard.activeProjectsWithCriticalActivities measures something
  // different (critical-path activities, not delay) and would create a confusing
  // "0 need attention" headline while listing delayed projects underneath.
  const list = delayed ?? [];
  const count = list.length;
  const top = list.slice(0, 3);
  const tone = count > 0 ? "warn" : "default";

  return (
    <MetricTile
      title="Delayed projects"
      icon={AlertTriangle}
      href="/reports/delayed-projects"
      testid="mc-tile-at-risk"
      tone={tone}
    >
      <div className="flex items-baseline gap-2">
        <MetricNumber
          value={count}
          className="font-display text-[40px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
          style={{ fontVariationSettings: "'opsz' 144" }}
        />
        <span className="text-[12px] font-medium text-slate">
          {count === 1 ? "behind plan" : "behind plan"}
        </span>
      </div>

      {top.length > 0 ? (
        <ul className="mt-4 space-y-1.5 text-[12px]">
          {top.map((p) => (
            <li key={p.projectId} className="flex items-center justify-between gap-2">
              <span className="truncate text-charcoal">{p.projectName}</span>
              <span className="shrink-0 font-mono text-[10.5px] text-burgundy tabular-nums">
                {p.daysDelayed > 0 ? `+${p.daysDelayed}d` : `SPI ${p.spi.toFixed(2)}`}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-[12px] text-slate">All projects on track.</p>
      )}
    </MetricTile>
  );
}
