"use client";

import type {
  EquipmentRollup,
  SupervisorPerformanceComparison,
  TradeRollup,
} from "@/lib/api/capacityUtilizationApi";

function fmt(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: digits });
}

function utilBand(util: number | null | undefined): string {
  if (util === null || util === undefined)
    return "bg-surface/30 text-text-muted";
  if (util >= 100) return "bg-success/15 text-success ring-1 ring-success/30";
  if (util >= 80) return "bg-warning/15 text-warning ring-1 ring-warning/30";
  return "bg-danger/15 text-danger ring-1 ring-danger/30";
}

function UtilCell({ util }: { util: number | null | undefined }) {
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded text-xs font-semibold ${utilBand(util)}`}
    >
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
          render={(rollup) =>
            rollup ? (
              <div className="space-y-0.5 text-xs">
                <div>
                  Qty:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.qtyDone)}
                  </span>
                </div>
                <div>
                  Bud:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.budgetedManDays)}
                  </span>
                </div>
                <div>
                  Act:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.actualManDays)}
                  </span>
                </div>
                <div>
                  Cost:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.costImplication)}
                  </span>
                </div>
                <div>
                  <UtilCell util={rollup.utilizationPct} />
                </div>
              </div>
            ) : (
              <span className="text-text-muted">—</span>
            )
          }
        />
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
          render={(rollup) =>
            rollup ? (
              <div className="space-y-0.5 text-xs">
                <div>
                  Qty:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.qtyDone)}
                  </span>
                </div>
                <div>
                  Bud:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.budgetedDays)}
                  </span>
                </div>
                <div>
                  Act:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.actualDays)}
                  </span>
                </div>
                <div>
                  Cost:{" "}
                  <span className="tabular-nums">
                    {fmt(rollup.costImplication)}
                  </span>
                </div>
                <div>
                  <UtilCell util={rollup.utilizationPct} />
                </div>
              </div>
            ) : (
              <span className="text-text-muted">—</span>
            )
          }
        />
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
