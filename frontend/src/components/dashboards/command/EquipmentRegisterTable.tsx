import React from "react";
import { cn } from "@/lib/utils/cn";
import { StatusBadge } from "@/components/ui/status-badge";

export interface EquipmentRow {
  id: string;
  code: string;
  name: string;
  type?: string;
  assignedTo?: string;
  location?: string;
  hoursToday?: number | null;
  totalHours?: number | null;
  fuelPct?: number | null;
  condition?: string;
  status?: string;
}

export interface EquipmentRegisterTableProps {
  rows: EquipmentRow[];
  className?: string;
}

function fuelColor(pct: number): string {
  if (pct < 30) return "bg-burgundy";
  if (pct < 60) return "bg-bronze-warn";
  return "bg-emerald";
}

export function EquipmentRegisterTable({ rows, className }: EquipmentRegisterTableProps) {
  if (rows.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary", className)}>
        No equipment to display.
      </div>
    );
  }
  return (
    <div className={cn("overflow-x-auto", className)}>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            <th className="px-3 py-3 text-left">ID</th>
            <th className="px-3 py-3 text-left">Equipment</th>
            <th className="px-3 py-3 text-left">Type</th>
            <th className="px-3 py-3 text-left">Assigned To</th>
            <th className="px-3 py-3 text-left">Location</th>
            <th className="px-3 py-3 text-right">Hrs Today</th>
            <th className="px-3 py-3 text-right">Total Hrs</th>
            <th className="px-3 py-3 text-left">Fuel%</th>
            <th className="px-3 py-3 text-left">Condition</th>
            <th className="px-3 py-3 text-left">Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="border-t border-hairline">
              <td className="px-3 py-3 font-semibold text-text-primary tabular-nums">{row.code}</td>
              <td className="px-3 py-3 text-text-primary">{row.name}</td>
              <td className="px-3 py-3 text-text-secondary">{row.type ?? "—"}</td>
              <td className="px-3 py-3 text-text-secondary tabular-nums">{row.assignedTo ?? "—"}</td>
              <td className="px-3 py-3 text-text-secondary">{row.location ?? "—"}</td>
              <td className="px-3 py-3 text-right text-text-primary tabular-nums">
                {row.hoursToday == null ? "—" : `${row.hoursToday.toFixed(1)}h`}
              </td>
              <td className="px-3 py-3 text-right text-text-secondary tabular-nums">
                {row.totalHours == null ? "—" : `${row.totalHours.toFixed(0)}h`}
              </td>
              <td className="px-3 py-3">
                {row.fuelPct == null ? (
                  <span className="text-text-secondary">—</span>
                ) : (
                  <div className="flex flex-col gap-1">
                    <span className="text-[10px] text-text-secondary tabular-nums">{Math.round(row.fuelPct)}%</span>
                    <div className="h-1.5 w-20 overflow-hidden rounded-full bg-parchment">
                      <span
                        className={cn("block h-full rounded-full", fuelColor(row.fuelPct))}
                        style={{ width: `${Math.max(0, Math.min(100, row.fuelPct))}%` }}
                      />
                    </div>
                  </div>
                )}
              </td>
              <td className="px-3 py-3">
                {row.condition ? <StatusBadge status={row.condition} /> : <span className="text-text-secondary">—</span>}
              </td>
              <td className="px-3 py-3">
                {row.status ? <StatusBadge status={row.status} /> : <span className="text-text-secondary">—</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
