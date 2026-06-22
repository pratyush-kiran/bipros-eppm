"use client";

import { memo } from "react";
import type { RolePeriod } from "@/lib/api/capacityUtilizationApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import {
  COUNTED_TOOLTIP,
  countedDays,
  efficiencyFormula,
  reconciliationText,
  type CapacitySide,
} from "@/lib/capacity/reconciliation";

function fmt(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: digits });
}

export function utilBand(util: number | null | undefined): string {
  if (util === null || util === undefined)
    return "bg-surface/30 text-text-muted";
  if (util >= 100) return "bg-success/15 text-success ring-1 ring-success/30";
  if (util >= 80) return "bg-warning/15 text-warning ring-1 ring-warning/30";
  return "bg-danger/15 text-danger ring-1 ring-danger/30";
}

const EFFICIENCY_TOOLTIP =
  "Output vs the productivity norm per resource-day. Not deployment utilization.";

/**
 * SC180-style cell for one bucket of a role row. Shared between the project-level capacity
 * utilization view and the multi-period aggregate view. Renders Qty done / Budget / Counted /
 * Efficiency % / Cost in a tight stack.
 *
 * "Counted" (not the total deployed) is the headline because it is the exact number Efficiency %
 * and Cost divide by — so Budget ÷ Counted = Efficiency reproduces by eye. A self-reconciling
 * line ("118 counted + 73 measured under Equipment = 191 deployed") keeps every number on screen
 * and visibly tallies. "Planned" stays hidden — the activity-plan headcount commitment seeded
 * too much "why didn't we use all 100?" confusion when shown next to a single-day Actual.
 */
export const PeriodCell = memo(function PeriodCell({
  period,
  side,
}: {
  period: RolePeriod | null;
  side?: CapacitySide;
}) {
  const { money } = useProjectCurrency();
  if (!period) {
    return <span className="text-xs text-text-muted">—</span>;
  }
  const untracked = period.normResolved === false;
  const counted = countedDays(
    period.actualDays,
    period.actualDaysOnHiddenSides,
    period.actualDaysUntracked,
  );
  const identity = reconciliationText(
    period.actualDays,
    period.actualDaysOnHiddenSides,
    period.actualDaysUntracked,
    side,
  );
  const formula = untracked
    ? null
    : efficiencyFormula(period.budgetDays, counted, 1, 1);
  return (
    <div className="space-y-0.5 text-xs">
      {period.qty != null && period.qty > 0 && (
        <div>
          <span className="text-text-muted">Qty done:</span> {fmt(period.qty, 2)}
        </div>
      )}
      <div>
        <span className="text-text-muted">Budget:</span>{" "}
        {untracked ? "—" : fmt(period.budgetDays, 1)}
      </div>
      {untracked ? (
        <div>
          <span className="text-text-muted">Actual:</span>{" "}
          {fmt(period.actualDays, 1)}
        </div>
      ) : (
        <>
          <div>
            <span className="text-text-muted">Counted:</span> {fmt(counted, 1)}
          </div>
          {identity && (
            <div className="text-text-muted italic" title={COUNTED_TOOLTIP}>
              {identity}
            </div>
          )}
        </>
      )}
      <div className="flex items-center gap-2 flex-wrap">
        <span
          className={`inline-block px-2 py-0.5 rounded text-xs font-semibold cursor-help ${utilBand(untracked ? null : period.utilizationPct)}`}
          title={EFFICIENCY_TOOLTIP}
        >
          Eff:{" "}
          {untracked || period.utilizationPct == null
            ? "—"
            : `${fmt(period.utilizationPct, 1)} %`}
        </span>
        {!untracked && period.costImplication != null && period.costImplication !== 0 && (
          <span
            className={`text-xs ${period.costImplication < 0 ? "text-success" : "text-danger"}`}
          >
            {period.costImplication < 0
              ? `Cost saved: ${money(Math.abs(period.costImplication), { decimals: 0 })}`
              : `Cost overrun: ${money(period.costImplication, { decimals: 0 })}`}
          </span>
        )}
      </div>
      {formula && (
        <div className="text-[10px] text-text-muted">Eff = {formula}</div>
      )}
      {untracked && (
        <div className="mt-1 text-text-muted italic">
          No productivity norm for this role on this activity.
        </div>
      )}
    </div>
  );
});
