import React from "react";
import { cn } from "@/lib/utils/cn";

export interface RiskRegisterRow {
  id: string;
  code: string;
  description: string;
  category: string;
  probability: string;
  impact: string;
  score: number;
  mitigation?: string;
}

export interface RiskRegisterTableProps {
  rows: RiskRegisterRow[];
  className?: string;
}

function scoreColor(score: number): string {
  if (score >= 20) return "bg-burgundy text-white";
  if (score >= 15) return "bg-burgundy/70 text-white";
  if (score >= 10) return "bg-bronze-warn text-[var(--accent-foreground)]";
  if (score >= 5) return "bg-bronze-warn/70 text-[var(--accent-foreground)]";
  return "bg-emerald/70 text-white";
}

export function RiskRegisterTable({ rows, className }: RiskRegisterTableProps) {
  if (rows.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary", className)}>
        No risks logged.
      </div>
    );
  }
  return (
    <div className={cn("overflow-x-auto", className)}>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            <th className="px-3 py-3 text-left">ID</th>
            <th className="px-3 py-3 text-left">Risk Description</th>
            <th className="px-3 py-3 text-left">Category</th>
            <th className="px-3 py-3 text-left">Prob</th>
            <th className="px-3 py-3 text-left">Impact</th>
            <th className="px-3 py-3 text-center">Score</th>
            <th className="px-3 py-3 text-left">Mitigation</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="border-t border-hairline">
              <td className="px-3 py-3 font-semibold text-text-primary tabular-nums">{row.code}</td>
              <td className="px-3 py-3 text-text-primary">
                <span className="line-clamp-1">{row.description}</span>
              </td>
              <td className="px-3 py-3 text-text-secondary">{row.category}</td>
              <td className="px-3 py-3 text-text-secondary">{row.probability}</td>
              <td className="px-3 py-3 text-text-secondary">{row.impact}</td>
              <td className="px-3 py-3 text-center">
                <span
                  className={cn(
                    "inline-flex h-7 w-9 items-center justify-center rounded-md text-sm font-bold tabular-nums",
                    scoreColor(row.score)
                  )}
                >
                  {row.score}
                </span>
              </td>
              <td className="px-3 py-3 text-text-secondary">
                <span className="line-clamp-1">{row.mitigation ?? "—"}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
