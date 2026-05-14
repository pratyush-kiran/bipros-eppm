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

  const totals = useMemo(() => {
    return rows.reduce(
      (acc, r) => {
        acc.plannedUnits += r.plannedUnits ?? 0;
        acc.actualUnits += r.actualUnits ?? 0;
        acc.remainingUnits += r.remainingUnits ?? 0;
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
  }, [rows]);

  const sz = compact ? "text-xs" : "text-sm";
  const pad = compact ? "py-1.5 px-2" : "py-2 px-3";

  if (rows.length === 0) {
    return (
      <section className="rounded-md border border-border bg-surface p-3">
        <h3 className="mb-1 text-xs font-semibold uppercase tracking-wider text-text-muted">{title}</h3>
        <p className={`${sz} text-text-muted`}>No resources planned yet.</p>
      </section>
    );
  }

  return (
    <section className="rounded-md border border-border bg-surface p-3">
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">{title}</h3>
      <div className="overflow-x-auto">
        <table className={`w-full ${sz}`}>
          <thead className="border-b border-border text-text-muted">
            <tr>
              <th className={`${pad} text-left font-medium`}>Role</th>
              <th className={`${pad} text-left font-medium`}>Variant</th>
              <th className={`${pad} text-right font-medium`}>Planned Units</th>
              <th className={`${pad} text-right font-medium`}>Actual Units</th>
              <th className={`${pad} text-right font-medium`}>Remaining Units</th>
              <th className={`${pad} text-right font-medium`}>Planned Cost</th>
              <th className={`${pad} text-right font-medium`}>Actual Cost</th>
              <th className={`${pad} text-right font-medium`}>Remaining Cost</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-b border-border/40">
                <td className={pad}>{r.roleName ?? "—"}</td>
                <td className={pad}>{r.variantLabel ?? "—"}</td>
                <td className={`${pad} text-right`}>{fmtNum(r.plannedUnits)}</td>
                <td className={`${pad} text-right`}>{fmtNum(r.actualUnits)}</td>
                <td className={`${pad} text-right`}>{fmtNum(r.remainingUnits)}</td>
                <td className={`${pad} text-right`}>{fmtCost(r.plannedCost)}</td>
                <td className={`${pad} text-right`}>{fmtCost(r.actualCost)}</td>
                <td className={`${pad} text-right`}>{fmtCost(r.remainingCost)}</td>
              </tr>
            ))}
            <tr className="border-t-2 border-border font-semibold">
              <td className={pad} colSpan={2}>Totals</td>
              <td className={`${pad} text-right`}>{fmtNum(totals.plannedUnits)}</td>
              <td className={`${pad} text-right`}>{fmtNum(totals.actualUnits)}</td>
              <td className={`${pad} text-right`}>{fmtNum(totals.remainingUnits)}</td>
              <td className={`${pad} text-right`}>{fmtCost(totals.plannedCost)}</td>
              <td className={`${pad} text-right`}>{fmtCost(totals.actualCost)}</td>
              <td className={`${pad} text-right`}>{fmtCost(totals.remainingCost)}</td>
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
