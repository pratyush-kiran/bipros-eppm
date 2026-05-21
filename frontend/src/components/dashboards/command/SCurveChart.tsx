"use client";

import React from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  CHART_COLORS_COMMAND,
  CHART_TOOLTIP_STYLE_COMMAND,
  formatCrore,
} from "@/components/common/dashboard/primitives";

export interface SCurvePoint {
  /** Bucket label, e.g. "Apr 25" or month index. */
  period: string;
  planned: number | null;
  actual: number | null;
  forecast: number | null;
}

export interface SCurveChartProps {
  data: SCurvePoint[];
  /** Currency suffix (defaults to Cr). */
  unit?: string;
  height?: number;
}

export function SCurveChart({ data, unit = "Cr", height = 320 }: SCurveChartProps) {
  if (data.length === 0) {
    return (
      <div className="flex h-72 items-center justify-center rounded-xl border border-dashed border-hairline text-sm text-text-secondary">
        No cost-curve data yet.
      </div>
    );
  }
  return (
    <div style={{ width: "100%", height }}>
      <ResponsiveContainer>
        <AreaChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id="actualFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={CHART_COLORS_COMMAND.actual} stopOpacity={0.35} />
              <stop offset="100%" stopColor={CHART_COLORS_COMMAND.actual} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke={CHART_COLORS_COMMAND.grid} strokeDasharray="3 3" />
          <XAxis
            dataKey="period"
            tick={{ fill: CHART_COLORS_COMMAND.textSecondary, fontSize: 11 }}
            stroke={CHART_COLORS_COMMAND.grid}
          />
          <YAxis
            tick={{ fill: CHART_COLORS_COMMAND.textSecondary, fontSize: 11 }}
            stroke={CHART_COLORS_COMMAND.grid}
            tickFormatter={(v) => `${v}${unit}`}
          />
          <Tooltip
            contentStyle={CHART_TOOLTIP_STYLE_COMMAND}
            formatter={(value) => formatCrore(Number(value), 1)}
          />
          <Area
            type="monotone"
            dataKey="actual"
            stroke={CHART_COLORS_COMMAND.actual}
            strokeWidth={2.5}
            fill="url(#actualFill)"
            dot={{ r: 3, fill: CHART_COLORS_COMMAND.actual }}
            activeDot={{ r: 5 }}
            name="Actual"
          />
          <Line
            type="monotone"
            dataKey="planned"
            stroke={CHART_COLORS_COMMAND.planned}
            strokeWidth={2}
            dot={false}
            name="Planned"
          />
          <Line
            type="monotone"
            dataKey="forecast"
            stroke={CHART_COLORS_COMMAND.forecast}
            strokeWidth={2}
            strokeDasharray="5 5"
            dot={{ r: 3, fill: CHART_COLORS_COMMAND.forecast }}
            name="Forecast"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
