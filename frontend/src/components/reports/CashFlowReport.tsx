"use client";

import { useState, useMemo } from "react";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from "recharts";
import { Button } from "@/components/ui/button";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";
import type { CashFlowEntry } from "@/lib/api/reportDataApi";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";
import { CHART_TOOLTIP_STYLE, CHART_TOOLTIP_LABEL_STYLE, CHART_TOOLTIP_ITEM_STYLE } from "@/components/common/dashboard/primitives";

interface CashFlowReportProps {
  data: CashFlowEntry[];
}

export function CashFlowReport({ data }: CashFlowReportProps) {
  const [showCumulative, setShowCumulative] = useState(false);
  // May render on the portfolio reports page (outside a project route), so fall
  // back to INR when no project currency is in context.
  const currency = useProjectCurrencyOptional();
  const moneyCompact = (v: number | null | undefined) =>
    currency ? currency.moneyCompact(v) : formatMoney(v, { code: "INR" }, { compact: true });
  const chartData = useMemo(() => {
    return data.map((entry) => ({
      period: entry.period,
      "Planned": showCumulative ? entry.cumulativePlanned : entry.planned,
      "Actual": showCumulative ? entry.cumulativeActual : entry.actual,
      "Forecast": showCumulative ? entry.cumulativeForecast : entry.forecast,
    }));
  }, [data, showCumulative]);

  const totals = useMemo(() => {
    const lastEntry = data[data.length - 1];
    return {
      totalPlanned: lastEntry?.cumulativePlanned || 0,
      totalActual: lastEntry?.cumulativeActual || 0,
      totalForecast: lastEntry?.cumulativeForecast || 0,
    };
  }, [data]);

  const variance = useMemo(() => {
    return {
      planned: totals.totalPlanned - totals.totalActual,
      forecast: totals.totalForecast - totals.totalActual,
    };
  }, [totals]);

  const columns = useMemo<ColumnDef<CashFlowEntry>[]>(
    () => [
      {
        accessorKey: "period",
        header: "Period",
        cell: (info) => (
          <span className="font-semibold text-text-primary">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: showCumulative ? "cumulativePlanned" : "planned",
        header: showCumulative ? "Cum. Planned" : "Planned",
        cell: (info) => (
          <span className="block text-right">
            {moneyCompact(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: showCumulative ? "cumulativeActual" : "actual",
        header: showCumulative ? "Cum. Actual" : "Actual",
        cell: (info) => (
          <span className="block text-right">
            {moneyCompact(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: showCumulative ? "cumulativeForecast" : "forecast",
        header: showCumulative ? "Cum. Forecast" : "Forecast",
        cell: (info) => (
          <span className="block text-right">
            {moneyCompact(Number(info.getValue()))}
          </span>
        ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [showCumulative, currency]
  );

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="flex justify-between items-start">
          <div>
            <h3 className="font-semibold text-lg text-text-primary">Cash Flow Report</h3>
            <p className="text-sm text-text-secondary">Planned vs Actual vs Forecasted cash flows</p>
          </div>
          <div className="flex gap-2">
            <Button
              variant={showCumulative ? "primary" : "ghost"}
              size="sm"
              onClick={() => setShowCumulative(!showCumulative)}
            >
              {showCumulative ? "Show Period" : "Show Cumulative"}
            </Button>
          </div>
        </div>
      </div>

      {/* Chart */}
      <div className="bg-surface/50 border border-border rounded-lg p-4">
        <ResponsiveContainer width="100%" height={400}>
          <LineChart data={chartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis
              dataKey="period"
              stroke="#64748b"
              style={{ fontSize: "12px" }}
            />
            <YAxis
              stroke="#64748b"
              style={{ fontSize: "12px" }}
              label={{ value: `Amount (${currency?.code ?? ""})`, angle: -90, position: "insideLeft" }}
            />
            <Tooltip
              formatter={(value) => moneyCompact(Number(value))}
              contentStyle={CHART_TOOLTIP_STYLE}
              labelStyle={CHART_TOOLTIP_LABEL_STYLE}
              itemStyle={CHART_TOOLTIP_ITEM_STYLE}
            />
            <Legend />
            <Line
              type="monotone"
              dataKey="Planned"
              stroke="#3b82f6"
              strokeWidth={2}
              dot={false}
            />
            <Line
              type="monotone"
              dataKey="Actual"
              stroke="#10b981"
              strokeWidth={2}
              dot={false}
            />
            <Line
              type="monotone"
              dataKey="Forecast"
              stroke="#f59e0b"
              strokeWidth={2}
              dot={false}
              strokeDasharray="5 5"
            />
          </LineChart>
        </ResponsiveContainer>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider mb-2">Total Planned</p>
          <p className="text-3xl font-bold text-accent">{moneyCompact(totals.totalPlanned)}</p>
        </div>
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider mb-2">Total Actual</p>
          <p className="text-3xl font-bold text-success">{moneyCompact(totals.totalActual)}</p>
        </div>
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider mb-2">Total Forecast</p>
          <p className="text-3xl font-bold text-amber-600">{moneyCompact(totals.totalForecast)}</p>
        </div>
      </div>

      {/* Variance Summary */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <h4 className="font-semibold text-text-primary mb-3">Planned vs Actual</h4>
          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-text-secondary">Variance</span>
              <span className={`font-semibold ${variance.planned >= 0 ? "text-success" : "text-danger"}`}>
                {moneyCompact(variance.planned)}
              </span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-text-secondary">Status</span>
              <span className={`px-2 py-1 rounded text-xs font-semibold ${
                variance.planned >= 0 ? "bg-success/10 text-success" : "bg-danger/10 text-danger"
              }`}>
                {variance.planned >= 0 ? "Under Budget" : "Over Budget"}
              </span>
            </div>
          </div>
        </div>

        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <h4 className="font-semibold text-text-primary mb-3">Forecast vs Actual</h4>
          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-text-secondary">Remaining</span>
              <span className={`font-semibold ${variance.forecast >= 0 ? "text-success" : "text-danger"}`}>
                {moneyCompact(variance.forecast)}
              </span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-text-secondary">Status</span>
              <span className={`px-2 py-1 rounded text-xs font-semibold ${
                variance.forecast >= 0 ? "bg-success/10 text-success" : "bg-orange-500/10 text-orange-400"
              }`}>
                {variance.forecast >= 0 ? "On Track" : "At Risk"}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Data Table */}
      <div className="bg-surface/50 border border-border rounded-lg p-4">
        <h4 className="font-semibold text-text-primary mb-4">Detailed Cash Flow</h4>
        <SimpleTable columns={columns} data={data} sortable={false} />
      </div>
    </div>
  );
}
