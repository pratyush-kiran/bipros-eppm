"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { manpowerKpiApi } from "@/lib/api/manpowerKpiApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { formatMoney } from "@/lib/hooks/useCurrency";

interface Props {
  projectId: string;
  /**
   * Period in ISO yyyy-MM-dd format. If omitted, defaults to last 30 days ending today.
   */
  from?: string;
  to?: string;
  /**
   * Visual density. "compact" hides per-activity tables and shows only the headline cards.
   */
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

/** Currency-aware money formatter. Currency is injected from the component's projectCurrency state. */
function makeFormatMoney(currency: string) {
  return (value: number | null | undefined, fractionDigits = 0): string =>
    formatMoney(value, currency, fractionDigits);
}

export function ManpowerKpiSection({ projectId, from, to, density = "compact" }: Props) {
  const range = useMemo(() => {
    if (from && to) return { from, to };
    return defaultRange();
  }, [from, to]);

  const { data, isLoading, error } = useQuery({
    queryKey: ["manpower-kpis", projectId, range.from, range.to],
    queryFn: () => manpowerKpiApi.getKpis(projectId, range.from, range.to),
    enabled: !!projectId,
  });

  const { data: budgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    enabled: !!projectId,
    staleTime: 5 * 60 * 1000,
  });
  const projectCurrency = budgetData?.data?.budgetCurrency ?? "INR";
  const formatRupees = makeFormatMoney(projectCurrency);

