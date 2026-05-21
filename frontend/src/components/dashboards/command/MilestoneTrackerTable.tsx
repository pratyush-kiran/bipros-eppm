import React from "react";
import { cn } from "@/lib/utils/cn";
import { StatusBadge } from "@/components/ui/status-badge";

export interface MilestoneRow {
  id: string;
  code: string;
  name: string;
  planned?: string;
  forecast?: string;
  status: string;
}

export interface MilestoneTrackerTableProps {
  rows: MilestoneRow[];
  className?: string;
}

function fmt(value?: string): string {
  if (!value) return "—";
  return new Date(value).toLocaleDateString("en-US", {
    day: "numeric",
    month: "short",
    year: "2-digit",
  });
}

export function MilestoneTrackerTable({ rows, className }: MilestoneTrackerTableProps) {
  if (rows.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary", className)}>
        No milestones scheduled.
      </div>
    );
  }
  return (
    <div className={cn("overflow-x-auto", className)}>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            <th className="px-3 py-3 text-left">ID</th>
            <th className="px-3 py-3 text-left">Milestone</th>
            <th className="px-3 py-3 text-left">Planned</th>
            <th className="px-3 py-3 text-left">Forecast</th>
            <th className="px-3 py-3 text-left">Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="border-t border-hairline">
              <td className="px-3 py-3 font-semibold text-text-primary tabular-nums">{row.code}</td>
              <td className="px-3 py-3 text-text-primary">{row.name}</td>
              <td className="px-3 py-3 text-text-secondary tabular-nums">{fmt(row.planned)}</td>
              <td className="px-3 py-3 text-text-secondary tabular-nums">{fmt(row.forecast)}</td>
              <td className="px-3 py-3">
                <StatusBadge status={row.status} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
