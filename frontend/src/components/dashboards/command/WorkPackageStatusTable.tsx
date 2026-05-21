import React from "react";
import { cn } from "@/lib/utils/cn";
import { StatusBadge } from "@/components/ui/status-badge";

export interface WorkPackageRow {
  id: string;
  code: string;
  name: string;
  contractor?: string;
  progressPct: number;
  status: string;
  due?: string;
}

export interface WorkPackageStatusTableProps {
  rows: WorkPackageRow[];
  onRowClick?: (row: WorkPackageRow) => void;
  className?: string;
}

function progressColor(pct: number, status: string): string {
  const s = status.toUpperCase();
  if (s.includes("DELAY")) return "bg-burgundy";
  if (s === "DONE" || pct >= 100) return "bg-emerald";
  if (s.includes("PROGRESS")) return "bg-bronze-warn";
  return "bg-steel";
}

export function WorkPackageStatusTable({ rows, onRowClick, className }: WorkPackageStatusTableProps) {
  if (rows.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary", className)}>
        No work packages yet.
      </div>
    );
  }
  return (
    <div className={cn("overflow-x-auto", className)}>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            <th className="px-3 py-3 text-left">#</th>
            <th className="px-3 py-3 text-left">Package</th>
            <th className="px-3 py-3 text-left">Contractor</th>
            <th className="px-3 py-3 text-right">Prog%</th>
            <th className="px-3 py-3 text-left">Status</th>
            <th className="px-3 py-3 text-left">Due</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              className={cn(
                "border-t border-hairline",
                onRowClick && "cursor-pointer transition-colors hover:bg-parchment/40"
              )}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
            >
              <td className="px-3 py-3 font-semibold text-text-primary tabular-nums">{row.code}</td>
              <td className="px-3 py-3 text-text-primary">{row.name}</td>
              <td className="px-3 py-3 text-text-secondary">{row.contractor ?? "—"}</td>
              <td className="px-3 py-3 text-right tabular-nums">
                <div className="flex items-center justify-end gap-2">
                  <span className="font-semibold text-text-primary">{row.progressPct}%</span>
                  <div className="h-1.5 w-16 overflow-hidden rounded-full bg-parchment">
                    <span
                      className={cn("block h-full rounded-full", progressColor(row.progressPct, row.status))}
                      style={{ width: `${Math.max(0, Math.min(100, row.progressPct))}%` }}
                    />
                  </div>
                </div>
              </td>
              <td className="px-3 py-3">
                <StatusBadge status={row.status} />
              </td>
              <td className="px-3 py-3 text-text-secondary tabular-nums">{row.due ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
