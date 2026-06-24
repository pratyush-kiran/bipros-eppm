"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell,
} from "recharts";
import { LayoutList } from "lucide-react";
import { dprIssueApi } from "@/lib/api/dprIssueApi";
import type { DprIssueRow, IssueCategory, IssueSeverity, IssueStatus } from "@/lib/types/dpr";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { KpiTile } from "@/components/common/KpiTile";
import {
  SectionCard,
  LoadingBlock,
  EmptyBlock,
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
} from "@/components/common/dashboard/primitives";
import {
  CATEGORY_OPTIONS,
  SEVERITY_OPTIONS,
  STATUS_OPTIONS,
  categoryLabel,
} from "@/components/dpr/IssueBadges";
import { PageHeader } from "@/components/common/PageHeader";

const SEVERITY_COLORS: Record<string, string> = {
  LOW: "#2E7D5B",
  MEDIUM: "#3b82f6",
  HIGH: "#C7882E",
  CRITICAL: "#9B2C2C",
};

const HEATMAP_COLOR = (count: number) => {
  if (count === 0) return "";
  if (count <= 2) return "bg-blue-50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-300";
  if (count <= 5) return "bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-300";
  return "bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300";
};

const inputCls =
  "rounded-md border border-border bg-surface-hover px-3 py-1.5 text-sm text-text-primary focus:border-accent focus:outline-none";

