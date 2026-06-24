"use client";

import { useMemo } from "react";
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
import { reportDataApi, type ResourceUtilRow } from "@/lib/api/reportDataApi";
import { KpiTile } from "@/components/common/KpiTile";
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

export function ResourcesSection({ projectId }: { projectId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["project-resource-utilization", projectId],
    queryFn: () => reportDataApi.getResourceUtilization(projectId),
    staleTime: 60_000,
    retry: false,
  });

  const rows: ResourceUtilRow[] = useMemo(() => data?.resources ?? [], [data]);

  const byType = useMemo(() => {
    const map = new Map<string, { planned: number; actual: number }>();
    rows.forEach((r) => {
      const key = r.type || "Other";
      const g = map.get(key) ?? { planned: 0, actual: 0 };
      g.planned += r.plannedHours ?? 0;
      g.actual += r.actualHours ?? 0;
      map.set(key, g);
    });
    return Array.from(map.entries()).map(([type, g]) => ({
      type,
      planned: g.planned,
      actual: g.actual,
      utilPct: g.planned > 0 ? (g.actual / g.planned) * 100 : 0,
    }));
  }, [rows]);

  if (isLoading)
    return (
      <SectionCard title="Resources">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="Resources">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );
  if (rows.length === 0) {
    return (
      <SectionCard title="Resources">
        <EmptyBlock label="No resource assignments for this project" />
      </SectionCard>
    );
  }

  const topRows = [...rows]
    .sort((a, b) => b.utilPct - a.utilPct)
    .slice(0, 10)
    .map((r) => ({
      name: truncate(r.name || r.code, 24),
      util: r.utilPct,
      type: r.type,
    }));

  const totalPlanned = byType.reduce((s, t) => s + t.planned, 0);
  const totalActual = byType.reduce((s, t) => s + t.actual, 0);
  const overallPct = totalPlanned > 0 ? (totalActual / totalPlanned) * 100 : 0;

  return (
    <SectionCard title="Resources" subtitle="Utilisation by type and top allocations">
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <KpiTile label="Total resources" value={data?.totalResources ?? rows.length} />
        <KpiTile label="Avg utilisation" value={`${(data?.avgUtilization ?? overallPct).toFixed(1)}%`} />
        <KpiTile label="Planned hrs" value={totalPlanned.toFixed(0)} />
        <KpiTile label="Actual hrs" value={totalActual.toFixed(0)} />
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <div>
          <h3 className="mb-2 text-sm font-medium text-text-secondary">By type</h3>
          <div className="space-y-3">
            {byType.map((t) => {
              const pct = Math.min(100, t.utilPct);
              const fill = ragFill(ragFromScore(t.utilPct));
              return (
                <div key={t.type}>
                  <div className="mb-1 flex items-center justify-between text-xs">
                    <span className="font-medium text-text-primary">{t.type}</span>
                    <span className="text-text-secondary">
                      {t.actual.toFixed(0)} / {t.planned.toFixed(0)} ({t.utilPct.toFixed(1)}%)
                    </span>
                  </div>
                  <div className="h-2 w-full overflow-hidden rounded-full bg-surface-hover">
                    <div className="h-full rounded-full" style={{ width: `${pct}%`, backgroundColor: fill }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-medium text-text-secondary">
            Top 10 by utilisation
          </h3>
          <ResponsiveContainer width="100%" height={Math.max(220, topRows.length * 30)}>
            <BarChart data={topRows} layout="vertical" margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis type="number" stroke="#64748b" style={{ fontSize: "12px" }} domain={[0, 100]} />
              <YAxis type="category" dataKey="name" stroke="#64748b" style={{ fontSize: "11px" }} width={160} />
              <Tooltip
                contentStyle={CHART_TOOLTIP_STYLE}
                labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                formatter={(value, _name, props) => {
                  const row = props.payload as (typeof topRows)[number];
                  return [`${Number(value ?? 0).toFixed(1)}% (${row.type})`, "Util"];
                }}
              />
              <Bar dataKey="util" radius={[0, 4, 4, 0]}>
                {topRows.map((row, i) => (
                  <Cell key={i} fill={ragFill(ragFromScore(row.util))} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </SectionCard>
  );
}
