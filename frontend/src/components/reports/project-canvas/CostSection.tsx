"use client";

import { useQuery } from "@tanstack/react-query";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  truncate,
} from "@/components/common/dashboard/primitives";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";

export function CostSection({ projectId }: { projectId: string }) {
  const currency = useProjectCurrencyOptional();
  const moneyCompact = (v: number | null | undefined) =>
    currency ? currency.moneyCompact(v) : formatMoney(v, { code: "INR" }, { compact: true });

  const { data, isLoading, isError } = useQuery({
    queryKey: ["project-cost-variance", projectId],
    queryFn: () => projectInsightsApi.getCostVariance(projectId),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="Cost Analysis">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="Cost Analysis">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const rows = (data ?? []).filter(
    (r) => (r.budgetCrores ?? 0) > 0 || (r.actualCrores ?? 0) > 0 || (r.forecastCrores ?? 0) > 0,
  );
  if (rows.length === 0) {
    return (
      <SectionCard
        title="Cost Analysis"
        subtitle="Per-WBS budget vs committed vs actual vs forecast"
      >
        <EmptyBlock label="No cost data recorded against this project's WBS" />
      </SectionCard>
    );
  }

  const chartData = rows.map((r) => ({
    name: truncate(r.wbsCode, 18),
    Budget: r.budgetCrores,
    Actual: r.actualCrores,
    Forecast: r.forecastCrores,
    variance: r.varianceCrores,
    variancePct: r.variancePct,
  }));

  const varianceData = rows.map((r) => ({
    name: truncate(r.wbsCode, 18),
    variance: r.varianceCrores,
    pct: r.variancePct,
  }));

  return (
    <SectionCard
      title="Cost Analysis"
      subtitle="Budget vs forecast vs actual by WBS element"
    >
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <div>
          <h3 className="mb-2 text-sm font-medium text-text-secondary">
            Budget / Actual / Forecast
          </h3>
          <ResponsiveContainer width="100%" height={Math.max(220, chartData.length * 38)}>
            <BarChart data={chartData} layout="vertical" margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis type="number" stroke="#64748b" style={{ fontSize: "12px" }} />
              <YAxis type="category" dataKey="name" stroke="#64748b" style={{ fontSize: "11px" }} width={120} />
              <Tooltip
                contentStyle={CHART_TOOLTIP_STYLE}
                labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                formatter={(value) => moneyCompact(Number(value ?? 0) * 1e7)}
              />
              <Legend wrapperStyle={{ fontSize: "11px" }} />
              <Bar dataKey="Budget" fill={CHART_COLORS.pv} />
              <Bar dataKey="Actual" fill={CHART_COLORS.ev} />
              <Bar dataKey="Forecast" fill={CHART_COLORS.forecast} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-medium text-text-secondary">
            Variance (EAC − Budget). Red = overrun, green = underrun.
          </h3>
          <ResponsiveContainer width="100%" height={Math.max(220, varianceData.length * 38)}>
            <BarChart data={varianceData} layout="vertical" margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis type="number" stroke="#64748b" style={{ fontSize: "12px" }} />
              <YAxis type="category" dataKey="name" stroke="#64748b" style={{ fontSize: "11px" }} width={120} />
              <Tooltip
                contentStyle={CHART_TOOLTIP_STYLE}
                labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                formatter={(value, _name, props) => {
                  const row = props.payload as (typeof varianceData)[number];
                  return [
                    `${moneyCompact(Number(value ?? 0) * 1e7)} (${row.pct.toFixed(1)}%)`,
                    "Variance",
                  ];
                }}
              />
              <Bar dataKey="variance" radius={[0, 4, 4, 0]}>
                {varianceData.map((row, i) => (
                  <Cell
                    key={i}
                    fill={row.variance > 0 ? CHART_COLORS.danger : CHART_COLORS.good}
                  />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </SectionCard>
  );
}
