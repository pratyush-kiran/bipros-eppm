"use client";

import { PieChart as PieChartIcon } from "lucide-react";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  formatPct,
} from "@/components/common/dashboard/primitives";
import type { BoqSummaryResponse } from "@/lib/api/boqApi";
import { bucketVariance } from "@/lib/dashboard/financialAggregators";

interface Props {
  summary: BoqSummaryResponse | undefined;
  isLoading: boolean;
}

export function CostVarianceDonut({ summary, isLoading }: Props) {
  const buckets = bucketVariance(summary?.items ?? []);
  const grandPct = summary?.grandCostVariancePercent ?? null;
  const onBudgetPct = buckets.total > 0 ? (buckets.onBudget / buckets.total) * 100 : 0;
  const overPct = buckets.total > 0 ? (buckets.over / buckets.total) * 100 : 0;
  const underPct = buckets.total > 0 ? (buckets.under / buckets.total) * 100 : 0;

  const slices = [
    { name: "On Budget", value: buckets.onBudget, color: CHART_COLORS.good },
    { name: "Over", value: buckets.over, color: CHART_COLORS.red },
    { name: "Under", value: buckets.under, color: CHART_COLORS.amber },
  ].filter((s) => s.value > 0);

  // BIPROS sign convention: grandCostVariance > 0 ⇒ under budget. The donut centre
  // label reads from the grand percent so the verdict matches the project rollup,
  // not the slice mix (which counts items).
  const verdict = grandPct == null
    ? "—"
    : grandPct > 1
      ? "UNDER BUDGET"
      : grandPct < -1
        ? "OVER BUDGET"
        : "ON BUDGET";

  return (
    <SectionCard
      title="Cost variance"
      subtitle="BOQ items grouped by variance band"
      icon={<PieChartIcon size={16} strokeWidth={1.75} />}
    >
      {isLoading ? (
        <LoadingBlock label="Loading variance…" />
      ) : slices.length === 0 ? (
        <EmptyBlock label="No BOQ variance data yet." />
      ) : (
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center">
          <div className="relative h-56 w-full lg:w-1/2">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={slices}
                  dataKey="value"
                  nameKey="name"
                  innerRadius={62}
                  outerRadius={86}
                  paddingAngle={2}
                  strokeWidth={0}
                >
                  {slices.map((s) => (
                    <Cell key={s.name} fill={s.color} />
                  ))}
                </Pie>
                <Tooltip
                  contentStyle={CHART_TOOLTIP_STYLE}
                  formatter={(value, name) => [`${Number(value)} items`, String(name)]}
                />
              </PieChart>
            </ResponsiveContainer>
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
              <div className="font-display text-2xl font-semibold leading-none tracking-tight text-charcoal">
                {formatPct(grandPct)}
              </div>
              <div className="mt-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-slate">
                {verdict}
              </div>
            </div>
          </div>

          <ul className="flex-1 space-y-2.5 text-sm">
            <LegendRow
              swatch={CHART_COLORS.good}
              label="On Budget"
              count={buckets.onBudget}
              pct={onBudgetPct}
            />
            <LegendRow
              swatch={CHART_COLORS.red}
              label="Over"
              count={buckets.over}
              pct={overPct}
            />
            <LegendRow
              swatch={CHART_COLORS.amber}
              label="Under"
              count={buckets.under}
              pct={underPct}
            />
          </ul>
        </div>
      )}
    </SectionCard>
  );
}

function LegendRow({
  swatch,
  label,
  count,
  pct,
}: {
  swatch: string;
  label: string;
  count: number;
  pct: number;
}) {
  return (
    <li className="flex items-center justify-between gap-3">
      <div className="flex items-center gap-2">
        <span
          className="inline-block h-2.5 w-2.5 rounded-full"
          style={{ background: swatch }}
        />
        <span className="text-charcoal">{label}</span>
      </div>
      <div className="flex items-baseline gap-2">
        <span className="text-xs text-slate">{count} items</span>
        <span className="font-display text-base font-semibold text-charcoal">
          {pct.toFixed(0)}%
        </span>
      </div>
    </li>
  );
}
