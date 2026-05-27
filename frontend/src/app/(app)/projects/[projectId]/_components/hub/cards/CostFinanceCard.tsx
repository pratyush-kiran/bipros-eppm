"use client";
import { useQuery } from "@tanstack/react-query";
import { budgetApi } from "@/lib/api/budgetApi";
import { costApi } from "@/lib/api/costApi";
import { projectApi } from "@/lib/api/projectApi";
import { useAuthStore } from "@/lib/state/store";
import { formatBudget } from "@/lib/utils/format";
import { MetricNumber } from "@/components/hub/mission-control/primitives/MetricNumber";
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

  // Body CPI colour: emerald ≥1, bronze-warn 0.95–1, burgundy <0.95.
  const cpiColorClass =
    cpi == null
      ? "text-text-primary"
      : cpi >= 1
        ? "text-emerald"
        : cpi >= 0.95
          ? "text-bronze-warn"
          : "text-burgundy";

  const variance =
    summary?.costVariance != null
      ? rawToMajorScale(summary.costVariance, currency)
      : null;
  // Positive variance is favourable (under budget) → emerald; negative → burgundy.
  const varianceFavourable = variance != null ? variance >= 0 : null;

  const burnPct =
    totalBudgetMajor != null && totalBudgetMajor > 0 && actualSpendMajor != null
      ? Math.max(0, Math.min(100, (actualSpendMajor / totalBudgetMajor) * 100))
      : null;

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
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {/* Left — budget burn bar */}
          {totalBudgetMajor != null && actualSpendMajor != null ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Budget burn
              </div>
              <div className="mt-1.5 flex items-baseline gap-1.5">
                <span className="font-mono text-2xl text-text-primary tabular-nums">
                  {formatBudget(actualSpendMajor, currency)}
                </span>
                <span className="font-mono text-sm text-text-muted tabular-nums">
                  / {formatBudget(totalBudgetMajor, currency)}
                </span>
              </div>
              {burnPct != null ? (
                <>
                  <div className="mt-2.5 h-2 rounded-full bg-parchment dark:bg-white/10 overflow-hidden">
                    <div
                      className="h-full rounded-full bg-gradient-to-r from-emerald to-emerald/70 motion-safe:transition-[width] motion-safe:duration-700"
                      style={{ width: `${burnPct}%` }}
                    />
                  </div>
                  {utilisationPct != null ? (
                    <div className="mt-1.5 text-[10px] text-text-muted">
                      <span className="font-mono tabular-nums text-text-secondary">
                        {utilisationPct}%
                      </span>{" "}
                      utilised
                    </div>
                  ) : null}
                </>
              ) : null}
            </div>
          ) : null}

          {/* Right — CPI hero + cost variance */}
          {cpi != null ? (
            <div className="rounded-lg border border-border bg-ivory p-2.5">
              <div className="text-[10px] uppercase tracking-[0.1em] text-text-muted">
                Cost Performance Index
              </div>
              <MetricNumber
                value={cpi}
                format={(n) => n.toFixed(2)}
                className={`mt-1 block font-mono text-3xl tabular-nums leading-none ${cpiColorClass}`}
              />
              {variance != null ? (
                <div
                  className={`mt-2 flex items-center gap-1 text-xs font-mono tabular-nums ${
                    varianceFavourable ? "text-emerald" : "text-burgundy"
                  }`}
                >
                  <span aria-hidden>{varianceFavourable ? "▲" : "▼"}</span>
                  <span>{formatBudget(Math.abs(variance), currency)}</span>
                  <span className="text-text-muted font-sans">variance</span>
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      )}
    </CardShell>
  );
}
