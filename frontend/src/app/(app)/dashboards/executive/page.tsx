"use client";

import { useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { dashboardApi } from "@/lib/api/dashboardApi";
import { projectApi } from "@/lib/api/projectApi";
import { riskApi } from "@/lib/api/riskApi";
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
import { formatMoney } from "@/lib/currency/format";
import { BudgetByCurrencyChip } from "@/components/dashboards/common/BudgetByCurrencyChip";
import Link from "next/link";
import { ArrowLeft, ChevronRight, ShieldAlert, Sparkles } from "lucide-react";
import type { ProjectResponse } from "@/lib/types";
import type { RiskRag, RiskResponse } from "@/lib/api/riskApi";
import { Badge, type BadgeVariant } from "@/components/ui/badge";

const RAG_SEVERITY: Record<RiskRag, number> = {
  CRIMSON: 5,
  RED: 4,
  AMBER: 3,
  GREEN: 2,
  OPPORTUNITY: 1,
};

const RAG_BADGE: Record<RiskRag, BadgeVariant> = {
  CRIMSON: "danger",
  RED: "danger",
  AMBER: "warning",
  GREEN: "success",
  OPPORTUNITY: "info",
};

function statusBadge(status: string): BadgeVariant {
  switch (status) {
    case "ACTIVE":
      return "success";
    case "PLANNED":
      return "gold";
    case "COMPLETED":
      return "info";
    case "ON_HOLD":
      return "warning";
    case "CANCELLED":
      return "danger";
    default:
      return "neutral";
  }
}

function progressBarClass(percent: number, planned?: number): string {
  if (planned != null && percent >= planned) return "bg-emerald";
  if (percent >= 75) return "bg-emerald";
  if (percent >= 40) return "bg-gold";
  return "bg-bronze-warn";
}

export default function ExecutiveDashboardPage() {
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);

  const { isLoading: isLoadingConfig } = useQuery({
    queryKey: ["dashboard-config", "EXECUTIVE"],
    queryFn: () => dashboardApi.getDashboardByTier("EXECUTIVE"),
  });

  const { data: projectsData, isLoading: isLoadingProjects } = useQuery({
    queryKey: ["projects"],
    queryFn: () => projectApi.listProjects(),
  });

  const { data: projectKpis, isLoading: isLoadingKpis } = useQuery({
    queryKey: ["project-kpis", selectedProjectId],
    queryFn: () =>
      selectedProjectId
        ? dashboardApi.getProjectKpiSnapshots(selectedProjectId)
        : null,
    enabled: !!selectedProjectId,
  });

  const { data: scorecard } = useQuery({
    queryKey: ["portfolio-scorecard"],
    queryFn: () => portfolioReportApi.getScorecard(),
    staleTime: 60_000,
  });

  const { data: evmRollup } = useQuery({
    queryKey: ["portfolio-evm-rollup"],
    queryFn: () => portfolioReportApi.getEvmRollup(),
    staleTime: 60_000,
  });

  const projects = projectsData?.data?.content ?? [];

  const evmByProjectId = new Map((evmRollup?.data ?? []).map((r) => [r.projectId, r]));

  const riskQueries = useQueries({
    queries: projects.map((p: ProjectResponse) => ({
      queryKey: ["risks", p.id],
      queryFn: () => riskApi.getRisksByProject(p.id),
      staleTime: 60_000,
    })),
  });

  const isLoadingRisks = riskQueries.some((q) => q.isLoading);
  const allRisks: RiskResponse[] = riskQueries.flatMap((q) => q.data?.data ?? []);

  const topRisks = [...allRisks]
    .sort((a, b) => {
      const ragDiff = (RAG_SEVERITY[b.rag] ?? 0) - (RAG_SEVERITY[a.rag] ?? 0);
      if (ragDiff !== 0) return ragDiff;
      return (b.residualRiskScore ?? b.riskScore ?? 0) - (a.residualRiskScore ?? a.riskScore ?? 0);
    })
    .slice(0, 5);

  // Roll-ups for the KPI strip
  const totalProjects = projects.length;
  const activeCount = projects.filter((p: ProjectResponse) => p.status === "ACTIVE").length;
  const criticalRiskCount = allRisks.filter(
    (r) => r.rag === "CRIMSON" || r.rag === "RED"
  ).length;

  if (isLoadingConfig) {
    return (
      <div className="flex items-center justify-center p-12">
        <div className="text-sm text-slate">Loading dashboard…</div>
      </div>
    );
  }

  return (
    <div>
      {/* Page head */}
      <div className="mb-7 flex items-start gap-4">
        <Link
          href="/dashboards"
          aria-label="Back to dashboards"
          className="mt-1.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-hairline bg-paper text-slate transition-all duration-200 hover:border-gold/50 hover:text-gold-deep"
        >
          <ArrowLeft size={16} strokeWidth={1.75} />
        </Link>
        <div className="flex-1">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            Executive · corridor view
          </div>
          <h1
            className="font-display text-[34px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Executive dashboard
          </h1>
          <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
            Strategic posture across the corridor — top risks, project health, and budget burn rolled up.
          </p>
        </div>
      </div>

      {/* KPI strip */}
      <div className="mb-4 grid grid-cols-2 gap-3.5 lg:grid-cols-4">
        <KpiCard label="Projects" value={totalProjects} />
        <KpiCard label="Active" value={activeCount} accent="emerald" />
        <KpiCard label="Avg progress" value={`${Math.round(scorecard?.avgPercentComplete ?? 0)}%`} accent="gold" />
        <KpiCard
          label="Critical risks"
          value={criticalRiskCount}
          accent={criticalRiskCount > 0 ? "burgundy" : "emerald"}
        />
      </div>
      <div className="mb-7 flex items-center gap-2 text-xs text-slate">
        <span className="font-semibold uppercase tracking-[0.14em] text-gold-deep">Budget by currency</span>
        <BudgetByCurrencyChip items={scorecard?.budgetByCurrency ?? []} />
      </div>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-3">
        {/* Project Portfolio */}
        <section className="xl:col-span-2">
          <SectionHeading
            kicker="Portfolio"
            title="Project portfolio"
            subtitle="Click a project to inspect its KPIs below"
          />
          <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
            {isLoadingProjects ? (
              <div className="space-y-3 p-5">
                {[...Array(3)].map((_, i) => (
                  <div key={i} className="h-24 animate-pulse rounded-lg bg-parchment/60" />
                ))}
              </div>
            ) : projects.length === 0 ? (
              <EmptyState label="No projects available" />
            ) : (
              <ul className="divide-y divide-hairline">
                {projects.map((project: ProjectResponse) => {
                  const row = evmByProjectId.get(project.id);
                  const progressPercent = Math.round(row?.percentComplete ?? 0);
                  const budgetLabel =
                    row && row.bac > 0
                      ? formatMoney(
                          row.bac,
                          { code: row.budgetCurrency ?? project.budgetCurrency ?? "INR" },
                          { compact: true },
                        )
                      : "—";
                  const isSelected = selectedProjectId === project.id;

                  return (
                    <li key={project.id}>
                      <button
                        type="button"
                        onClick={() => setSelectedProjectId(project.id)}
                        className={`group w-full p-5 text-left transition-colors duration-150 hover:bg-ivory ${
                          isSelected ? "bg-gold-tint/30" : ""
                        }`}
                      >
                        <div className="mb-3 flex items-start justify-between gap-4">
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2">
                              <h3 className="truncate font-display text-base font-semibold tracking-tight text-charcoal">
                                {project.name}
                              </h3>
                              {isSelected && (
                                <span className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                                  selected
                                </span>
                              )}
                            </div>
                            {project.description && (
                              <p className="mt-0.5 truncate text-xs text-slate">
                                {project.description}
                              </p>
                            )}
                          </div>
                          <Badge variant={statusBadge(project.status || "ACTIVE")} withDot>
                            {project.status || "ACTIVE"}
                          </Badge>
                        </div>

                        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                          <ProgressRow
                            label="Progress"
                            value={`${progressPercent}%`}
                            percent={progressPercent}
                            barClass={progressBarClass(progressPercent)}
                          />
                          <ProgressRow
                            label="Budget"
                            value={budgetLabel}
                            percent={0}
                            barClass=""
                          />
                        </div>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </section>

        {/* Top Risks */}
        <section>
          <SectionHeading
            kicker="Risk register"
            title="Top 5 risks"
            icon={<ShieldAlert size={14} strokeWidth={1.75} />}
          />
          <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
            {isLoadingRisks ? (
              <div className="space-y-3 p-5">
                {[...Array(3)].map((_, i) => (
                  <div key={i} className="h-20 animate-pulse rounded-lg bg-parchment/60" />
                ))}
              </div>
            ) : topRisks.length === 0 ? (
              <EmptyState label="No risks logged" />
            ) : (
              <ul className="divide-y divide-hairline">
                {topRisks.map((risk) => (
                  <li key={risk.id} className="p-4">
                    <div className="mb-1.5 flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                          {risk.code}
                        </div>
                        <div className="mt-0.5 truncate text-sm font-semibold text-charcoal">
                          {risk.title}
                        </div>
                      </div>
                      <Badge variant={risk.rag ? RAG_BADGE[risk.rag] : "neutral"} withDot>
                        {risk.rag || "—"}
                      </Badge>
                    </div>
                    {risk.description && (
                      <p className="line-clamp-2 text-xs leading-relaxed text-slate">
                        {risk.description}
                      </p>
                    )}
                    {risk.residualRiskScore != null && (
                      <div className="mt-2 text-[11px] text-ash">
                        Residual score:{" "}
                        <span className="font-semibold text-charcoal">
                          {Number(risk.residualRiskScore).toFixed(1)}
                        </span>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
      </div>

      {/* Project KPIs */}
      {selectedProjectId && (
        <section className="mt-7">
          <SectionHeading
            kicker="Project KPIs"
            title={`Snapshots · ${
              projects.find((p: ProjectResponse) => p.id === selectedProjectId)?.name ?? ""
            }`}
            icon={<Sparkles size={14} strokeWidth={1.75} />}
          />
          <div className="rounded-2xl border border-hairline bg-paper p-5">
            {isLoadingKpis ? (
              <div className="text-center text-sm text-slate">Loading KPIs…</div>
            ) : projectKpis && projectKpis.data && projectKpis.data.length > 0 ? (
              <div className="grid grid-cols-2 gap-3.5 md:grid-cols-4">
                {projectKpis.data.map((kpi) => {
                  const tone =
                    kpi.status === "GREEN"
                      ? "emerald"
                      : kpi.status === "AMBER"
                        ? "bronze"
                        : "burgundy";
                  return (
                    <div
                      key={kpi.id}
                      className="rounded-xl border border-hairline bg-ivory p-4"
                    >
                      <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                        KPI {kpi.kpiDefinitionId}
                      </div>
                      <div
                        className={`mt-2 font-display text-3xl font-semibold leading-none tracking-tight ${
                          tone === "emerald"
                            ? "text-emerald"
                            : tone === "bronze"
                              ? "text-bronze-warn"
                              : "text-burgundy"
                        }`}
                      >
                        {kpi.value.toFixed(2)}
                      </div>
                      <div className="mt-2">
                        <Badge
                          variant={
                            kpi.status === "GREEN"
                              ? "success"
                              : kpi.status === "AMBER"
                                ? "warning"
                                : "danger"
                          }
                          withDot
                        >
                          {kpi.status}
                        </Badge>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <EmptyState label="No KPIs available for this project" />
            )}
          </div>
        </section>
      )}
    </div>
  );
}

// ----------------------- shared local primitives -----------------------

function KpiCard({
  label,
  value,
  accent = "default",
}: {
  label: string;
  value: number | string;
  accent?: "default" | "emerald" | "burgundy" | "gold";
}) {
  const rail =
    accent === "emerald"
      ? "border-l-[3px] border-l-emerald"
      : accent === "burgundy"
        ? "border-l-[3px] border-l-burgundy"
        : accent === "gold"
          ? "border-l-[3px] border-l-gold"
          : "";
  return (
    <div
      className={`rounded-xl border border-hairline bg-paper p-5 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_4px_20px_rgba(28,28,28,0.05)] ${rail}`}
    >
      <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-2">
        {label}
      </div>
      <div
        className="font-display text-[32px] font-semibold leading-none tracking-tight text-charcoal"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {value}
      </div>
    </div>
  );
}

function SectionHeading({
  kicker,
  title,
  subtitle,
  icon,
}: {
  kicker: string;
  title: string;
  subtitle?: string;
  icon?: React.ReactNode;
}) {
  return (
    <div className="mb-3.5 flex items-baseline justify-between gap-3">
      <div>
        <div className="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
          {icon && <span>{icon}</span>}
          {kicker}
        </div>
        <h2 className="mt-0.5 font-display text-xl font-semibold tracking-tight text-charcoal">
          {title}
        </h2>
        {subtitle && <p className="mt-0.5 text-xs text-slate">{subtitle}</p>}
      </div>
    </div>
  );
}

function ProgressRow({
  label,
  value,
  percent,
  barClass,
}: {
  label: string;
  value: string;
  percent: number;
  barClass: string;
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wide text-slate">
          {label}
        </span>
        <span className="text-xs font-semibold text-charcoal">{value}</span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-parchment">
        <div
          className={`h-full rounded-full transition-all duration-500 ${barClass}`}
          style={{ width: `${Math.min(100, Math.max(0, percent))}%` }}
        />
      </div>
    </div>
  );
}

function EmptyState({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center gap-2 rounded-xl border border-dashed border-hairline bg-ivory/50 p-8 text-sm text-slate">
      <ChevronRight size={14} className="text-ash" />
      {label}
    </div>
  );
}
