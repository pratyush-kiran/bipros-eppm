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
import { ragFill } from "@/lib/utils/rag";
import {
  CHART_TOOLTIP_ITEM_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  truncate,
} from "@/components/common/dashboard/primitives";

export function DelayedProjectsChart() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-delayed"],
    queryFn: () => portfolioReportApi.getDelayedProjects(10),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="Top Delayed Projects">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="Top Delayed Projects">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const rows = (data ?? []).filter((r) => r.daysDelayed > 0);
  if (rows.length === 0) {
    return (
      <SectionCard
        title="Top Delayed Projects"
        subtitle="Projects ranked by schedule slippage"
      >
        <EmptyBlock label="No projects are currently delayed" />
      </SectionCard>
    );
  }

  const chartData = rows.map((r) => ({
    name: truncate(r.projectName, 32),
    code: r.projectCode,
    days: r.daysDelayed,
    spi: r.spi,
    rag: r.rag,
    forecast: r.forecastFinish,
  }));

  return (
    <SectionCard
      title="Top Delayed Projects"
      subtitle="Ranked by days delayed vs plan. Bars coloured by RAG."
    >
      <ResponsiveContainer width="100%" height={Math.max(200, chartData.length * 40)}>
        <BarChart
          data={chartData}
          layout="vertical"
          margin={{ top: 5, right: 30, left: 10, bottom: 5 }}
        >
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis type="number" stroke="#64748b" style={{ fontSize: "12px" }} />
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
              return [
                `${Number(value ?? 0)} days (SPI ${row.spi.toFixed(2)})`,
                "Delay",
              ];
            }}
          />
          <Bar dataKey="days" radius={[0, 4, 4, 0]}>
            {chartData.map((row) => (
              <Cell key={row.code} fill={ragFill(row.rag)} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </SectionCard>
  );
}
