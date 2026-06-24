"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, ChevronDown } from "lucide-react";
import { projectApi } from "@/lib/api/projectApi";
import { baselineApi } from "@/lib/api/baselineApi";
import type { ProjectResponse } from "@/lib/types";
import { ScheduleVarianceSection } from "@/components/reports/ScheduleVarianceSection";
import { CostVarianceSection } from "@/components/reports/CostVarianceSection";
import { ProjectCurrencyProvider } from "@/lib/currency/ProjectCurrencyProvider";

type Tab = "schedule" | "cost";

export default function VarianceReportPage() {
  const [tab, setTab] = useState<Tab>("schedule");
  // User's explicit picks. Empty string = "no explicit pick yet, fall back to default".
  // Defaults are derived during render below — keeping fallbacks out of state avoids
  // the effect → setState cascade that the react-hooks lint enforces against.
  const [userPickedProjectId, setUserPickedProjectId] = useState<string>("");
  const [userPickedBaselineId, setUserPickedBaselineId] = useState<string>("");

  const { data: projectsData, isLoading: loadingProjects } = useQuery({
    queryKey: ["projects-for-variance"],
    queryFn: () => projectApi.listProjects(0, 100),
    staleTime: 60_000,
  });

  const projects: ProjectResponse[] = useMemo(
    () => projectsData?.data?.content ?? [],
    [projectsData],
  );

  // Effective project: user pick if any, else first project with an active baseline,
  // else the first project at all.
  const projectId = useMemo(() => {
    if (userPickedProjectId && projects.some((p) => p.id === userPickedProjectId)) {
      return userPickedProjectId;
    }
    return projects.find((p) => p.activeBaselineId)?.id ?? projects[0]?.id ?? "";
  }, [userPickedProjectId, projects]);

  const selectedProject = useMemo(
    () => projects.find((p) => p.id === projectId) ?? null,
    [projects, projectId],
  );

  const { data: baselinesData } = useQuery({
    queryKey: ["baselines-for-variance", projectId],
    queryFn: () => baselineApi.listBaselines(projectId),
    enabled: !!projectId,
    staleTime: 30_000,
  });
  const baselines = useMemo(() => baselinesData?.data ?? [], [baselinesData]);

  // Effective baseline: user pick if it belongs to the current project, else the
  // project's active baseline, else the first baseline returned, else empty.
  const baselineId = useMemo(() => {
    if (userPickedBaselineId && baselines.some((b) => b.id === userPickedBaselineId)) {
      return userPickedBaselineId;
    }
    if (selectedProject?.activeBaselineId) return selectedProject.activeBaselineId;
    return baselines[0]?.id ?? "";
  }, [userPickedBaselineId, baselines, selectedProject]);

  const handleProjectChange = (next: string) => {
    setUserPickedProjectId(next);
    // Reset baseline pick — it would point at a baseline that doesn't belong to the
    // new project. The memo above will pick a sensible default for us.
    setUserPickedBaselineId("");
  };

  const noBaseline = !!selectedProject && baselines.length === 0;

  return (
    <div>
      {/* Page head */}
      <div className="mb-7 flex items-start gap-4">
        <Link
          href="/reports"
          aria-label="Back to reports"
          className="mt-1.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-hairline bg-paper text-slate transition-all duration-200 hover:border-gold/50 hover:text-gold-deep"
        >
          <ArrowLeft size={16} strokeWidth={1.75} />
        </Link>
        <div className="flex-1">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            Reports · variance
          </div>
          <h1
            className="font-display text-[34px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Variance report
          </h1>
          <p className="mt-2 max-w-[640px] text-sm leading-relaxed text-slate">
            Compare the live programme against an assigned baseline. Primavera P6-style
            schedule and cost variance — pick a project, pick a baseline, and the report
            populates.
          </p>
        </div>
      </div>

      {/* Picker bar */}
      <div className="mb-6 flex flex-wrap items-end gap-4 rounded-2xl border border-hairline bg-paper p-4">
        <div className="flex-1 min-w-[260px]">
          <label className="mb-1.5 block text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
            Project
          </label>
          <SelectShell>
            <select
              value={projectId}
              onChange={(e) => handleProjectChange(e.target.value)}
              disabled={loadingProjects || projects.length === 0}
              className="h-10 w-full appearance-none rounded-md border border-hairline bg-ivory px-3 pr-9 text-sm text-charcoal focus:border-gold/40 focus:outline-none focus:ring-1 focus:ring-gold/30 disabled:opacity-50"
            >
              {projects.length === 0 ? (
                <option>No projects available</option>
              ) : (
                projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.code} · {p.name}
                  </option>
                ))
              )}
            </select>
          </SelectShell>
        </div>

        <div className="flex-1 min-w-[260px]">
          <label className="mb-1.5 block text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
            Baseline
          </label>
          <SelectShell>
            <select
              value={baselineId}
              onChange={(e) => setUserPickedBaselineId(e.target.value)}
              disabled={baselines.length === 0}
              className="h-10 w-full appearance-none rounded-md border border-hairline bg-ivory px-3 pr-9 text-sm text-charcoal focus:border-gold/40 focus:outline-none focus:ring-1 focus:ring-gold/30 disabled:opacity-50"
            >
              {baselines.length === 0 ? (
                <option>No baselines for this project</option>
              ) : (
                baselines.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                    {selectedProject?.activeBaselineId === b.id ? " · active" : ""}
                  </option>
                ))
              )}
            </select>
          </SelectShell>
        </div>
      </div>

      {/* Tab switcher */}
      <div className="mb-5 inline-flex rounded-lg border border-hairline bg-ivory p-0.5">
        <TabButton active={tab === "schedule"} onClick={() => setTab("schedule")}>
          Schedule variance
        </TabButton>
        <TabButton active={tab === "cost"} onClick={() => setTab("cost")}>
          Cost variance
        </TabButton>
      </div>

      {noBaseline ? (
        <div className="rounded-2xl border border-dashed border-hairline bg-ivory/50 p-10 text-center">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-2">
            No baselines for {selectedProject?.code}
          </div>
          <p className="text-sm text-slate">
            Create a baseline first.{" "}
            <Link
              href={`/projects/${selectedProject?.id}?tab=baselines`}
              className="font-semibold text-gold-deep underline-offset-2 hover:underline"
            >
              Open the Baselines tab →
            </Link>
          </p>
        </div>
      ) : !projectId || !baselineId ? (
        <div className="rounded-2xl border border-dashed border-hairline bg-ivory/50 p-10 text-center text-sm text-slate">
          Pick a project and baseline above to load the report.
        </div>
      ) : (
        <ProjectCurrencyProvider key={projectId} currency={selectedProject?.budgetCurrency}>
          {tab === "schedule" ? (
            <ScheduleVarianceSection projectId={projectId} baselineId={baselineId} />
          ) : (
            <CostVarianceSection projectId={projectId} baselineId={baselineId} />
          )}
        </ProjectCurrencyProvider>
      )}
    </div>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-md px-4 py-2 text-sm font-semibold transition-colors ${
        active
          ? "bg-paper text-charcoal shadow-sm"
          : "text-slate hover:text-charcoal"
      }`}
    >
      {children}
    </button>
  );
}

function SelectShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative">
      {children}
      <ChevronDown
        size={14}
        className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-ash"
      />
    </div>
  );
}
