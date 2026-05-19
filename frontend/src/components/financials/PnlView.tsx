"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { marginApi, type MarginScope } from "@/lib/api/marginApi";
import { KpiTile } from "@/components/common/KpiTile";
import { CadenceToggle, type Cadence } from "@/components/financials/CadenceToggle";
import { MarginTrendChart } from "@/components/financials/MarginTrendChart";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";

function fmtCurrency(n: number | null | undefined): string {
  if (n === null || n === undefined) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: 0 });
}

function fmtPct(n: number | null | undefined): string {
  if (n === null || n === undefined) return "—";
  return (n * 100).toFixed(2) + "%";
}

function marginTone(pct: number | null | undefined): "default" | "success" | "warning" | "danger" {
  if (pct === null || pct === undefined) return "default";
  if (pct >= 0.10) return "success";
  if (pct >= 0) return "warning";
  return "danger";
}

export function PnlView({
  projectId,
  scope,
  title,
  description,
  revenueLabel,
}: {
  projectId: string;
  scope: MarginScope;
  title: string;
  description: string;
  revenueLabel: string;
}) {
  const [cadence, setCadence] = useState<Cadence>("M");

  const summary = useQuery({
    queryKey: ["pnl-summary", projectId, scope],
    queryFn: () => marginApi.summary(projectId, scope),
    enabled: !!projectId,
  });
  const periods = useQuery({
    queryKey: ["pnl-periods", projectId, scope, cadence],
    queryFn: () => marginApi.periods(projectId, scope, cadence),
    enabled: !!projectId,
  });
  const items = useQuery({
    queryKey: ["pnl-items", projectId, scope],
    queryFn: () => marginApi.items(projectId, scope),
    enabled: !!projectId,
  });
  const activities = useQuery({
    queryKey: ["pnl-activities", projectId, scope],
    queryFn: () => marginApi.activities(projectId, scope),
    enabled: !!projectId,
  });

  const summaryRow = summary.data?.data;
  const periodRows = useMemo(() => periods.data?.data ?? [], [periods.data]);
  const itemRows = useMemo(() => items.data?.data ?? [], [items.data]);
  const activityRows = useMemo(() => activities.data?.data ?? [], [activities.data]);

  const error =
    summary.error ?? periods.error ?? items.error ?? activities.error;

  return (
    <div className="p-6">
      <TabTip title={title} description={description} />

      <div className="mb-6 flex items-center justify-between">
        <h1 className="font-display text-3xl font-semibold text-charcoal">{title}</h1>
        <CadenceToggle value={cadence} onChange={setCadence} />
      </div>

      {error && (
        <div className="mb-4 text-danger">{getErrorMessage(error, "Failed to load P&L data")}</div>
      )}

      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-4">
        <KpiTile label={revenueLabel} value={fmtCurrency(summaryRow?.revenue)} hint="Σ rate × qty" />
        <KpiTile label="Actual Cost" value={fmtCurrency(summaryRow?.actualCost)} hint="Σ cost from DPR / DBS" />
        <KpiTile
          label="Margin"
          value={fmtCurrency(summaryRow?.margin)}
          hint="Revenue − Cost"
          tone={marginTone(summaryRow?.marginPct)}
        />
        <KpiTile
          label="Margin %"
          value={fmtPct(summaryRow?.marginPct)}
          hint="Margin / Revenue"
          tone={marginTone(summaryRow?.marginPct)}
        />
      </div>

      <div className="mb-6 rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-widest text-slate">
          {revenueLabel} vs Cost — by period
        </h2>
        {periods.isLoading ? (
          <div className="py-12 text-center text-slate">Loading…</div>
        ) : periodRows.length === 0 ? (
          <div className="py-12 text-center text-slate">
            No periods of cadence <span className="font-semibold">{cadence}</span> for this project.
          </div>
        ) : (
          <MarginTrendChart
            data={periodRows.map((p) => ({
              periodName: p.periodName,
              revenue: p.revenue,
              actualCost: p.actualCost,
              margin: p.margin,
            }))}
            revenueLabel={revenueLabel}
          />
        )}
      </div>

      <div className="mb-6 rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-widest text-slate">Per-period detail</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wide text-slate">
              <tr>
                <th className="py-2 pr-3">Period</th>
                <th className="py-2 pr-3">Start</th>
                <th className="py-2 pr-3">End</th>
                <th className="py-2 pr-3 text-right">{revenueLabel}</th>
                <th className="py-2 pr-3 text-right">Actual Cost</th>
                <th className="py-2 pr-3 text-right">Margin</th>
                <th className="py-2 pr-3 text-right">Margin %</th>
              </tr>
            </thead>
            <tbody>
              {periodRows.map((p) => (
                <tr key={p.periodId} className="border-t border-hairline">
                  <td className="py-2 pr-3 font-medium">{p.periodName}</td>
                  <td className="py-2 pr-3">{p.startDate}</td>
                  <td className="py-2 pr-3">{p.endDate}</td>
                  <td className="py-2 pr-3 text-right">{fmtCurrency(p.revenue)}</td>
                  <td className="py-2 pr-3 text-right">{fmtCurrency(p.actualCost)}</td>
                  <td className={`py-2 pr-3 text-right ${p.margin < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {fmtCurrency(p.margin)}
                  </td>
                  <td className={`py-2 pr-3 text-right ${(p.marginPct ?? 0) < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {fmtPct(p.marginPct)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="mb-6 rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-widest text-slate">By activity</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wide text-slate">
              <tr>
                <th className="py-2 pr-3">Activity</th>
                <th className="py-2 pr-3 text-right">{revenueLabel}</th>
                <th className="py-2 pr-3 text-right">Actual Cost</th>
                <th className="py-2 pr-3 text-right">Margin</th>
                <th className="py-2 pr-3 text-right">Margin %</th>
              </tr>
            </thead>
            <tbody>
              {activityRows.map((a, idx) => (
                <tr key={`${a.activity}-${idx}`} className="border-t border-hairline">
                  <td className="py-2 pr-3 font-medium">{a.activity}</td>
                  <td className="py-2 pr-3 text-right">{fmtCurrency(a.revenue)}</td>
                  <td className="py-2 pr-3 text-right">{fmtCurrency(a.actualCost)}</td>
                  <td className={`py-2 pr-3 text-right ${a.margin < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {fmtCurrency(a.margin)}
                  </td>
                  <td className={`py-2 pr-3 text-right ${(a.marginPct ?? 0) < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {fmtPct(a.marginPct)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-widest text-slate">By BOQ item</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wide text-slate">
              <tr>
                <th className="py-2 pr-3">Item No</th>
                <th className="py-2 pr-3">Description</th>
                <th className="py-2 pr-3 text-right">Qty Executed</th>
                <th className="py-2 pr-3">Unit</th>
                <th className="py-2 pr-3 text-right">Rate</th>
                <th className="py-2 pr-3 text-right">{revenueLabel}</th>
                <th className="py-2 pr-3 text-right">Actual Cost</th>
                <th className="py-2 pr-3 text-right">Margin</th>
                <th className="py-2 pr-3 text-right">Margin %</th>
              </tr>
            </thead>
            <tbody>
              {itemRows.map((i) => (
                <tr key={i.boqItemId} className="border-t border-hairline">
                  <td className="py-2 pr-3 font-mono">{i.itemNo}</td>
                  <td className="py-2 pr-3">{i.description}</td>
                  <td className="py-2 pr-3 text-right">
                    {i.qtyExecuted?.toLocaleString("en-IN") ?? "—"}
                  </td>
                  <td className="py-2 pr-3">{i.unit ?? "—"}</td>
                  <td className="py-2 pr-3 text-right">{fmtCurrency(i.rate)}</td>
                  <td className="py-2 pr-3 text-right">{fmtCurrency(i.revenue)}</td>
                  <td className="py-2 pr-3 text-right">{fmtCurrency(i.actualCost)}</td>
                  <td className={`py-2 pr-3 text-right ${i.margin < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {fmtCurrency(i.margin)}
                  </td>
                  <td className={`py-2 pr-3 text-right ${(i.marginPct ?? 0) < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {fmtPct(i.marginPct)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
