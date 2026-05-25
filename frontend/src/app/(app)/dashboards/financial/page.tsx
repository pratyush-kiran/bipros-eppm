"use client";

import { useEffect, useMemo, useState } from "react";

function readStoredProjectId(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(STORAGE_KEY);
}
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  Briefcase,
  ChevronDown,
  Download,
  FileText,
  Landmark,
  RefreshCw,
  Sparkles,
  TrendingDown,
  Wallet,
} from "lucide-react";
import { KpiTile } from "@/components/common/KpiTile";
import {
  EmptyBlock,
  formatINR,
  formatPct,
} from "@/components/common/dashboard/primitives";
import { SectionNav, type SectionNavItem } from "@/components/common/dashboard/SectionNav";
import { boqApi } from "@/lib/api/boqApi";
import { contractApi } from "@/lib/api/contractApi";
import { costApi } from "@/lib/api/costApi";
import { projectApi } from "@/lib/api/projectApi";
import { raBillApi } from "@/lib/api/raBillApi";
import { evmKpiApi } from "@/lib/api/evmKpiApi";
import {
  aggregateBudgetByCategory,
  billingRaisedTotal,
  pendingRecoveryTotal,
} from "@/lib/dashboard/financialAggregators";
import { downloadCsv, toCsv } from "@/lib/utils/csvExport";
import { BudgetByCategoryList } from "@/components/dashboards/financial/BudgetByCategoryList";
import { CostSCurveChart } from "@/components/dashboards/financial/CostSCurveChart";
import { CostVarianceDonut } from "@/components/dashboards/financial/CostVarianceDonut";
import { InvoiceSummaryTable } from "@/components/dashboards/financial/InvoiceSummaryTable";

const STORAGE_KEY = "bipros.financial.project.v1";

const sections: SectionNavItem[] = [
  { id: "overview", label: "Overview" },
  { id: "curve", label: "S-Curve" },
  { id: "invoices", label: "Invoices" },
  { id: "breakdown", label: "Breakdown" },
  { id: "variance", label: "Variance" },
];

