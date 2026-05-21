"use client";

import React from "react";
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  CHART_COLORS_COMMAND,
  CHART_TOOLTIP_STYLE_COMMAND,
} from "@/components/common/dashboard/primitives";

export interface PerformanceIndexPoint {
  period: string;
  spi: number | null;
  cpi: number | null;
}

export interface PerformanceIndexChartProps {
  data: PerformanceIndexPoint[];
  /** Optional latest SPI/CPI to render in the corner badge. */
  latestSpi?: number;
  latestCpi?: number;
  height?: number;
}

export function PerformanceIndexChart({
  data,
  latestSpi,
  latestCpi,
  height = 240,
}: PerformanceIndexChartProps) {
  if (data.length === 0) {
    return (
      <div className="flex h-48 items-center justify-center rounded-xl border border-dashed border-hairline text-sm text-text-secondary">
        No performance data yet.
      </div>
    );
  }
  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <div className="flex items-center gap-4 text-[10px] font-semibold uppercase tracking-[0.14em]">
          <span className="flex items-center gap-1.5 text-[var(--cmd-cyan)]">
            <span className="inline-block h-1.5 w-3 rounded-full bg-[var(--cmd-cyan)]" />
            SPI
          </span>
          <span className="flex items-center gap-1.5 text-bronze-warn">
            <span className="inline-block h-1.5 w-3 rounded-full bg-bronze-warn" />
            CPI
          </span>
        </div>
        {(latestSpi != null || latestCpi != null) && (
          <div className="text-xs font-semibold text-text-primary tabular-nums">
            {latestSpi != null && (
              <span className="mr-3">
                <span className="text-text-secondary">SPI</span>: {latestSpi.toFixed(2)}
              </span>
            )}
            {latestCpi != null && (
              <span>
                <span className="text-text-secondary">CPI</span>: {latestCpi.toFixed(2)}
              </span>
            )}
          </div>
        )}
      </div>
      <div style={{ width: "100%", height }}>
        <ResponsiveContainer>
          <LineChart data={data} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
            <CartesianGrid stroke={CHART_COLORS_COMMAND.grid} strokeDasharray="3 3" />
            <XAxis
              dataKey="period"
              tick={{ fill: CHART_COLORS_COMMAND.textSecondary, fontSize: 11 }}
              stroke={CHART_COLORS_COMMAND.grid}
            />
            <YAxis
              tick={{ fill: CHART_COLORS_COMMAND.textSecondary, fontSize: 11 }}
              stroke={CHART_COLORS_COMMAND.grid}
              domain={[0.6, 1.4]}
            />
            <Tooltip contentStyle={CHART_TOOLTIP_STYLE_COMMAND} />
            <ReferenceLine y={1} stroke={CHART_COLORS_COMMAND.muted} strokeDasharray="4 4" />
            <Line
              type="monotone"
              dataKey="spi"
              stroke={CHART_COLORS_COMMAND.cyan}
              strokeWidth={2.5}
              dot={{ r: 3, fill: CHART_COLORS_COMMAND.cyan }}
              activeDot={{ r: 5 }}
            />
            <Line
              type="monotone"
              dataKey="cpi"
              stroke={CHART_COLORS_COMMAND.warning}
              strokeWidth={2.5}
              dot={{ r: 3, fill: CHART_COLORS_COMMAND.warning }}
              activeDot={{ r: 5 }}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