export default function IssuesDashboardPage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;

  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [filterStatus, setFilterStatus] = useState<IssueStatus | "">("");
  const [filterSeverity, setFilterSeverity] = useState<IssueSeverity | "">("");
  const [filterCategory, setFilterCategory] = useState<IssueCategory | "">("");

  const { data, isLoading } = useQuery({
    queryKey: ["dpr-issues", projectId, { dateFrom, dateTo }],
    queryFn: () =>
      dprIssueApi.list(projectId, {
        dateFrom: dateFrom || undefined,
        dateTo: dateTo || undefined,
      }),
    enabled: !!projectId,
  });

  const allRows: DprIssueRow[] = data?.data ?? [];

  const rows = useMemo(
    () =>
      allRows
        .filter((r) => !filterStatus || r.status === filterStatus)
        .filter((r) => !filterSeverity || r.severity === filterSeverity)
        .filter((r) => !filterCategory || r.category === filterCategory),
    [allRows, filterStatus, filterSeverity, filterCategory]
  );

  // ── aggregations ──────────────────────────────────────────────────────────
  const totalOpen = rows.filter((r) => r.status === "OPEN" || r.status === "BLOCKED").length;
  const totalCritical = rows.filter((r) => r.severity === "CRITICAL").length;
  const totalResolved = rows.filter((r) => r.status === "RESOLVED" || r.status === "CLOSED").length;

  const categoryData = CATEGORY_OPTIONS.map((o) => ({
    name: o.label,
    count: rows.filter((r) => r.category === o.value).length,
  }));

  const severityData = SEVERITY_OPTIONS.map((o) => ({
    name: o.label,
    value: rows.filter((r) => r.severity === o.value).length,
    color: SEVERITY_COLORS[o.value],
  }));

  return (
    <div className="space-y-6">
      <PageHeader
        title="Issues Dashboard"
        description="Visual summary of all project issues."
        actions={
          <Link
            href={`/projects/${projectId}/issues`}
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-surface-hover px-3 py-1.5 text-sm font-medium text-text-secondary hover:bg-surface"
          >
            <LayoutList className="h-4 w-4" />
            List
          </Link>
        }
      />

      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2">
          <label className="text-xs font-medium text-text-muted">From</label>
          <input
            type="date"
            value={dateFrom}
            onChange={(e) => setDateFrom(e.target.value)}
            className={inputCls}
          />
        </div>
        <div className="flex items-center gap-2">
          <label className="text-xs font-medium text-text-muted">To</label>
          <input
            type="date"
            value={dateTo}
            onChange={(e) => setDateTo(e.target.value)}
            className={inputCls}
          />
        </div>

        <SearchableSelect
          options={[{ value: "", label: "All statuses" }, ...STATUS_OPTIONS]}
          value={filterStatus}
          onChange={(v) => setFilterStatus(v as IssueStatus | "")}
          placeholder="Status"
          className="w-40"
        />

        <SearchableSelect
          options={[{ value: "", label: "All severities" }, ...SEVERITY_OPTIONS]}
          value={filterSeverity}
          onChange={(v) => setFilterSeverity(v as IssueSeverity | "")}
          placeholder="Severity"
          className="w-36"
        />

        <SearchableSelect
          options={[{ value: "", label: "All categories" }, ...CATEGORY_OPTIONS]}
          value={filterCategory}
          onChange={(v) => setFilterCategory(v as IssueCategory | "")}
          placeholder="Category"
          className="w-48"
        />

        {(filterStatus || filterSeverity || filterCategory || dateFrom || dateTo) && (
          <button
            onClick={() => {
              setFilterStatus("");
              setFilterSeverity("");
              setFilterCategory("");
              setDateFrom("");
              setDateTo("");
            }}
            className="text-xs text-text-muted underline hover:text-text-primary"
          >
            Clear all
          </button>
        )}
      </div>

      {isLoading ? (
        <LoadingBlock label="Loading issue data…" />
      ) : (
        <>
          {/* KPI row */}
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <KpiTile label="Total Issues" value={rows.length} tone="default" />
            <KpiTile label="Open / Blocked" value={totalOpen} tone="warning" />
            <KpiTile label="Critical" value={totalCritical} tone="danger" />
            <KpiTile label="Resolved" value={totalResolved} tone="success" />
          </div>

          {/* Bar + Donut row */}
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {/* Bar chart — by category */}
            <SectionCard title="By Category">
              {rows.length === 0 ? (
                <EmptyBlock label="No issues in selected range" />
              ) : (
                <ResponsiveContainer width="100%" height={340}>
                  <BarChart
                    data={categoryData}
                    layout="vertical"
                    margin={{ top: 0, right: 16, bottom: 0, left: 4 }}
                  >
                    <XAxis type="number" tick={{ fontSize: 11 }} allowDecimals={false} />
                    <YAxis
                      type="category"
                      dataKey="name"
                      width={130}
                      tick={{ fontSize: 11 }}
                    />
                    <Tooltip
                      contentStyle={CHART_TOOLTIP_STYLE}
                      labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                      itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                      formatter={(v) => [v, "Issues"]}
                    />
                    <Bar dataKey="count" fill={CHART_COLORS.planned} radius={[0, 4, 4, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}
            </SectionCard>

            {/* Donut — by severity */}
            <SectionCard title="By Severity">
              {rows.length === 0 ? (
                <EmptyBlock label="No issues in selected range" />
              ) : (
                <div className="flex flex-col items-center">
                  <ResponsiveContainer width="100%" height={260}>
                    <PieChart>
                      <Pie
                        data={severityData}
                        cx="50%"
                        cy="50%"
                        innerRadius={72}
                        outerRadius={110}
                        dataKey="value"
                        paddingAngle={2}
                      >
                        {severityData.map((entry) => (
                          <Cell key={entry.name} fill={entry.color} />
                        ))}
                      </Pie>
                      <Tooltip
                        contentStyle={CHART_TOOLTIP_STYLE}
                        labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                        itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                        formatter={(v, name) => [v, name]}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                  {/* legend */}
                  <div className="flex flex-wrap justify-center gap-x-4 gap-y-1.5">
                    {severityData.map((d) => (
                      <div key={d.name} className="flex items-center gap-1.5 text-xs text-text-secondary">
                        <span
                          className="inline-block h-2.5 w-2.5 rounded-full"
                          style={{ background: d.color }}
                        />
                        {d.name} ({d.value})
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </SectionCard>
          </div>

          {/* Heatmap grid */}
          <SectionCard title="Category × Severity Heatmap" subtitle="Issue count per cell">
            {rows.length === 0 ? (
              <EmptyBlock label="No issues in selected range" />
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead>
                    <tr>
                      <th className="px-3 py-2 text-left text-xs font-semibold text-text-muted">
                        Category
                      </th>
                      {SEVERITY_OPTIONS.map((s) => (
                        <th
                          key={s.value}
                          className="px-4 py-2 text-center text-xs font-semibold uppercase tracking-wide"
                          style={{ color: SEVERITY_COLORS[s.value] }}
                        >
                          {s.label}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {CATEGORY_OPTIONS.map((cat) => (
                      <tr key={cat.value} className="hover:bg-surface-hover">
                        <td className="whitespace-nowrap px-3 py-2 text-xs font-medium text-text-secondary">
                          {categoryLabel(cat.value)}
                        </td>
                        {SEVERITY_OPTIONS.map((sev) => {
                          const count = rows.filter(
                            (r) => r.category === cat.value && r.severity === sev.value
                          ).length;
                          return (
                            <td
                              key={sev.value}
                              className={`px-4 py-2 text-center text-xs font-semibold tabular-nums rounded ${HEATMAP_COLOR(count)}`}
                            >
                              {count > 0 ? count : <span className="text-text-muted opacity-30">·</span>}
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
