import React from "react";
import { cn } from "@/lib/utils/cn";
import { formatCrore } from "@/components/common/dashboard/primitives";

export interface BudgetBreakdownRow {
  id: string;
  category: string;
  budget: number;
  actual: number;
}

export interface BudgetBreakdownTableProps {
  rows: BudgetBreakdownRow[];
  className?: string;
}

export function BudgetBreakdownTable({ rows, className }: BudgetBreakdownTableProps) {
  if (rows.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary", className)}>
        No budget breakdown yet.
      </div>
    );
  }
  const maxValue = Math.max(...rows.flatMap((r) => [r.budget, r.actual]), 1);
  const accentColors = [
    "var(--bronze-warn)",
    "var(--steel)",
    "var(--emerald)",
    "var(--cmd-cyan)",
    "var(--cmd-violet)",
    "var(--gold)",
  ];

  return (
    <div className={cn("overflow-x-auto", className)}>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            <th className="px-3 py-3 text-left">Category</th>
            <th className="px-3 py-3 text-right">Budget</th>
            <th className="px-3 py-3 text-right">Actual</th>
            <th className="px-3 py-3 text-left">Budgeted</th>
            <th className="px-3 py-3 text-left">Spent</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, idx) => {
            const color = accentColors[idx % accentColors.length];
            const budgetPct = (row.budget / maxValue) * 100;
            const actualPct = (row.actual / maxValue) * 100;
            return (
              <tr key={row.id} className="border-t border-hairline">
                <td className="px-3 py-3 text-text-primary">{row.category}</td>
                <td className="px-3 py-3 text-right text-text-primary tabular-nums">
                  {formatCrore(row.budget, 1)}
                </td>
                <td className="px-3 py-3 text-right text-text-primary tabular-nums">
                  {formatCrore(row.actual, 1)}
                </td>
                <td className="px-3 py-3">
                  <div className="h-2.5 w-full overflow-hidden rounded-full bg-parchment/60">
                    <span
                      className="block h-full rounded-full"
                      style={{ width: `${budgetPct}%`, background: color, opacity: 0.45 }}
                    />
                  </div>
                </td>
                <td className="px-3 py-3">
                  <div className="h-2.5 w-full overflow-hidden rounded-full bg-parchment/60">
                    <span
                      className="block h-full rounded-full"
                      style={{ width: `${actualPct}%`, background: color }}
                    />
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
