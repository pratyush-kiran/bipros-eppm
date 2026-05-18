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
          className={`inline-block px-2 py-0.5 rounded text-xs font-semibold ${utilBand(period.utilizationPct)}`}
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
    </div>
  );
});
