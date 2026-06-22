"use client";

import type {
  EquipmentRollup,
  HiddenSideNote,
  SupervisorPerformanceComparison,
  TradeRollup,
} from "@/lib/api/capacityUtilizationApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import {
  COUNTED_TOOLTIP,
  countedDays,
  efficiencyFormula,
  hiddenSideSentence,
  reconciliationText,
} from "@/lib/capacity/reconciliation";

function fmt(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: digits });
}

function CostLine({ value }: { value: number | null | undefined }) {
  const { money } = useProjectCurrency();
  if (value === null || value === undefined || value === 0) {
    return <span className="text-text-muted">—</span>;
  }
  if (value < 0) {
    return <span className="text-success">Cost saved: {money(Math.abs(value), { decimals: 0 })}</span>;
  }
  return <span className="text-danger">Cost overrun: {money(value, { decimals: 0 })}</span>;
}

function utilBand(util: number | null | undefined): string {
  if (util === null || util === undefined)
    return "bg-surface/30 text-text-muted";
  if (util >= 100) return "bg-success/15 text-success ring-1 ring-success/30";
  if (util >= 80) return "bg-warning/15 text-warning ring-1 ring-warning/30";
  return "bg-danger/15 text-danger ring-1 ring-danger/30";
}

const EFFICIENCY_TOOLTIP =
  "Output vs the productivity norm per resource-day. Not deployment utilization.";

/** Aggregate hidden-side notes across supervisors, deduped by activityId. Used to render a
 *  single combined banner under each side's table — covers any supervisor who saw the
 *  activity governed by the other side. */
function aggregateHiddenNotes(
  comparison: SupervisorPerformanceComparison,
  side: "manpower" | "equipment",
): HiddenSideNote[] {
  const seen = new Set<string>();
  const out: HiddenSideNote[] = [];
  for (const rep of comparison.reports) {
    const notes =
      side === "manpower"
        ? rep.summary.manpowerHiddenNotes
        : rep.summary.equipmentHiddenNotes;
    if (!notes) continue;
    for (const n of notes) {
      if (seen.has(n.activityId)) continue;
      seen.add(n.activityId);
      out.push(n);
    }
  }
  return out;
}

function HiddenSideBanner({ notes }: { notes: HiddenSideNote[] }) {
  if (notes.length === 0) return null;
  return (
    <div className="mt-3 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning space-y-1">
      {notes.map((n) => (
        <div key={n.activityId}>
          <span className="font-medium">
            {n.workActivityName ?? "Activity"}
          </span>
          {hiddenSideSentence(n.mode, n.governingSide)}
        </div>
      ))}
    </div>
  );
}

