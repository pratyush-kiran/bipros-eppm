"use client";

import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  BarChart3,
  ChevronDown,
  ClipboardList,
  Download,
  DollarSign,
  FileSpreadsheet,
  FileText,
  FolderKanban,
  GitCompare,
  Layers,
  RefreshCw,
  Shield,
  Sparkles,
  TrendingUp,
} from "lucide-react";
import { ProjectReportsCanvas } from "@/components/reports/project-canvas/ProjectReportsCanvas";
import { downloadCsv, toCsv } from "@/lib/utils/csvExport";
import { LineChart, Line, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from "recharts";
import { reportApi } from "@/lib/api/reportApi";
import { reportDataApi } from "@/lib/api/reportDataApi";
import { projectApi } from "@/lib/api/projectApi";
import { resourceApi, type ResourceResponse } from "@/lib/api/resourceApi";
import toast from "react-hot-toast";
import { MonthlyProgressReport } from "@/components/reports/MonthlyProgressReport";
import { EvmReport } from "@/components/reports/EvmReport";
import { CashFlowReport } from "@/components/reports/CashFlowReport";
import { ContractStatusReport } from "@/components/reports/ContractStatusReport";
import { RiskRegisterReport } from "@/components/reports/RiskRegisterReport";
import { ResourceUtilizationReport } from "@/components/reports/ResourceUtilizationReport";
import type { ProjectResponse } from "@/lib/types";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";

interface ReportCard {
  id: string;
  icon: React.ReactNode;
  title: string;
  description: string;
}

const reportCards: ReportCard[] = [
  {
    id: "s-curve",
    icon: <TrendingUp size={32} />,
    title: "S-Curve",
    description: "Planned vs Actual vs Earned value over time",
  },
  {
    id: "resource-histogram",
    icon: <BarChart3 size={32} />,
    title: "Resource Histogram",
    description: "Resource allocation and utilization by date",
  },
  {
    id: "cash-flow",
    icon: <DollarSign size={32} />,
    title: "Cash Flow",
    description: "Project cash inflows and outflows",
  },
  {
    id: "schedule-comparison",
    icon: <GitCompare size={32} />,
    title: "Schedule Comparison",
    description: "Compare baseline vs current schedule",
  },
];

interface SCurveChartData {
  period: string;
  pv: number;
  ev: number;
  ac: number;
}

interface HistogramChartData {
  date: string;
  [key: string]: string | number;
}

interface CashFlowChartData {
  period: string;
  income: number;
  expense: number;
}

interface AllocationItem {
  date: string;
  allocated: Record<string, number>;
}

interface ScheduleComparisonChartData {
  activityCode: string;
  activityName: string;
  baselineStart: string | null;
  baselineFinish: string | null;
  currentStart: string | null;
  currentFinish: string | null;
  startVarianceDays: number;
  finishVarianceDays: number;
}

interface ReportChartData {
  scurve?: SCurveChartData[];
  histogram?: HistogramChartData[];
  cashflow?: CashFlowChartData[];
  scheduleComparison?: ScheduleComparisonChartData[];
}

const TABS: { id: "project" | "standard" | "classic"; label: string; icon: React.ReactNode; hint: string }[] = [
  { id: "project", label: "Project Reports", icon: <FolderKanban size={14} />, hint: "Single-canvas executive view" },
  { id: "standard", label: "Standard Reports", icon: <FileText size={14} />, hint: "Monthly progress, EVM, cash-flow…" },
  { id: "classic", label: "Classic Reports", icon: <Layers size={14} />, hint: "S-curve, histogram, comparison" },
];

export default function ReportsPage() {
  const [activeTab, setActiveTab] = useState<"classic" | "standard" | "project">("project");
  const [generatingReport, setGeneratingReport] = useState<string | null>(null);
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);
  const [selectedResourceId, setSelectedResourceId] = useState<string | null>(null);
  const [reportData, setReportData] = useState<ReportChartData>({});
  const [projectMenuOpen, setProjectMenuOpen] = useState(false);
  const [resourceMenuOpen, setResourceMenuOpen] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const generatedReportRef = useRef<HTMLDivElement | null>(null);
  const qc = useQueryClient();

  const { data: projectsData } = useQuery({
    queryKey: ["projects"],
    queryFn: () => projectApi.listProjects(0, 100),
  });

  const { data: resourcesData } = useQuery({
    queryKey: ["resources"],
    queryFn: () => resourceApi.listResources(0, 100),
  });

  // Standard reports queries. The backend /v1/reports/* endpoints currently 500
  // for every report type, so we disable retry to avoid 6*3 = 18 network errors
  // and surface a clean empty state below instead of crashing the page.
  const { data: monthlyProgressData, isLoading: monthlyProgressLoading, isError: monthlyProgressError } = useQuery({
    queryKey: ["report-monthly-progress", selectedProjectId],
    queryFn: () => selectedProjectId ? reportDataApi.getMonthlyProgress(selectedProjectId, new Date().toISOString().slice(0, 7)) : Promise.resolve(null),
    enabled: !!selectedProjectId && activeTab === "standard",
    retry: false,
  });

  const { data: evmReportData, isLoading: evmReportLoading, isError: evmReportError } = useQuery({
    queryKey: ["report-evm", selectedProjectId],
    queryFn: () => selectedProjectId ? reportDataApi.getEvmReport(selectedProjectId) : Promise.resolve(null),
    enabled: !!selectedProjectId && activeTab === "standard",
    retry: false,
  });

  const { data: cashFlowData, isLoading: cashFlowLoading, isError: cashFlowError } = useQuery({
    queryKey: ["report-cash-flow", selectedProjectId],
    queryFn: () => selectedProjectId ? reportDataApi.getCashFlowReport(selectedProjectId) : Promise.resolve(null),
    enabled: !!selectedProjectId && activeTab === "standard",
    retry: false,
  });

  const { data: contractStatusData, isLoading: contractStatusLoading, isError: contractStatusError } = useQuery({
    queryKey: ["report-contract-status", selectedProjectId],
    queryFn: () => selectedProjectId ? reportDataApi.getContractStatus(selectedProjectId) : Promise.resolve(null),
    enabled: !!selectedProjectId && activeTab === "standard",
    retry: false,
  });

  const { data: riskRegisterData, isLoading: riskRegisterLoading, isError: riskRegisterError } = useQuery({
    queryKey: ["report-risk-register", selectedProjectId],
    queryFn: () => selectedProjectId ? reportDataApi.getRiskRegister(selectedProjectId) : Promise.resolve(null),
    enabled: !!selectedProjectId && activeTab === "standard",
    retry: false,
  });

  const { data: resourceUtilizationData, isLoading: resourceUtilizationLoading, isError: resourceUtilizationError } = useQuery({
    queryKey: ["report-resource-utilization", selectedProjectId],
    queryFn: () => selectedProjectId ? reportDataApi.getResourceUtilization(selectedProjectId) : Promise.resolve(null),
    enabled: !!selectedProjectId && activeTab === "standard",
    retry: false,
  });

  // True when every standard-report query resolved (success or failure) and
  // none of them returned data — used to render the empty-state card.
  const standardReportsAllSettled =
    !!selectedProjectId &&
    !monthlyProgressLoading &&
    !evmReportLoading &&
    !cashFlowLoading &&
    !contractStatusLoading &&
    !riskRegisterLoading &&
    !resourceUtilizationLoading;

  const standardReportsAllEmpty =
    standardReportsAllSettled &&
    !monthlyProgressData &&
    !evmReportData &&
    !cashFlowData &&
    !contractStatusData &&
    !riskRegisterData &&
    !resourceUtilizationData;

  const standardReportsAllFailed =
    standardReportsAllEmpty &&
    monthlyProgressError &&
    evmReportError &&
    cashFlowError &&
    contractStatusError &&
    riskRegisterError &&
    resourceUtilizationError;

  const projects = projectsData?.data?.content ?? [];
  // resourceApi.listResources returns a flat array (List<ResourceResponse>),
  // not a paged response. Be defensive in case that ever changes.
  const rawResources = resourcesData?.data as unknown;
  const resources: ResourceResponse[] = Array.isArray(rawResources)
    ? (rawResources as ResourceResponse[])
    : ((rawResources as { content?: ResourceResponse[] } | undefined)?.content ?? []);

  const selectedProject = projects.find(
    (p: ProjectResponse) => p.id === selectedProjectId,
  );

  const exportFileStem = (reportId: string) => {
    const code = selectedProject?.code ?? selectedProjectId ?? "project";
    const date = new Date().toISOString().slice(0, 10);
    return `${code}_${reportId}_${date}`.replace(/[^a-zA-Z0-9_-]/g, "_");
  };

  const exportCurrentReportCsv = () => {
    if (reportData.scurve) {
      downloadCsv(
        exportFileStem("s-curve"),
        toCsv(reportData.scurve, [
          { key: "period", header: "Period" },
          { key: "pv", header: "Planned Value" },
          { key: "ev", header: "Earned Value" },
          { key: "ac", header: "Actual Cost" },
        ]),
      );
      return;
    }
    if (reportData.histogram) {
      const rows = reportData.histogram;
      const resourceCols = Object.keys(rows[0] ?? {}).filter((k) => k !== "date");
      const cols = [
        { key: "date", header: "Date" },
        ...resourceCols.map((k) => ({ key: k, header: k })),
      ];
      downloadCsv(exportFileStem("resource-histogram"), toCsv(rows, cols));
      return;
    }
    if (reportData.cashflow) {
      downloadCsv(
        exportFileStem("cash-flow"),
        toCsv(reportData.cashflow, [
          { key: "period", header: "Period" },
          { key: "income", header: "Income" },
          { key: "expense", header: "Expense" },
        ]),
      );
      return;
    }
    if (reportData.scheduleComparison) {
      downloadCsv(
        exportFileStem("schedule-comparison"),
        toCsv(reportData.scheduleComparison, [
          { key: "activityCode", header: "Activity Code" },
          { key: "activityName", header: "Activity Name" },
          { key: "baselineStart", header: "Baseline Start" },
          { key: "currentStart", header: "Current Start" },
          { key: "startVarianceDays", header: "Start Variance (days)" },
          { key: "baselineFinish", header: "Baseline Finish" },
          { key: "currentFinish", header: "Current Finish" },
          { key: "finishVarianceDays", header: "Finish Variance (days)" },
        ]),
      );
      return;
    }
    toast.error("Nothing to export yet — generate a report first");
  };

  const clearGeneratedReport = () => setReportData({});

  const handleGenerateReport = async (reportId: string) => {
    setGeneratingReport(reportId);
    try {
      if (reportId === "s-curve" && selectedProjectId) {
        const response = await reportApi.generateSCurve(selectedProjectId);
        if (response.data) {
          const data = response.data;
          const chartData: SCurveChartData[] = data.periods.map((period: string, idx: number) => ({
            period,
            pv: data.plangedCumulativeValue[idx] ?? 0,
            ev: data.earnedCumulativeValue[idx] ?? 0,
            ac: data.actualCumulativeValue[idx] ?? 0,
          }));
          setReportData({ scurve: chartData });
        }
      } else if (reportId === "resource-histogram" && selectedProjectId) {
        const response = await reportApi.generateResourceHistogram(
          selectedProjectId,
          selectedResourceId || undefined
        );
        if (response.data) {
          const data = response.data;
          const chartData: HistogramChartData[] = data.allocations.map((a: AllocationItem) => ({
            date: a.date,
            ...a.allocated,
          }));
          setReportData({ histogram: chartData });
        }
      } else if (reportId === "cash-flow" && selectedProjectId) {
        const response = await reportApi.generateCashFlow(selectedProjectId);
        if (response.data) {
          const data = response.data;
          const chartData: CashFlowChartData[] = data.periods.map((period: string, idx: number) => ({
            period,
            income: data.inflows[idx] ?? 0,
            expense: data.outflows[idx] ?? 0,
          }));
          setReportData({ cashflow: chartData });
        }
      } else if (reportId === "schedule-comparison" && selectedProjectId) {
        const response = await reportApi.generateScheduleComparison(selectedProjectId);
        if (response.data) {
          const data = response.data as { activities?: ScheduleComparisonChartData[] };
          setReportData({ scheduleComparison: data.activities ?? [] });
        }
      }
    } catch {
      toast.error("Failed to generate report");
    } finally {
      setGeneratingReport(null);
      // Scroll the rendered result into view on the next frame so the user
      // immediately sees what they clicked for, instead of a silent state change
      // that happens below the fold.
      requestAnimationFrame(() => {
        generatedReportRef.current?.scrollIntoView({
          behavior: "smooth",
          block: "start",
        });
      });
    }
  };

  const selectedResource = resources.find((r) => r.id === selectedResourceId);
  const handleRefresh = async () => {
    setRefreshing(true);
    await qc.invalidateQueries();
    setTimeout(() => setRefreshing(false), 600);
  };

  return (
    <div>
      {/* HERO HEADER */}
      {/* NOTE: outer wrapper deliberately does NOT use overflow-hidden so the project
          picker dropdown can extend below the hero. Decorative gold blurs are clipped
          by their own inner overflow-hidden layer instead. */}
      <div className="relative mb-6 rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-parchment/40 p-6 shadow-[0_2px_4px_rgba(28,28,28,0.04),0_20px_50px_-30px_rgba(28,28,28,0.18)]">
        <div className="pointer-events-none absolute inset-0 overflow-hidden rounded-2xl">
          <div className="absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gold/10 blur-3xl" />
          <div className="absolute -bottom-24 left-1/3 h-56 w-56 rounded-full bg-gold-tint/40 blur-3xl" />
        </div>

        <div className="relative flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
          <div className="flex-1">
            <div className="mb-2 flex items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-full border border-gold/30 bg-gold-tint/40 px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-ink">
                <Sparkles size={11} />
                Reports · project insights
              </span>
            </div>
            <h1
              className="font-display text-[36px] font-semibold leading-[1.05] tracking-tight text-charcoal"
              style={{ fontVariationSettings: "'opsz' 144" }}
            >
              Project & programme reports
            </h1>
            <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
              Single-canvas executive view, monthly progress packs, and classic
              schedule/cost analytics — pick a project to begin.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/* Project picker */}
            <div className="relative">
              <button
                type="button"
                onClick={() => setProjectMenuOpen((v) => !v)}
                onBlur={() => setTimeout(() => setProjectMenuOpen(false), 150)}
                className={`inline-flex min-w-[260px] items-center justify-between gap-2 rounded-xl border bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors ${
                  selectedProject
                    ? "border-gold/40 hover:border-gold-deep"
                    : "border-hairline hover:border-gold/40 hover:text-gold-deep"
                }`}
              >
                <span className="flex items-center gap-2 truncate">
                  <ClipboardList size={14} strokeWidth={1.75} />
                  {selectedProject ? (
                    <>
                      <span className="font-mono text-[10px] text-slate">
                        {selectedProject.code}
                      </span>
                      <span className="truncate">{selectedProject.name}</span>
                    </>
                  ) : (
                    <span className="text-slate">Select a project…</span>
                  )}
                </span>
                <ChevronDown
                  size={12}
                  className={`shrink-0 transition-transform ${projectMenuOpen ? "rotate-180" : ""}`}
                />
              </button>
              {projectMenuOpen && (
                <div className="absolute right-0 top-full z-30 mt-1.5 flex w-[360px] flex-col rounded-xl border border-hairline bg-paper shadow-[0_12px_32px_-12px_rgba(28,28,28,0.18)]">
                  <div className="flex items-center justify-between border-b border-hairline px-3 py-2">
                    <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                      {projects.length} project{projects.length === 1 ? "" : "s"}
                    </span>
                    <span className="text-[10px] text-ash">Scroll for more</span>
                  </div>
                  <div className="max-h-[60vh] min-h-0 overflow-y-auto overscroll-contain p-1">
                    {projects.length === 0 && (
                      <div className="px-3 py-4 text-center text-xs text-slate">
                        No projects available
                      </div>
                    )}
                    {projects.map((p) => (
                      <button
                        key={p.id}
                        type="button"
                        onMouseDown={(e) => {
                          e.preventDefault();
                          setSelectedProjectId(p.id);
                          setSelectedResourceId(null);
                          setProjectMenuOpen(false);
                        }}
                        className={`flex w-full flex-col items-start gap-0.5 rounded-lg px-3 py-2 text-left transition-colors ${
                          p.id === selectedProjectId
                            ? "bg-gold-tint/40 text-gold-ink"
                            : "text-charcoal hover:bg-ivory"
                        }`}
                      >
                        <span className="font-mono text-[10px] uppercase tracking-wide text-slate">
                          {p.code}
                        </span>
                        <span className="text-xs font-medium">{p.name}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {/* Resource picker (only shown after a project is chosen) */}
            {selectedProjectId && (
              <div className="relative">
                <button
                  type="button"
                  onClick={() => setResourceMenuOpen((v) => !v)}
                  onBlur={() => setTimeout(() => setResourceMenuOpen(false), 150)}
                  className="inline-flex items-center gap-2 rounded-xl border border-hairline bg-paper px-3.5 py-2 text-xs font-semibold text-charcoal shadow-sm transition-colors hover:border-gold/40 hover:text-gold-deep"
                >
                  <BarChart3 size={14} strokeWidth={1.75} />
                  {selectedResource ? selectedResource.name : "All resources"}
                  <ChevronDown
                    size={12}
                    className={`transition-transform ${resourceMenuOpen ? "rotate-180" : ""}`}
                  />
                </button>
                {resourceMenuOpen && (
                  <div className="absolute right-0 top-full z-30 mt-1.5 flex w-64 flex-col rounded-xl border border-hairline bg-paper shadow-[0_12px_32px_-12px_rgba(28,28,28,0.18)]">
                    <div className="flex items-center justify-between border-b border-hairline px-3 py-2">
                      <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                        {resources.length} resource{resources.length === 1 ? "" : "s"}
                      </span>
                      <span className="text-[10px] text-ash">Scroll for more</span>
                    </div>
                    <div className="max-h-[60vh] min-h-0 overflow-y-auto overscroll-contain p-1">
                      <button
                        type="button"
                        onMouseDown={(e) => {
                          e.preventDefault();
                          setSelectedResourceId(null);
                          setResourceMenuOpen(false);
                        }}
                        className={`flex w-full items-center rounded-lg px-3 py-2 text-left text-xs font-medium transition-colors ${
                          !selectedResourceId
                            ? "bg-gold-tint/40 text-gold-ink"
                            : "text-charcoal hover:bg-ivory"
                        }`}
                      >
                        All resources
                      </button>
                      {resources.map((r) => (
                        <button
                          key={r.id}
                          type="button"
                          onMouseDown={(e) => {
                            e.preventDefault();
                            setSelectedResourceId(r.id);
                            setResourceMenuOpen(false);
                          }}
                          className={`flex w-full flex-col items-start gap-0.5 rounded-lg px-3 py-2 text-left transition-colors ${
                            r.id === selectedResourceId
                              ? "bg-gold-tint/40 text-gold-ink"
                              : "text-charcoal hover:bg-ivory"
                          }`}
                        >
                          <span className="font-mono text-[10px] uppercase tracking-wide text-slate">
                            {r.code}
                          </span>
                          <span className="text-xs">{r.name}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}

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
              onClick={
                activeTab === "classic" ? exportCurrentReportCsv : () => toast("Use a Classic report to export CSV")
              }
              className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-br from-gold to-gold-deep px-3.5 py-2 text-xs font-semibold text-paper shadow-[0_4px_12px_-2px_rgba(0,88,202,0.45)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_18px_-4px_rgba(0,88,202,0.55)]"
            >
              <Download size={14} strokeWidth={1.75} />
              Export
            </button>
          </div>
        </div>
      </div>

      {/* PREMIUM TAB STRIP */}
      <div className="mb-6 rounded-2xl border border-hairline bg-paper p-1.5 shadow-[0_1px_2px_rgba(28,28,28,0.03)]">
        <div className="grid grid-cols-1 gap-1 sm:grid-cols-3">
          {TABS.map((t) => {
            const isActive = activeTab === t.id;
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => setActiveTab(t.id)}
                className={`group relative flex items-start gap-3 overflow-hidden rounded-xl px-4 py-3 text-left transition-all duration-200 ${
                  isActive
                    ? "bg-gradient-to-br from-gold-tint/60 via-paper to-paper ring-1 ring-gold/30 shadow-[0_4px_14px_-6px_rgba(0,88,202,0.35)]"
                    : "hover:bg-ivory"
                }`}
              >
                <span
                  className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border transition-colors ${
                    isActive
                      ? "border-gold/40 bg-gradient-to-br from-gold to-gold-deep text-paper shadow-[0_4px_10px_-4px_rgba(0,88,202,0.5)]"
                      : "border-hairline bg-ivory text-slate group-hover:text-gold-deep"
                  }`}
                >
                  {t.icon}
                </span>
                <span className="flex flex-col">
                  <span
                    className={`text-sm font-semibold leading-tight ${
                      isActive ? "text-charcoal" : "text-charcoal"
                    }`}
                  >
                    {t.label}
                  </span>
                  <span className="text-[11px] leading-snug text-slate">{t.hint}</span>
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Project Reports Tab — single scrollable canvas */}
      {activeTab === "project" && (
        <>
          {!selectedProjectId ? (
            <NoProjectSelected
              title="Pick a project to load its report canvas"
              hint="Use the project picker in the header. The canvas pulls Status, Tasks, Schedule, Cost, EVM, Resources, Risks, Milestones, Bills & Compliance into one scrollable view."
            />
          ) : (
            <ProjectReportsCanvas projectId={selectedProjectId} />
          )}
        </>
      )}

      {/* Standard Reports Tab */}
      {activeTab === "standard" && (
        <div className="space-y-6">
          {/* Featured: Variance report (P6-style) */}
          <a
            href="/reports/variance"
            className="group relative block overflow-hidden rounded-2xl border border-gold/40 bg-gradient-to-br from-paper via-ivory to-gold-tint/30 p-6 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_10px_30px_rgba(28,28,28,0.08)]"
          >
            <div
              aria-hidden
              className="pointer-events-none absolute -right-10 -top-10 h-32 w-32 rounded-full bg-gold/15 blur-3xl"
            />
            <div className="relative flex items-center gap-5">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gold-tint text-gold-deep ring-1 ring-gold/30 transition-all duration-200 group-hover:bg-gold group-hover:text-paper group-hover:ring-gold">
                <GitCompare size={22} strokeWidth={1.75} />
              </div>
              <div className="flex-1">
                <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                  P6-style report
                </div>
                <div className="mt-0.5 font-display text-lg font-semibold tracking-tight text-charcoal">
                  Variance report — schedule &amp; cost
                </div>
                <p className="mt-1 max-w-[640px] text-sm leading-relaxed text-slate">
                  Compare the live programme against an assigned baseline. Slip / on-track / ahead
                  by activity and milestone, plus EVM-driven cost variance. Filterable, sortable, CSV-exportable.
                </p>
              </div>
              <span className="hidden h-9 w-9 items-center justify-center rounded-full border border-hairline text-slate transition-all duration-200 group-hover:border-gold group-hover:text-gold-deep group-hover:translate-x-0.5 sm:inline-flex">
                →
              </span>
            </div>
          </a>

          {/* Featured: Cross-project Risk Register */}
          <a
            href="/reports/risk-register"
            className="group relative block overflow-hidden rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-burgundy/5 p-6 transition-all duration-200 hover:-translate-y-0.5 hover:border-burgundy/30 hover:shadow-[0_10px_30px_rgba(28,28,28,0.08)]"
          >
            <div
              aria-hidden
              className="pointer-events-none absolute -right-10 -top-10 h-32 w-32 rounded-full bg-burgundy/10 blur-3xl"
            />
            <div className="relative flex items-center gap-5">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-burgundy/10 text-burgundy ring-1 ring-burgundy/20 transition-all duration-200 group-hover:bg-burgundy group-hover:text-paper group-hover:ring-burgundy">
                <Shield size={22} strokeWidth={1.75} />
              </div>
              <div className="flex-1">
                <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-burgundy">
                  Portfolio view
                </div>
                <div className="mt-0.5 font-display text-lg font-semibold tracking-tight text-charcoal">
                  Cross-project Risk Register
                </div>
                <p className="mt-1 max-w-[640px] text-sm leading-relaxed text-slate">
                  View and manage risks across all projects from a single register. Includes 5×5 risk matrix,
                  triggered risk alerts, and library imports.
                </p>
              </div>
              <span className="hidden h-9 w-9 items-center justify-center rounded-full border border-hairline text-slate transition-all duration-200 group-hover:border-burgundy group-hover:text-burgundy group-hover:translate-x-0.5 sm:inline-flex">
                →
              </span>
            </div>
          </a>

          {/* Featured: Capacity Utilization (multi-sheet Excel export) */}
          <a
            href="/reports/capacity-utilization"
            className="group relative block overflow-hidden rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-gold-tint/30 p-6 transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_10px_30px_rgba(28,28,28,0.08)]"
          >
            <div
              aria-hidden
              className="pointer-events-none absolute -right-10 -top-10 h-32 w-32 rounded-full bg-gold/15 blur-3xl"
            />
            <div className="relative flex items-center gap-5">
              <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-gold-tint text-gold-deep ring-1 ring-gold/30 transition-all duration-200 group-hover:bg-gold group-hover:text-paper group-hover:ring-gold">
                <FileSpreadsheet size={22} strokeWidth={1.75} />
              </div>
              <div className="flex-1">
                <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                  Excel export
                </div>
                <div className="mt-0.5 font-display text-lg font-semibold tracking-tight text-charcoal">
                  Resource Capacity Utilization Report
                </div>
                <p className="mt-1 max-w-[640px] text-sm leading-relaxed text-slate">
                  5-sheet workbook (Plant utilization, Manpower utilization, SUMMARY,
                  Daily Deployment, DPR) for the selected project &amp; month — matches
                  the Capacity_Utilization construction template.
                </p>
              </div>
              <span className="hidden h-9 w-9 items-center justify-center rounded-full border border-hairline text-slate transition-all duration-200 group-hover:border-gold group-hover:text-gold-deep group-hover:translate-x-0.5 sm:inline-flex">
                →
              </span>
            </div>
          </a>

          {monthlyProgressData && !monthlyProgressLoading && (
            <div className="rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
              <MonthlyProgressReport data={monthlyProgressData as any} />
            </div>
          )}

          {evmReportData && !evmReportLoading && (
            <div className="rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
              <EvmReport data={evmReportData as any} />
            </div>
          )}

          {cashFlowData && !cashFlowLoading && (
            <div className="rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
              <CashFlowReport data={cashFlowData as any} />
            </div>
          )}

          {contractStatusData && !contractStatusLoading && (
            <div className="rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
              <ContractStatusReport data={contractStatusData as any} />
            </div>
          )}

          {riskRegisterData && !riskRegisterLoading && (
            <div className="rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
              <RiskRegisterReport data={riskRegisterData as any} />
            </div>
          )}

          {resourceUtilizationData && !resourceUtilizationLoading && (
            <div className="rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
              <ResourceUtilizationReport data={resourceUtilizationData as any} />
            </div>
          )}

          {!selectedProjectId && (
            <NoProjectSelected
              title="Pick a project to view standard reports"
              hint="Standard reports include Monthly Progress, EVM Analysis, Cash Flow, Contract Status, Risk Register and Resource Utilization."
            />
          )}

          {selectedProjectId && (monthlyProgressLoading || evmReportLoading || cashFlowLoading || contractStatusLoading || riskRegisterLoading || resourceUtilizationLoading) && (
            <div className="flex items-center justify-center rounded-2xl border border-dashed border-hairline bg-ivory/40 p-8 text-sm text-slate">
              <span className="inline-flex items-center gap-2">
                <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-gold" />
                Loading reports…
              </span>
            </div>
          )}

          {standardReportsAllFailed && (
            <div className="rounded-2xl border border-burgundy/25 bg-burgundy/5 p-6">
              <p className="mb-1 font-semibold text-burgundy">
                Standard reports are temporarily unavailable
              </p>
              <p className="text-sm text-slate">
                The reporting service returned errors for every report type. This usually
                means the backend <code className="rounded bg-paper px-1 font-mono text-[11px]">/v1/reports/*</code>{" "}
                endpoints are offline or misconfigured. Try the Classic Reports tab, or retry
                once the service is back online.
              </p>
            </div>
          )}

          {standardReportsAllEmpty && !standardReportsAllFailed && (
            <div className="rounded-2xl border border-hairline bg-ivory/40 p-6 text-center text-sm text-slate">
              No standard reports available for this project
            </div>
          )}
        </div>
      )}

      {/* Classic Reports Tab */}
      {activeTab === "classic" && (
        <div className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
          {reportCards.map((card) => (
            <div
              key={card.id}
              className="group relative overflow-hidden rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04)] transition-all duration-200 hover:-translate-y-0.5 hover:border-gold/30 hover:shadow-[0_12px_32px_-14px_rgba(0,88,202,0.25)]"
            >
              <div className="pointer-events-none absolute -right-12 -top-12 h-28 w-28 rounded-full bg-gold/8 opacity-0 blur-2xl transition-opacity duration-300 group-hover:opacity-100" />
              <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl border border-gold/25 bg-gradient-to-br from-gold-tint/60 to-paper text-gold-deep shadow-sm">
                {card.icon}
              </div>
              <h3
                className="mb-1 font-display text-lg font-semibold leading-tight tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                {card.title}
              </h3>
              <p className="mb-5 text-sm leading-relaxed text-slate">{card.description}</p>
              <button
                onClick={() => handleGenerateReport(card.id)}
                disabled={generatingReport === card.id || !selectedProjectId}
                className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-br from-gold to-gold-deep px-4 py-2 text-xs font-semibold text-paper shadow-[0_4px_12px_-2px_rgba(0,88,202,0.45)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_18px_-4px_rgba(0,88,202,0.55)] disabled:translate-y-0 disabled:cursor-not-allowed disabled:bg-none disabled:bg-ivory disabled:text-slate disabled:shadow-none"
              >
                {generatingReport === card.id ? (
                  <>
                    <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-current" />
                    Generating…
                  </>
                ) : (
                  <>
                    <TrendingUp size={12} strokeWidth={2} />
                    {selectedProjectId ? "Generate" : "Pick a project first"}
                  </>
                )}
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Classic Report Output */}
      {activeTab === "classic" && (reportData.scurve || reportData.histogram || reportData.cashflow || reportData.scheduleComparison) && (
        <div
          ref={generatedReportRef}
          className="mt-6 scroll-mt-4 rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_12px_32px_-14px_rgba(28,28,28,0.12)]"
        >
          <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
            <div>
              <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                Output
              </div>
              <h2
                className="font-display text-xl font-semibold leading-tight tracking-tight text-charcoal"
                style={{ fontVariationSettings: "'opsz' 144" }}
              >
                Generated report
              </h2>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={clearGeneratedReport}
                className="inline-flex items-center gap-1.5 rounded-xl border border-hairline bg-paper px-3 py-1.5 text-xs font-semibold text-slate transition-colors hover:border-gold/40 hover:text-gold-deep"
              >
                <ArrowLeft size={12} />
                Back to reports
              </button>
              <button
                onClick={exportCurrentReportCsv}
                className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-br from-gold to-gold-deep px-3 py-1.5 text-xs font-semibold text-paper shadow-[0_4px_12px_-2px_rgba(0,88,202,0.45)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_18px_-4px_rgba(0,88,202,0.55)]"
              >
                <Download size={12} />
                Download CSV
              </button>
            </div>
          </div>
          {reportData.scurve && (
            <div className="mb-8">
              <h3 className="mb-4 font-semibold text-text-secondary">S-Curve Analysis</h3>
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={reportData.scurve}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="period" />
                  <YAxis />
                  <Tooltip contentStyle={{ backgroundColor: "#1e293b", border: "1px solid #334155", borderRadius: "8px", color: "#e2e8f0" }} />
                  <Legend />
                  <Line type="monotone" dataKey="pv" stroke="#3b82f6" name="Planned Value" />
                  <Line type="monotone" dataKey="ev" stroke="#10b981" name="Earned Value" />
                  <Line type="monotone" dataKey="ac" stroke="#ef4444" name="Actual Cost" />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
          {reportData.histogram && (
            <div className="mb-8">
              <h3 className="mb-4 font-semibold text-text-secondary">Resource Histogram</h3>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={reportData.histogram}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" />
                  <YAxis />
                  <Tooltip contentStyle={{ backgroundColor: "#1e293b", border: "1px solid #334155", borderRadius: "8px", color: "#e2e8f0" }} />
                  <Legend />
                  {reportData.histogram.length > 0 &&
                    Object.keys(reportData.histogram[0])
                      .filter((k) => k !== "date")
                      .map((key, idx) => (
                        <Bar key={key} dataKey={key} fill={["#3b82f6", "#10b981", "#f59e0b"][idx % 3]} />
                      ))}
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
          {reportData.cashflow && (
            <div className="mb-6">
              <h3 className="mb-4 font-semibold text-text-secondary">Cash Flow Analysis</h3>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={reportData.cashflow}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="period" />
                  <YAxis />
                  <Tooltip contentStyle={{ backgroundColor: "#1e293b", border: "1px solid #334155", borderRadius: "8px", color: "#e2e8f0" }} />
                  <Legend />
                  <Bar dataKey="income" fill="#10b981" name="Income" />
                  <Bar dataKey="expense" fill="#ef4444" name="Expense" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
          {reportData.scheduleComparison && (
            <div className="mb-6">
              <h3 className="mb-4 font-semibold text-text-secondary">Schedule Comparison</h3>
              {reportData.scheduleComparison.length === 0 ? (
                <p className="text-sm text-text-secondary">No schedule comparison data available. Create a baseline first.</p>
              ) : (
                <SimpleTable
                  columns={[
                    { accessorKey: "activityCode", header: "Activity", cell: ({ row }) => <span className="font-mono">{row.original.activityCode}</span> },
                    { accessorKey: "baselineStart", header: "Baseline Start", cell: ({ row }) => row.original.baselineStart ?? "N/A" },
                    { accessorKey: "currentStart", header: "Current Start", cell: ({ row }) => row.original.currentStart ?? "N/A" },
                    {
                      accessorKey: "startVarianceDays",
                      header: "Start Var (days)",
                      cell: ({ row }) => (
                        <span className={`font-mono ${row.original.startVarianceDays > 0 ? "text-danger" : row.original.startVarianceDays < 0 ? "text-success" : ""}`}>
                          {row.original.startVarianceDays > 0 ? `+${row.original.startVarianceDays}` : row.original.startVarianceDays}
                        </span>
                      ),
                    },
                    { accessorKey: "baselineFinish", header: "Baseline Finish", cell: ({ row }) => row.original.baselineFinish ?? "N/A" },
                    { accessorKey: "currentFinish", header: "Current Finish", cell: ({ row }) => row.original.currentFinish ?? "N/A" },
                    {
                      accessorKey: "finishVarianceDays",
                      header: "Finish Var (days)",
                      cell: ({ row }) => (
                        <span className={`font-mono ${row.original.finishVarianceDays > 0 ? "text-danger" : row.original.finishVarianceDays < 0 ? "text-success" : ""}`}>
                          {row.original.finishVarianceDays > 0 ? `+${row.original.finishVarianceDays}` : row.original.finishVarianceDays}
                        </span>
                      ),
                    },
                  ]}
                  data={reportData.scheduleComparison}
                />
              )}
            </div>
          )}
        </div>
      )}

    </div>
  );
}

function NoProjectSelected({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="relative overflow-hidden rounded-2xl border border-hairline bg-gradient-to-br from-paper via-ivory to-parchment/40 p-10 text-center shadow-[0_1px_2px_rgba(28,28,28,0.04),0_12px_32px_-14px_rgba(28,28,28,0.12)]">
      <div className="pointer-events-none absolute -right-20 -top-20 h-56 w-56 rounded-full bg-gold/10 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-20 left-1/3 h-44 w-44 rounded-full bg-gold-tint/40 blur-3xl" />
      <div className="relative mx-auto flex h-12 w-12 items-center justify-center rounded-2xl border border-gold/30 bg-gold-tint/40 text-gold-deep">
        <ClipboardList size={20} strokeWidth={1.75} />
      </div>
      <h3
        className="relative mt-4 font-display text-xl font-semibold leading-tight tracking-tight text-charcoal"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {title}
      </h3>
      <p className="relative mx-auto mt-2 max-w-md text-sm leading-relaxed text-slate">
        {hint}
      </p>
    </div>
  );
}
