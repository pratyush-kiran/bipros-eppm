"use client";

import { memo } from "react";
import type { RolePeriod } from "@/lib/api/capacityUtilizationApi";

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

/**
 * Hover-tooltip text on the Util % badge. Explains the norm-tracked-role rule so users
 * don't perceive the governing role's util % as unfair credit — supporting roles without
 * norms contribute to cost but aren't measured against output, by design.
 */
const UTIL_TOOLTIP =
  "Util % measures how efficiently this role was used against the activity's total output. " +
  "Output ÷ the role's productivity norm gives the Budget days; comparing against actual gives this %. " +
  "Supporting roles without norms (helpers, finishers, etc.) contribute to cost but aren't measured " +
  "against output — they only show Qty done and Actual, no Util %.";

function sideLabel(side: "MANPOWER" | "EQUIPMENT" | null | undefined): string {
  if (side === "MANPOWER") return "Manpower";
  if (side === "EQUIPMENT") return "Equipment";
  return "the other";
}

/**
 * SC180-style cell for one bucket of a role row. Shared between the project-level capacity
 * utilization view and the multi-period aggregate view. Renders Budget / Planned / Actual /
 * Untracked / %Util / Cost in a tight stack.
 */
export const PeriodCell = memo(function PeriodCell({
  period,
}: {
  period: RolePeriod | null;
}) {
  if (!period) {
    return <span className="text-xs text-text-muted">—</span>;
  }
  return (
    <div className="space-y-0.5 text-xs">
      {period.qty != null && period.qty > 0 && (
        <div>
          <span className="text-text-muted">Qty done:</span> {fmt(period.qty, 2)}
        </div>
      )}
      <div>
        <span className="text-text-muted">Budget:</span> {fmt(period.budgetDays, 1)}
      </div>
      {period.plannedDays != null && (
        <div>
          <span className="text-text-muted">Planned:</span> {fmt(period.plannedDays, 1)} nos
        </div>
      )}
      <div>
        <span className="text-text-muted">Actual:</span> {fmt(period.actualDays, 1)}
      </div>
      {period.actualDaysUntracked != null && period.actualDaysUntracked > 0 && (
        <div className="text-text-muted italic">
          ({fmt(period.actualDaysUntracked, 1)} day{period.actualDaysUntracked === 1 ? "" : "s"} on activities not tracking productivity)
        </div>
      )}
      <div className="flex items-center gap-2">
        <span
          className={`inline-block px-2 py-0.5 rounded text-xs font-semibold cursor-help ${utilBand(period.utilizationPct)}`}
          title={UTIL_TOOLTIP}
        >
          {period.utilizationPct == null ? "—" : `${fmt(period.utilizationPct, 1)} %`}
        </span>
        {period.costImplication != null && (
          <span
            className={`text-xs ${period.costImplication < 0 ? "text-success" : period.costImplication > 0 ? "text-danger" : "text-text-muted"}`}
          >
            ₹{fmt(period.costImplication, 0)}
          </span>
        )}
      </div>
      {period.constrainedDays != null && period.constrainedDays > 0 && (
        <div className="mt-1 text-warning italic">
          ⚠ {fmt(period.constrainedDays, 1)} day{period.constrainedDays === 1 ? "" : "s"} constrained by{" "}
          {sideLabel(period.constrainedBySide)} side (SERIES) — that side's norm capped the
          activity's output, so this row's low util % is the bottleneck, not the role's
          efficiency.
        </div>
      )}
    </div>
  );
});
