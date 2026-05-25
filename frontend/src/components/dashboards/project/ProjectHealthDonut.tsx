"use client";

import { Activity } from "lucide-react";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  EmptyBlock,
  SectionCard,
  formatPct,
} from "@/components/common/dashboard/primitives";

interface ProjectHealthDonutProps {
  buckets: { onTrack: number; atRisk: number; delayed: number };
  physicalPct: number;
}

const LEGEND = [
  { key: "onTrack", label: "On Track", color: CHART_COLORS.good },
  { key: "atRisk", label: "At Risk", color: CHART_COLORS.amber },
  { key: "delayed", label: "Delayed", color: CHART_COLORS.red },
] as const;

export function ProjectHealthDonut({
  buckets,
  physicalPct,
}: ProjectHealthDonutProps) {
  const data = [
    { name: "On Track", value: buckets.onTrack, color: CHART_COLORS.good },
    { name: "At Risk", value: buckets.atRisk, color: CHART_COLORS.amber },
    { name: "Delayed", value: buckets.delayed, color: CHART_COLORS.red },
  ].filter((d) => d.value > 0);

  const total = buckets.onTrack + buckets.atRisk + buckets.delayed;

  return (
    <SectionCard
      title="Project Health"
      subtitle="Activity health distribution"
      icon={<Activity size={16} />}
      accent
    >
      {total === 0 ? (
        <EmptyBlock label="No activity data yet" />
      ) : (
        <div className="grid grid-cols-1 items-center gap-6 sm:grid-cols-[180px_1fr]">
          <div className="relative mx-auto h-[180px] w-[180px]">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={data}
                  dataKey="value"
                  nameKey="name"
                  innerRadius={60}
                  outerRadius={84}
                  paddingAngle={3}
                  stroke="#fff"
                  strokeWidth={2}
                >
                  {data.map((entry) => (
                    <Cell key={entry.name} fill={entry.color} />
                  ))}
                </Pie>
                <Tooltip
                  contentStyle={CHART_TOOLTIP_STYLE}
                  formatter={(v, n) => [
                    `${v} (${formatPct((Number(v) / total) * 100, 0)})`,
                    n,
                  ]}
                />
              </PieChart>
            </ResponsiveContainer>
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
              <div
                className="font-display text-3xl font-semibold leading-none text-emerald"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {formatPct(physicalPct, 0)}
              </div>
              <div className="mt-1 text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                Complete
              </div>
            </div>
          </div>

          <ul className="space-y-2">
            {LEGEND.map((seg) => {
              const count = buckets[seg.key];
              return (
                <li
                  key={seg.key}
                  className="flex items-center justify-between rounded-lg border border-transparent px-2 py-2 transition-colors hover:border-hairline hover:bg-paper"
                >
                  <div className="flex items-center gap-2">
                    <span
                      className="h-2.5 w-2.5 shrink-0 rounded-full"
                      style={{ backgroundColor: seg.color }}
                    />
                    <span className="text-sm font-medium text-charcoal">
                      {seg.label}
                    </span>
                  </div>
                  <span
                    className="font-display text-lg font-semibold leading-none"
                    style={{ color: seg.color }}
                  >
                    {count}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </SectionCard>
  );
}
