"use client";

import { memo } from "react";
import type { RolePeriod } from "@/lib/api/capacityUtilizationApi";

function fmt(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: digits });
}

function fmtMoney(n: number): string {
  return n.toLocaleString("en-IN", { maximumFractionDigits: 0 });
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
 * Builds the "Actual" breakdown line: "(115 tracked · 154 suppressed · 8 untracked)".
 * Returns null when nothing was suppressed or untracked — the breakdown line is hidden
 * in that case because Actual already tells the whole story.
 */
function actualBreakdown(p: RolePeriod): string | null {
  const total = p.actualDays ?? 0;
  if (total <= 0) return null;
  const suppressed = p.actualDaysOnHiddenSides ?? 0;
  const untracked = p.actualDaysUntracked ?? 0;
  if (suppressed <= 0 && untracked <= 0) return null;
  const tracked = total - suppressed - untracked;
  const parts: string[] = [];
  if (tracked > 0) parts.push(`${fmt(tracked, 1)} tracked`);
  if (suppressed > 0) parts.push(`${fmt(suppressed, 1)} suppressed`);
  if (untracked > 0) parts.push(`${fmt(untracked, 1)} untracked`);
  return parts.join(" · ");
}

/**
 * SC180-style cell for one bucket of a role row. Shared between the project-level capacity
 * utilization view and the multi-period aggregate view. Renders Qty done / Budget / Actual /
 * Untracked / Efficiency % / Cost in a tight stack. "Planned" is intentionally hidden — the
 * activity-plan headcount commitment seeded too much "why didn't we use all 100?" confusion
 * when shown next to a single-day Actual.
 */
export const PeriodCell = memo(function PeriodCell({
  period,
}: {
  period: RolePeriod | null;
}) {
  if (!period) {
    return <span className="text-xs text-text-muted">—</span>;
  }
  const untracked = period.normResolved === false;
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
      <div>
        <span className="text-text-muted">Actual:</span> {fmt(period.actualDays, 1)}
      </div>
      {actualBreakdown(period) && (
        <div
          className="text-text-muted italic"
          title="Tracked = counted toward Efficiency on this side. Suppressed = on activities where the other side governs (SERIES / SUBSTITUTE) — see banner. Untracked = on activities with no productivity norm for this role."
        >
          ({actualBreakdown(period)})
        </div>
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
              ? `Cost saved: ₹${fmtMoney(Math.abs(period.costImplication))}`
              : `Cost overrun: ₹${fmtMoney(period.costImplication)}`}
          </span>
        )}
      </div>
      {untracked && (
        <div className="mt-1 text-text-muted italic">
          No productivity norm for this role on this activity.
        </div>
      )}
    </div>
  );
});
