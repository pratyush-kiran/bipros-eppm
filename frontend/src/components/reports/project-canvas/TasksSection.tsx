"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from "recharts";
import {
  projectInsightsApi,
  type ActivityStatusRow,
} from "@/lib/api/projectInsightsApi";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
} from "@/components/common/dashboard/primitives";
import { KpiTile } from "@/components/common/KpiTile";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";

const STATUS_COLORS: Record<string, string> = {
  NOT_STARTED: CHART_COLORS.muted,
  IN_PROGRESS: CHART_COLORS.pv,
  COMPLETED: CHART_COLORS.ev,
};

export function TasksSection({ projectId }: { projectId: string }) {
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [criticalOnly, setCriticalOnly] = useState(false);

  const { data, isLoading, isError } = useQuery({
    queryKey: ["project-activity-status", projectId, statusFilter, criticalOnly],
    queryFn: () =>
      projectInsightsApi.getActivityStatus(projectId, {
        status: statusFilter || undefined,
        criticalOnly,
        limit: 500,
      }),
    staleTime: 30_000,
  });

  const rows: ActivityStatusRow[] = useMemo(() => data ?? [], [data]);

  const taskColumns = useMemo<ColumnDef<ActivityStatusRow>[]>(
    () => [
      {
        accessorKey: "code",
        header: "Code",
        cell: (info) => (
          <span className="font-mono text-[11px] text-text-muted">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "name",
        header: "Name",
        cell: (info) => (
          <span className="text-text-primary">{info.getValue() as string}</span>
        ),
      },
      {
        accessorKey: "wbsCode",
        header: "WBS",
        cell: (info) => (
          <span className="font-mono text-[11px] text-text-muted">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: (info) => {
          const status = info.getValue() as string;
          return (
            <span
              className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                status === "COMPLETED"
                  ? "bg-success/15 text-success"
                  : status === "IN_PROGRESS"
                    ? "bg-accent/15 text-accent"
                    : "bg-surface-hover text-text-secondary"
              }`}
            >
              {status}
            </span>
          );
        },
      },
      {
        accessorKey: "pctComplete",
        header: "%",
        cell: (info) => (
          <span className="block text-right font-mono">
            {Number(info.getValue()).toFixed(0)}%
          </span>
        ),
      },
      {
        accessorKey: "plannedFinish",
        header: "Planned Finish",
        cell: (info) => <span>{(info.getValue() as string) ?? "—"}</span>,
      },
      {
        accessorKey: "totalFloat",
        header: "Float",
        cell: (info) => (
          <span className="block text-right font-mono">
            {(info.getValue() as number | null) != null
              ? Number(info.getValue()).toFixed(0)
              : "—"}
          </span>
        ),
      },
      {
        accessorKey: "daysDelay",
        header: "Delay",
        cell: (info) => {
          const daysDelay = Number(info.getValue());
          return (
            <span
              className={`block text-right font-mono ${
                daysDelay > 0 ? "text-danger" : "text-text-muted"
              }`}
            >
              {daysDelay > 0 ? `+${daysDelay}d` : "—"}
            </span>
          );
        },
      },
    ],
    []
  );

  const summary = useMemo(() => {
    const byStatus = new Map<string, number>();
    let critical = 0;
    let overdue = 0;
    let avgPct = 0;
    rows.forEach((r) => {
      byStatus.set(r.status, (byStatus.get(r.status) ?? 0) + 1);
      if (r.isCritical) critical++;
      if (r.daysDelay > 0 && r.pctComplete < 100) overdue++;
      avgPct += r.pctComplete;
    });
    if (rows.length > 0) avgPct /= rows.length;
    return {
      total: rows.length,
      byStatus,
      critical,
      overdue,
      avgPct,
    };
  }, [rows]);

  const statusChartData = Array.from(summary.byStatus.entries()).map(([name, value]) => ({
    name,
    value,
  }));

  if (isLoading)
    return (
      <SectionCard title="Tasks">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="Tasks">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  return (
    <SectionCard
      title="Tasks"
      subtitle="Task-wise dashboard: status, critical path, delays, progress"
      actions={
        <div className="flex items-center gap-2 text-xs">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-md border border-border bg-surface-hover/40 px-2 py-1 text-text-primary"
          >
            <option value="">All statuses</option>
            <option value="NOT_STARTED">Not started</option>
            <option value="IN_PROGRESS">In progress</option>
            <option value="COMPLETED">Completed</option>
          </select>
          <label className="flex items-center gap-1 text-text-secondary">
            <input
              type="checkbox"
              checked={criticalOnly}
              onChange={(e) => setCriticalOnly(e.target.checked)}
            />
            Critical only
          </label>
        </div>
      }
    >
      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <KpiTile label="Total" value={summary.total} />
        <KpiTile label="Critical" value={summary.critical} tone={summary.critical > 0 ? "warning" : "default"} />
        <KpiTile label="Overdue" value={summary.overdue} tone={summary.overdue > 0 ? "danger" : "success"} />
        <KpiTile label="Avg % complete" value={`${summary.avgPct.toFixed(1)}%`} tone="accent" />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[260px_1fr]">
        <div>
          <div className="mb-2 text-xs font-medium uppercase tracking-wide text-text-muted">
            Status mix
          </div>
          {statusChartData.length === 0 ? (
            <EmptyBlock label="No tasks" />
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <PieChart>
                <Pie
                  data={statusChartData}
                  dataKey="value"
                  nameKey="name"
                  innerRadius={40}
                  outerRadius={80}
                >
                  {statusChartData.map((s) => (
                    <Cell key={s.name} fill={STATUS_COLORS[s.name] ?? CHART_COLORS.muted} />
                  ))}
                </Pie>
                <Tooltip contentStyle={CHART_TOOLTIP_STYLE} labelStyle={CHART_TOOLTIP_LABEL_STYLE} itemStyle={CHART_TOOLTIP_ITEM_STYLE} />
                <Legend wrapperStyle={{ fontSize: "11px" }} />
              </PieChart>
            </ResponsiveContainer>
          )}
        </div>

        <div className="overflow-x-auto">
          {rows.length === 0 ? (
            <EmptyBlock label="No tasks match the current filter" />
          ) : (
            <VirtualDataTable
              columns={taskColumns}
              data={rows}
              searchable={false}
              resizable={false}
              rowClassName={(row) => (row.isCritical ? "bg-danger/5" : "")}
              maxHeight={400}
            />
          )}
        </div>
      </div>
    </SectionCard>
  );
}
