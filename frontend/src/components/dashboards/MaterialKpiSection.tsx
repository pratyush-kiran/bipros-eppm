"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { materialKpiApi } from "@/lib/api/materialKpiApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

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

export function MaterialKpiSection({ projectId, from, to, density = "compact" }: Props) {
  const { money, symbol } = useProjectCurrency();
  const range = useMemo(() => {
    if (from && to) return { from, to };
    return defaultRange();
  }, [from, to]);

  const { data, isLoading, error } = useQuery({
    queryKey: ["material-kpis", projectId, range.from, range.to],
    queryFn: () => materialKpiApi.getKpis(projectId, range.from, range.to),
    enabled: !!projectId,
  });

  if (!projectId) return null;
  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-text-muted">
        Loading material KPIs…
      </div>
    );
  }
  if (error || !data?.data) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-danger">
        Failed to load material KPIs.
      </div>
    );
  }
  const kpis = data.data;
  const hasData = kpis.issuedQty > 0 || kpis.consumedQty > 0;

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-lg font-semibold text-text-primary">
          Material KPIs <span className="text-xs font-normal text-text-muted">({range.from} → {range.to})</span>
        </h2>
        <div className="text-[11px] text-text-muted">
          {kpis.byMaterial.length} materials tracked
        </div>
      </div>

      {!hasData && (
        <div className="rounded-md border border-border bg-surface/40 p-3 text-xs text-text-muted">
          No material issues or consumption logs in this window. KPIs will populate as the
          stores team records issues and the daily consumption log.
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Material Utilisation</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              kpis.materialUtilizationPct < 0.85 ? "text-warning" : "text-success"
            }`}
          >
            {hasData ? formatPct(kpis.materialUtilizationPct) : "—"}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="KPI 8.1 — Σ consumed ÷ Σ issued × 100">
            consumed ÷ issued
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Wastage %</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              kpis.wastagePct > 0.05 ? "text-danger" : "text-text-primary"
            }`}
          >
            {hasData ? formatPct(kpis.wastagePct) : "—"}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="KPI 8.3 — Wastage qty ÷ Issued qty × 100">
            wastage ÷ issued
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Issued vs Consumed</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">
            {formatNumber(kpis.consumedQty, 0)} / {formatNumber(kpis.issuedQty, 0)}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="Σ issued and Σ consumed across all materials">
            total qty
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Reconciliation Balance</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              Math.abs(kpis.reconciliationBalance) > 0.001 ? "text-warning" : "text-success"
            }`}
          >
            {formatNumber(kpis.reconciliationBalance, 3)}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="KPI 8.5 — Issued − Consumed − Wastage. Target = 0.">
            target = 0
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Cost / Unit Finished</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">
            {(kpis.weightedAvgCostPerUnitFinished ?? 0) > 0
              ? money(kpis.weightedAvgCostPerUnitFinished)
              : "—"}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="KPI 9.5 — Σ DPR-material line cost ÷ Σ qty finished, weighted across activities. Compared per-activity vs BOQ budgeted_rate in the drill-down."
          >
            weighted across activities
          </div>
        </div>
      </div>

      {density === "full" && kpis.byMaterial.length > 0 && (
        <div className="rounded-lg border border-border bg-surface/40 p-4">
          <h3 className="text-sm font-semibold text-text-primary mb-2">
            Per-material breakdown — top 10 by consumption
          </h3>
          <table className="w-full text-xs">
            <thead className="text-text-muted">
              <tr>
                <th className="text-left pb-1">Material</th>
                <th className="text-right pb-1">Consumed</th>
                <th className="text-right pb-1">Wastage</th>
                <th className="text-right pb-1">Util %</th>
                <th className="text-right pb-1">Avg {symbol}/unit</th>
              </tr>
            </thead>
            <tbody>
              {kpis.byMaterial.slice(0, 10).map((row) => (
                <tr key={row.materialName} className="text-text-primary">
                  <td className="py-1 truncate max-w-[200px]">{row.materialName}</td>
                  <td className="py-1 text-right">{formatNumber(row.consumedQty, 2)}</td>
                  <td className="py-1 text-right">{formatNumber(row.wastageQty, 2)}</td>
                  <td className="py-1 text-right">{formatPct(row.utilizationPct)}</td>
                  <td className="py-1 text-right">
                    {row.avgUnitRate > 0 ? money(row.avgUnitRate) : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {density === "full" && kpis.costPerUnitByActivity && kpis.costPerUnitByActivity.length > 0 && (
        <div className="rounded-lg border border-border bg-surface/40 p-4">
          <h3 className="text-sm font-semibold text-text-primary mb-2">
            Cost / Unit Finished — top 10 by spend (KPI 9.5)
          </h3>
          <table className="w-full text-xs">
            <thead className="text-text-muted">
              <tr>
                <th className="text-left pb-1">Activity</th>
                <th className="text-right pb-1">Material {symbol}</th>
                <th className="text-right pb-1">Qty</th>
                <th className="text-right pb-1">{symbol} / unit</th>
                <th className="text-right pb-1">BOQ rate</th>
                <th className="text-right pb-1">Δ vs BOQ</th>
              </tr>
            </thead>
            <tbody>
              {kpis.costPerUnitByActivity.slice(0, 10).map((row, i) => (
                <tr key={row.activityId ?? `mat-unmapped-${i}`} className="text-text-primary">
                  <td className="py-1 truncate max-w-[200px]">{row.activityName}</td>
                  <td className="py-1 text-right">{money(row.materialCost, { decimals: 0 })}</td>
                  <td className="py-1 text-right">{formatNumber(row.qtyFinished, 2)}</td>
                  <td className="py-1 text-right">{money(row.costPerUnit)}</td>
                  <td className="py-1 text-right">
                    {row.boqBudgetedRate != null ? money(row.boqBudgetedRate) : "—"}
                  </td>
                  <td
                    className={`py-1 text-right ${
                      row.varianceVsBoqPct == null
                        ? ""
                        : row.varianceVsBoqPct < -0.1
                          ? "text-danger"
                          : row.varianceVsBoqPct > 0.1
                            ? "text-success"
                            : ""
                    }`}
                  >
                    {row.varianceVsBoqPct != null ? formatPct(row.varianceVsBoqPct) : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {kpis.materialPriceVariance == null && (
        <div className="rounded-md border border-border bg-surface/30 p-3 text-[11px] text-text-muted">
          Material Price / Usage Variance (KPIs 9.1 / 9.2) require a BOQ ↔ material map (planned
          rate &amp; planned qty per material). Not yet captured in the schema — surfaced once
          {" "}
          <code>material_batch_designs</code> lands in Phase 2.
        </div>
      )}
    </section>
  );
}