function UtilCell({ util }: { util: number | null | undefined }) {
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded text-xs font-semibold cursor-help ${utilBand(util)}`}
      title={EFFICIENCY_TOOLTIP}
    >
      Eff:{" "}
      {util === null || util === undefined ? "—" : `${fmt(util, 1)} %`}
    </span>
  );
}

interface ComparisonProps {
  comparison: SupervisorPerformanceComparison;
}

export function SupervisorComparisonSections({ comparison }: ComparisonProps) {
  const supervisors = comparison.reports.map((r) => ({
    id: r.supervisorUserId ?? "_project_",
    label: r.supervisorName ?? "—",
  }));

  const manpowerHidden = aggregateHiddenNotes(comparison, "manpower");
  const equipmentHidden = aggregateHiddenNotes(comparison, "equipment");

  return (
    <div className="mt-8 space-y-8">
      <section>
        <h2 className="text-lg font-bold text-text-primary mb-3">
          Manpower Utilization — comparison
        </h2>
        <ComparisonTable
          supervisors={supervisors}
          deltas={comparison.tradeDeltas.map((d) => ({
            key: d.tradeKey,
            label: d.tradeLabel,
            best: d.bestSupervisorId,
            byKey: d.bySupervisor as Record<string, TradeRollup>,
          }))}
          render={(rollup) => {
            if (!rollup) return <span className="text-text-muted">—</span>;
            const counted = countedDays(
              rollup.actualManDays,
              rollup.actualDaysOnHiddenSides,
              rollup.actualDaysUntracked,
            );
            const identity = reconciliationText(
              rollup.actualManDays,
              rollup.actualDaysOnHiddenSides,
              rollup.actualDaysUntracked,
              "MANPOWER",
            );
            const formula = efficiencyFormula(
              rollup.budgetedManDays,
              counted,
              2,
              2,
            );
            return (
              <div className="space-y-0.5 text-xs">
                <div>
                  Qty:{" "}
                  <span className="tabular-nums">{fmt(rollup.qtyDone)}</span>
                </div>
                <div>
                  Bud:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.budgetedManDays)}
                  </span>
                </div>
                <div>
                  Counted:{" "}
                  <span className="tabular-nums">{fmt(counted)}</span>
                </div>
                {identity && (
                  <div
                    className="text-[11px] text-text-muted italic"
                    title={COUNTED_TOOLTIP}
                  >
                    {identity}
                  </div>
                )}
                {formula && (
                  <div className="text-[11px] text-text-muted">Eff = {formula}</div>
                )}
                <div>
                  <CostLine value={rollup.costImplication} />
                </div>
                <div>
                  <UtilCell util={rollup.utilizationPct} />
                </div>
              </div>
            );
          }}
        />
        <HiddenSideBanner notes={manpowerHidden} />
      </section>

      <section>
        <h2 className="text-lg font-bold text-text-primary mb-3">
          Equipment Utilization — comparison
        </h2>
        <ComparisonTable
          supervisors={supervisors}
          deltas={comparison.equipmentDeltas.map((d) => ({
            key: d.equipmentKey,
            label: d.equipmentLabel,
            best: d.bestSupervisorId,
            byKey: d.bySupervisor as Record<string, EquipmentRollup>,
          }))}
          render={(rollup) => {
            if (!rollup) return <span className="text-text-muted">—</span>;
            const counted = countedDays(
              rollup.actualDays,
              rollup.actualDaysOnHiddenSides,
              rollup.actualDaysUntracked,
            );
            const identity = reconciliationText(
              rollup.actualDays,
              rollup.actualDaysOnHiddenSides,
              rollup.actualDaysUntracked,
              "EQUIPMENT",
            );
            const formula = efficiencyFormula(
              rollup.budgetedDays,
              counted,
              2,
              2,
            );
            return (
              <div className="space-y-0.5 text-xs">
                <div>
                  Qty:{" "}
                  <span className="tabular-nums">{fmt(rollup.qtyDone)}</span>
                </div>
                <div>
                  Bud:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.budgetedDays)}
                  </span>
                </div>
                <div>
                  Counted:{" "}
                  <span className="tabular-nums">{fmt(counted)}</span>
                </div>
                {identity && (
                  <div
                    className="text-[11px] text-text-muted italic"
                    title={COUNTED_TOOLTIP}
                  >
                    {identity}
                  </div>
                )}
                {formula && (
                  <div className="text-[11px] text-text-muted">Eff = {formula}</div>
                )}
                <div>
                  <CostLine value={rollup.costImplication} />
                </div>
                <div>
                  <UtilCell util={rollup.utilizationPct} />
                </div>
              </div>
            );
          }}
        />
        <HiddenSideBanner notes={equipmentHidden} />
      </section>
    </div>
  );
}

interface DeltaRow<T> {
  key: string;
  label: string;
  best: string | null;
  byKey: Record<string, T>;
}

function ComparisonTable<T>({
  supervisors,
  deltas,
  render,
}: {
  supervisors: Array<{ id: string; label: string }>;
  deltas: DeltaRow<T>[];
  render: (rollup: T | null) => React.ReactNode;
}) {
  if (deltas.length === 0) {
    return (
      <div className="text-text-muted text-sm py-6 text-center border border-border rounded-lg">
        No data in this window.
      </div>
    );
  }
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="min-w-full text-sm">
        <thead className="bg-surface/50 text-text-secondary text-xs uppercase tracking-wide">
          <tr>
            <th className="px-3 py-2 text-left">Trade / Equipment</th>
            {supervisors.map((s) => (
              <th key={s.id} className="px-3 py-2 text-left">
                {s.label}
              </th>
            ))}
            <th className="px-3 py-2 text-left">Best</th>
          </tr>
        </thead>
        <tbody>
          {deltas.map((d) => (
            <tr key={d.key} className="border-t border-border align-top">
              <td className="px-3 py-2 font-medium text-text-primary">
                {d.label}
              </td>
              {supervisors.map((s) => {
                const isBest = d.best === s.id;
                return (
                  <td
                    key={s.id}
                    className={`px-3 py-2 ${isBest ? "ring-1 ring-success/40 bg-success/5 rounded" : ""}`}
                  >
                    {render(d.byKey[s.id] ?? null)}
                  </td>
                );
              })}
              <td className="px-3 py-2">
                {d.best ? (
                  <span className="text-xs font-semibold text-success">
                    {supervisors.find((s) => s.id === d.best)?.label ?? "—"}
                  </span>
                ) : (
                  <span className="text-text-muted">—</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
