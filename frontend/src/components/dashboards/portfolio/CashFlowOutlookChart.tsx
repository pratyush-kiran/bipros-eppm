"use client";

import { useQuery } from "@tanstack/react-query";
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
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
import { formatMoney } from "@/lib/currency/format";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
} from "@/components/common/dashboard/primitives";

export function CashFlowOutlookChart() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-cash-flow"],
    queryFn: () => portfolioReportApi.getCashFlowOutlook(12),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="Cash Flow Outlook">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError || !data)
    return (
      <SectionCard title="Cash Flow Outlook">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const hasData = data.some(
    (r) => r.plannedOutflowCrores !== 0 || r.plannedInflowCrores !== 0,
  );
  if (!hasData) {
    return (
      <SectionCard
        title="Cash Flow Outlook"
        subtitle="Next 12 months: monthly bars (net) + cumulative line"
      >
        <EmptyBlock label="No forecast data seeded yet" />
      </SectionCard>
    );
  }

  const currencyCode = data[0]?.currency ?? "USD";
  const currencyMeta = { code: currencyCode };

  const chartData = data.map((r) => ({
    month: r.yearMonth,
    Outflow: -r.plannedOutflowCrores,
    Inflow: r.plannedInflowCrores,
    Net: r.netCrores,
    Cumulative: r.cumulativeCrores,
  }));

  return (
    <SectionCard
      title="Cash Flow Outlook"
      subtitle="Next 12 months. Bars = monthly net; line = cumulative position."
    >
      <ResponsiveContainer width="100%" height={360}>
        <ComposedChart data={chartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis dataKey="month" stroke="#64748b" style={{ fontSize: "11px" }} />
          <YAxis
            yAxisId="left"
            stroke="#64748b"
            style={{ fontSize: "12px" }}
            tickFormatter={(v: number) => formatMoney(v, currencyMeta, { compact: true })}
          />
          <YAxis
            yAxisId="right"
            orientation="right"
            stroke="#64748b"
            style={{ fontSize: "12px" }}
            tickFormatter={(v: number) => formatMoney(v, currencyMeta, { compact: true })}
          />
          <Tooltip
            contentStyle={CHART_TOOLTIP_STYLE}
            labelStyle={CHART_TOOLTIP_LABEL_STYLE}
            itemStyle={CHART_TOOLTIP_ITEM_STYLE}
            formatter={(value) =>
              formatMoney(Math.abs(Number(value ?? 0)), currencyMeta, { compact: true })
            }
          />
          <Legend wrapperStyle={{ fontSize: "12px" }} />
          <Bar yAxisId="left" dataKey="Inflow" fill={CHART_COLORS.ev} />
          <Bar yAxisId="left" dataKey="Outflow" fill={CHART_COLORS.ac} />
          <Line
            yAxisId="right"
            type="monotone"
            dataKey="Cumulative"
            stroke={CHART_COLORS.forecast}
            strokeWidth={2}
            dot={{ r: 3 }}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </SectionCard>
  );
}
