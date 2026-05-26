"use client";
import { useQuery } from "@tanstack/react-query";
import { budgetApi } from "@/lib/api/budgetApi";
import { costApi } from "@/lib/api/costApi";
import { projectApi } from "@/lib/api/projectApi";
import { useAuthStore } from "@/lib/state/store";
import { formatBudget } from "@/lib/utils/format";
import { CardShell } from "./CardShell";
import { Skeleton, StatusPill, cardQueryOpts, rawToMajorScale } from "./_shared";

/**
 * Cost & Finance card — two sub-tiles:
 *  1. Budget Utilised — actual ÷ current budget (with progress bar)
 *  2. Cost performance — CPI from the cost-summary endpoint
 *
 * Gated by COST.READ; BentoGrid already filters it out for users without the
 * permission but we also guard the queries with {@code enabled} so we don't
 * fire requests we can't make.
 */
export function CostFinanceCard({ projectId }: { projectId: string }) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canReadCost = hasPermission("COST.READ");

  // Project query shares cache with the hub root.
  const projectQuery = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: canReadCost,
    ...cardQueryOpts,
  });
  const currency = projectQuery.data?.data?.budgetCurrency ?? null;

  const budgetQuery = useQuery({
    queryKey: ["project", projectId, "hub-card", "cost", "budget-summary"],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    enabled: canReadCost,
    ...cardQueryOpts,
  });

  // Shares cache with useHubBadges
  const costSummaryQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "cost-summary"],
    queryFn: () => costApi.getCostSummary(projectId),
    enabled: canReadCost,
    ...cardQueryOpts,
  });

  const budget = budgetQuery.data?.data;
  const summary = costSummaryQuery.data?.data;
  const cpi = summary?.costPerformanceIndex ?? null;

  // Both budget and cost-summary return values in raw project-currency units
  // (rupees / rials). The only pre-scaled field on the budget API is
  // `WbsBudgetNode.budgetCrores`; top-level `currentBudget` / `originalBudget`
  // are NOT pre-scaled. Apply rawToMajorScale to both before formatting.
  const rawTotalBudget = budget?.currentBudget ?? budget?.originalBudget ?? null;
  const totalBudgetMajor =
    rawTotalBudget != null ? rawToMajorScale(rawTotalBudget, currency) : null;
  const actualSpendMajor =
    summary?.totalActual != null ? rawToMajorScale(summary.totalActual, currency) : null;
  const utilisationPct =
    totalBudgetMajor != null && totalBudgetMajor > 0 && actualSpendMajor != null
      ? Math.round((actualSpendMajor / totalBudgetMajor) * 100)
      : null;

  // CPI tone: ≥1 healthy, ≥0.9 warning, <0.9 danger.
  const cpiTone: "success" | "warn" | "danger" | undefined =
    cpi == null ? undefined : cpi >= 1 ? "success" : cpi >= 0.9 ? "warn" : "danger";

  const statusPill =
    cpi != null ? (
      <StatusPill tone={cpiTone ?? "neutral"}>CPI {cpi.toFixed(2)}</StatusPill>
    ) : undefined;

  const isLoading = budgetQuery.isLoading || costSummaryQuery.isLoading;

  return (
    <CardShell
      groupId="cost"
      title="Cost & Finance"
      subtitle="Earned value, P&L, commercial controls"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={isLoading}
    >
      {isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-3 w-1/2" />
          <Skeleton className="h-2 w-full" />
          <Skeleton className="h-3 w-1/3" />
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-3">
          {totalBudgetMajor != null && actualSpendMajor != null ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Budget utilised
              </div>
              <div className="mt-1 font-mono text-sm text-text-primary tabular-nums">
                {formatBudget(actualSpendMajor, currency)}
                <span className="text-text-muted"> / </span>
                {formatBudget(totalBudgetMajor, currency)}
              </div>
              {utilisationPct != null ? (
                <div className="mt-1.5 h-1.5 rounded-full bg-parchment overflow-hidden">
                  <div
                    className="h-full bg-emerald-600"
                    style={{
                      width: `${Math.max(0, Math.min(100, utilisationPct))}%`,
                    }}
                  />
                </div>
              ) : null}
            </div>
          ) : null}

          {cpi != null ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Cost Performance Index
              </div>
              <div className="mt-1 font-mono text-lg text-text-primary tabular-nums">
                {cpi.toFixed(2)}
              </div>
              {summary?.costVariance != null ? (
                <div className="text-[10px] text-text-muted mt-0.5">
                  Variance {formatBudget(rawToMajorScale(summary.costVariance, currency), currency)}
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      )}
    </CardShell>
  );
}
