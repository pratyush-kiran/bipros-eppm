"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, BarChart3 } from "lucide-react";
import { projectApi } from "@/lib/api/projectApi";
import {
  capacityUtilizationApi,
  type AggregateGroupBy,
  type AggregatePeriodType,
  type CapacityAggregateBucket,
  type CapacityRoleRow,
} from "@/lib/api/capacityUtilizationApi";
import { PeriodCell } from "@/components/capacity/PeriodCell";

const today = () => new Date().toISOString().split("T")[0];
const ninetyDaysAgo = () => {
  const d = new Date();
  d.setDate(d.getDate() - 90);
  return d.toISOString().split("T")[0];
};

interface PivotKey {
  kind: "Manpower" | "Equipment";
  roleId: string;
  roleLabel: string;
}

interface PivotRow extends PivotKey {
  cells: Array<CapacityRoleRow | null>;
}

function pivotBuckets(buckets: CapacityAggregateBucket[]): PivotRow[] {
  const seen = new Map<string, PivotKey>();
  // Collect every (kind, roleId) seen across buckets, preserving order of first appearance.
  for (const b of buckets) {
    const visit = (kind: "Manpower" | "Equipment", rows: CapacityRoleRow[] | undefined) => {
      if (!rows) return;
      for (const r of rows) {
        const k = `${kind}::${r.roleId}`;
        if (!seen.has(k)) {
          seen.set(k, {
            kind,
            roleId: r.roleId,
            roleLabel: r.roleName ?? r.roleCode ?? "(role)",
          });
        }
      }
    };
    visit("Manpower", b.manpower?.rows);
    visit("Equipment", b.equipment?.rows);
  }

  const rows: PivotRow[] = [];
  for (const key of seen.values()) {
    const cells: Array<CapacityRoleRow | null> = buckets.map((b) => {
      const src = (key.kind === "Manpower" ? b.manpower : b.equipment)?.rows ?? [];
      return src.find((r) => r.roleId === key.roleId) ?? null;
    });
    rows.push({ ...key, cells });
  }
  return rows;
}

export default function CapacityUtilizationAggregatePage() {
  const [projectId, setProjectId] = useState<string>("");
  const [periodType, setPeriodType] = useState<AggregatePeriodType>("MONTHLY");
  const [groupBy, setGroupBy] = useState<AggregateGroupBy>("ROLE");
  const [from, setFrom] = useState<string>(ninetyDaysAgo());
  const [to, setTo] = useState<string>(today());

  const { data: projectsData, isLoading: projectsLoading } = useQuery({
    queryKey: ["projects"],
    queryFn: () => projectApi.listProjects(0, 100),
  });
  const projects = projectsData?.data?.content ?? [];

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["capacity-utilization-aggregate", projectId, periodType, from, to, groupBy],
    queryFn: () =>
      capacityUtilizationApi.getAggregate({
        projectId,
        periodType,
        from,
        to,
        groupBy,
      }),
    enabled: !!projectId,
  });

  const buckets = useMemo(() => data?.data?.buckets ?? [], [data]);
  const pivot = useMemo(() => pivotBuckets(buckets), [buckets]);

  return (
    <div className="p-6">
      <Link
        href="/reports"
        className="inline-flex items-center gap-1.5 text-sm text-slate hover:text-charcoal mb-4"
      >
        <ArrowLeft size={14} /> Back to Reports
      </Link>

      <div className="mb-8">
        <div className="flex items-center gap-3 mb-2">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-gold to-gold-deep text-paper shadow-[0_4px_10px_-4px_rgba(212,175,55,0.5)]">
            <BarChart3 size={20} strokeWidth={1.75} />
          </div>
          <div>
            <h1 className="font-display text-2xl font-semibold tracking-tight text-charcoal">
              Capacity Utilization · Multi-Period Aggregate
            </h1>
            <p className="text-sm text-slate">
              Slice a date range into weekly or monthly buckets. Each cell is the per-role
              cumulative section over that bucket&apos;s window.
            </p>
          </div>
        </div>
      </div>

      <div className="rounded-2xl border border-hairline bg-paper p-5 mb-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
        <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
              Project
            </label>
            <select
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              disabled={projectsLoading}
              className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
            >
              <option value="">— Pick a project —</option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.code} — {p.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
              Period
            </label>
            <select
              value={periodType}
              onChange={(e) => setPeriodType(e.target.value as AggregatePeriodType)}
              className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
            >
              <option value="WEEKLY">Weekly</option>
              <option value="MONTHLY">Monthly</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
              Group By
            </label>
            <select
              value={groupBy}
              onChange={(e) => setGroupBy(e.target.value as AggregateGroupBy)}
              className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
            >
              <option value="ROLE">Role</option>
              <option value="RESOURCE_TYPE">Resource Type</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
              From
            </label>
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
            />
          </div>
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
              To
            </label>
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
            />
          </div>
        </div>
      </div>

      {!projectId && (
        <div className="rounded-xl border border-hairline bg-ivory/60 p-6 text-center text-sm text-slate">
          Pick a project to start.
        </div>
      )}

      {projectId && isLoading && (
        <div className="text-slate text-sm">Loading…</div>
      )}
      {projectId && isError && (
        <div className="text-danger text-sm">
          Failed to load: {(error as Error)?.message ?? "unknown error"}
        </div>
      )}

      {projectId && !isLoading && !isError && (
        <>
          {buckets.length === 0 ? (
            <div className="rounded-xl border border-hairline bg-paper px-6 py-8 text-center text-sm text-slate">
              No data in this date range for the chosen project.
            </div>
          ) : (
            <div className="rounded-2xl border border-hairline bg-paper overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-sm border-collapse">
                  <thead className="bg-ivory border-b border-hairline">
                    <tr>
                      <th className="px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate sticky left-0 bg-ivory min-w-[180px]">
                        Section · Role
                      </th>
                      {buckets.map((b) => (
                        <th
                          key={`${b.from}-${b.to}`}
                          className="px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-slate border-l border-hairline min-w-[200px]"
                        >
                          {b.label}
                          <div className="text-[10px] font-normal normal-case text-slate/80">
                            {b.from} → {b.to}
                          </div>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {pivot.map((row) => (
                      <tr key={`${row.kind}-${row.roleId}`} className="border-t border-hairline/50">
                        <td className="px-3 py-2 align-top sticky left-0 bg-paper">
                          <div className="text-xs text-slate uppercase tracking-wide">{row.kind}</div>
                          <div className="text-sm text-charcoal">{row.roleLabel}</div>
                        </td>
                        {row.cells.map((cell, i) => (
                          <td
                            key={`${row.roleId}-${i}`}
                            className="px-3 py-2 align-top border-l border-hairline"
                          >
                            <PeriodCell period={cell?.cumulative ?? null} />
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
