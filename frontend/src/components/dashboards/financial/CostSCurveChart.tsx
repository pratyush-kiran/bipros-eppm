"use client";

import { LineChart as LineChartIcon } from "lucide-react";
import {
  Area,
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
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  formatINR,
} from "@/components/common/dashboard/primitives";
import type { CashFlowForecastItem } from "@/lib/api/costApi";

interface Props {
  data: CashFlowForecastItem[] | undefined;
  isLoading: boolean;
}

function fmtPeriod(period: string): string {
  // Backend periods are typically YYYY-MM strings. Render as "Jan 25" so the axis
  // stays readable for 12+ months. Anything we can't parse falls back to raw.
  const m = /^(\d{4})-(\d{2})/.exec(period);
  if (!m) return period;
  const date = new Date(Number(m[1]), Number(m[2]) - 1, 1);
  return date.toLocaleDateString("en-IN", { month: "short", year: "2-digit" });
}

export function CostSCurveChart({ data, isLoading }: Props) {
  const rows = (data ?? [])
    .slice()
    .sort((a, b) => (a.period ?? "").localeCompare(b.period ?? ""))
    .map((d) => ({
      period: fmtPeriod(d.period),
      planned: d.cumulativePlanned ?? 0,
      actual: d.cumulativeActual ?? 0,
      forecast: d.cumulativeForecast ?? 0,
    }));

  return (
    <SectionCard
      title="Cost S-Curve"
      subtitle="Planned vs Actual vs Forecast (cumulative)"
      icon={<LineChartIcon size={16} strokeWidth={1.75} />}
      accent
    >
      {isLoading ? (
        <LoadingBlock label="Loading cost curve…" />
      ) : rows.length === 0 ? (
        <EmptyBlock label="No cash-flow data for this project yet." />
      ) : (
        <div className="h-72 w-full">
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={rows} margin={{ top: 8, right: 8, left: -8, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#E6E0CF" vertical={false} />
              <XAxis
                dataKey="period"
                tick={{ fill: "#7a7368", fontSize: 11 }}
                axisLine={{ stroke: "#E6E0CF" }}
                tickLine={false}
              />
              <YAxis
                tickFormatter={(v: number) => formatINR(v, 0)}
                tick={{ fill: "#7a7368", fontSize: 11 }}
                axisLine={{ stroke: "#E6E0CF" }}
                tickLine={false}
                width={70}
              />
              <Tooltip
                contentStyle={CHART_TOOLTIP_STYLE}
                labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                formatter={(value, name) => [formatINR(Number(value)), String(name)]}
              />
              <Legend
                wrapperStyle={{ fontSize: 11, paddingTop: 8 }}
                iconType="line"
              />
              <Area
                type="monotone"
                name="Planned"
                dataKey="planned"
                stroke={CHART_COLORS.planned}
                strokeWidth={2}
                fill={CHART_COLORS.planned}
                fillOpacity={0.16}
              />
              <Line
                type="monotone"
                name="Actual"
                dataKey="actual"
                stroke={CHART_COLORS.actual}
                strokeWidth={2.5}
                dot={{ r: 3 }}
                activeDot={{ r: 4 }}
              />
              <Line
                type="monotone"
                name="Forecast"
                dataKey="forecast"
                stroke={CHART_COLORS.forecast}
                strokeWidth={2}
                strokeDasharray="6 4"
                dot={false}
              />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      )}
    </SectionCard>
  );
}
