"use client";

import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  CHART_TOOLTIP_ITEM_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_STYLE,
} from "@/components/common/dashboard/primitives";

export interface MarginPeriodPoint {
  periodName: string;
  revenue: number | null;
  actualCost: number | null;
  margin: number | null;
}

/**
 * Stacked-style chart used by the three financial views: revenue and actual cost as bars,
 * margin overlaid as a line. Empty state handled by the parent — passing an empty list
 * renders an empty axis.
 */
export function MarginTrendChart({
  data,
  revenueLabel = "Revenue",
  costLabel = "Actual Cost",
}: {
  data: MarginPeriodPoint[];
  revenueLabel?: string;
  costLabel?: string;
}) {
  const chartData = data.map((d) => ({
    name: d.periodName,
    Revenue: d.revenue ?? 0,
    Cost: d.actualCost ?? 0,
    Margin: d.margin ?? 0,
  }));

  return (
    <ResponsiveContainer width="100%" height={320}>
      <ComposedChart data={chartData} margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
        <XAxis dataKey="name" stroke="#64748b" style={{ fontSize: 11 }} />
        <YAxis stroke="#64748b" style={{ fontSize: 12 }} />
        <Tooltip
          contentStyle={CHART_TOOLTIP_STYLE}
          labelStyle={CHART_TOOLTIP_LABEL_STYLE}
          itemStyle={CHART_TOOLTIP_ITEM_STYLE}
        />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        <Bar dataKey="Revenue" name={revenueLabel} fill="#16a34a" radius={[4, 4, 0, 0]} />
        <Bar dataKey="Cost" name={costLabel} fill="#dc2626" radius={[4, 4, 0, 0]} />
        <Line type="monotone" dataKey="Margin" stroke="#1e3a8a" strokeWidth={2.5} dot={{ r: 3 }} />
      </ComposedChart>
    </ResponsiveContainer>
  );
}
