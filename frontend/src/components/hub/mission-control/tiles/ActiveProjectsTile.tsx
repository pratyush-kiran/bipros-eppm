"use client";

import { FolderTree } from "lucide-react";
import { MetricTile } from "../MetricTile";
import { MetricNumber } from "../primitives/MetricNumber";
import type { PortfolioScorecard } from "@/lib/api/portfolioReportApi";

interface Props {
  scorecard: PortfolioScorecard | null;
}

export function ActiveProjectsTile({ scorecard }: Props) {
  const total = scorecard?.totalProjects ?? 0;
  const rag = scorecard?.rag ?? { green: 0, amber: 0, red: 0 };
  const denom = rag.green + rag.amber + rag.red || 1;
  const gPct = (rag.green / denom) * 100;
  const aPct = (rag.amber / denom) * 100;
  const rPct = (rag.red / denom) * 100;

  return (
    <MetricTile
      title="Active projects"
      icon={FolderTree}
      href="/projects"
      testid="mc-tile-active-projects"
    >
      <div className="flex items-baseline gap-2">
        <MetricNumber
          value={total}
          className="font-display text-[40px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
          style={{ fontVariationSettings: "'opsz' 144" }}
        />
        <span className="text-[12px] font-medium text-slate">in portfolio</span>
      </div>

      <div className="mt-4 h-1.5 w-full overflow-hidden rounded-full bg-hairline/60">
        <div className="flex h-full">
          <div style={{ width: `${gPct}%` }} className="h-full bg-emerald" />
          <div style={{ width: `${aPct}%` }} className="h-full bg-bronze-warn" />
          <div style={{ width: `${rPct}%` }} className="h-full bg-burgundy" />
        </div>
      </div>

      <div className="mt-2.5 flex items-center justify-between text-[10.5px] font-medium tabular-nums text-ash">
        <span className="text-emerald">● {rag.green}</span>
        <span className="text-bronze-warn">● {rag.amber}</span>
        <span className="text-burgundy">● {rag.red}</span>
      </div>
    </MetricTile>
  );
}
