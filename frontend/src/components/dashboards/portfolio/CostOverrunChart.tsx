"use client";

import { useQuery } from "@tanstack/react-query";
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
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
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
import { formatMoney } from "@/lib/currency/format";

export function CostOverrunChart() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-cost-overrun"],
    queryFn: () => portfolioReportApi.getCostOverrunProjects(10),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="Cost Overruns">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="Cost Overruns">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const rows = (data ?? []).filter((r) => Math.abs(r.varianceCrores ?? 0) > 0);
  if (rows.length === 0) {
    return (
      <SectionCard
        title="Cost Overruns"
        subtitle="Projects ranked by % variance (|EAC − BAC| / BAC)"
      >
        <EmptyBlock label="No measurable cost variances yet (EVM snapshots pending)" />
      </SectionCard>
    );
  }

  const chartData = rows.map((r) => ({
    name: truncate(r.projectName, 32),
    code: r.projectCode,
    variance: r.varianceCrores,
    bac: r.bacCrores,
    eac: r.eacCrores,
    cpi: r.cpi,
    budgetCurrency: r.budgetCurrency ?? "USD",
  }));

  return (
    <SectionCard
      title="Cost Overruns"
      subtitle="Ranked by % variance (|EAC − BAC| / BAC). Red = overrun, green = underrun. Each bar in its own currency."
    >
      <ResponsiveContainer width="100%" height={Math.max(200, chartData.length * 40)}>
        <BarChart
          data={chartData}
          layout="vertical"
          margin={{ top: 5, right: 30, left: 10, bottom: 5 }}
        >
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis
            type="number"
            stroke="#64748b"
            style={{ fontSize: "12px" }}
            tickFormatter={(v: number) => v.toLocaleString()}
          />
          <YAxis
            type="category"
            dataKey="name"
            stroke="#64748b"
            style={{ fontSize: "11px" }}
            width={200}
          />
          <Tooltip
            contentStyle={CHART_TOOLTIP_STYLE}
            labelStyle={CHART_TOOLTIP_LABEL_STYLE}
            itemStyle={CHART_TOOLTIP_ITEM_STYLE}
            formatter={(value, _name, props) => {
              const row = props.payload as (typeof chartData)[number];
              const v = Number(value ?? 0);
              const formatted = formatMoney(v, { code: row.budgetCurrency }, { compact: true });
              return [`${formatted} (CPI ${row.cpi.toFixed(2)})`, "Variance"];
            }}
          />
          <Bar dataKey="variance" radius={[0, 4, 4, 0]}>
            {chartData.map((row) => (
              <Cell
                key={row.code}
                fill={row.variance > 0 ? CHART_COLORS.danger : CHART_COLORS.good}
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </SectionCard>
  );
}
