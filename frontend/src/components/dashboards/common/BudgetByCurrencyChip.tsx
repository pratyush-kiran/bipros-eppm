"use client";
import { formatMoney } from "@/lib/currency/format";
import type { CurrencyBudget } from "@/lib/api/portfolioReportApi";

/** Per-currency budget subtotals — never summed across currencies (no FX). */
export function BudgetByCurrencyChip({ items }: { items: CurrencyBudget[] }) {
  const valid = (items ?? []).filter((i) => i && i.totalBudgetRaw > 0);
  if (valid.length === 0) return <span className="text-slate">—</span>;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
      {valid.map((i, idx) => (
        <span key={i.currency} className="font-semibold text-charcoal">
          {formatMoney(i.totalBudgetRaw, { code: i.currency }, { compact: true })}
          {idx < valid.length - 1 && <span className="ml-2 text-ash">·</span>}
        </span>
      ))}
    </span>
  );
}
