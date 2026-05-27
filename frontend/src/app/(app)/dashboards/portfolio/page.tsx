"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  CalendarDays,
  ChevronDown,
  Download,
  RefreshCw,
  Sparkles,
} from "lucide-react";
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
import { CashFlowOutlookChart } from "@/components/dashboards/portfolio/CashFlowOutlookChart";
import { CompliancePanel } from "@/components/dashboards/portfolio/CompliancePanel";
import { ContractorLeagueChart } from "@/components/dashboards/portfolio/ContractorLeagueChart";
import { CostOverrunChart } from "@/components/dashboards/portfolio/CostOverrunChart";
import { DelayedProjectsChart } from "@/components/dashboards/portfolio/DelayedProjectsChart";
import { EvmRollupChart } from "@/components/dashboards/portfolio/EvmRollupChart";
import { FundingUtilizationChart } from "@/components/dashboards/portfolio/FundingUtilizationChart";
import { PortfolioKpiRow } from "@/components/dashboards/portfolio/PortfolioKpiRow";
import { SectionNav, type SectionNavItem } from "@/components/common/dashboard/SectionNav";
import { PortfolioStatusMix } from "@/components/dashboards/portfolio/PortfolioStatusMix";
import { RiskHeatmapPanel } from "@/components/dashboards/portfolio/RiskHeatmapPanel";
import { ScheduleHealthChart } from "@/components/dashboards/portfolio/ScheduleHealthChart";

const sections: SectionNavItem[] = [
  { id: "overview", label: "Overview" },
  { id: "schedule", label: "Schedule" },
  { id: "cost", label: "Cost" },
  { id: "cash-flow", label: "Cash Flow" },
  { id: "funding", label: "Funding" },
  { id: "risks", label: "Risks" },
  { id: "vendors", label: "Vendors" },
  { id: "compliance", label: "Compliance" },
];

const RANGE_OPTIONS = [
  { id: "30d", label: "Last 30 days" },
  { id: "90d", label: "Last 90 days" },
  { id: "ytd", label: "Year to date" },
  { id: "12m", label: "Last 12 months" },
  { id: "all", label: "All time" },
];

export default function PortfolioDashboardPage() {
  const qc = useQueryClient();
  const [range, setRange] = useState("90d");
  const [rangeOpen, setRangeOpen] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const { data: evmData } = useQuery({
    queryKey: ["portfolio-evm-rollup"],
    queryFn: () => portfolioReportApi.getEvmRollup(),
    staleTime: 60_000,
  });

  const handleRefresh = async () => {
    setRefreshing(true);
    await qc.invalidateQueries();
    setTimeout(() => setRefreshing(false), 600);
  };

  // Auto-refresh every 60 seconds
  useEffect(() => {
    const id = setInterval(() => {
      qc.invalidateQueries({ queryKey: ["portfolio-scorecard"] });
      qc.invalidateQueries({ queryKey: ["portfolio-evm-rollup"] });
    }, 60_000);
    return () => clearInterval(id);
  }, [qc]);

  const handleExport = () => {
    if (!evmData?.data) return;
    const headers = ["Project Code", "Project Name", "PV", "EV", "AC", "CPI", "SPI", "CV", "SV", "EAC", "BAC"];
    const rows = evmData.data.map((r) => [
      r.projectCode,
      r.projectName,
      r.pv,
      r.ev,
      r.ac,
      r.cpi,
      r.spi,
      r.cv,
      r.sv,
      r.eac,
      r.bac,
    ]);
    const csv = [headers.join(","), ...rows.map((r) => r.join(","))].join("\n");
    const blob = new Blob([csv], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `portfolio-evm-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const activeRange = RANGE_OPTIONS.find((o) => o.id === range) ?? RANGE_OPTIONS[1];

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
              className="mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-hairline bg-paper text-slate shadow-sm transition-all duration-200 hover:-translate-x-0.5 hover:border-gold/40 hover:text-gold-deep hover:shadow-[0_4px_12px_-4px_rgba(0,88,202,0.3)]"
            >
              <ArrowLeft size={16} strokeWidth={1.75} />
            </Link>
            <div className="flex-1">
              <div className="mb-2 flex items-center gap-2">
                <span className="inline-flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/40 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
                  <Sparkles size={11} />
                  Portfolio · cross-project scorecard
                </span>
              </div>
              <h1
                className="font-display text-[36px] font-semibold leading-[1.05] tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                Portfolio dashboard
              </h1>
              <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
                Cross-project performance — schedule, cost, cash-flow, funding, risks
                and compliance — at a glance.
              </p>
            </div>
          </div>

          {/* Toolbar */}
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative">
              <button
                type="button"
                onClick={() => setRangeOpen((v) => !v)}
                onBlur={() => setTimeout(() => setRangeOpen(false), 150)}
                className="inline-flex items-center gap-2 rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep"
              >
                <CalendarDays size={14} strokeWidth={1.75} />
                {activeRange.label}
                <ChevronDown
                  size={12}
                  className={`transition-transform ${rangeOpen ? "rotate-180" : ""}`}
                />
              </button>
              {rangeOpen && (
                <div className="absolute right-0 top-full z-20 mt-1.5 w-44 overflow-hidden rounded-xl border border-hairline bg-paper p-1 shadow-[0_12px_32px_-12px_rgba(28,28,28,0.18)]">
                  {RANGE_OPTIONS.map((o) => (
                    <button
                      key={o.id}
                      type="button"
                      onMouseDown={(e) => {
                        e.preventDefault();
                        setRange(o.id);
                        setRangeOpen(false);
                      }}
                      className={`flex w-full items-center rounded-lg px-3 py-2 text-left text-xs font-medium transition-colors ${
                        o.id === range
                          ? "bg-gold-tint/40 text-gold-ink"
                          : "text-charcoal hover:bg-ivory"
                      }`}
                    >
                      {o.label}
                    </button>
                  ))}
                </div>
              )}
            </div>

            <button
              type="button"
              onClick={handleRefresh}
              className="inline-flex items-center gap-2 rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep"
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
              className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-br from-gold to-gold-deep px-3.5 py-2 text-xs font-semibold text-paper shadow-[0_4px_12px_-2px_rgba(0,88,202,0.45)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_18px_-4px_rgba(0,88,202,0.55)]"
            >
              <Download size={14} strokeWidth={1.75} />
              Export
            </button>
          </div>
        </div>
      </div>

      <SectionNav sections={sections} />

      <div className="space-y-6">
        <section id="overview" className="scroll-mt-24 space-y-6">
          <PortfolioKpiRow />
          <PortfolioStatusMix />
        </section>

        <section id="schedule" className="scroll-mt-24">
          <DelayedProjectsChart />
        </section>

        <section id="cost" className="scroll-mt-24 grid grid-cols-1 gap-6 xl:grid-cols-2">
          <CostOverrunChart />
          <EvmRollupChart />
        </section>

        <section id="cash-flow" className="scroll-mt-24">
          <CashFlowOutlookChart />
        </section>

        <section id="funding" className="scroll-mt-24">
          <FundingUtilizationChart />
        </section>

        <section id="risks" className="scroll-mt-24">
          <RiskHeatmapPanel />
        </section>

        <section id="vendors" className="scroll-mt-24">
          <ContractorLeagueChart />
        </section>

        <section
          id="compliance"
          className="scroll-mt-24 grid grid-cols-1 gap-6 xl:grid-cols-2"
        >
          <CompliancePanel />
          <ScheduleHealthChart />
        </section>
      </div>
    </div>
  );
}
