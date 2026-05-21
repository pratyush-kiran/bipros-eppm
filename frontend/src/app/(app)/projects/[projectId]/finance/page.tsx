"use client";

import React, { useMemo } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";

import { boqApi } from "@/lib/api/boqApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { costApi } from "@/lib/api/costApi";
import { raBillApi } from "@/lib/api/raBillApi";

import { KpiCard } from "@/components/ui/kpi-card";
import { MetricDelta } from "@/components/ui/metric-delta";
import { DashboardBreadcrumb } from "@/components/dashboards/command/DashboardBreadcrumb";
import { SCurveChart, type SCurvePoint } from "@/components/dashboards/command/SCurveChart";
import {
  BudgetBreakdownTable,
  type BudgetBreakdownRow,
} from "@/components/dashboards/command/BudgetBreakdownTable";
import { CostVarianceDonut } from "@/components/dashboards/command/CostVarianceDonut";
import {
  InvoiceSummaryTable,
  type InvoiceRow,
} from "@/components/dashboards/command/InvoiceSummaryTable";
import { formatCrore } from "@/components/common/dashboard/primitives";

const CR = 1e7;

export default function ProjectFinancialDashboardPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  const { data: boqEnv } = useQuery({
    queryKey: ["boq-summary", projectId],
    queryFn: () => boqApi.list(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: budgetEnv } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    enabled: !!projectId,
  });

  const { data: wbsBudgetEnv } = useQuery({
    queryKey: ["wbs-budget-summary", projectId],
    queryFn: () => budgetApi.getWbsBudgetSummary(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: costEnv } = useQuery({
    queryKey: ["cost-summary", projectId],
    queryFn: () => costApi.getCostSummary(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: cashFlowEnv } = useQuery({
    queryKey: ["cash-flow", projectId],
    queryFn: () => costApi.getCashFlowForecast(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: raBillsEnv } = useQuery({
    queryKey: ["ra-bills", projectId],
    queryFn: () => raBillApi.getRaBillsByProject(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const boq = boqEnv?.data;
  const budget = budgetEnv?.data;
  const wbsBudget = wbsBudgetEnv?.data;
  const cost = costEnv?.data;
  const cashFlow = cashFlowEnv?.data ?? [];
  const raBills = Array.isArray(raBillsEnv?.data) ? raBillsEnv.data : [];

  const contractValueCr = (budget?.originalBudget ?? 0) / CR;
  const boqValueCr = (boq?.boqGrandTotal ?? 0) / CR;
  const expenditureCr = (cost?.totalActual ?? 0) / CR;
  const billingCr = raBills.reduce((s, b) => s + (b.netAmount ?? 0), 0) / CR;
  const pendingRecoveryCr = Math.max(0, billingCr - expenditureCr);

  const boqDeltaPct =
    contractValueCr > 0 ? ((boqValueCr - contractValueCr) / contractValueCr) * 100 : 0;
  // Convention from the design: actual-vs-budget. Negative = under, positive = over.
  // EVM cost-variance (CV) uses the opposite sign (CV = EV − AC), so derive from totals.
  const rawVariancePct =
    cost?.totalBudget && cost.totalBudget > 0
      ? ((cost.totalActual - cost.totalBudget) / cost.totalBudget) * 100
      : 0;
  // Clamp the displayed variance to a sensible range so tiny budgets don't render as 4-digit %.
  const variancePct = Math.max(-100, Math.min(100, rawVariancePct));

  const sCurveData: SCurvePoint[] = useMemo(() => {
    return cashFlow.map((c) => ({
      period: new Date(c.period).toLocaleDateString("en-US", { month: "short" }),
      planned: c.cumulativePlanned / CR,
      actual: c.cumulativeActual / CR,
      forecast: c.cumulativeForecast / CR,
    }));
  }, [cashFlow]);

  const budgetBreakdown: BudgetBreakdownRow[] = useMemo(() => {
    if (!wbsBudget) return [];
    return wbsBudget.nodes
      .filter((n) => (n.wbsLevel ?? 2) === 2)
      .slice(0, 8)
      .map((n) => ({
        id: n.wbsNodeId,
        category: n.name,
        budget: n.budgetCrores * CR,
        actual: (n.budgetCrores - n.unallocatedCrores) * CR,
      }));
  }, [wbsBudget]);

  const invoiceRows: InvoiceRow[] = useMemo(() => {
    return raBills.slice(0, 5).map((b) => ({
      id: b.id,
      number: b.billNumber,
      amount: b.netAmount,
      date: b.billPeriodTo,
      status: b.status,
    }));
  }, [raBills]);

  const onBudgetCount = budgetBreakdown.filter((r) => r.actual <= r.budget).length;
  const overBudgetCount = budgetBreakdown.filter((r) => r.actual > r.budget).length;
  const underBudgetCount = Math.max(0, budgetBreakdown.length - onBudgetCount - overBudgetCount);

  return (
    <div className="space-y-6 pb-12">
      <header className="space-y-2">
        <DashboardBreadcrumb label="Finance" />
        <p className="text-xs text-text-secondary">Budget tracking, cost analysis and billing</p>
      </header>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <KpiCard
          label="Contract Value"
          value={formatCrore(contractValueCr * CR, 1)}
          accent="info"
          rail="top"
        />
        <KpiCard
          label="Approved BOQ"
          value={formatCrore(boqValueCr * CR, 1)}
          accent="violet"
          rail="top"
          delta={
            <MetricDelta tone={boqDeltaPct >= 0 ? "positive" : "negative"}>
              {boqDeltaPct >= 0 ? "+" : ""}
              {boqDeltaPct.toFixed(1)}%
            </MetricDelta>
          }
        />
        <KpiCard
          label="Expenditure to Date"
          value={formatCrore(expenditureCr * CR, 1)}
          accent="warning"
          rail="top"
        />
        <KpiCard
          label="Billing Raised"
          value={formatCrore(billingCr * CR, 1)}
          accent="success"
          rail="top"
        />
        <KpiCard
          label="Pending Recovery"
          value={formatCrore(pendingRecoveryCr * CR, 1)}
          accent="danger"
          rail="top"
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Cost S-Curve — Planned vs Actual vs Forecast
          </h2>
          <SCurveChart data={sCurveData} />
        </section>
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Invoice Summary
          </h2>
          <InvoiceSummaryTable rows={invoiceRows} />
        </section>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Budget Breakdown by Category
          </h2>
          <BudgetBreakdownTable rows={budgetBreakdown} />
        </section>
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Cost Variance
          </h2>
          <CostVarianceDonut
            variancePct={variancePct}
            segments={[
              { label: "On Budget", value: onBudgetCount, color: "var(--emerald)" },
              { label: "Over", value: overBudgetCount, color: "var(--burgundy)" },
              { label: "Under", value: underBudgetCount, color: "var(--steel)" },
            ]}
          />
        </section>
      </div>
    </div>
  );
}
