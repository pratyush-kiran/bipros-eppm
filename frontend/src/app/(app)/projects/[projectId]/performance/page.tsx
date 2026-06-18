"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { periodPerformanceApi, type PeriodPerformanceRollup } from "@/lib/api/periodPerformanceApi";
import { KpiTile } from "@/components/common/KpiTile";
import { CadenceToggle, type Cadence } from "@/components/financials/CadenceToggle";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

function fmtRatio(n: number | null | undefined): string {
  if (n === null || n === undefined) return "—";
  return n.toFixed(3);
}

function tone(value: number | null | undefined): "default" | "success" | "warning" | "danger" {
  if (value === null || value === undefined) return "default";
  if (value >= 0.95) return "success";
  if (value >= 0.85) return "warning";
  return "danger";
}

export default function PerformanceDashboardPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const { money } = useProjectCurrency();
  const [cadence, setCadence] = useState<Cadence>("M");

  const { data, isLoading, error } = useQuery({
    queryKey: ["performance-rollup", projectId, cadence],
    queryFn: () => periodPerformanceApi.getPerformanceRollup(projectId, cadence),
    enabled: !!projectId,
  });

  const rows: PeriodPerformanceRollup[] = useMemo(() => data?.data ?? [], [data]);

  const totals = useMemo(() => {
    const sum = rows.reduce(
      (acc, r) => ({
        ac: acc.ac + (r.actualCost ?? 0),
        ev: acc.ev + (r.earnedValue ?? 0),
        pv: acc.pv + (r.plannedValue ?? 0),
      }),
      { ac: 0, ev: 0, pv: 0 },
    );
    const cpi = sum.ac > 0 ? sum.ev / sum.ac : null;
    const spi = sum.pv > 0 ? sum.ev / sum.pv : null;
    return { ...sum, cpi, spi };
  }, [rows]);

  const chartData = rows.map((r) => ({
    name: r.periodName,
    PV: r.plannedValue,
    EV: r.earnedValue,
    AC: r.actualCost,
    CV: r.cv,
  }));

  return (
    <div className="p-6">
      <TabTip
        title="Performance (Daily / Weekly / Monthly)"
        description="Rolls up Store Period Performance entries by the period type stored on each Financial Period. Switch the cadence to see daily, weekly, or monthly buckets."
      />

      <div className="mb-6 flex items-center justify-between">
        <h1 className="font-display text-3xl font-semibold text-charcoal">Performance</h1>
        <CadenceToggle value={cadence} onChange={setCadence} />
      </div>

      {error && (
        <div className="mb-4 text-danger">{getErrorMessage(error, "Failed to load performance rollup")}</div>
      )}

      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-5">
        <KpiTile label="Actual Cost" value={money(totals.ac, { decimals: 0 })} hint="AC across selected cadence" />
        <KpiTile label="Earned Value" value={money(totals.ev, { decimals: 0 })} hint="EV across selected cadence" />
        <KpiTile label="Planned Value" value={money(totals.pv, { decimals: 0 })} hint="PV across selected cadence" />
        <KpiTile
          label="CPI"
          value={fmtRatio(totals.cpi)}
          hint="EV / AC — >1 means under budget"
          tone={tone(totals.cpi)}
        />
        <KpiTile
          label="SPI"
          value={fmtRatio(totals.spi)}
          hint="EV / PV — >1 means ahead of schedule"
          tone={tone(totals.spi)}
        />
      </div>

      <div className="mb-6 rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-widest text-slate">PV vs EV vs AC by period</h2>
        {isLoading ? (
          <div className="py-12 text-center text-slate">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="py-12 text-center text-slate">
            No period performance data for cadence{" "}
            <span className="font-semibold">{cadence}</span>. Add Financial Periods with this period type and Store Period Performance entries.
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={320}>
            <ComposedChart data={chartData} margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
              <XAxis dataKey="name" stroke="#64748b" style={{ fontSize: 11 }} />
              <YAxis stroke="#64748b" style={{ fontSize: 12 }} />
              <Tooltip contentStyle={{ background: "#fff", border: "1px solid #e5e7eb", borderRadius: 6, fontSize: 12 }} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Bar dataKey="PV" fill="#94a3b8" radius={[4, 4, 0, 0]} />
              <Bar dataKey="EV" fill="#16a34a" radius={[4, 4, 0, 0]} />
              <Bar dataKey="AC" fill="#dc2626" radius={[4, 4, 0, 0]} />
              <Line type="monotone" dataKey="CV" stroke="#1e3a8a" strokeWidth={2.5} dot={{ r: 3 }} />
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </div>

      <div className="rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-widest text-slate">Per-period detail</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase tracking-wide text-slate">
              <tr>
                <th className="py-2 pr-3">Period</th>
                <th className="py-2 pr-3">Start</th>
                <th className="py-2 pr-3">End</th>
                <th className="py-2 pr-3 text-right">PV</th>
                <th className="py-2 pr-3 text-right">EV</th>
                <th className="py-2 pr-3 text-right">AC</th>
                <th className="py-2 pr-3 text-right">CV</th>
                <th className="py-2 pr-3 text-right">SV</th>
                <th className="py-2 pr-3 text-right">CPI</th>
                <th className="py-2 pr-3 text-right">SPI</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.periodId} className="border-t border-hairline">
                  <td className="py-2 pr-3 font-medium">{r.periodName}</td>
                  <td className="py-2 pr-3">{r.startDate}</td>
                  <td className="py-2 pr-3">{r.endDate}</td>
                  <td className="py-2 pr-3 text-right">{money(r.plannedValue, { decimals: 0 })}</td>
                  <td className="py-2 pr-3 text-right">{money(r.earnedValue, { decimals: 0 })}</td>
                  <td className="py-2 pr-3 text-right">{money(r.actualCost, { decimals: 0 })}</td>
                  <td className={`py-2 pr-3 text-right ${r.cv < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {money(r.cv, { decimals: 0 })}
                  </td>
                  <td className={`py-2 pr-3 text-right ${r.sv < 0 ? "text-burgundy" : "text-emerald"}`}>
                    {money(r.sv, { decimals: 0 })}
                  </td>
                  <td className="py-2 pr-3 text-right">{fmtRatio(r.cpi)}</td>
                  <td className="py-2 pr-3 text-right">{fmtRatio(r.spi)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
