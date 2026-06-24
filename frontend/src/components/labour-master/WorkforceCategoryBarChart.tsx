"use client";

import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { LabourCategorySummary } from "@/lib/api/labourMasterApi";
import { CHART_TOOLTIP_STYLE, CHART_TOOLTIP_LABEL_STYLE, CHART_TOOLTIP_ITEM_STYLE } from "@/components/common/dashboard/primitives";
import { CATEGORY_ACCENT } from "./labourMasterTokens";

type Props = { rows: LabourCategorySummary[] };

export function WorkforceCategoryBarChart({ rows }: Props) {
  const data = rows.map((r) => ({
    name: r.categoryDisplay,
    workers: r.workerCount,
    fill: CATEGORY_ACCENT[r.category].hex,
  }));

  return (
    <div className="rounded-xl border border-hairline bg-paper p-4 shadow-[0_1px_2px_rgba(0,0,0,0.20)]">
      <header className="mb-3 flex items-end justify-between">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
            Workforce
          </div>
          <h3 className="font-display text-[18px] font-semibold text-charcoal">
            Headcount by category
          </h3>
        </div>
      </header>
      <div className="h-64">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} layout="vertical" margin={{ top: 8, right: 24, left: 12, bottom: 8 }}>
            <CartesianGrid horizontal={false} strokeDasharray="2 4" stroke="var(--hairline)" />
            <XAxis
              type="number"
              tick={{ fill: "var(--slate)", fontSize: 11 }}
              axisLine={{ stroke: "var(--hairline)" }}
              tickLine={{ stroke: "var(--hairline)" }}
            />
            <YAxis
              type="category"
              dataKey="name"
              width={170}
              tick={{ fill: "var(--charcoal)", fontSize: 12 }}
              axisLine={{ stroke: "var(--hairline)" }}
              tickLine={false}
            />
            <Tooltip
              cursor={{ fill: "var(--ivory)", opacity: 0.6 }}
              contentStyle={CHART_TOOLTIP_STYLE}
              labelStyle={CHART_TOOLTIP_LABEL_STYLE}
              itemStyle={CHART_TOOLTIP_ITEM_STYLE}
              formatter={(value) => [value ?? 0, "Workers"]}
            />
            <Bar dataKey="workers" radius={[0, 4, 4, 0]}>
              {data.map((entry, idx) => (
                <Cell key={`cell-${idx}`} fill={entry.fill} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
