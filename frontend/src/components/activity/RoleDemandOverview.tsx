"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  roleAssignmentApi,
  type RoleAssignmentResponse,
} from "@/lib/api/roleAssignmentApi";
import {
  activitySubContractorApi,
  type ActivitySubContractorAssignment,
} from "@/lib/api/activitySubContractorApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

interface Props {
  projectId: string;
  activityId: string;
  /** Show a small title heading above the table. */
  title?: string;
  /** Optional compact mode used in the activity drawer (smaller fonts/padding). */
  compact?: boolean;
}

type ResourcePlanRow =
  | { kind: "role"; data: RoleAssignmentResponse }
  | { kind: "subContractor"; data: ActivitySubContractorAssignment };

/**
 * Read-only flat overview of all resource demand on an activity.
 * Merges role assignments (manpower/equipment/material) and sub-contractor
 * assignments into a single table showing planned/actual/remaining units and cost.
 * Totals row at the bottom across all rows.
 */
export function RoleDemandOverview({ projectId, activityId, title = "Resource Plan", compact = false }: Props) {
  const { money } = useProjectCurrency();
  const fmtCost = (n: number | null | undefined): string => (n == null ? "—" : money(n));
  const { data: roleResp } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId),
  });
  const roleRows = useMemo<RoleAssignmentResponse[]>(
    () => (Array.isArray(roleResp?.data) ? roleResp.data : []),
    [roleResp],
  );

  const { data: scResp } = useQuery({
    queryKey: ["sub-contractor-assignments", projectId, activityId],
    queryFn: () => activitySubContractorApi.listForActivity(projectId, activityId),
  });
  const scRows = useMemo<ActivitySubContractorAssignment[]>(
    () => (Array.isArray(scResp?.data) ? scResp.data : []),
    [scResp],
  );

  const allRows = useMemo<ResourcePlanRow[]>(
    () => [
      ...roleRows.map((r) => ({ kind: "role" as const, data: r })),
      ...scRows.map((r) => ({ kind: "subContractor" as const, data: r })),
    ],
    [roleRows, scRows],
  );

  // Display rule for role rows:
  //   Planned   = headcount (or quantity for material)
  //   Actual    = stored actualUnits
  //   Remaining = max(Planned − Actual, 0)
  const roleDisplay = (r: RoleAssignmentResponse) => {
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

  const scDisplay = (r: ActivitySubContractorAssignment) => {
    const planned = r.plannedUnits ?? 0;
    const actual = r.actualUnits ?? 0;
    return { planned, actual, remaining: Math.max(planned - actual, 0) };
  };

  const totals = useMemo(() => {
    return allRows.reduce(
      (acc, row) => {
        if (row.kind === "role") {
          const d = roleDisplay(row.data);
          acc.plannedUnits += d.planned;
          acc.actualUnits += d.actual;
          acc.remainingUnits += d.remaining;
          acc.plannedCost += row.data.plannedCost ?? 0;
          acc.actualCost += row.data.actualCost ?? 0;
          acc.remainingCost += row.data.remainingCost ?? 0;
        } else {
          const d = scDisplay(row.data);
          acc.plannedUnits += d.planned;
          acc.actualUnits += d.actual;
          acc.remainingUnits += d.remaining;
          acc.plannedCost += row.data.plannedCost ?? 0;
          acc.actualCost += row.data.actualCost ?? 0;
          // SC API may omit remainingCost on legacy responses — fall back to planned − actual.
          acc.remainingCost +=
            row.data.remainingCost ??
            Math.max((row.data.plannedCost ?? 0) - (row.data.actualCost ?? 0), 0);
        }
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
  }, [allRows]);

  const sz = compact ? "text-xs" : "text-sm";
  const pad = compact ? "py-1.5 px-2" : "py-2 px-3";

  if (allRows.length === 0) {
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
          {allRows.length} {allRows.length === 1 ? "row" : "rows"}
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
            {allRows.map((row, idx) => {
              if (row.kind === "role") {
                const r = row.data;
                const d = roleDisplay(r);
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
              }

              const r = row.data;
              const d = scDisplay(r);
              return (
                <tr
                  key={r.id}
                  className={`border-b border-border/40 transition-colors hover:bg-accent/5 ${
                    idx % 2 === 1 ? "bg-surface-hover/40" : ""
                  }`}
                >
                  <td className={`${pad} font-medium text-text-primary`}>
                    {r.subContractorName ?? "—"}
                    <span
                      className="ml-2 inline-flex items-center rounded-full bg-info/15 px-1.5 py-0.5 text-[10px] font-medium text-info"
                      title="Sub-contractor assignment"
                    >
                      Sub-Contractor
                    </span>
                  </td>
                  <td className={`${pad} text-text-secondary`}>{scVariantLabel(r)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellPlanned}`}>{fmtNum(d.planned)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellActual}`}>{fmtNum(d.actual)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellRemaining}`}>{fmtNum(d.remaining)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellPlanned}`}>{fmtCost(r.plannedCost)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellActual}`}>{fmtCost(r.actualCost ?? 0)}</td>
                  <td className={`${pad} text-right tabular-nums ${cellRemaining}`}>{fmtCost(r.remainingCost ?? Math.max((r.plannedCost ?? 0) - (r.actualCost ?? 0), 0))}</td>
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

/**
 * Mirrors the role-row "Skilled / Grade A — Hr @ 0.5700" format using the sub-contractor's
 * work-type name, unit, and rate. Falls back gracefully when any piece is missing — at
 * minimum the work-type label shows so the variant column is never blank.
 */
function scVariantLabel(r: ActivitySubContractorAssignment): string {
  const workType = r.workTypeName?.trim();
  const unit = r.unit?.trim();
  const rate = r.ratePerUnit;
  if (!workType && !unit && rate == null) return "—";
  const parts: string[] = [];
  if (workType) parts.push(workType);
  if (unit || rate != null) {
    const unitPart = unit ?? "";
    const ratePart = rate != null ? `@ ${rate.toFixed(4)}` : "";
    const tail = [unitPart, ratePart].filter(Boolean).join(" ");
    if (tail) parts.push(tail);
  }
  return parts.join(" — ");
}
