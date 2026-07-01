"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { budgetApi } from "@/lib/api/budgetApi";
import { formatBudget } from "@/lib/utils/format";

interface BudgetBandProps {
  projectId: string;
}

export function BudgetBand({ projectId }: BudgetBandProps) {
  const { data } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
  });

  const budget = data?.data;

  if (budget?.originalBudget == null) return null;

  const currency = budget.budgetCurrency ?? "INR";
  const fmt = (v: number | null) => formatBudget(v, currency);
  const approvedCount = budget.approvedChangeCount ?? 0;

  return (
    <Link
      href={`/projects/${projectId}/budget-changes`}
      className="flex w-full items-center gap-2 rounded-xl border border-hairline bg-paper px-4 py-2.5 text-sm shadow-[0_1px_2px_rgba(28,28,28,0.03)] transition-colors hover:border-gold/30 hover:bg-ivory/60"
    >
      <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">BAC</span>
      <span className="font-semibold text-charcoal">{fmt(budget.currentBudget)}</span>
      <span className="text-slate">·</span>
      <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">Original (contract)</span>
      <span className="font-semibold text-charcoal">{fmt(budget.originalBudget)}</span>
      {approvedCount > 0 && (
        <>
          <span className="text-slate">·</span>
          <span className="text-emerald font-medium">
            {approvedCount} approved change request{approvedCount === 1 ? "" : "s"}
          </span>
        </>
      )}
    </Link>
  );
}