  if (!projectId) return null;
  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-text-muted">
        Loading manpower KPIs…
      </div>
    );
  }
  if (error || !data?.data) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-danger">
        Failed to load manpower KPIs.
      </div>
    );
  }
  const kpis = data.data;
  const wu = kpis.workforceUtilization;
  const dq = kpis.dataQuality;

  const totalLabourCost = kpis.labourCostSummary?.actualLabourCost ?? 0;
  const productivityRows = kpis.productivityFactor.filter(
    (p) => p.normOutputPerManPerDay > 0 && !p.unitMismatch,
  );
  const avgProductivityFactor =
    kpis.headlineProductivityFactor > 0 ? kpis.headlineProductivityFactor : null;
  const underPerformingCount = productivityRows.filter((p) => p.factor < 0.8).length;

  const worstProductivity = kpis.productivityFactor.slice(0, 5);
  const topCpu = kpis.labourCostPerUnit.slice(0, 5);
  const worstCrews = kpis.crewOutput.slice(0, 5);
  const worstAchievement = kpis.outputAchievement.slice(0, 5);

  const showBanner =
    dq.missingRateResourceCount > 0
    || dq.missingAttendanceResourceCount > 0
    || dq.unitMismatchActivityCount > 0
    || dq.noNormActivityCount > 0
    || dq.noBoqBaselineActivityCount > 0;

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-lg font-semibold text-text-primary">
          Manpower KPIs <span className="text-xs font-normal text-text-muted">({range.from} → {range.to})</span>
        </h2>
        <div className="text-[11px] text-text-muted">
          {wu.activeResourceCount} of {wu.laborResourceCount} manpower active · {kpis.productivityFactor.length} activities tracked · {kpis.labourCostPerUnit.length} BOQ items costed
        </div>
      </div>

      {showBanner && (
        <div className="rounded-md border border-amber-500/40 bg-amber-50 p-3 text-xs text-amber-900">
          <div className="font-semibold mb-1">Data quality</div>
          <ul className="list-disc list-inside space-y-0.5">
            {dq.missingRateResourceCount > 0 && (
              <li>
                {dq.missingRateResourceCount} manpower resource(s) have no rate set — affects Total Manpower Cost and Cost / Unit. <a className="underline" href="/admin/labour-master">Fix in Admin → Manpower Master</a>
              </li>
            )}
            {dq.missingAttendanceResourceCount > 0 && (
              <li>
                {dq.missingAttendanceResourceCount} manpower resource(s) missing attendance master — defaulted to 8 h/day for available-hours.
              </li>
            )}
            {dq.unitMismatchActivityCount > 0 && (
              <li>
                {dq.unitMismatchActivityCount} activity row(s) have DPR/norm unit mismatch — excluded from Avg Productivity Factor.
              </li>
            )}
            {dq.noNormActivityCount > 0 && (
              <li>
                {dq.noNormActivityCount} activity row(s) have no productivity norm linked — Productivity Factor and Crew Output show "—". <a className="underline" href="/admin/productivity-norms">Set norms in Admin</a>
              </li>
            )}
            {dq.noBoqBaselineActivityCount > 0 && (
              <li>
                {dq.noBoqBaselineActivityCount} activity row(s) skipped from Output Achievement — no BOQ qty match (link the activity name to a BOQ item).
              </li>
            )}
          </ul>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Workforce Utilisation</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">
            {formatPct(wu.utilizationPct)}
            {wu.overflow && <span className="ml-1 text-xs text-warning">⚠</span>}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="Σ actual nos (DPR deployment) ÷ Σ planned headcount across manpower assignments × 100. Hours are logging-only and do not enter this metric."
          >
            {wu.actualNos} of {wu.plannedNos} nos
            {wu.overflow && ` · raw ${formatPct(wu.rawUtilizationPct)}`}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Total Manpower Cost</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">{formatRupees(totalLabourCost)}</div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="Σ DPR line_cost across all manpower rows in the window (each row = nos × rate). Hours are logging-only."
          >
            {dq.missingRateResourceCount > 0
              ? `${dq.missingRateResourceCount} resource(s) missing rate`
              : "across all manpower activities"}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Avg Productivity Factor</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              avgProductivityFactor != null && avgProductivityFactor < 0.8
                ? "text-danger"
                : avgProductivityFactor != null && avgProductivityFactor > 1.1
                  ? "text-success"
                  : "text-text-primary"
            }`}
          >
            {avgProductivityFactor != null ? formatNumber(avgProductivityFactor, 2) : "—"}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="Average of (actual ÷ ProductivityNorm.outputPerManPerDay) excluding unit-mismatched activities. 1.0 = on norm; <0.8 = under-performing."
          >
            actual ÷ norm · {productivityRows.length} activities
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Under-Performing Activities</div>
          <div
            className={`mt-1 text-2xl font-semibold ${underPerformingCount > 0 ? "text-warning" : "text-text-primary"}`}
          >
            {underPerformingCount}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="Activities with productivity factor < 0.8 (delivering <80% of norm output per man-day)."
          >
            factor &lt; 0.8 of norm
          </div>
        </div>
      </div>

      {/* Second strip: qty-based KPIs (idle/OT removed — hours are logging-only in nos × rate model) */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Cost per Unit Output</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">
            {kpis.weightedAvgCostPerUnit > 0 ? formatRupees(kpis.weightedAvgCostPerUnit, 2) : "—"}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="KPI 3.5 — Σ manpower cost ÷ Σ qty executed (weighted)">
            weighted across BOQ items
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Output Achievement</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">
            {kpis.outputAchievement.length === 0
              ? "—"
              : formatPct(
                  kpis.outputAchievement.reduce((s, r) => s + r.achievementPct, 0)
                    / kpis.outputAchievement.length,
                )}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="Average BOQ % complete across tracked activities (qtyExecuted ÷ boqQty per BOQ item).">
            {kpis.outputAchievement.length} activit{kpis.outputAchievement.length === 1 ? "y" : "ies"} tracked
          </div>
        </div>
      </div>

      {/* NH-48 Cost block — KPIs 3.1 / 3.3 / 3.4 + 2.7 (OT Cost % dropped — hours are logging-only) */}
      {kpis.labourCostSummary && (
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Planned Manpower Cost</div>
          <div className="mt-1 text-2xl font-semibold text-text-primary">
            {kpis.labourCostSummary.plannedLabourCost > 0
              ? formatRupees(kpis.labourCostSummary.plannedLabourCost)
              : "—"}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="KPI 3.1 — Σ planned_cost across MANPOWER assignments (= headcount × rate, mirrors Resource Plan)."
          >
            {kpis.labourCostSummary.activityCoverageCount} activities planned
            {kpis.labourCostSummary.missingPlanCount > 0 && ` · ${kpis.labourCostSummary.missingPlanCount} skipped`}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Manpower Cost Variance</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              kpis.labourCostSummary.plannedLabourCost === 0
                ? "text-text-primary"
                : kpis.labourCostSummary.labourCostVariance < 0
                  ? "text-danger"
                  : "text-success"
            }`}
          >
            {kpis.labourCostSummary.plannedLabourCost > 0
              ? formatRupees(kpis.labourCostSummary.labourCostVariance)
              : "—"}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="KPI 3.3 — PLC − ALC. Negative = over budget.">
            PLC − ALC
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">LCPI</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              kpis.labourCostSummary.lcpi === 0
                ? "text-text-primary"
                : kpis.labourCostSummary.lcpi < 0.95
                  ? "text-danger"
                  : kpis.labourCostSummary.lcpi >= 1.0
                    ? "text-success"
                    : "text-warning"
            }`}
          >
            {kpis.labourCostSummary.lcpi > 0 ? formatNumber(kpis.labourCostSummary.lcpi, 2) : "—"}
          </div>
          <div className="mt-1 text-xs text-text-secondary" title="KPI 3.4 — Budgeted Manpower Cost ÷ Actual Manpower Cost. ≥ 1.0 = on budget.">
            PLC ÷ ALC
          </div>
        </div>
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <div className="text-xs uppercase tracking-wide text-text-muted">Cumulative Progress</div>
          <div
            className={`mt-1 text-2xl font-semibold ${
              !kpis.cumulativeProgressPct
                ? "text-text-primary"
                : kpis.cumulativeProgressPct < 0.9
                  ? "text-danger"
                  : kpis.cumulativeProgressPct > 1.05
                    ? "text-success"
                    : "text-text-primary"
            }`}
          >
            {kpis.cumulativeProgressPct && kpis.cumulativeProgressPct > 0
              ? formatPct(kpis.cumulativeProgressPct)
              : "—"}
          </div>
          <div
            className="mt-1 text-xs text-text-secondary"
            title="KPI 2.7 — Cumulative Actual Qty ÷ Cumulative Planned Qty × 100. Planned uses linear interpolation in Phase 2A — replaced by activity baseline snapshots later."
          >
            actual ÷ planned (linear)
          </div>
        </div>
      </div>
      )}

      {density === "full" && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="rounded-lg border border-border bg-surface/40 p-4">
            <h3 className="text-sm font-semibold text-text-primary mb-2">
              Productivity Factor — bottom 5 activities
            </h3>
            <table className="w-full text-xs">
              <thead className="text-text-muted">
                <tr>
                  <th className="text-left pb-1">Activity</th>
                  <th className="text-right pb-1">Actual</th>
                  <th className="text-right pb-1">Norm</th>
                  <th className="text-right pb-1">Factor</th>
                </tr>
              </thead>
              <tbody>
                {worstProductivity.length === 0 && (
                  <tr>
                    <td colSpan={4} className="py-2 text-center text-text-muted">No data</td>
                  </tr>
                )}
                {worstProductivity.map((p, i) => (
                  <tr key={p.activityId ?? `unmapped-${i}`} className="text-text-primary">
                    <td className="py-1 truncate max-w-[200px]">
                      {p.activityName}
                      {p.unitMismatch && (
                        <span
                          className="ml-1 text-[10px] text-amber-700"
                          title={`Unit mismatch: DPR=${p.darUnit ?? "?"}, norm=${p.normUnit ?? "?"}`}
                        >
                          ⚠
                        </span>
                      )}
                    </td>
                    <td className="py-1 text-right">{formatNumber(p.actualOutputPerManPerDay, 2)}</td>
                    <td className="py-1 text-right">{formatNumber(p.normOutputPerManPerDay, 2)}</td>
                    <td className={`py-1 text-right ${p.factor < 0.8 ? "text-danger" : p.factor > 1.1 ? "text-success" : ""}`}>
                      {formatNumber(p.factor, 2)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="rounded-lg border border-border bg-surface/40 p-4">
            <h3 className="text-sm font-semibold text-text-primary mb-2">
              Manpower Cost / Unit — top 5 BOQ items
            </h3>
            <table className="w-full text-xs">
              <thead className="text-text-muted">
                <tr>
                  <th className="text-left pb-1">BOQ Item</th>
                  <th className="text-right pb-1">Qty</th>
                  <th className="text-right pb-1">{projectCurrency} / unit</th>
                </tr>
              </thead>
              <tbody>
                {topCpu.length === 0 && (
                  <tr>
                    <td colSpan={3} className="py-2 text-center text-text-muted">No data</td>
                  </tr>
                )}
                {topCpu.map((row) => (
                  <tr key={row.boqItemId} className="text-text-primary">
                    <td className="py-1 truncate max-w-[200px]">{row.itemNo}</td>
                    <td className="py-1 text-right">{formatNumber(row.qtyExecuted, 3)}</td>
                    <td className="py-1 text-right">{formatRupees(row.costPerUnit, 2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="rounded-lg border border-border bg-surface/40 p-4">
            <h3 className="text-sm font-semibold text-text-primary mb-2">
              Output Achievement — bottom 5 activities
            </h3>
            <table className="w-full text-xs">
              <thead className="text-text-muted">
                <tr>
                  <th className="text-left pb-1">Activity</th>
                  <th className="text-right pb-1">Qty executed</th>
                  <th className="text-right pb-1">BOQ qty</th>
                  <th className="text-right pb-1">%</th>
                </tr>
              </thead>
              <tbody>
                {worstAchievement.length === 0 && (
                  <tr>
                    <td colSpan={4} className="py-2 text-center text-text-muted">
                      No BOQ linkage on DPRs in this window.
                    </td>
                  </tr>
                )}
                {worstAchievement.map((row, i) => (
                  <tr key={row.activityId ?? `unmapped-${i}`} className="text-text-primary">
                    <td className="py-1 truncate max-w-[160px]">{row.activityName}</td>
                    <td className="py-1 text-right">{formatNumber(row.actualDailyOutput, 2)}</td>
                    <td className="py-1 text-right">{formatNumber(row.plannedDailyOutput, 2)}</td>
                    <td
                      className={`py-1 text-right ${row.achievementPct < 0.8 ? "text-danger" : row.achievementPct > 1.1 ? "text-success" : ""}`}
                    >
                      {formatPct(row.achievementPct)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="rounded-lg border border-border bg-surface/40 p-4">
            <h3 className="text-sm font-semibold text-text-primary mb-2">
              Crew Output vs Norm — bottom 5 by deviation
            </h3>
            <table className="w-full text-xs">
              <thead className="text-text-muted">
                <tr>
                  <th className="text-left pb-1">Activity</th>
                  <th className="text-right pb-1">Crew</th>
                  <th className="text-right pb-1">Actual / day</th>
                  <th className="text-right pb-1">Δ</th>
                </tr>
              </thead>
              <tbody>
                {worstCrews.length === 0 && (
                  <tr>
                    <td colSpan={4} className="py-2 text-center text-text-muted">No data</td>
                  </tr>
                )}
                {worstCrews.map((c, i) => (
                  <tr key={c.activityId ?? `unmapped-${i}`} className="text-text-primary">
                    <td className="py-1 truncate max-w-[160px]">{c.activityName}</td>
                    <td className="py-1 text-right">{c.crewSize ?? "—"}</td>
                    <td className="py-1 text-right">{formatNumber(c.actualOutputPerDay, 2)}</td>
                    <td
                      className={`py-1 text-right ${c.deviationPct < -0.1 ? "text-danger" : c.deviationPct > 0.1 ? "text-success" : ""}`}
                    >
                      {formatPct(c.deviationPct)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  );
}
