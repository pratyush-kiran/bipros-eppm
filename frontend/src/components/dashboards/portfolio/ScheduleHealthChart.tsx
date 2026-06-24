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
import { ragFill, ragFromScore } from "@/lib/utils/rag";
import {
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  truncate,
} from "@/components/common/dashboard/primitives";

export function ScheduleHealthChart() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-schedule-health"],
    queryFn: () => portfolioReportApi.getScheduleHealth(),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="Schedule Health">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="Schedule Health">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const rows = data ?? [];
  if (rows.length === 0) {
    return (
      <SectionCard title="Schedule Health">
        <EmptyBlock label="No projects" />
      </SectionCard>
    );
  }

  const chartData = rows.map((r) => ({
    name: truncate(r.projectCode, 24),
    fullName: r.projectName,
    health: r.overallHealthPct,
    bei: r.beiActual,
    missed: r.missedTasksCount,
    critical: r.criticalPathLength,
  }));

  return (
    <SectionCard
      title="Schedule Health"
      subtitle="DCMA-style health score per project (higher is better)"
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
            domain={[0, 100]}
          />
          <YAxis
            type="category"
            dataKey="name"
            stroke="#64748b"
            style={{ fontSize: "11px" }}
            width={180}
          />
          <Tooltip
            contentStyle={CHART_TOOLTIP_STYLE}
            labelStyle={CHART_TOOLTIP_LABEL_STYLE}
            itemStyle={CHART_TOOLTIP_ITEM_STYLE}
            formatter={(value, _name, props) => {
              const row = props.payload as (typeof chartData)[number];
              return [
                `${Number(value ?? 0).toFixed(0)}% · BEI ${row.bei.toFixed(2)} · ${row.missed} missed · ${row.critical} critical`,
                "Health",
              ];
            }}
          />
          <Bar dataKey="health" radius={[0, 4, 4, 0]}>
            {chartData.map((row) => (
              <Cell key={row.name} fill={ragFill(ragFromScore(row.health))} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </SectionCard>
  );
}
