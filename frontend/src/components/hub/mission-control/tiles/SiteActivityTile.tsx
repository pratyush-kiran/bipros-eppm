"use client";

import { Activity } from "lucide-react";
import { MetricTile } from "../MetricTile";
import { MetricNumber } from "../primitives/MetricNumber";
import type { DashboardSummary } from "@/lib/api/permitApi";

interface Props {
  summary: DashboardSummary | null;
}

const STATUS_COLOR: Record<string, string> = {
  ISSUED: "bg-emerald",
  IN_PROGRESS: "bg-gold",
  APPROVED: "bg-emerald",
  PENDING_PM: "bg-bronze-warn",
  PENDING_HSE: "bg-bronze-warn",
  PENDING_SITE_ENGINEER: "bg-bronze-warn",
  REJECTED: "bg-burgundy",
  SUSPENDED: "bg-burgundy",
  CLOSED: "bg-ash",
};

export function SiteActivityTile({ summary }: Props) {
  const recent = (summary?.recentActivity ?? []).slice(0, 3);
  const count = summary?.recentActivity?.length ?? 0;

  return (
    <MetricTile
      title="Site activity · today"
      icon={Activity}
      href="/permits"
      testid="mc-tile-site-activity"
    >
      <div className="flex items-baseline gap-2">
        <MetricNumber
          value={count}
          className="font-display text-[40px] font-semibold leading-none tracking-tight text-charcoal tabular-nums"
          style={{ fontVariationSettings: "'opsz' 144" }}
        />
        <span className="text-[12px] font-medium text-slate">
          {count === 1 ? "permit movement" : "permit movements"}
        </span>
      </div>

      {recent.length > 0 ? (
        <ul className="mt-4 space-y-1.5 text-[12px]">
          {recent.map((p) => (
            <li key={p.id} className="flex items-center gap-2">
              <span
                aria-hidden
                className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full ${STATUS_COLOR[p.status] ?? "bg-ash"}`}
              />
              <span className="truncate text-charcoal">
                {p.permitTypeName ?? p.permitCode}
              </span>
              <span className="ml-auto shrink-0 font-mono text-[10.5px] text-ash tabular-nums">
                {p.permitCode}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-4 text-[12px] text-slate">No permit movements yet today.</p>
      )}
    </MetricTile>
  );
}
