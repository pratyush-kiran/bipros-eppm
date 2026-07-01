"use client";

import { Suspense, useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useParams, useSearchParams, useRouter, usePathname } from "next/navigation";
import { BarChart3, ChevronDown, Database } from "lucide-react";
import { projectApi } from "@/lib/api/projectApi";
import { cn } from "@/lib/utils/cn";
import { useAuthStore } from "@/lib/state/store";
import { useRecentProjects } from "@/hooks/useRecentProjects";
import { ProjectCurrencyProvider } from "@/lib/currency/ProjectCurrencyProvider";

function ProjectDetailLayoutInner({
  children,
}: {
  children: React.ReactNode;
}) {
  const params = useParams();
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const projectId = params.projectId as string;
  const hasPermission = useAuthStore((s) => s.hasPermission);
  // Check if we're on a sub-route (not the base project page with ?tab= params)
  const isOnSubRoute = pathname !== `/projects/${projectId}` && !pathname.endsWith(`/projects/${projectId}`);
  const activeTab = isOnSubRoute ? null : (searchParams.get("tab") || "overview");
  const [moreDropdownOpen, setMoreDropdownOpen] = useState(false);
  const [masterDataOpen, setMasterDataOpen] = useState(false);
  const [insightsHeaderOpen, setInsightsHeaderOpen] = useState(false);

  const { data: projectData, isLoading, error } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    // 403/404 is a final state — retrying just delays the explanatory message.
    retry: (failureCount, err) => {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 403 || status === 404) return false;
      return failureCount < 2;
    },
  });

  const project = projectData?.data;
  const status = (error as { response?: { status?: number } } | null)?.response?.status;

  // Record this visit in the per-user MRU list so the home hub's "Recent
  // projects" strip can surface it. Re-running only when the project identity
  // changes; navigating between sub-tabs of the same project doesn't re-record.
  const { recordVisit } = useRecentProjects();
  useEffect(() => {
    if (project?.id && project?.code && project?.name) {
      recordVisit({ id: project.id, code: project.code, name: project.name });
    }
  }, [project?.id, project?.code, project?.name, recordVisit]);

  if (isLoading) {
    return <div className="p-6 text-center text-text-muted">Loading project…</div>;
  }

  if (status === 403) {
    return (
      <div className="p-6 text-center">
        <p className="text-sm font-semibold uppercase tracking-widest text-accent">No access</p>
        <p className="mt-2 text-text-primary">
          You&apos;re not a member of this project.
        </p>
        <p className="mt-1 text-sm text-text-muted">
          Ask the project manager to add you under <em>Project &rsaquo; Members</em>.
        </p>
      </div>
    );
  }

  if (!project) {
    return <div className="p-6 text-center text-danger">Project not found</div>;
  }

  // Tab-based navigation (query parameter). Each tab can declare a `permission`
  // gate — tabs whose perm the current user lacks are filtered out before render
  // so SUPERVISOR-tier users don't see finance/contract surfaces.
  type ProjectTab = { id: string; label: string; href: string | null; permission?: string };
  // Order follows the runbook flow: plan → commercial+org → execution → daily P&L →
  // financial analysis → schedule views → cross-cutting. Team is promoted out of
  // the More dropdown because it's load-bearing for the DBS Engineer/CM/PM rollup.
  const allTabs: ProjectTab[] = [
    { id: "overview",           label: "Overview",            href: null },
    { id: "wbs",                label: "WBS",                 href: null },
    { id: "activities",         label: "Activities",          href: `/projects/${projectId}/activities` },
    { id: "boq",                label: "BOQ",                 href: `/projects/${projectId}/boq` },
    { id: "team",               label: "Team",                href: `/projects/${projectId}/team` },
    { id: "general-expenses",   label: "General Expenses",    href: `/projects/${projectId}/general-expenses` },
    { id: "dpr",                label: "DPR",                 href: `/projects/${projectId}/dpr` },
    { id: "capacity",           label: "Capacity Util.",      href: `/projects/${projectId}/capacity-utilization` },
    { id: "dbs",                label: "DBS",                 href: `/projects/${projectId}/dbs` },
    { id: "costs",              label: "Costs",               href: null, permission: "COST.READ" },
    { id: "insights",           label: "Insights",            href: `/projects/${projectId}/insights` },
    { id: "risks",              label: "Risks",               href: `/projects/${projectId}/risks`, permission: "RISK.READ" },
    { id: "material-consumption", label: "Material Consumptions", href: `/projects/${projectId}/material-consumption` },
    { id: "gis",                label: "GIS",                 href: `/projects/${projectId}/gis-viewer` },
  ];

  const tabs = allTabs.filter((t) => !t.permission || hasPermission(t.permission));

  // PMS Master Data — the 5 project-scoped reference entities from TESTING_GUIDE.md §2
  const masterDataLinks = [
    { label: "BOQ & Budget", href: `/projects/${projectId}/boq` },
    { label: "Stretches", href: `/projects/${projectId}/stretches` },
    { label: "Material Sources", href: `/projects/${projectId}/material-sources` },
    { label: "Material Catalogue", href: `/projects/${projectId}/materials` },
    { label: "Stock Register", href: `/projects/${projectId}/stock-register` },
  ];

  const moreLinks: { label: string; href: string; permission?: string }[] = [
    { label: "Quality", href: `/projects/${projectId}/quality`, permission: "NCR.READ" },
    { label: "Procurement", href: `/projects/${projectId}/procurement`, permission: "RESOURCE.READ" },
    { label: "EVM", href: `/projects/${projectId}/evm` },
    // Team is now a top-level tab (see allTabs above).
    { label: "Budget Changes", href: `/projects/${projectId}/budget-changes` },
    { label: "Relationships", href: `/projects/${projectId}/relationships` },
    // { label: "Daily Cost Report", href: `/projects/${projectId}/daily-cost-report` },  // hidden per request
    { label: "Performance (D/W/M)", href: `/projects/${projectId}/performance` },
    { label: "P&L vs Budgeted Rates", href: `/projects/${projectId}/pnl/budgeted` },
    { label: "P&L vs BOQ Rates", href: `/projects/${projectId}/pnl/boq` },
    { label: "Material Consumption Report", href: `/projects/${projectId}/reports/material-consumption` },
    /* { label: "Material Reconciliation", href: `/projects/${projectId}/material-reconciliation` },
    { label: "Resource Deployment", href: `/projects/${projectId}/resource-deployment` }, */
    { label: "Weather Log", href: `/projects/${projectId}/weather-log` },
    { label: "Next Day Plan", href: `/projects/${projectId}/next-day-plan` },
    { label: "Schedule Health", href: `/projects/${projectId}/schedule-health` },
    { label: "Schedule Compression", href: `/projects/${projectId}/schedule-compression` },
    { label: "Risk Analysis", href: `/projects/${projectId}/risk-analysis` },
    // { label: "Activity Correlations", href: `/projects/${projectId}/activity-correlations` },
    // { label: "Predictions", href: `/projects/${projectId}/predictions` },
    // { label: "RA Bills", href: `/projects/${projectId}/ra-bills` },
    // { label: "Drawings", href: `/projects/${projectId}/drawings` },
    // { label: "RFIs", href: `/projects/${projectId}/rfis` },
    // { label: "Equipment Logs", href: `/projects/${projectId}/equipment-logs` },
    // { label: "Labour Returns", href: `/projects/${projectId}/labour-returns` },
    // { label: "GRNs", href: `/projects/${projectId}/grns` },
    { label: "Issues", href: `/projects/${projectId}/issues` },
    { label: "Baselines", href: `/projects/${projectId}?tab=baselines` },
    { label: "Contracts", href: `/projects/${projectId}/contracts` },
  ];

  // Check if any dropdown link is active (check first so we can exclude them below)
  const isMasterDataActive = masterDataLinks.some((link) => pathname.includes(link.href));
  const isMoreActive = moreLinks.some((link) => pathname.includes(link.href));

  // Check if any href-based tab matches
  const isAnyHrefTabActive = tabs.some((t) => t.href && pathname.includes(t.href));

  // Determine if a tab is active
  const isTabActive = (tab: typeof tabs[0]): boolean => {
    if (tab.href) {
      return pathname.includes(tab.href);
    }
    // For query-based tabs on the base project page
    if (activeTab !== null) {
      return activeTab === tab.id;
    }
    // On a sub-route: check if the pathname contains /activities/, /activity-codes/ etc.
    // that should map back to the query-based tab
    if (isOnSubRoute && !isMasterDataActive && !isMoreActive && !isAnyHrefTabActive) {
      const subRouteSegment = pathname.replace(`/projects/${projectId}`, "").split("/")[1];
      if (tab.id === "activities" && (subRouteSegment === "activities" || subRouteSegment === "activity-codes")) return true;
    }
    return false;
  };

  return (
    <ProjectCurrencyProvider currency={project.budgetCurrency}>
    <div className="min-w-0" style={{ ["--tab-nav-h" as string]: "53px" }}>
      <div className="mb-6 flex items-start justify-between gap-4 px-6 pt-6">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-text-primary">{project.name}</h1>
          <p className="text-sm text-text-secondary">{project.code}</p>
        </div>
        <div className="relative shrink-0">
          <button
            type="button"
            onClick={() => {
              setInsightsHeaderOpen(!insightsHeaderOpen);
              setMasterDataOpen(false);
              setMoreDropdownOpen(false);
            }}
            className="inline-flex items-center gap-2 rounded-lg border border-gold/45 bg-gold-tint/40 px-3 py-2 text-sm font-semibold text-gold-deep transition-colors hover:border-gold hover:bg-gold-tint"
            aria-haspopup="menu"
            aria-expanded={insightsHeaderOpen}
          >
            <BarChart3 size={15} strokeWidth={1.75} />
            Open dashboards
            <ChevronDown
              size={14}
              className={cn("transition-transform duration-200", insightsHeaderOpen && "rotate-180")}
            />
          </button>
          {insightsHeaderOpen && (
            <div className="absolute right-0 top-full mt-1 w-56 rounded-md border border-border bg-surface shadow-lg z-50">
              <button
                type="button"
                onClick={() => {
                  router.push(`/projects/${projectId}/insights/operational`);
                  setInsightsHeaderOpen(false);
                }}
                className="block w-full rounded-t-md px-4 py-2 text-left text-sm text-text-secondary transition-colors hover:bg-surface-hover/50 hover:text-text-primary"
              >
                Operational
              </button>
              <button
                type="button"
                onClick={() => {
                  router.push(`/projects/${projectId}/insights/field`);
                  setInsightsHeaderOpen(false);
                }}
                className="block w-full px-4 py-2 text-left text-sm text-text-secondary transition-colors hover:bg-surface-hover/50 hover:text-text-primary"
              >
                Field
              </button>
              <button
                type="button"
                onClick={() => {
                  router.push(`/projects/${projectId}/capacity-utilization`);
                  setInsightsHeaderOpen(false);
                }}
                className="block w-full rounded-b-md border-t border-border px-4 py-2 text-left text-sm text-text-secondary transition-colors hover:bg-surface-hover/50 hover:text-text-primary"
              >
                Capacity Utilisation
              </button>
            </div>
          )}
        </div>
      </div>

      <div className="sticky top-0 z-30 border-b border-border bg-ivory px-6">
        <nav className="flex items-center gap-8" aria-label="Tabs">
          <div className="flex items-center gap-8 overflow-x-auto">
            {tabs.map((t) => {
              const isActive = isTabActive(t);
              return (
                <button
                  key={t.id}
                  onClick={() => {
                    if (t.href) {
                      router.push(t.href);
                    } else {
                      router.push(`/projects/${projectId}?tab=${t.id}`);
                    }
                  }}
                  className={cn(
                    "px-1 py-4 text-sm font-medium border-b-2 transition-colors cursor-pointer shrink-0",
                    isActive
                      ? "border-accent text-accent"
                      : "border-transparent text-text-secondary hover:text-text-primary hover:border-border"
                  )}
                >
                  {t.label}
                </button>
              );
            })}
          </div>

          {/* Master Data Dropdown — hidden per request */}
          {/*
          <div className="relative shrink-0">
            <button
              onClick={() => {
                setMasterDataOpen(!masterDataOpen);
                setMoreDropdownOpen(false);
              }}
              className={cn(
                "flex items-center gap-1.5 px-1 py-4 text-sm font-medium border-b-2 transition-colors cursor-pointer",
                isMasterDataActive
                  ? "border-accent text-accent"
                  : "border-transparent text-text-secondary hover:text-text-primary hover:border-border"
              )}
            >
              <Database size={15} strokeWidth={1.75} />
              Master data
              <ChevronDown
                size={16}
                className={cn(
                  "transition-transform duration-200",
                  masterDataOpen && "rotate-180"
                )}
              />
            </button>

            {masterDataOpen && (
              <div className="absolute right-0 mt-0 w-56 bg-surface border border-border rounded-md shadow-lg z-50">
                {masterDataLinks.map((link) => (
                  <button
                    key={link.href}
                    onClick={() => {
                      router.push(link.href);
                      setMasterDataOpen(false);
                    }}
                    className={cn(
                      "block w-full text-left px-4 py-2 text-sm first:rounded-t-md last:rounded-b-md transition-colors",
                      pathname.includes(link.href)
                        ? "bg-surface-hover/50 text-accent font-semibold"
                        : "text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
                    )}
                  >
                    {link.label}
                  </button>
                ))}
              </div>
            )}
          </div>
          */}

          {/* More Dropdown */}
          <div className="relative shrink-0">
            <button
              onClick={() => {
                setMoreDropdownOpen(!moreDropdownOpen);
                setMasterDataOpen(false);
              }}
              className={cn(
                "flex items-center gap-1 px-1 py-4 text-sm font-medium border-b-2 transition-colors cursor-pointer",
                isMoreActive
                  ? "border-accent text-accent"
                  : "border-transparent text-text-secondary hover:text-text-primary hover:border-border"
              )}
            >
              More
              <ChevronDown
                size={16}
                className={cn(
                  "transition-transform duration-200",
                  moreDropdownOpen && "rotate-180"
                )}
              />
            </button>

            {moreDropdownOpen && (
              <div className="absolute right-0 mt-0 w-48 bg-surface border border-border rounded-md shadow-lg z-50">
                {moreLinks
                  .filter((link) => !link.permission || hasPermission(link.permission))
                  .map((link) => (
                  <button
                    key={link.href}
                    onClick={() => {
                      router.push(link.href);
                      setMoreDropdownOpen(false);
                    }}
                    className="block w-full text-left px-4 py-2 text-sm text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary first:rounded-t-md last:rounded-b-md transition-colors"
                  >
                    {link.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </nav>
      </div>

      <div className="mt-6 min-w-0">{children}</div>
    </div>
    </ProjectCurrencyProvider>
  );
}

export default function ProjectDetailLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <Suspense fallback={<div className="p-6 text-center text-text-muted">Loading…</div>}>
      <ProjectDetailLayoutInner>{children}</ProjectDetailLayoutInner>
    </Suspense>
  );
}
