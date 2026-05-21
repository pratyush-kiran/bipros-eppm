"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { equipmentKpiApi } from "@/lib/api/equipmentKpiApi";

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

function formatPct(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return "—";
  return `${(value * 100).toFixed(1)}%`;
}

function formatNumber(value: number | null | undefined, fractionDigits = 2): string {
  if (value == null || Number.isNaN(value)) return "—";
  return value.toLocaleString("en-IN", {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
}

export function EquipmentKpiSection({ projectId, from, to, density = "compact" }: Props) {
  const range = useMemo(() => {
    if (from && to) return { from, to };
    return defaultRange();
  }, [from, to]);

  const { data, isLoading, error } = useQuery({
    queryKey: ["equipment-kpis", projectId, range.from, range.to],
    queryFn: () => equipmentKpiApi.getKpis(projectId, range.from, range.to),
    enabled: !!projectId,
  });

  if (!projectId) return null;
  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-text-muted">
        Loading equipment KPIs…
      </div>
    );
  }
  if (error || !data?.data) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-danger">
        Failed to load equipment KPIs.
      </div>
    );
  }
  const kpis = data.data;
  const totalCost = kpis.ownedVsRented.reduce((s, x) => s + x.cost, 0);

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-lg font-semibold text-text-primary">
          Equipment KPIs <span className="text-xs font-normal text-text-muted">({range.from} → {range.to})</span>
        </h2>
        <div className="text-[11px] text-text-muted">
          {kpis.utilization.length} machines tracked
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Equipment Utilisation</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              kpis.nosUtilizationPct < 0.6
                ? "text-warning"
                : kpis.nosUtilizationPct > 0.85
                  ? "text-success"
                  : "text-text-primary"
            }`}
          >
            {formatPct(kpis.nosUtilizationPct)}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="Σ actual nos (DPR equipment deployment) ÷ Σ planned headcount across equipment assignments × 100. Hours are logging-only."
          >
            {kpis.actualNos} of {kpis.plannedNos} nos
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Owned/Rented Cost</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">₹{formatNumber(totalCost, 0)}</div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="Σ (nos × rate) on DPR equipment rows, grouped by ownership."
          >
            actual cost by ownership
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Service Due (next 7 days)</div>
          <div
            className={`mt-1 text-2xl font-semibold ${kpis.serviceDue.length > 0 ? "text-warning" : "text-text-primary"}`}
          >
            {kpis.serviceDue.length}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="Equipment whose nextServiceDate falls within the next 7 days. Schedule preventive maintenance to avoid breakdowns on the critical path."
          >
            machines
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Equipment Productivity Index</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              kpis.equipmentProductivityIndexPct < 0.8
                ? "text-danger"
                : kpis.equipmentProductivityIndexPct > 1.0
                  ? "text-success"
                  : "text-text-primary"
            }`}
          >
            {kpis.equipmentProductivityIndexPct > 0
              ? formatPct(kpis.equipmentProductivityIndexPct)
              : "—"}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="KPI 6.1 — Actual Output ÷ Standard Output × 100, averaged across machines.">
            actual ÷ standard output
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="rounded-lg border border-border bg-surface/40 p-4">
          <h3 className="text-sm font-semibold text-text-primary mb-2">Per-machine summary</h3>
          <table className="w-full text-xs">
            <thead className="text-text-muted">
              <tr>
                <th className="text-left pb-1">Machine</th>
                <th className="text-right pb-1">Op hrs</th>
                <th className="text-right pb-1" title="KPI 6.2 — Qty produced per operating hour">Qty / hr</th>
                <th className="text-right pb-1">Perf</th>
              </tr>
            </thead>
            <tbody>
              {kpis.utilization.length === 0 && (
                <tr><td colSpan={4} className="py-2 text-center text-text-muted">No data</td></tr>
              )}
              {kpis.utilization.slice(0, 8).map((u) => {
                const perfRow = kpis.availabilityPerformance.find((r) => r.resourceId === u.resourceId);
                return (
                  <tr key={u.resourceId} className="text-text-primary">
                    <td className="py-1 truncate max-w-[160px]">{u.resourceCode}</td>
                    <td className="py-1 text-right">{formatNumber(u.operatingHours, 1)}</td>
                    <td className="py-1 text-right">
                      {(perfRow?.outputRatePerHour ?? 0) > 0 ? formatNumber(perfRow!.outputRatePerHour, 3) : "—"}
                    </td>
                    <td
                      className={`py-1 text-right ${
                        (perfRow?.performance ?? 0) === 0
                          ? "text-text-muted"
                          : (perfRow?.performance ?? 0) < 0.8
                            ? "text-danger"
                            : (perfRow?.performance ?? 0) > 1.0
                              ? "text-success"
                              : ""
                      }`}
                    >
                      {(perfRow?.performance ?? 0) > 0 ? formatPct(perfRow!.performance) : "—"}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="mt-2 text-[11px] text-text-muted">
            Perf shows "—" when the variant has no <code>standardOutputPerDay</code> set.
          </p>
        </div>

        <div className="rounded-lg border border-border bg-surface/40 p-4">
          <h3 className="text-sm font-semibold text-text-primary mb-2">Owned vs Rented</h3>
          <table className="w-full text-xs">
            <thead className="text-text-muted">
              <tr>
                <th className="text-left pb-1">Ownership</th>
                <th className="text-right pb-1">Op Hours</th>
                <th className="text-right pb-1">Cost (₹)</th>
              </tr>
            </thead>
            <tbody>
              {kpis.ownedVsRented.length === 0 && (
                <tr><td colSpan={3} className="py-2 text-center text-text-muted">No data</td></tr>
              )}
              {kpis.ownedVsRented.map((s) => (
                <tr key={s.ownershipType} className="text-text-primary">
                  <td className="py-1">{s.ownershipType}</td>
                  <td className="py-1 text-right">{formatNumber(s.operatingHours, 1)}</td>
                  <td className="py-1 text-right">{formatNumber(s.cost, 0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-2 text-[11px] text-text-muted">
            Cost = Σ DPR <code>nos × rate</code> grouped by ownership. UNKNOWN bucket appears when supervisor hasn't picked OWNED/HIRED on the DPR.
          </p>
        </div>
      </div>

    </section>
  );
}
