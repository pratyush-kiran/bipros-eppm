"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { subContractorKpiApi } from "@/lib/api/subContractorKpiApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { formatMoney } from "@/lib/hooks/useCurrency";

interface Props {
  projectId: string;
  from?: string;
  to?: string;
  density?: "compact" | "full";
}

function defaultRange(): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1000);
  const fmt = (d: Date) => d.toISOString().split("T")[0];
  return { from: fmt(from), to: fmt(to) };
}

function formatPct(value: number | null | undefined, digits = 1): string {
  if (value == null || Number.isNaN(value)) return "—";
  return `${value.toFixed(digits)}%`;
}

function formatNum(value: number | null | undefined, digits = 2): string {
  if (value == null || Number.isNaN(value)) return "—";
  return value.toLocaleString("en-IN", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

function pfClass(pf: number | null): string {
  if (pf == null) return "text-text-muted";
  if (pf >= 1.0) return "text-success";
  if (pf >= 0.8) return "text-warning";
  return "text-danger";
}

export function SubContractorKpiSection({ projectId, from, to, density = "compact" }: Props) {
  const range = useMemo(() => {
    if (from && to) return { from, to };
    return defaultRange();
  }, [from, to]);

  const { data, isLoading, error } = useQuery({
    queryKey: ["sub-contractor-kpis", projectId, range.from, range.to],
    queryFn: () => subContractorKpiApi.getKpis(projectId, range.from, range.to),
    enabled: !!projectId,
  });

  const { data: budgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    enabled: !!projectId,
    staleTime: 5 * 60 * 1000,
  });
  const projectCurrency = budgetData?.data?.budgetCurrency ?? "OMR";
  const money = (v: number | null | undefined) => formatMoney(v, projectCurrency, 0);

  if (!projectId) return null;
  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-text-muted">
        Loading sub-contractor KPIs…
      </div>
    );
  }
  if (error || !data?.data) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-danger">
        Failed to load sub-contractor KPIs.
      </div>
    );
  }
  const kpis = data.data;

  if (kpis.activeSubContractors === 0) {
    return (
      <section className="rounded-lg border border-border bg-surface/40 p-4">
        <h3 className="text-sm font-semibold text-text-primary">
          Sub-Contractor KPIs <span className="text-text-muted">({range.from} → {range.to})</span>
        </h3>
        <p className="mt-2 text-sm text-text-muted">No sub-contractors assigned to this project.</p>
      </section>
    );
  }

  return (
    <section className="space-y-4 rounded-lg border border-border bg-surface/40 p-4">
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold text-text-primary">
          Sub-Contractor KPIs <span className="text-text-muted font-normal">({range.from} → {range.to})</span>
        </h3>
        <div className="text-xs text-text-muted">
          {kpis.activeSubContractors} sub-contractor{kpis.activeSubContractors === 1 ? "" : "s"} active ·
          {" "}{kpis.workTypesTracked} work-type{kpis.workTypesTracked === 1 ? "" : "s"} tracked
          {kpis.unmatchedDprRows > 0 ? ` · ${kpis.unmatchedDprRows} unmatched DPR row${kpis.unmatchedDprRows === 1 ? "" : "s"}` : ""}
        </div>
      </header>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-5">
        <Tile label="Quantity Completion"
              value={formatPct(kpis.quantityCompletionPct)}
              caption={`${formatNum(kpis.totalActualQty, 0)} of ${formatNum(kpis.totalPlannedQty, 0)}`}
              valueClass={kpis.quantityCompletionPct > 100 ? "text-danger" : "text-text-primary"} />
        <Tile label="Avg Productivity"
              value={formatNum(kpis.avgProductivityFactor, 2)}
              caption="actual ÷ norm output/day"
              valueClass={pfClass(kpis.avgProductivityFactor)} />
        <Tile label="Actual Cost"
              value={money(kpis.totalActualCost)}
              caption="Σ qty × rate" />
        <Tile label="Planned Cost"
              value={money(kpis.totalPlannedCost)}
              caption="Σ planned commitment" />
        <Tile label="Cost Variance"
              value={money(kpis.costVariance)}
              caption="planned − actual"
              valueClass={kpis.costVariance >= 0 ? "text-success" : "text-danger"} />
      </div>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        <Tile label="SC LCPI"
              value={formatNum(kpis.costPerformanceIndex, 2)}
              caption="planned ÷ actual cost" />
        <Tile label="Days Worked"
              value={`${kpis.daysWorked}${kpis.impliedPlannedDays != null ? ` of ~${kpis.impliedPlannedDays}` : ""}`}
              caption="vs implied duration" />
        <Tile label="Under-Performing SC"
              value={kpis.underPerformingCount.toString()}
              caption="PF < 0.8"
              valueClass={kpis.underPerformingCount > 0 ? "text-danger" : "text-text-primary"} />
      </div>

      {density === "full" && kpis.perScWorkType.length > 0 ? (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full text-xs">
            <thead className="bg-surface-hover/60 text-text-muted">
              <tr>
                <th className="px-2 py-1.5 text-left">SC Code</th>
                <th className="px-2 py-1.5 text-left">SC Name</th>
                <th className="px-2 py-1.5 text-left">Work Type</th>
                <th className="px-2 py-1.5 text-left">Unit</th>
                <th className="px-2 py-1.5 text-right">Planned</th>
                <th className="px-2 py-1.5 text-right">Actual</th>
                <th className="px-2 py-1.5 text-right">Days</th>
                <th className="px-2 py-1.5 text-right">Avg/Day</th>
                <th className="px-2 py-1.5 text-right">Norm/Day</th>
                <th className="px-2 py-1.5 text-right">PF</th>
                <th className="px-2 py-1.5 text-right">Cost (act/plan)</th>
                <th className="px-2 py-1.5 text-right">% Complete</th>
              </tr>
            </thead>
            <tbody>
              {kpis.perScWorkType.map((r) => (
                <tr key={`${r.scMasterId}-${r.scWorkTypeId}`} className="border-t border-border">
                  <td className="px-2 py-1.5">{r.scCode}</td>
                  <td className="px-2 py-1.5">{r.scName}</td>
                  <td className="px-2 py-1.5">{r.workTypeName}</td>
                  <td className="px-2 py-1.5">{r.unit ?? "—"}</td>
                  <td className="px-2 py-1.5 text-right tabular-nums">{formatNum(r.plannedQty, 2)}</td>
                  <td className="px-2 py-1.5 text-right tabular-nums">{formatNum(r.actualQty, 2)}</td>
                  <td className="px-2 py-1.5 text-right tabular-nums">{r.distinctDays}</td>
                  <td className="px-2 py-1.5 text-right tabular-nums">{formatNum(r.avgQtyPerDay, 2)}</td>
                  <td className="px-2 py-1.5 text-right tabular-nums">{formatNum(r.normPerDay, 2)}</td>
                  <td className={`px-2 py-1.5 text-right tabular-nums ${pfClass(r.productivityFactor)}`}>
                    {formatNum(r.productivityFactor, 2)}
                  </td>
                  <td className="px-2 py-1.5 text-right tabular-nums">
                    {money(r.actualCost)} / {money(r.plannedCost)}
                  </td>
                  <td className={`px-2 py-1.5 text-right tabular-nums ${r.qtyCompletionPct > 100 ? "text-danger" : ""}`}>
                    {formatPct(r.qtyCompletionPct)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {density === "full" ? (
        <div className="grid gap-3 md:grid-cols-3">
          <SidePanel title="Productivity Factor — bottom 5"
                     rows={kpis.bottomProductivity}
                     render={(r) => `${r.scCode} / ${r.workTypeName}: PF ${formatNum(r.productivityFactor, 2)}`} />
          <SidePanel title="Cost — top 5 by actual spend"
                     rows={kpis.topByCost}
                     render={(r) => `${r.scCode} / ${r.workTypeName}: ${money(r.actualCost)}`} />
          <SidePanel title="Output Achievement — bottom 5"
                     rows={kpis.bottomOutputAchievement}
                     render={(r) => `${r.scCode} / ${r.workTypeName}: ${formatPct(r.qtyCompletionPct)}`} />
        </div>
      ) : null}
    </section>
  );
}

function Tile({
  label, value, caption, valueClass,
}: {
  label: string; value: string; caption?: string; valueClass?: string;
}) {
  return (
    <div className="rounded-md border border-border bg-surface-hover/40 p-3">
      <div className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">{label}</div>
      <div className={`mt-1 text-xl font-bold ${valueClass ?? "text-text-primary"}`}>{value}</div>
      {caption ? <div className="mt-0.5 text-[11px] text-text-muted">{caption}</div> : null}
    </div>
  );
}

function SidePanel({
  title, rows, render,
}: {
  title: string;
  rows: import("@/lib/api/subContractorKpiApi").SubContractorWorkTypeRow[];
  render: (r: import("@/lib/api/subContractorKpiApi").SubContractorWorkTypeRow) => string;
}) {
  return (
    <div className="rounded-md border border-border bg-surface-hover/30 p-3">
      <h4 className="text-xs font-semibold text-text-primary">{title}</h4>
      {rows.length === 0 ? (
        <p className="mt-2 text-xs text-text-muted">No data.</p>
      ) : (
        <ul className="mt-2 space-y-1 text-xs text-text-primary">
          {rows.map((r) => (
            <li key={`${r.scMasterId}-${r.scWorkTypeId}`} className="truncate">{render(r)}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
