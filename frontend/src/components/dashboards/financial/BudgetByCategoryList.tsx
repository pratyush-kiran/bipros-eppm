"use client";

import { Layers } from "lucide-react";
import {
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  formatINR,
} from "@/components/common/dashboard/primitives";
import type { CategoryRow } from "@/lib/dashboard/financialAggregators";

interface Props {
  rows: CategoryRow[];
  isLoading: boolean;
}

function barTone(actual: number, budget: number): string {
  if (budget <= 0) return "bg-slate/40";
  const ratio = actual / budget;
  if (ratio > 1.05) return "bg-burgundy";
  if (ratio >= 0.9) return "bg-gold";
  return "bg-emerald";
}

export function BudgetByCategoryList({ rows, isLoading }: Props) {
  // Scale all bars against the largest budget so visual lengths are comparable.
  const maxBudget = rows.reduce((m, r) => Math.max(m, r.budget, r.actual), 0);

  return (
    <SectionCard
      title="Budget breakdown by category"
      subtitle="BOQ chapters rolled up — budget vs actual"
      icon={<Layers size={16} strokeWidth={1.75} />}
      accent
    >
      {isLoading ? (
        <LoadingBlock label="Loading breakdown…" />
      ) : rows.length === 0 || maxBudget === 0 ? (
        <EmptyBlock label="No BOQ items grouped by chapter yet." />
      ) : (
        <ul className="space-y-3.5">
          {rows.map((r) => {
            const budgetPct = maxBudget > 0 ? (r.budget / maxBudget) * 100 : 0;
            const actualPct = maxBudget > 0 ? (r.actual / maxBudget) * 100 : 0;
            const variancePct = r.variancePct;
            const varianceTone =
              variancePct == null
                ? "text-slate"
                : variancePct >= 5
                  ? "text-emerald"
                  : variancePct <= -5
                    ? "text-burgundy"
                    : "text-bronze-warn";
            return (
              <li key={r.category} className="space-y-1.5">
                <div className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="font-medium text-charcoal">{r.category}</span>
                  <div className="flex items-baseline gap-3 text-xs text-slate">
                    <span>
                      Budget{" "}
                      <span className="font-display font-semibold text-charcoal">
                        {formatINR(r.budget)}
                      </span>
                    </span>
                    <span>
                      Actual{" "}
                      <span className="font-display font-semibold text-charcoal">
                        {formatINR(r.actual)}
                      </span>
                    </span>
                    {variancePct != null && (
                      <span className={`font-semibold ${varianceTone}`}>
                        {variancePct >= 0 ? "▼" : "▲"} {Math.abs(variancePct).toFixed(1)}%
                      </span>
                    )}
                  </div>
                </div>

                <div className="relative h-4 w-full overflow-hidden rounded-full bg-parchment/70">
                  {/* Budget track — translucent gold, full width of the budget */}
                  <div
                    className="absolute inset-y-0 left-0 rounded-full bg-gold/22"
                    style={{ width: `${budgetPct}%` }}
                  />
                  {/* Actual bar — coloured by over/under ratio */}
                  <div
                    className={`absolute inset-y-0 left-0 rounded-full ${barTone(r.actual, r.budget)}`}
                    style={{ width: `${Math.min(actualPct, 100)}%` }}
                  />
                  {/* Budget tick mark */}
                  {r.budget > 0 && (
                    <div
                      className="absolute inset-y-0 w-px bg-gold-deep"
                      style={{ left: `${Math.min(budgetPct, 100)}%` }}
                    />
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </SectionCard>
  );
}
