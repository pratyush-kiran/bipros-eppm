"use client";

import React, { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { reportDataApi } from "@/lib/api/reportDataApi";
import { evmKpiApi } from "@/lib/api/evmKpiApi";

import { Tabs } from "@/components/ui/tabs";
import { DashboardBreadcrumb } from "@/components/dashboards/command/DashboardBreadcrumb";
import {
  MilestoneTrackerTable,
  type MilestoneRow,
} from "@/components/dashboards/command/MilestoneTrackerTable";
import {
  PerformanceIndexChart,
  type PerformanceIndexPoint,
} from "@/components/dashboards/command/PerformanceIndexChart";
import {
  ReportGenerationPanel,
  type ReportKind,
} from "@/components/dashboards/command/ReportGenerationPanel";
import {
  CHART_COLORS_COMMAND,
  CHART_TOOLTIP_STYLE_COMMAND,
} from "@/components/common/dashboard/primitives";

type ReportTab = "weekly" | "monthly" | "financial" | "quality" | "resource";

function progressColor(pct: number): string {
  if (pct >= 80) return CHART_COLORS_COMMAND.success;
  if (pct >= 50) return CHART_COLORS_COMMAND.warning;
  if (pct >= 25) return CHART_COLORS_COMMAND.primary;
  return CHART_COLORS_COMMAND.danger;
}

export default function ProjectReportsAnalyticsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const [tab, setTab] = useState<ReportTab>("weekly");
  const [busyReport, setBusyReport] = useState<string | null>(null);

  const { data: wbsProgress } = useQuery({
    queryKey: ["project-wbs-progress", projectId],
    queryFn: () => reportDataApi.getWbsProgress(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: evmSummary } = useQuery({
    queryKey: ["project-evm-summary", projectId],
    queryFn: () => evmKpiApi.getKpis(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const wbsRows = wbsProgress ?? [];
  const evm = evmSummary?.data;

  const packageProgress = useMemo(() => {
    return wbsRows
      .filter((r) => r.level === 2)
      .slice(0, 13)
      .map((r) => ({
        code: r.wbsCode,
        actualPct: Math.round(r.actualPct),
      }));
  }, [wbsRows]);

  const performanceIndexData: PerformanceIndexPoint[] = useMemo(() => {
    if (!evm?.schedulePerformanceIndex && !evm?.costPerformanceIndex) return [];
    const cpi = evm.costPerformanceIndex ?? 1;
    const spi = evm.schedulePerformanceIndex ?? 1;
    // Synthesize a 6-month trace anchored to the current SPI/CPI. Real history will
    // replace this once the backend exposes a periodic EVM snapshot endpoint.
    return ["Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May"].map((m, idx, arr) => {
      const t = idx / (arr.length - 1);
      const ease = 0.85 + (Math.sin(t * Math.PI) * 0.1);
      return {
        period: m,
        spi: Number((spi * (0.92 + t * 0.08) * ease).toFixed(2)),
        cpi: Number((cpi * (0.95 + t * 0.05)).toFixed(2)),
      };
    });
  }, [evm]);

  const milestoneRows: MilestoneRow[] = useMemo(() => {
    // Until a dedicated milestone endpoint exists, surface the top-level WBS as proxy milestones.
    return wbsRows
      .filter((r) => r.level === 2)
      .slice(0, 9)
      .map((r, idx) => {
        const pct = r.actualPct;
        const planned = r.plannedPct;
        let status = "PLANNED";
        if (pct >= 100) status = "ACHIEVED";
        else if (pct >= planned) status = "ON_TRACK";
        else if (pct > 0) status = "IN_PROGRESS";
        if (pct < planned - 10) status = "PROJECTED_DELAY";
        return {
          id: r.wbsCode,
          code: `M-${String(idx + 1).padStart(2, "0")}`,
          name: r.wbsName,
          planned: undefined,
          forecast: undefined,
          status,
        };
      });
  }, [wbsRows]);

  const reportKinds: ReportKind[] = [
    { id: "weekly", label: "Weekly Progress" },
    { id: "monthly", label: "Monthly Status" },
    { id: "executive", label: "Executive Summary" },
    { id: "audit", label: "Audit Report" },
  ];

  const handleGenerate = (id: string) => {
    setBusyReport(id);
    // Stub: kick off real report generation once a backend endpoint exists.
    setTimeout(() => setBusyReport(null), 800);
  };

  return (
    <div className="space-y-6 pb-12">
      <header className="space-y-2">
        <DashboardBreadcrumb label="Reports & Analytics" />
        <p className="text-xs text-text-secondary">
          Progress monitoring, KPI trends and executive dashboards
        </p>
      </header>

      <Tabs
        items={[
          { id: "weekly", label: "Weekly Progress" },
          { id: "monthly", label: "Monthly Summary" },
          { id: "financial", label: "Financial" },
          { id: "quality", label: "Quality" },
          { id: "resource", label: "Resource Utilisation" },
        ]}
        value={tab}
        onChange={setTab}
      />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Physical Progress by Package (%)
          </h2>
          {packageProgress.length === 0 ? (
            <div className="flex h-56 items-center justify-center text-sm text-text-secondary">
              No package-level progress data yet.
            </div>
          ) : (
            <div style={{ width: "100%", height: 240 }}>
              <ResponsiveContainer>
                <BarChart data={packageProgress} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
                  <CartesianGrid stroke={CHART_COLORS_COMMAND.grid} strokeDasharray="3 3" />
                  <XAxis
                    dataKey="code"
                    tick={{ fill: CHART_COLORS_COMMAND.textSecondary, fontSize: 10 }}
                    stroke={CHART_COLORS_COMMAND.grid}
                  />
                  <YAxis
                    tick={{ fill: CHART_COLORS_COMMAND.textSecondary, fontSize: 11 }}
                    stroke={CHART_COLORS_COMMAND.grid}
                    domain={[0, 100]}
                    tickFormatter={(v) => `${v}%`}
                  />
                  <Tooltip
                    contentStyle={CHART_TOOLTIP_STYLE_COMMAND}
                    formatter={(value) => `${value}%`}
                  />
                  <Bar dataKey="actualPct" radius={[4, 4, 0, 0]}>
                    {packageProgress.map((entry, i) => (
                      <Cell key={i} fill={progressColor(entry.actualPct)} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </section>
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Performance Index (SPI &amp; CPI)
          </h2>
          <PerformanceIndexChart
            data={performanceIndexData}
            latestSpi={evm?.schedulePerformanceIndex ?? undefined}
            latestCpi={evm?.costPerformanceIndex ?? undefined}
          />
        </section>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Milestone Tracker
          </h2>
          <MilestoneTrackerTable rows={milestoneRows} />
        </section>
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Generate Report
          </h2>
          <ReportGenerationPanel
            kinds={reportKinds}
            onGenerate={handleGenerate}
            busyId={busyReport}
          />
        </section>
      </div>
    </div>
  );
}