export default function FinancialDashboardPage() {
  const qc = useQueryClient();
  // Mirror Sidebar.tsx's `hydrated` gate so localStorage-derived state doesn't
  // diverge between SSR and the first client paint. Until `hydrated` flips
  // post-mount, the page behaves as if no project is stored.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => {
    // Same SSR-safety gate as Sidebar.tsx — flips on after the first client paint
    // so localStorage-derived state doesn't diverge from server rendering. The
    // setState in an effect is intentional here.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setHydrated(true);
  }, []);

  const [pickerSelection, setPickerSelection] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const { data: projectsEnv, isLoading: isLoadingProjects } = useQuery({
    queryKey: ["financial-dashboard-projects"],
    queryFn: () => projectApi.listAccessible(),
    staleTime: 5 * 60_000,
  });
  const projects = projectsEnv?.data ?? [];

  const storedProjectId = hydrated ? readStoredProjectId() : null;
  // Order of precedence: explicit picker selection > localStorage > first accessible.
  const projectId =
    pickerSelection ?? storedProjectId ?? (hydrated ? projects[0]?.id ?? null : null);

  const enabled = !!projectId;
  const pid = projectId ?? "";

  const handlePick = (id: string) => {
    setPickerSelection(id);
    if (typeof window !== "undefined") {
      window.localStorage.setItem(STORAGE_KEY, id);
    }
  };

  const { data: costSummaryEnv } = useQuery({
    queryKey: ["financial-dashboard", pid, "cost-summary"],
    queryFn: () => costApi.getCostSummary(pid),
    enabled,
    staleTime: 60_000,
  });
  const { data: cashFlowEnv, isLoading: isLoadingCashFlow } = useQuery({
    queryKey: ["financial-dashboard", pid, "cash-flow"],
    queryFn: () => costApi.getCashFlowForecast(pid),
    enabled,
    staleTime: 60_000,
  });
  const { data: boqEnv, isLoading: isLoadingBoq } = useQuery({
    queryKey: ["financial-dashboard", pid, "boq"],
    queryFn: () => boqApi.list(pid),
    enabled,
    staleTime: 60_000,
  });
  const { data: raBillsEnv, isLoading: isLoadingBills } = useQuery({
    queryKey: ["financial-dashboard", pid, "ra-bills"],
    queryFn: () => raBillApi.getRaBillsByProject(pid),
    enabled,
    staleTime: 60_000,
  });
  const { data: contractsEnv } = useQuery({
    queryKey: ["financial-dashboard", pid, "contracts"],
    queryFn: () => contractApi.listContracts(pid, 0, 200),
    enabled,
    staleTime: 60_000,
  });
  const { data: evmEnv } = useQuery({
    queryKey: ["financial-dashboard", pid, "evm"],
    queryFn: () => evmKpiApi.getKpis(pid),
    enabled,
    staleTime: 60_000,
  });

  const costSummary = costSummaryEnv?.data ?? undefined;
  const cashFlow = cashFlowEnv?.data ?? undefined;
  const boqSummary = boqEnv?.data ?? undefined;
  const raBills = raBillsEnv?.data ?? undefined;
  const contracts = contractsEnv?.data?.content ?? [];
  const evm = evmEnv?.data ?? undefined;

  // KPI derivations (see the plan §3 for sources).
  const contractValueOriginal = contracts.reduce((s, c) => s + (c.contractValue ?? 0), 0);
  const contractValue = contracts.reduce(
    (s, c) => s + (c.revisedValue ?? c.contractValue ?? 0),
    0,
  );
  const contractDeltaPct =
    contractValueOriginal > 0
      ? ((contractValue - contractValueOriginal) / contractValueOriginal) * 100
      : 0;

  // Approved BOQ = the contracted BOQ value. boqGrandTotal already excludes nothing
  // and matches the screenshot's "Approved BOQ" semantics. The `status` workflow on
  // BoqItem is item-level state, not BOQ-level approval, so it isn't the right filter.
  const approvedBoq = boqSummary?.boqGrandTotal ?? 0;

  const expenditure = costSummary?.totalActual ?? 0;
  const expenditureDelta = useMemo(() => {
    if (!costSummary || costSummary.totalBudget <= 0) return null;
    return ((costSummary.totalActual - costSummary.totalBudget) / costSummary.totalBudget) * 100;
  }, [costSummary]);

  const billingRaised = useMemo(() => billingRaisedTotal(raBills ?? []), [raBills]);
  const pendingRecovery = useMemo(() => pendingRecoveryTotal(raBills ?? []), [raBills]);

  const billingPctOfContract =
    contractValue > 0 ? (billingRaised / contractValue) * 100 : null;
  const recoveryPctOfRaised =
    billingRaised > 0 ? (pendingRecovery / billingRaised) * 100 : null;

  const categoryRows = useMemo(
    () => aggregateBudgetByCategory(boqSummary?.items ?? []),
    [boqSummary],
  );

  const selectedProject = projects.find((p) => p.id === projectId) ?? null;

  const handleRefresh = async () => {
    if (!projectId) return;
    setRefreshing(true);
    await qc.invalidateQueries({ queryKey: ["financial-dashboard", pid] });
    setTimeout(() => setRefreshing(false), 600);
  };

  const handleExport = () => {
    if (!selectedProject) return;
    const kpiBlock = toCsv([
      { metric: "Contract Value", value: contractValue },
      { metric: "Approved BOQ", value: approvedBoq },
      { metric: "Expenditure to Date", value: expenditure },
      { metric: "Billing Raised", value: billingRaised },
      { metric: "Pending Recovery", value: pendingRecovery },
    ]);
    const invoiceBlock = toCsv(
      (raBills ?? []).map((b) => ({
        billNumber: b.billNumber,
        date: b.billPeriodFrom,
        gross: b.grossAmount,
        net: b.netAmount,
        retention: b.retention5Pct ?? 0,
        status: b.status,
      })),
    );
    const categoryBlock = toCsv(
      categoryRows.map((c) => ({
        category: c.category,
        budget: c.budget,
        actual: c.actual,
        variance: c.variance,
        variancePct: c.variancePct,
        itemCount: c.itemCount,
      })),
    );
    const date = new Date().toISOString().slice(0, 10);
    const csv = [
      `KPIs — ${selectedProject.code} ${selectedProject.name}`,
      kpiBlock,
      "",
      "Invoices",
      invoiceBlock || "(none)",
      "",
      "Budget by category",
      categoryBlock || "(none)",
    ].join("\n");
    downloadCsv(`financial-${selectedProject.code}-${date}.csv`, csv);
  };

  return (
    <div>
      {/* HERO HEADER */}
      <div className="relative mb-6 overflow-hidden rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-parchment/40 p-6 shadow-[0_2px_4px_rgba(28,28,28,0.04),0_20px_50px_-30px_rgba(28,28,28,0.18)]">
        <div className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gold/10 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-24 left-1/3 h-56 w-56 rounded-full bg-gold-tint/40 blur-3xl" />

        <div className="relative flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
          <div className="flex items-start gap-4">
            <Link
              href="/dashboards"
              aria-label="Back to dashboards"
              className="mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-hairline bg-paper text-slate shadow-sm transition-all duration-200 hover:-translate-x-0.5 hover:border-gold/40 hover:text-gold-deep hover:shadow-[0_4px_12px_-4px_rgba(212,175,55,0.3)]"
            >
              <ArrowLeft size={16} strokeWidth={1.75} />
            </Link>
            <div className="flex-1">
              <div className="mb-2 flex items-center gap-2">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/40 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
                  <Sparkles size={11} />
                  Finance · project lens
                </span>
              </div>
              <h1
                className="font-display text-[36px] font-semibold leading-[1.05] tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                Financial Dashboard
              </h1>
              <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
                Budget tracking, cost analysis and billing for one project — contract value, expenditure, billing raised, S-curve and category breakdown.
              </p>
            </div>
          </div>

          {/* Toolbar */}
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative">
              <button
                type="button"
                onClick={() => setPickerOpen((v) => !v)}
                onBlur={() => setTimeout(() => setPickerOpen(false), 180)}
                disabled={isLoadingProjects}
                className="inline-flex min-w-[200px] items-center justify-between gap-2 rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep disabled:opacity-50"
              >
                <span className="flex items-center gap-2">
                  <Briefcase size={14} strokeWidth={1.75} />
                  {selectedProject
                    ? `${selectedProject.code} · ${selectedProject.name}`
                    : isLoadingProjects
                      ? "Loading projects…"
                      : "Pick a project"}
                </span>
                <ChevronDown
                  size={12}
                  className={`transition-transform ${pickerOpen ? "rotate-180" : ""}`}
                />
              </button>
              {pickerOpen && projects.length > 0 && (
                <div className="absolute right-0 top-full z-20 mt-1.5 max-h-72 w-72 overflow-auto rounded-xl border border-hairline bg-paper p-1 shadow-[0_12px_32px_-12px_rgba(28,28,28,0.18)]">
                  {projects.map((p) => (
                    <button
                      key={p.id}
                      type="button"
                      onMouseDown={(e) => {
                        e.preventDefault();
                        handlePick(p.id);
                        setPickerOpen(false);
                      }}
                      className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-xs font-medium transition-colors ${
                        p.id === projectId
                          ? "bg-gold-tint/40 text-gold-ink"
                          : "text-charcoal hover:bg-ivory"
                      }`}
                    >
                      <span className="truncate">{p.name}</span>
                      <span className="ml-2 shrink-0 text-[10px] uppercase tracking-wide text-slate">
                        {p.code}
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>

            <button
              type="button"
              onClick={handleRefresh}
              disabled={!projectId}
              className="inline-flex items-center gap-2 rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep disabled:opacity-50"
            >
              <RefreshCw
                size={14}
                strokeWidth={1.75}
                className={refreshing ? "animate-spin" : ""}
              />
              Refresh
            </button>

            <button
              type="button"
              onClick={handleExport}
              disabled={!projectId}
              className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-br from-gold to-gold-deep px-3.5 py-2 text-xs font-semibold text-paper shadow-[0_4px_12px_-2px_rgba(212,175,55,0.45)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_18px_-4px_rgba(212,175,55,0.55)] disabled:opacity-50"
            >
              <Download size={14} strokeWidth={1.75} />
              Export
            </button>
          </div>
        </div>
      </div>

      {!projectId ? (
        <EmptyBlock label="Pick a project to inspect its financials." />
      ) : (
        <>
          <SectionNav sections={sections} />

          <div className="space-y-6">
            {/* KPI strip */}
            <section id="overview" className="scroll-mt-24">
              <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-5">
                <KpiTile
                  label="Contract Value"
                  value={formatINR(contractValue)}
                  hint={contractValueOriginal > 0 && contractValue !== contractValueOriginal
                    ? `Original ${formatINR(contractValueOriginal)}`
                    : `${contracts.length} contract${contracts.length === 1 ? "" : "s"}`}
                  tone="accent"
                  icon={<Landmark size={14} strokeWidth={1.75} />}
                  delta={
                    contractDeltaPct !== 0
                      ? {
                          value: `${contractDeltaPct >= 0 ? "+" : ""}${contractDeltaPct.toFixed(1)}%`,
                          direction: contractDeltaPct > 0 ? "up" : "down",
                        }
                      : undefined
                  }
                />
                <KpiTile
                  label="Approved BOQ"
                  value={formatINR(approvedBoq)}
                  hint={
                    contractValue > 0
                      ? `${((approvedBoq / contractValue) * 100).toFixed(1)}% of contract · ${boqSummary?.items?.length ?? 0} items`
                      : `${boqSummary?.items?.length ?? 0} BOQ items`
                  }
                  tone="default"
                  icon={<FileText size={14} strokeWidth={1.75} />}
                />
                <KpiTile
                  label="Expenditure to Date"
                  value={formatINR(expenditure)}
                  // Budget reference instead of CPI — keeps CPI in one place (the EVM
                  // footer) so two different CPI bases (cost-summary vs EVM) don't appear
                  // on the same screen with conflicting numbers.
                  hint={`of ${formatINR(costSummary?.totalBudget)} budget`}
                  tone={expenditureDelta != null && expenditureDelta > 0 ? "warning" : "default"}
                  icon={<TrendingDown size={14} strokeWidth={1.75} />}
                  delta={
                    expenditureDelta != null
                      ? {
                          // Show absolute magnitude + verdict; arrow already encodes good/bad.
                          value: `${Math.abs(expenditureDelta).toFixed(1)}% ${expenditureDelta > 0 ? "over" : "under"}`,
                          direction: expenditureDelta > 0 ? "down" : "up",
                        }
                      : undefined
                  }
                />
                <KpiTile
                  label="Billing Raised"
                  value={formatINR(billingRaised)}
                  hint={
                    billingPctOfContract != null
                      ? `${formatPct(billingPctOfContract)} of contract`
                      : undefined
                  }
                  tone="success"
                  icon={<Wallet size={14} strokeWidth={1.75} />}
                />
                <KpiTile
                  label="Pending Recovery"
                  value={formatINR(pendingRecovery)}
                  hint={
                    recoveryPctOfRaised != null
                      ? `${formatPct(recoveryPctOfRaised)} of raised`
                      : undefined
                  }
                  tone="warning"
                  icon={<TrendingDown size={14} strokeWidth={1.75} />}
                />
              </div>
            </section>

            <section id="curve" className="scroll-mt-24 grid grid-cols-1 gap-6 xl:grid-cols-3">
              <div className="xl:col-span-2">
                <CostSCurveChart data={cashFlow} isLoading={isLoadingCashFlow} />
              </div>
              <div id="invoices" className="scroll-mt-24 xl:col-span-1">
                <InvoiceSummaryTable
                  bills={raBills}
                  isLoading={isLoadingBills}
                  projectId={projectId}
                />
              </div>
            </section>

            <section id="breakdown" className="scroll-mt-24 grid grid-cols-1 gap-6 xl:grid-cols-3">
              <div className="xl:col-span-2">
                <BudgetByCategoryList rows={categoryRows} isLoading={isLoadingBoq} />
              </div>
              <div id="variance" className="scroll-mt-24 xl:col-span-1">
                <CostVarianceDonut summary={boqSummary} isLoading={isLoadingBoq} />
              </div>
            </section>

            {/* Footer note when EVM rollup is available — keeps the BIPROS habit of */}
            {/* surfacing CPI/SPI right next to financial summaries. */}
            {evm && evm.costPerformanceIndex != null && (
              <div className="rounded-2xl border border-hairline bg-paper px-5 py-3 text-xs text-slate">
                EVM snapshot:{" "}
                <span className="font-semibold text-charcoal">
                  CPI {evm.costPerformanceIndex.toFixed(2)}
                </span>
                {evm.schedulePerformanceIndex != null && (
                  <>
                    {" · "}
                    <span className="font-semibold text-charcoal">
                      SPI {evm.schedulePerformanceIndex.toFixed(2)}
                    </span>
                  </>
                )}
                {evm.estimateAtCompletion != null && (
                  <>
                    {" · EAC "}
                    <span className="font-semibold text-charcoal">
                      {formatINR(evm.estimateAtCompletion)}
                    </span>
                  </>
                )}
                {evm.varianceAtCompletion != null && (
                  <>
                    {" · VAC "}
                    <span className="font-semibold text-charcoal">
                      {formatINR(evm.varianceAtCompletion)}
                    </span>
                  </>
                )}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
