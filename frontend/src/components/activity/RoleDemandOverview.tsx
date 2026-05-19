"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  roleAssignmentApi,
  type RoleAssignmentResponse,
} from "@/lib/api/roleAssignmentApi";

interface Props {
  projectId: string;
  activityId: string;
  /** Show a small title heading above the table. */
  title?: string;
  /** Optional compact mode used in the activity drawer (smaller fonts/padding). */
  compact?: boolean;
}

/**
 * Read-only flat overview of all role-based resource demand on an activity.
 * One row per assignment showing planned/actual/remaining units and cost.
 * Unit (Day/Hour/MT) is intentionally hidden — the role+variant uniquely
 * identifies the rate context so the unit adds visual noise. Totals row at
 * the bottom across all rows.
 */
export function RoleDemandOverview({ projectId, activityId, title = "Resource Plan", compact = false }: Props) {
  const { data: resp } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId),
  });
  const rows = useMemo<RoleAssignmentResponse[]>(
    () => (Array.isArray(resp?.data) ? resp.data : []),
    [resp],
  );

  // Display rule, kept deliberately simple per user request:
  //   Planned   = headcount (or quantity for material)        — what was entered
  //   Actual    = stored actualUnits                          — cumulative nos from DPRs
  //   Remaining = max(Planned − Actual, 0)                    — plain subtraction
  // Hours never enter this math — DAY-basis rollups store just `nos`, HOUR-basis stores
  // `nos × hours`. Either way we surface the stored value directly.
  // Legacy rows (no headcount / no quantity) keep the original stored numbers.
  const display = (r: RoleAssignmentResponse) => {
    if (r.headcount != null) {
      const planned = r.headcount;
      const actual = r.actualUnits ?? 0;
      return { planned, actual, remaining: Math.max(planned - actual, 0) };
    }
    if (r.quantity != null) {
      const planned = Number(r.quantity);
      const actual = r.actualUnits ?? 0;
      return { planned, actual, remaining: Math.max(planned - actual, 0) };
    }
    return {
      planned: r.plannedUnits ?? 0,
      actual: r.actualUnits ?? 0,
      remaining: r.remainingUnits ?? Math.max((r.plannedUnits ?? 0) - (r.actualUnits ?? 0), 0),
    };
  };

  const totals = useMemo(() => {
    return rows.reduce(
      (acc, r) => {
        const d = display(r);
        acc.plannedUnits += d.planned;
        acc.actualUnits += d.actual;
        acc.remainingUnits += d.remaining;
        acc.plannedCost += r.plannedCost ?? 0;
        acc.actualCost += r.actualCost ?? 0;
        acc.remainingCost += r.remainingCost ?? 0;
        return acc;
      },
      {
        plannedUnits: 0,
        actualUnits: 0,
        remainingUnits: 0,
        plannedCost: 0,
        actualCost: 0,
        remainingCost: 0,
      },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows]);

  const sz = compact ? "text-xs" : "text-sm";
  const pad = compact ? "py-1.5 px-2" : "py-2 px-3";

  if (rows.length === 0) {
    return (
      <section className="overflow-hidden rounded-md border border-border bg-surface shadow-sm">
        <div className="border-l-4 border-accent bg-accent/5 px-3 py-2">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-accent">{title}</h3>
        </div>
        <p className={`${sz} px-3 py-3 text-text-muted`}>No resources planned yet.</p>
      </section>
    );
  }

  // Column-group tones: planned = info (steel blue), actual = success (emerald),
  // remaining = warning (bronze). Headers use the strong tone; cells use a
  // muted variant so values stay readable without screaming.
  const headPlanned = "bg-info/10 text-info";
  const headActual = "bg-success/10 text-success";
  const headRemaining = "bg-warning/10 text-warning";
  const cellPlanned = "text-info";
  const cellActual = "text-success";
  const cellRemaining = "text-warning";

  return (
    <section className="overflow-hidden rounded-md border border-border bg-surface shadow-sm">
      <div className="flex items-center justify-between border-l-4 border-accent bg-accent/5 px-3 py-2">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-accent">{title}</h3>
        <span className="text-[10px] font-medium uppercase tracking-wider text-text-muted">
          {rows.length} {rows.length === 1 ? "row" : "rows"}
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className={`w-full ${sz}`}>
          <thead className="border-b border-border">
            <tr>
              <th className={`${pad} text-left font-semibold text-text-secondary`}>Role</th>
              <th className={`${pad} text-left font-semibold text-text-secondary`}>Variant</th>
              <th className={`${pad} text-right font-semibold ${headPlanned}`}>Planned Units</th>
              <th className={`${pad} text-right font-semibold ${headActual}`}>Actual Units</th>
              <th className={`${pad} text-right font-semibold ${headRemaining}`}>Remaining Units</th>
              <th className={`${pad} text-right font-semibold ${headPlanned}`}>Planned Cost</th>
              <th className={`${pad} text-right font-semibold ${headActual}`}>Actual Cost</th>
              <th className={`${pad} text-right font-semibold ${headRemaining}`}>Remaining Cost</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, idx) => {
              const d = display(r);
              return (
                <tr
                  key={r.id}
                  className={`border-b border-border/40 transition-colors hover:bg-accent/5 ${
                    idx % 2 === 1 ? "bg-surface-hover/40" : ""
                  }`}
                >
                  <td className={`${pad} font-medium text-text-primary`}>
                    {r.roleName ?? "—"}
                    {r.unplanned && (
                      <span
                        className="ml-2 inline-flex items-center rounded-full bg-warning/15 px-1.5 py-0.5 text-[10px] font-medium text-warning"
                        title="Field-added by a DPR — not in the original plan"
                      >
                        Unplanned
                      </span>
                    )}
                  </td>
                  <td className={`${pad} text-text-secondary`}>{r.variantLabel ?? "—"}</td>
                  <td className={`${pad} text-right tabular-nums ${cellPlanned}`}>{fmtNum(d.planned)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellActual}`}>{fmtNum(d.actual)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellRemaining}`}>{fmtNum(d.remaining)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellPlanned}`}>{fmtCost(r.plannedCost)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellActual}`}>{fmtCost(r.actualCost)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellRemaining}`}>{fmtCost(r.remainingCost)}</td>
                </tr>
              );
            })}
            <tr className="border-t-2 border-accent/40 bg-accent/10 font-semibold">
              <td className={`${pad} text-accent`} colSpan={2}>Totals</td>
              <td className={`${pad} text-right tabular-nums ${cellPlanned}`}>{fmtNum(totals.plannedUnits)}</td>
              <td className={`${pad} text-right tabular-nums ${cellActual}`}>{fmtNum(totals.actualUnits)}</td>
              <td className={`${pad} text-right tabular-nums ${cellRemaining}`}>{fmtNum(totals.remainingUnits)}</td>
              <td className={`${pad} text-right tabular-nums ${cellPlanned}`}>{fmtCost(totals.plannedCost)}</td>
              <td className={`${pad} text-right tabular-nums ${cellActual}`}>{fmtCost(totals.actualCost)}</td>
              <td className={`${pad} text-right tabular-nums ${cellRemaining}`}>{fmtCost(totals.remainingCost)}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  );
}

function fmtNum(n: number | null | undefined): string {
  if (n == null) return "—";
  return Number.isInteger(n) ? String(n) : n.toFixed(2);
}

function fmtCost(n: number | null | undefined): string {
  if (n == null) return "—";
  return `₹${n.toFixed(2)}`;
}
