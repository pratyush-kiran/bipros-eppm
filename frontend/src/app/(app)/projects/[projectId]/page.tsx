"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useParams, useSearchParams, useRouter, usePathname } from "next/navigation";
import { getErrorMessage } from "@/lib/utils/error";
import { formatDate, getPriorityInfo, formatBudget, budgetUnit } from "@/lib/utils/format";
import { projectApi } from "@/lib/api/projectApi";
import { projectCategoryApi } from "@/lib/api/projectCategoryApi";
import { calendarApi } from "@/lib/api/calendarApi";
import { activityApi } from "@/lib/api/activityApi";
import { baselineApi, type BaselineActivityResponse, type BaselineDetailResponse } from "@/lib/api/baselineApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { StatusBadge } from "@/components/common/StatusBadge";
import { EmptyState } from "@/components/common/EmptyState";
import { ResourcesTab } from "@/components/resource/ResourcesTab";
import { CostsTab } from "@/components/cost/CostsTab";
import { EvmTab } from "@/components/evm/EvmTab";
import { PeriodPerformanceTab } from "@/components/cost/PeriodPerformanceTab";
import { CostAccountRollupTab } from "@/components/cost/CostAccountRollupTab";
import { ListTodo, Plus, Play, Pencil, Trash2, Eye, FileText, ChevronRight, ArrowRight, ChevronDown, Folder, FolderOpen, File, RefreshCw, List, FolderTree, Sparkles, AlertTriangle } from "lucide-react";
import { UdfSection } from "@/components/udf/UdfSection";
import { costApi } from "@/lib/api/costApi";
import { dashboardApi, type KpiSnapshot, type KpiDefinition } from "@/lib/api/dashboardApi";
import { projectResourceApi } from "@/lib/api/projectResourceApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { Breadcrumb } from "@/components/common/Breadcrumb";
import toast from "react-hot-toast";
import { apiClient } from "@/lib/api/client";
import { wbsTemplateApi } from "@/lib/api/wbsTemplateApi";
import { WorkPackagesListView } from "@/components/wbs/WorkPackagesListView";
import { TabTip } from "@/components/common/TabTip";
import { WbsAiGenerateDialog } from "@/components/wbs/WbsAiGenerateDialog";
import { useAuthStore } from "@/lib/state/store";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogBody } from "@/components/ui/dialog";
import { ProjectDocumentsPanel } from "@/components/document/ProjectDocumentsPanel";
import { ProjectSetupProgress } from "@/components/project/ProjectSetupProgress";
import { ProjectTeamCard } from "@/components/project/ProjectTeamCard";
import { ProjectDashboardTab } from "@/components/dashboards/project/ProjectDashboardTab";
import { VarianceDashboard } from "@/components/baseline/VarianceDashboard";
import { formatDefaultCurrency } from "@/lib/hooks/useCurrency";
import type { ContractType } from "@/lib/types";
import { ScheduleComparisonTable } from "@/components/baseline/ScheduleComparisonTable";
import { ScheduleVarianceSection } from "@/components/reports/ScheduleVarianceSection";
import { CostVarianceSection } from "@/components/reports/CostVarianceSection";
import type { ProjectResponse, ActivityResponse, WbsNodeResponse, BaselineResponse, BaselineVarianceRow, ApiResponse } from "@/lib/types";
import type { WbsTemplateResponse } from "@/lib/types";
import type { AxiosResponse } from "axios";
import { useScheduleStaleStore } from "@/lib/state/scheduleStaleStore";

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function ProjectDetailPage() {
  const params = useParams();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const router = useRouter();
  const projectId = params.projectId as string;
  const isUuid = UUID_REGEX.test(projectId);
  const tab = searchParams.get("tab") || "overview";

  // Redirect legacy activities tab to the dedicated activities page
  useEffect(() => {
    if (tab === "activities") {
      router.replace(`/projects/${projectId}/activities`);
    }
  }, [tab, projectId, router]);

  const [scheduleError, setScheduleError] = useState("");

  const markScheduleStale = useScheduleStaleStore((s) => s.markScheduleStale);
  const markScheduleFresh = useScheduleStaleStore((s) => s.markScheduleFresh);

  // If projectId is not a UUID, try to resolve it as a project code
  const { data: codeResolveData, isLoading: isResolvingCode } = useQuery({
    queryKey: ["project-by-code", projectId],
    queryFn: async () => {
      const result = await projectApi.listProjects(0, 200);
      const projects = result.data?.content ?? [];
      return projects.find((p) => p.code.toLowerCase() === projectId.toLowerCase()) ?? null;
    },
    enabled: !isUuid,
  });

  // Redirect to UUID-based URL when code resolves
  if (!isUuid && codeResolveData) {
    router.replace(`/projects/${codeResolveData.id}${tab !== "overview" ? `?tab=${tab}` : ""}`);
    return null;
  }

  const { data: projectData, isLoading: isLoadingProject } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: isUuid,
  });

  const { data: activitiesData, isLoading: isLoadingActivities, refetch: refetchActivities } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 100),
    enabled: tab === "activities",
  });

  const { data: wbsData, isLoading: isLoadingWbs } = useQuery({
    queryKey: ["wbs", projectId],
    queryFn: () => projectApi.getWbsTree(projectId),
    enabled: ["wbs", "activities"].includes(tab),
  });

  const { data: criticalPathData } = useQuery({
    queryKey: ["critical-path", projectId],
    queryFn: () => activityApi.getCriticalPath(projectId),
    enabled: tab === "activities",
  });

  const { data: relationshipsData, isLoading: isLoadingRelationships } = useQuery({
    queryKey: ["relationships", projectId],
    queryFn: () => activityApi.getRelationships(projectId),
    enabled: false,
  });

  const { data: baselinesData, isLoading: isLoadingBaselines, refetch: refetchBaselines } = useQuery({
    queryKey: ["baselines", projectId],
    queryFn: () => baselineApi.listBaselines(projectId),
    enabled: tab === "baselines",
  });

  // Phase 3: prefer the project's PRIMARY slot (project.primaryBaselineId) over scanning the
  // baselines list for type === "PRIMARY". The slot is the source of truth — the type column
  // is just a label set at creation. Fall back to the legacy activeBaselineId mirror for
  // backwards compat with projects whose slot was never explicitly set.
  const primaryBaselineId =
    projectData?.data?.primaryBaselineId ?? projectData?.data?.activeBaselineId ?? null;
  const primaryBaseline =
    baselinesData?.data?.find((b) => b.id === primaryBaselineId)
    ?? baselinesData?.data?.find((b) => b.baselineType === "PRIMARY");

  const { data: baselineDetailData, isLoading: isLoadingBaselineActivities } = useQuery({
    queryKey: ["baseline-detail", projectId, primaryBaseline?.id],
    queryFn: () =>
      primaryBaseline
        ? baselineApi.getBaseline(projectId, primaryBaseline.id)
        : Promise.resolve({ data: null, error: null, meta: { timestamp: "", version: "" } } as unknown as ApiResponse<BaselineDetailResponse>),
    enabled: false,
  });

  const scheduleMutation = useMutation({
    mutationFn: () => activityApi.triggerSchedule(projectId, "RETAINED_LOGIC"),
    onSuccess: () => {
      refetchActivities();
      queryClient.invalidateQueries({ queryKey: ["criticalPath", projectId] });
      markScheduleFresh(projectId);
      setScheduleError("");
      toast.success("Schedule calculated successfully");
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to trigger schedule");
      setScheduleError(msg);
      toast.error(msg);
    },
  });

  const deleteActivityMutation = useMutation({
    mutationFn: (activityId: string) => activityApi.deleteActivity(projectId, activityId),
    onSuccess: () => {
      refetchActivities();
      markScheduleStale(projectId);
      toast.success("Activity deleted");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to delete activity"));
    },
  });

  const createBaselineMutation = useMutation({
    mutationFn: (data: { name: string; baselineType: string }) =>
      baselineApi.createBaseline(projectId, data as { name: string; baselineType: "PROJECT" | "PRIMARY" | "SECONDARY" | "TERTIARY"; description?: string }),
    onSuccess: () => {
      refetchBaselines();
      toast.success("Baseline created successfully");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to create baseline"));
    },
  });

  const setActiveBaselineMutation = useMutation({
    mutationFn: (baselineId: string) => baselineApi.setActiveBaseline(projectId, baselineId),
    onSuccess: () => {
      refetchBaselines();
      queryClient.invalidateQueries({ queryKey: ["project", projectId] });
      toast.success("Baseline set as active");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to activate baseline"));
    },
  });

  const deleteBaselineMutation = useMutation({
    mutationFn: (baselineId: string) => baselineApi.deleteBaseline(projectId, baselineId),
    onSuccess: () => {
      refetchBaselines();
      toast.success("Baseline deleted");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to delete baseline"));
    },
  });

  // Phase 4.1: Restore Baseline. Destructive — overwrites planned dates / durations /
  // relationships on the live project from the snapshot. Actuals are preserved.
  const restoreBaselineMutation = useMutation({
    mutationFn: (baselineId: string) => baselineApi.restoreBaseline(projectId, baselineId),
    onSuccess: () => {
      refetchBaselines();
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
      queryClient.invalidateQueries({ queryKey: ["relationships", projectId] });
      queryClient.invalidateQueries({ queryKey: ["critical-path", projectId] });
      toast.success("Project restored from baseline");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to restore baseline"));
    },
  });

  // Phase 4.2: Selective Update Baseline. The button below runs with empty filters which
  // refreshes every activity + relationship — equivalent to P6's "Update" with all defaults.
  // The endpoint supports narrower scopes via UpdateBaselineRequest; a richer filter dialog
  // can be wired in a follow-up round.
  const updateBaselineMutation = useMutation({
    mutationFn: (baselineId: string) => baselineApi.updateBaseline(projectId, baselineId, {}),
    onSuccess: () => {
      refetchBaselines();
      queryClient.invalidateQueries({ queryKey: ["baseline-detail", projectId] });
      toast.success("Baseline updated from current schedule");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to update baseline"));
    },
  });

  const project = projectData?.data;
  const activities = activitiesData?.data?.content ?? [];
  const wbsTree = wbsData?.data ?? [];
  const criticalPathIds = new Set((criticalPathData?.data ?? []).map((a: ActivityResponse) => a.id));

  const activityColumns: ColumnDef<ActivityResponse>[] = [
    { accessorKey: "code", header: "Code", enableSorting: true },
    { accessorKey: "name", header: "Name", enableSorting: true },
    { accessorKey: "originalDuration", header: "Duration (days)", enableSorting: true },
    {
      accessorKey: "percentComplete",
      header: "% Complete",
      enableSorting: true,
      cell: (info) => `${String(info.getValue())}%`,
    },
    {
      accessorKey: "status",
      header: "Status",
      cell: (info) => <StatusBadge status={String(info.getValue())} />,
    },
    { accessorKey: "totalFloat", header: "Float (days)", enableSorting: true },
    {
      accessorKey: "plannedStartDate",
      header: "Planned Start",
      enableSorting: true,
      cell: (info) => formatDate(info.getValue() as string | null),
    },
    {
      accessorKey: "plannedFinishDate",
      header: "Planned Finish",
      enableSorting: true,
      cell: (info) => formatDate(info.getValue() as string | null),
    },
    {
      accessorKey: "earlyStartDate",
      header: "ES",
      enableSorting: true,
      cell: (info) => formatDate(info.getValue() as string | null),
    },
    {
      accessorKey: "earlyFinishDate",
      header: "EF",
      enableSorting: true,
      cell: (info) => formatDate(info.getValue() as string | null),
    },
    {
      accessorKey: "lateStartDate",
      header: "LS",
      enableSorting: true,
      cell: (info) => formatDate(info.getValue() as string | null),
    },
    {
      accessorKey: "lateFinishDate",
      header: "LF",
      enableSorting: true,
      cell: (info) => formatDate(info.getValue() as string | null),
    },
    {
      accessorKey: "id",
      header: "Actions",
      cell: (info) => {
        const row = info.row.original;
        const value = info.getValue();
        return (
          <div className="flex gap-2">
            <Link
              href={`/projects/${projectId}/activities/${value}`}
              className="text-accent hover:text-blue-300 text-sm font-medium"
            >
              Edit
            </Link>
            <button
              onClick={() => {
                if (window.confirm("Are you sure you want to delete this activity?")) {
                  deleteActivityMutation.mutate(String(value));
                }
              }}
              className="text-danger hover:text-danger text-sm font-medium"
            >
              Delete
            </button>
          </div>
        );
      },
    },
  ];

  if (isLoadingProject || isResolvingCode) {
    return (
      <div className="space-y-4">
        <div className="h-8 w-48 animate-pulse rounded bg-surface-hover/50" />
        <div className="grid grid-cols-2 gap-6">
          <div className="h-28 animate-pulse rounded-xl bg-surface-hover/50" />
          <div className="h-28 animate-pulse rounded-xl bg-surface-hover/50" />
        </div>
        <div className="h-32 animate-pulse rounded-xl bg-surface-hover/50" />
      </div>
    );
  }

  if (!project) {
    return (
      <div className="py-12 text-center">
        <p className="text-danger">Project not found</p>
        {!isUuid && (
          <p className="mt-2 text-sm text-text-muted">
            No project with code &ldquo;{projectId}&rdquo; was found. Try using the project&apos;s UUID or navigate from the projects list.
          </p>
        )}
      </div>
    );
  }

  const tabTips: Record<string, { title: string; description: string; steps?: string[] }> = {
    overview: {
      title: "Project Overview",
      description: "Your project's summary card showing key details. This is where you see status, dates, and priority at a glance.",
    },
    wbs: {
      title: "Work Breakdown Structure (WBS)",
      description: "Break your project into a tree of manageable work packages. Every activity must belong to a WBS node. Think of it as organizing your project like folders in a file system.",
      steps: ["Create top-level phases (e.g., Design, Construction, Handover)", "Add sub-packages under each phase", "Assign activities to the appropriate WBS node"],
    },
    activities: {
      title: "Activities — Your Project's Tasks",
      description: "Activities are the actual work items your team performs. Each has a code, name, duration, and dates. After creating activities, click 'Run Schedule' to calculate the Critical Path (CPM).",
      steps: ["Click '+ New Activity' to create tasks", "Set duration, dates, and WBS node", "Click 'Run Schedule' to calculate early/late dates and float", "Activities with 0 float are on the Critical Path — any delay there delays the whole project"],
    },
    gantt: {
      title: "Gantt Chart — Visual Timeline",
      description: "See all activities as horizontal bars on a calendar. Arrows show dependencies (which task must finish before another starts). Red bars = Critical Path. Use the zoom slider to adjust the view.",
    },
    resources: {
      title: "Resource Management",
      description: "Assign people, equipment, and materials to activities. Track who is working on what and when. The histogram shows resource usage over time to spot over-allocation.",
      steps: ["Click 'Assign Resource' to link a resource to this project", "View the Resource Histogram to check for over-allocation", "Level resources if needed to balance workloads"],
    },
    costs: {
      title: "Cost Tracking",
      description: "Track planned vs actual spend. Budget = total commitment from activity plans. Actual = costs accumulated from supervisor DPRs as work is recorded each day.",
      steps: [
        "Total Budget = planned costs from resource assignments (manpower, equipment, material) + sub-contractor allocations on each activity",
        "Total Actual = sum of DPR line costs (manpower nos × rate, equipment nos × rate, material qty × rate, sub-contractor qty × rate)",
        "BAC (Budget at Completion) is a manual project-level figure set by the PM; CV and CPI compare earned value against actual cost",
        "Cash Flow S-Curve plots planned, actual, and forecast spend across financial periods",
      ],
    },
    evm: {
      title: "Earned Value Management (EVM)",
      description: "EVM answers: Are we on schedule? Are we on budget? Using 3 key values: PV (Planned Value = budgeted cost of scheduled work), EV (Earned Value = budgeted cost of completed work), AC (Actual Cost = what you actually spent).",
      steps: ["Click 'Calculate EVM' to compute metrics", "SPI > 1.0 = ahead of schedule, < 1.0 = behind", "CPI > 1.0 = under budget, < 1.0 = over budget", "EAC = Estimate At Completion (predicted final cost)"],
    },
  };

  const currentTip = tabTips[tab];

  return (
    <div>
      <div className="mb-4">
        <Breadcrumb items={[
          { label: "Projects", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}`, active: true },
        ]} />
      </div>

      {/* CC-1: Re-baseline nudge. Set by VariationOrderApprovedListener (and any future listeners
          that detect material plan changes). Cleared on the next createBaseline call. */}
      {project.requiresRebaseline && (
        <div className="mb-4 flex items-center justify-between gap-4 rounded-md border border-warning/40 bg-warning/10 px-4 py-3">
          <div className="flex items-center gap-2 text-sm text-warning">
            <AlertTriangle size={16} className="shrink-0" />
            <span>
              <strong>Approved scope changes have not been baselined yet.</strong> Take a fresh
              baseline so variance reports reflect the new commitment.
            </span>
          </div>
          <button
            onClick={() => router.push(`/projects/${projectId}?tab=baselines`)}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-warning px-3 py-1.5 text-xs font-medium text-text-primary hover:bg-warning/80"
          >
            Take new baseline
          </button>
        </div>
      )}

      {currentTip && <TabTip title={currentTip.title} description={currentTip.description} steps={currentTip.steps} />}
      {tab === "overview" && (
        <div className="space-y-10">
          <ProjectDashboardTab project={project} projectId={projectId} />
          <div className="border-t border-hairline pt-8">
            <div className="mb-4 text-[11px] font-semibold uppercase tracking-[0.14em] text-slate">
              Project Details
            </div>
            <OverviewTab project={project} projectId={projectId} />
          </div>
        </div>
      )}
      {tab === "wbs" && (
        <WbsTab wbsTree={wbsTree} isLoading={isLoadingWbs} projectId={projectId} project={project} />
      )}

      {tab === "baselines" && (
        <BaselinesTab
          projectId={projectId}
          baselines={baselinesData?.data ?? []}
          activeBaselineId={projectData?.data?.activeBaselineId ?? null}
          isLoading={isLoadingBaselines}
          onCreateBaseline={(data) => createBaselineMutation.mutate(data)}
          isCreating={createBaselineMutation.isPending}
          onDeleteBaseline={(baselineId) => deleteBaselineMutation.mutate(baselineId)}
          isDeleting={deleteBaselineMutation.isPending}
          onSetActiveBaseline={(baselineId) => setActiveBaselineMutation.mutate(baselineId)}
          isActivating={setActiveBaselineMutation.isPending}
          activatingBaselineId={setActiveBaselineMutation.variables ?? null}
          onRestoreBaseline={(baselineId) => restoreBaselineMutation.mutate(baselineId)}
          isRestoring={restoreBaselineMutation.isPending}
          restoringBaselineId={restoreBaselineMutation.variables ?? null}
          onUpdateBaseline={(baselineId) => updateBaselineMutation.mutate(baselineId)}
          isUpdatingBaseline={updateBaselineMutation.isPending}
          updatingBaselineId={updateBaselineMutation.variables ?? null}
        />
      )}
      {tab === "resources" && <ResourcesTab projectId={projectId} />}
      {tab === "costs" && <CostsTab projectId={projectId} />}
      {tab === "evm" && <EvmTab projectId={projectId} />}
      {tab === "period-performance" && <PeriodPerformanceTab projectId={projectId} />}
      {tab === "cost-accounts" && <CostAccountRollupTab projectId={projectId} />}
    </div>
  );
}

function OverviewTab({ project, projectId }: { project: ProjectResponse; projectId: string }) {
  const queryClient = useQueryClient();
  const [isTransitioning, setIsTransitioning] = useState(false);
  const [documentsOpen, setDocumentsOpen] = useState(false);

  const { data: poolData } = useQuery({
    queryKey: ["resource-pool", projectId],
    queryFn: () => projectResourceApi.listPool(projectId),
  });

  const poolSize = (() => {
    const raw = poolData?.data as unknown;
    return Array.isArray(raw) ? (raw as unknown[]).length : -1;
  })();

  const { data: kpiSnapshotsData, isLoading: isLoadingKpis } = useQuery({
    queryKey: ["project-kpis", projectId],
    queryFn: () => dashboardApi.getProjectKpiSnapshots(projectId),
  });

  const { data: kpiDefsData } = useQuery({
    queryKey: ["kpi-definitions"],
    queryFn: () => dashboardApi.getKpiDefinitions(),
  });

  const calculateKpisMutation = useMutation({
    mutationFn: () => dashboardApi.calculateProjectKpis(projectId),
    onSuccess: (resp) => {
      // POST /kpi-snapshots/calculate returns the freshly computed snapshots in
      // its ApiResponse envelope. Push them straight into the query cache so the
      // tile re-renders synchronously instead of waiting for the invalidated
      // GET to come back (and so that an empty result is rendered as "0 KPIs"
      // rather than leaving the panel stuck on the "Click Recalculate" prompt
      // forever — DEFECT-9 from the 20260517-1249 QA run).
      const computed = resp?.data;
      if (Array.isArray(computed)) {
        queryClient.setQueryData(["project-kpis", projectId], resp);
      }
      queryClient.invalidateQueries({ queryKey: ["project-kpis", projectId] });
      const count = Array.isArray(computed) ? computed.length : 0;
      if (count === 0) {
        toast.success("KPI recalculation ran, but no active KPI definitions matched this project");
      } else {
        toast.success(`KPIs recalculated (${count})`);
      }
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to calculate KPIs"));
    },
  });

  // axios returns AxiosResponse, so .data is the ApiResponse envelope: {data: KpiSnapshot[]}.
  // Unwrap one more level (mirror line 535's rawKpiDefs handling).
  const rawKpiSnapshots = (kpiSnapshotsData as unknown as { data?: { data?: unknown } })?.data?.data;
  const kpiSnapshots: KpiSnapshot[] = Array.isArray(rawKpiSnapshots)
    ? (rawKpiSnapshots as KpiSnapshot[])
    : Array.isArray(kpiSnapshotsData?.data)
      ? (kpiSnapshotsData.data as unknown as KpiSnapshot[])
      : [];
  // Same envelope unwrap as kpiSnapshots above — kpiDefsData is the AxiosResponse,
  // .data is the ApiResponse envelope, .data.data is the actual array (or {content:[]} for pageable).
  const rawKpiDefs = (kpiDefsData as unknown as { data?: { data?: unknown } })?.data?.data ?? kpiDefsData?.data;
  const kpiDefs: KpiDefinition[] = Array.isArray(rawKpiDefs)
    ? (rawKpiDefs as KpiDefinition[])
    : (rawKpiDefs as unknown as { content?: KpiDefinition[] })?.content ?? [];
  const kpiDefMap = new Map(kpiDefs.map((d: KpiDefinition) => [d.id, d]));

  const statusTransitions: Record<string, { label: string; value: string }[]> = {
    PLANNED: [{ label: "Activate Project", value: "ACTIVE" }],
    ACTIVE: [
      { label: "Complete Project", value: "COMPLETED" },
      { label: "Deactivate Project", value: "INACTIVE" },
    ],
    INACTIVE: [{ label: "Reactivate Project", value: "ACTIVE" }],
    COMPLETED: [],
  };

  const availableTransitions = statusTransitions[project.status] ?? [];

  const handleStatusTransition = async (newStatus: string) => {
    setIsTransitioning(true);
    try {
      await projectApi.updateProject(project.id, { status: newStatus });
      queryClient.invalidateQueries({ queryKey: ["project", project.id] });
      toast.success(`Project status changed to ${newStatus}`);
    } catch (err: unknown) {
      toast.error(getErrorMessage(err, "Failed to update project status"));
    } finally {
      setIsTransitioning(false);
    }
  };

  return (
    <div className="space-y-6">
      <ProjectSetupProgress
        projectId={projectId}
        project={project}
        poolSize={poolSize >= 0 ? poolSize : 0}
      />
      <div className="grid grid-cols-2 gap-6">
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h3 className="text-sm font-medium text-text-secondary">Code</h3>
          <p className="mt-2 text-2xl font-bold text-text-primary">{project.code}</p>
        </div>
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h3 className="text-sm font-medium text-text-secondary">Status</h3>
          <div className="mt-2 flex items-center gap-3">
            <StatusBadge status={project.status} />
            {availableTransitions.length > 0 && (
              <div className="flex gap-2">
                {availableTransitions.map((t) => (
                  <button
                    key={t.value}
                    onClick={() => handleStatusTransition(t.value)}
                    disabled={isTransitioning}
                    className="rounded-md border border-border px-3 py-1 text-xs font-medium text-text-secondary hover:bg-surface-hover/50 disabled:opacity-50"
                  >
                    {isTransitioning ? "..." : t.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <h3 className="text-sm font-medium text-text-secondary">Description</h3>
        <p className="mt-2 text-text-primary">{project.description || "No description"}</p>
      </div>

      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg flex items-center justify-between gap-4">
        <div className="min-w-0">
          <h3 className="text-sm font-medium text-text-secondary uppercase tracking-wider">Documents</h3>
          <p className="mt-1 text-sm text-text-secondary">
            Browse folders, upload files, and manage versions for this project.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setDocumentsOpen(true)}
          className="inline-flex shrink-0 items-center gap-2 rounded-lg border border-gold/45 bg-gold-tint/40 px-3 py-2 text-sm font-semibold text-gold-deep transition-colors hover:border-gold hover:bg-gold-tint"
        >
          <FileText size={15} strokeWidth={1.75} />
          Open Documents
        </button>
      </div>

      <div className="grid grid-cols-2 gap-6">
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h3 className="text-sm font-medium text-text-secondary">Planned Start Date</h3>
          <p className="mt-2 text-lg text-text-primary">{formatDate(project.plannedStartDate)}</p>
        </div>
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h3 className="text-sm font-medium text-text-secondary">Planned Finish Date</h3>
          <p className="mt-2 text-lg text-text-primary">{formatDate(project.plannedFinishDate)}</p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-6">
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h3 className="text-sm font-medium text-text-secondary">Priority</h3>
          <p className={`mt-2 text-lg font-medium ${getPriorityInfo(project.priority).color}`}>
            {getPriorityInfo(project.priority).label} ({project.priority})
          </p>
        </div>
        <DataDateCard project={project} />
      </div>

      <ProjectTeamCard projectId={projectId} />

      <BudgetCard projectId={projectId} />

      <ProjectDetailsSection project={project} projectId={projectId} />

      {/* KPI Mini-Dashboard */}
      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-sm font-medium text-text-secondary uppercase tracking-wider">Key Performance Indicators</h3>
          <button
            onClick={() => calculateKpisMutation.mutate()}
            disabled={calculateKpisMutation.isPending}
            className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-hover/50 disabled:opacity-50"
          >
            <RefreshCw size={12} className={calculateKpisMutation.isPending ? "animate-spin" : ""} />
            {calculateKpisMutation.isPending ? "Calculating..." : "Recalculate"}
          </button>
        </div>
        {isLoadingKpis ? (
          <div className="grid grid-cols-3 gap-4">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-20 animate-pulse rounded-lg bg-surface-hover/50" />
            ))}
          </div>
        ) : kpiSnapshots.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border py-8 text-center">
            <p className="text-text-secondary text-sm">
              {calculateKpisMutation.isSuccess
                ? "Recalculation completed but produced no snapshots. Configure active KPI definitions under Admin → KPI Definitions."
                : "No KPI data yet. Click \"Recalculate\" to generate KPI snapshots."}
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {kpiSnapshots.map((kpi: KpiSnapshot) => {
              const def = kpiDefMap.get(kpi.kpiDefinitionId);
              const statusColor = kpi.status === "GREEN" ? "text-success" : kpi.status === "AMBER" ? "text-warning" : "text-danger";
              const statusBg = kpi.status === "GREEN" ? "bg-success/10 border-success/20" : kpi.status === "AMBER" ? "bg-warning/10 border-warning/20" : "bg-danger/10 border-danger/20";
              return (
                <div key={kpi.id} className={`rounded-lg border p-4 ${statusBg}`}>
                  <p className="text-xs font-medium text-text-secondary truncate">{def?.name ?? kpi.kpiDefinitionId}</p>
                  <p className={`mt-1 text-2xl font-bold ${statusColor}`}>
                    {typeof kpi.value === "number" ? kpi.value.toFixed(2) : kpi.value}
                    {def?.unit ? <span className="ml-1 text-sm font-normal text-text-muted">{def.unit}</span> : null}
                  </p>
                  <p className="mt-1 text-xs text-text-muted">
                    {kpi.calculatedAt ? new Date(kpi.calculatedAt).toLocaleDateString() : ""}
                  </p>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <UdfSection entityId={projectId} subject="PROJECT" projectId={projectId} />

      <Dialog open={documentsOpen} onOpenChange={setDocumentsOpen}>
        <DialogContent className="max-w-6xl h-[85vh] flex flex-col p-0">
          <DialogHeader>
            <DialogTitle>Documents</DialogTitle>
          </DialogHeader>
          <DialogBody className="flex-1 min-h-0 overflow-hidden p-4">
            <ProjectDocumentsPanel projectId={projectId} embedded />
          </DialogBody>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function DataDateCard({ project }: { project: ProjectResponse }) {
  const queryClient = useQueryClient();
  const [isEditing, setIsEditing] = useState(false);
  const [dateValue, setDateValue] = useState(project.dataDate ?? "");
  const [isSaving, setIsSaving] = useState(false);

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await projectApi.updateProject(project.id, { dataDate: dateValue || undefined });
      queryClient.invalidateQueries({ queryKey: ["project", project.id] });
      toast.success("Data date updated");
      setIsEditing(false);
    } catch (err: unknown) {
      toast.error(getErrorMessage(err, "Failed to update data date"));
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
      <h3 className="text-sm font-medium text-text-secondary">Data Date</h3>
      {isEditing ? (
        <div className="mt-2 flex items-center gap-2">
          <input
            type="date"
            value={dateValue}
            onChange={(e) => setDateValue(e.target.value)}
            className="rounded-md border border-border bg-surface-hover px-3 py-1.5 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
          <button
            onClick={handleSave}
            disabled={isSaving}
            className="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
          >
            {isSaving ? "..." : "Save"}
          </button>
          <button
            onClick={() => { setIsEditing(false); setDateValue(project.dataDate ?? ""); }}
            className="rounded-md border border-border px-3 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-hover/50"
          >
            Cancel
          </button>
        </div>
      ) : (
        <div className="mt-2 flex items-center gap-3">
          <p className="text-lg text-text-primary">{project.dataDate ? formatDate(project.dataDate) : "Not set"}</p>
          <button
            onClick={() => setIsEditing(true)}
            className="rounded-md border border-border px-3 py-1 text-xs font-medium text-text-secondary hover:bg-surface-hover/50"
          >
            Set
          </button>
        </div>
      )}
    </div>
  );
}

function BudgetCard({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const [showInitForm, setShowInitForm] = useState(false);
  const [initAmount, setInitAmount] = useState("");

  const { data, isLoading } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
  });

  const setInitMutation = useMutation({
    mutationFn: (amount: number) => budgetApi.setInitialBudget(projectId, amount),
    onSuccess: () => {
      toast.success("Initial budget set");
      queryClient.invalidateQueries({ queryKey: ["project-budget", projectId] });
      queryClient.invalidateQueries({ queryKey: ["project", projectId] });
      setShowInitForm(false);
      setInitAmount("");
    },
    onError: (e: unknown) => toast.error(getErrorMessage(e, "Failed to set budget")),
  });

  const budget = data?.data;
  const original = budget?.originalBudget ?? null;
  const current = budget?.currentBudget ?? null;
  const isSet = original != null;
  const currency = budget?.budgetCurrency ?? "INR";
  const unit = budgetUnit(currency);
  const fmt = (v: number | null) => formatBudget(v, currency);

  const submitInit = () => {
    const n = parseFloat(initAmount);
    if (!isFinite(n) || n <= 0) {
      toast.error(`Enter a positive amount in ${unit.inputLabel}`);
      return;
    }
    setInitMutation.mutate(n);
  };

  return (
    <>
      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <h3 className="text-sm font-medium text-text-secondary">Budget at Completion (BAC)</h3>
            {isLoading ? (
              <div className="mt-2 h-6 w-32 animate-pulse rounded bg-surface-hover/50" />
            ) : isSet ? (
              <div className="mt-2 grid grid-cols-2 gap-6 sm:grid-cols-4">
                <div>
                  <p className="text-xs text-text-muted">Original</p>
                  <p className="mt-0.5 text-lg font-medium text-text-primary">{fmt(original)}</p>
                </div>
                <div>
                  <p className="text-xs text-text-muted">Current</p>
                  <p className="mt-0.5 text-lg font-medium text-text-primary">{fmt(current)}</p>
                </div>
                <div>
                  <p className="text-xs text-text-muted">Approved Δ</p>
                  <p className="mt-0.5 text-sm text-text-primary">
                    +{fmt(budget?.approvedAdditions ?? 0)} / −{fmt(budget?.approvedReductions ?? 0)}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-text-muted">Pending</p>
                  <p className="mt-0.5 text-sm text-text-primary">
                    {budget?.pendingChangeCount ?? 0} request{(budget?.pendingChangeCount ?? 0) === 1 ? "" : "s"}
                  </p>
                </div>
              </div>
            ) : (
              <p className="mt-2 text-lg text-text-muted">Not set</p>
            )}
          </div>
          {isSet ? (
            <Link
              href={`/projects/${projectId}/budget-changes`}
              className="shrink-0 rounded-md border border-border px-3 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Manage
            </Link>
          ) : (
            <button
              type="button"
              onClick={() => setShowInitForm(true)}
              className="shrink-0 rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover"
            >
              Set Budget
            </button>
          )}
        </div>
      </div>

      {showInitForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
          <div className="w-full max-w-md space-y-4 rounded-lg border border-border bg-surface p-6 shadow-2xl">
            <div>
              <h3 className="text-lg font-semibold text-text-primary">Set Initial Budget (BAC)</h3>
              <p className="mt-1 text-xs text-text-muted">
                The approved Budget at Completion. This is set once; later changes go through the budget-change-request workflow.
              </p>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-text-primary">Amount ({unit.inputLabel})</label>
              <input
                type="number"
                min="0.01"
                step="0.01"
                value={initAmount}
                onChange={(e) => setInitAmount(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") submitInit(); }}
                className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                placeholder={currency === "OMR" ? "e.g. 61.5" : "e.g. 250"}
                autoFocus
              />
            </div>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => { setShowInitForm(false); setInitAmount(""); }}
                className="rounded-md border border-border px-4 py-2 text-sm text-text-secondary hover:bg-surface-hover/50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={submitInit}
                disabled={setInitMutation.isPending || !initAmount}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
              >
                {setInitMutation.isPending ? "Saving..." : "Set Budget"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function ProjectDetailsSection({ project }: { project: ProjectResponse; projectId: string }) {
  const queryClient = useQueryClient();
  const [isEditing, setIsEditing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  const { data: categoriesData } = useQuery({
    queryKey: ["project-categories"],
    queryFn: () => projectCategoryApi.listActive(),
    enabled: isEditing,
  });

  const categories = categoriesData?.data ?? [];

  const { data: calendarsData, isLoading: isLoadingCalendars } = useQuery({
    queryKey: ["calendars", "all"],
    queryFn: () => calendarApi.listCalendars(),
  });
  const allCalendars = calendarsData?.data ?? [];

  const [form, setForm] = useState({
    category: project.category ?? "",
    morthCode: project.morthCode ?? "",
    fromChainageM: project.fromChainageM ?? "",
    toChainageM: project.toChainageM ?? "",
    fromLocation: project.fromLocation ?? "",
    toLocation: project.toLocation ?? "",
    calendarId: project.calendarId ?? "",
    contractNumber: project.contract?.contractNumber ?? "",
    contractType: project.contract?.contractType ?? "",
    contractValue: project.contract?.contractValue ?? "",
    revisedValue: project.contract?.revisedValue ?? "",
    dlpMonths: project.contract?.dlpMonths ?? "",
  });

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
  };

  const handleSave = async () => {
    setIsSaving(true);
    try {
      await projectApi.updateProject(project.id, {
        category: form.category || null,
        morthCode: form.morthCode || null,
        fromChainageM: form.fromChainageM ? Number(form.fromChainageM) : null,
        toChainageM: form.toChainageM ? Number(form.toChainageM) : null,
        fromLocation: form.fromLocation || null,
        toLocation: form.toLocation || null,
        calendarId: form.calendarId || null,
        contract: {
          contractNumber: form.contractNumber || null,
          contractType: (form.contractType || null) as ContractType | null,
          contractValue: form.contractValue ? Number(form.contractValue) : null,
          revisedValue: form.revisedValue ? Number(form.revisedValue) : null,
          dlpMonths: form.dlpMonths ? Number(form.dlpMonths) : null,
        },
      });
      queryClient.invalidateQueries({ queryKey: ["project", project.id] });
      toast.success("Project details updated");
      setIsEditing(false);
    } catch (err: unknown) {
      toast.error(getErrorMessage(err, "Failed to update project details"));
    } finally {
      setIsSaving(false);
    }
  };

  const inputClass =
    "mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent";

  const contractTypeOptions = [
    "EPC_LUMP_SUM_FIDIC_YELLOW",
    "EPC_LUMP_SUM_FIDIC_RED",
    "EPC_LUMP_SUM_FIDIC_SILVER",
    "ITEM_RATE_FIDIC_RED",
    "PERCENTAGE_BASED_PMC",
    "LUMP_SUM_UNIT_RATE",
    "EPC",
    "BOT",
    "HAM",
    "ITEM_RATE",
    "LUMP_SUM",
    "ANNUITY",
  ];

  if (isEditing) {
    return (
      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg space-y-6">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-text-secondary">
            Edit Project Details
          </h3>
          <div className="flex gap-2">
            <button
              onClick={handleSave}
              disabled={isSaving}
              className="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
            >
              {isSaving ? "Saving..." : "Save"}
            </button>
            <button
              onClick={() => setIsEditing(false)}
              className="rounded-md border border-border px-3 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
          </div>
        </div>

        <div className="border-t border-border pt-4">
          <h4 className="mb-3 text-xs font-semibold uppercase tracking-wide text-text-secondary">
            Project Category & Corridor
          </h4>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-text-secondary">Category</label>
              <select name="category" value={form.category} onChange={handleChange} className={inputClass}>
                <option value="">—</option>
                {categories.map((c) => (
                  <option key={c.code} value={c.code}>{c.name}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">MoRTH Code</label>
              <input name="morthCode" value={form.morthCode} onChange={handleChange} placeholder="NH-48" className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">From Chainage (m)</label>
              <input name="fromChainageM" type="number" value={form.fromChainageM} onChange={handleChange} placeholder="145000" className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">To Chainage (m)</label>
              <input name="toChainageM" type="number" value={form.toChainageM} onChange={handleChange} placeholder="165000" className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">From Location</label>
              <input name="fromLocation" value={form.fromLocation} onChange={handleChange} placeholder="Km 145+000" className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">To Location</label>
              <input name="toLocation" value={form.toLocation} onChange={handleChange} placeholder="Km 165+000" className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">Calendar</label>
              <select name="calendarId" value={form.calendarId} onChange={handleChange} className={inputClass} disabled={isLoadingCalendars}>
                {isLoadingCalendars && <option value="">Loading…</option>}
                <option value="">— none —</option>
                {allCalendars.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} ({c.standardWorkHoursPerDay}h / {c.standardWorkDaysPerWeek}d)
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>

        <div className="border-t border-border pt-4">
          <h4 className="mb-3 text-xs font-semibold uppercase tracking-wide text-text-secondary">
            Primary Contract
          </h4>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-text-secondary">Contract Number</label>
              <input name="contractNumber" value={form.contractNumber} onChange={handleChange} className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">Contract Type</label>
              <select name="contractType" value={form.contractType} onChange={handleChange} className={inputClass}>
                <option value="">—</option>
                {contractTypeOptions.map((t) => (
                  <option key={t} value={t}>{t.replace(/_/g, " ")}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">Contract Value (₹)</label>
              <input name="contractValue" type="number" step="0.01" value={form.contractValue} onChange={handleChange} className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">Revised Value (₹)</label>
              <input name="revisedValue" type="number" step="0.01" value={form.revisedValue} onChange={handleChange} className={inputClass} />
            </div>
            <div>
              <label className="block text-xs font-medium text-text-secondary">DLP Months</label>
              <input name="dlpMonths" type="number" value={form.dlpMonths} onChange={handleChange} placeholder="60" className={inputClass} />
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-text-secondary">
            Project Category & Corridor
          </h3>
          <button
            onClick={() => setIsEditing(true)}
            className="rounded-md border border-border px-3 py-1 text-xs font-medium text-text-secondary hover:bg-surface-hover/50"
          >
            Edit
          </button>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <p className="text-xs text-text-secondary">Category</p>
            <p className="text-sm font-medium text-text-primary">{project.category ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">MoRTH Code</p>
            <p className="text-sm font-medium text-text-primary">{project.morthCode ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">From Chainage (m)</p>
            <p className="text-sm font-medium text-text-primary">{project.fromChainageM ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">To Chainage (m)</p>
            <p className="text-sm font-medium text-text-primary">{project.toChainageM ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">From Location</p>
            <p className="text-sm font-medium text-text-primary">{project.fromLocation ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">To Location</p>
            <p className="text-sm font-medium text-text-primary">{project.toLocation ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">Calendar</p>
            <p className="text-sm font-medium text-text-primary">
              {(() => {
                const cal = allCalendars.find((c) => c.id === project.calendarId);
                return cal ? `${cal.name} (${cal.standardWorkHoursPerDay}h / ${cal.standardWorkDaysPerWeek}d)` : (project.calendarId ? "Linked" : "—");
              })()}
            </p>
          </div>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-text-secondary">
            Primary Contract
          </h3>
          <button
            onClick={() => setIsEditing(true)}
            className="rounded-md border border-border px-3 py-1 text-xs font-medium text-text-secondary hover:bg-surface-hover/50"
          >
            Edit
          </button>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <p className="text-xs text-text-secondary">Contract Number</p>
            <p className="text-sm font-medium text-text-primary">{project.contract?.contractNumber ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">Contract Type</p>
            <p className="text-sm font-medium text-text-primary">{project.contract?.contractType ?? "—"}</p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">Contract Value (₹)</p>
            <p className="text-sm font-medium text-text-primary">
              {project.contract?.contractValue != null ? `₹ ${project.contract.contractValue.toLocaleString("en-IN")}` : "—"}
            </p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">Revised Value (₹)</p>
            <p className="text-sm font-medium text-text-primary">
              {project.contract?.revisedValue != null ? `₹ ${project.contract.revisedValue.toLocaleString("en-IN")}` : "—"}
            </p>
          </div>
          <div>
            <p className="text-xs text-text-secondary">DLP Months</p>
            <p className="text-sm font-medium text-text-primary">{project.contract?.dlpMonths ?? "—"}</p>
          </div>
        </div>
      </div>
    </div>
  );
}


function WbsTab({ wbsTree, isLoading, projectId, project }: { wbsTree: WbsNodeResponse[]; isLoading: boolean; projectId: string; project: ProjectResponse }) {
  const queryClient = useQueryClient();
  // WbsController gates create/update/delete on PROJECT.UPDATE, so the server
  // is the source of truth — these checks just hide affordances the user
  // can't act on (clean UX, not security).
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canEditWbs = hasPermission("PROJECT.UPDATE");
  // Tree vs flat list — URL-backed so ?tab=wbs&view=list is shareable and survives reloads.
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const view: "tree" | "list" = searchParams.get("view") === "list" ? "list" : "tree";
  const setView = (next: "tree" | "list") => {
    const sp = new URLSearchParams(searchParams.toString());
    if (next === "tree") sp.delete("view");
    else sp.set("view", "list");
    router.replace(`${pathname}?${sp.toString()}`, { scroll: false });
  };
  const [showForm, setShowForm] = useState(false);
  const [showTemplateSelector, setShowTemplateSelector] = useState(false);
  const [showAiDialog, setShowAiDialog] = useState(false);
  const [parentNode, setParentNode] = useState<{ id: string; code: string; name: string } | null>(null);
  const [formData, setFormData] = useState({ code: "", name: "" });
  const [selectedWbs, setSelectedWbs] = useState<{ id: string; code: string; name: string } | null>(null);
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState({ name: "", costAccountId: "" });

  const { data: costAccountsData } = useQuery({
    queryKey: ["cost-accounts"],
    queryFn: () => costApi.listCostAccounts(),
  });
  const costAccounts = costAccountsData?.data ?? [];

  const { data: templatesData } = useQuery({
    queryKey: ["wbs-templates"],
    queryFn: () => wbsTemplateApi.listTemplates(),
    enabled: showTemplateSelector,
  });

  const applyTemplateMutation = useMutation({
    mutationFn: (templateId: string) => wbsTemplateApi.applyTemplate(templateId, projectId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["wbs", projectId] });
      setShowTemplateSelector(false);
      toast.success("WBS template applied successfully");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to apply template"));
    },
  });

  const createMutation = useMutation({
    mutationFn: (data: { code: string; name: string; projectId: string; parentId?: string }) =>
      apiClient.post<AxiosResponse<ApiResponse<WbsNodeResponse>>>(`/v1/projects/${projectId}/wbs`, data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["wbs", projectId] });
      setFormData({ code: "", name: "" });
      setParentNode(null);
      setShowForm(false);
      toast.success("WBS node created");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to create WBS node"));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (nodeId: string) =>
      apiClient.delete(`/v1/projects/${projectId}/wbs/${nodeId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["wbs", projectId] });
      toast.success("WBS node deleted");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to delete WBS node"));
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ nodeId, name, costAccountId }: { nodeId: string; name: string; costAccountId?: string }) =>
      apiClient
        .put<AxiosResponse<ApiResponse<WbsNodeResponse>>>(
          `/v1/projects/${projectId}/wbs/${nodeId}`,
          { name, costAccountId: costAccountId || null }
        )
        .then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["wbs", projectId] });
      setEditingNodeId(null);
      toast.success("WBS node updated");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to update WBS node"));
    },
  });

  const handleDelete = (node: WbsNodeResponse) => {
    if (confirm(`Delete "${node.code} — ${node.name}"${node.children?.length ? " and all its children" : ""}?`)) {
      deleteMutation.mutate(node.id);
    }
  };

  const handleEditOpen = (node: WbsNodeResponse) => {
    setEditingNodeId(node.id);
    setEditForm({ name: node.name, costAccountId: node.costAccountId ?? "" });
    setShowForm(false);
    setParentNode(null);
  };

  const handleEditSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingNodeId || !editForm.name.trim()) return;
    updateMutation.mutate({ nodeId: editingNodeId, name: editForm.name.trim(), costAccountId: editForm.costAccountId });
  };

  const handleAddChild = (node: WbsNodeResponse) => {
    setParentNode({ id: node.id, code: node.code, name: node.name });
    setFormData({ code: "", name: "" });
    setShowForm(true);
  };

  const handleAddRoot = () => {
    setParentNode(null);
    setFormData({ code: "", name: "" });
    setShowForm(!showForm);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.code || !formData.name) return;
    createMutation.mutate({
      code: formData.code,
      name: formData.name,
      projectId,
      parentId: parentNode?.id,
    });
  };

  if (isLoading) {
    return <div className="text-center text-text-muted">Loading WBS...</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="inline-flex overflow-hidden rounded-md border border-border bg-surface-hover/40 text-sm">
          <button
            type="button"
            onClick={() => setView("tree")}
            aria-pressed={view === "tree"}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 font-medium transition-colors ${
              view === "tree"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover hover:text-text-primary"
            }`}
          >
            <FolderTree size={14} /> Tree
          </button>
          <button
            type="button"
            onClick={() => setView("list")}
            aria-pressed={view === "list"}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 font-medium transition-colors ${
              view === "list"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover hover:text-text-primary"
            }`}
          >
            <List size={14} /> List
          </button>
        </div>
        {canEditWbs && (
          <div className="flex gap-2">
            <button
              onClick={() => setShowTemplateSelector(!showTemplateSelector)}
              className="inline-flex items-center gap-2 rounded-md border border-border bg-surface-hover/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover hover:text-text-primary"
            >
              <FileText size={16} />
              Apply Template
            </button>
            <button
              onClick={() => setShowAiDialog(true)}
              className="inline-flex items-center gap-2 rounded-md border border-gold/40 bg-gold-tint px-4 py-2 text-sm font-medium text-gold-ink hover:bg-gold/20"
            >
              <Sparkles size={16} />
              Generate with AI
            </button>
            <button
              onClick={handleAddRoot}
              className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
            >
              <Plus size={16} />
              Add WBS Node
            </button>
          </div>
        )}
      </div>

      {showTemplateSelector && (
        <div className="rounded-xl border border-border bg-surface/50 p-4 shadow-lg">
          <h3 className="text-sm font-semibold text-text-primary mb-3">Select a WBS Template to Apply</h3>
          <p className="text-xs text-text-muted mb-4">This will create WBS nodes from the template structure. Existing nodes will not be affected.</p>
          {!templatesData?.data || (Array.isArray(templatesData.data) && templatesData.data.length === 0) ? (
            <p className="text-sm text-text-muted">No templates available. Create templates in Settings &gt; WBS Templates.</p>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {(Array.isArray(templatesData.data) ? templatesData.data : []).map((template: WbsTemplateResponse) => (
                <button
                  key={template.id}
                  onClick={() => {
                    if (confirm(`Apply template "${template.name}" to this project? This will create WBS nodes from the template.`)) {
                      applyTemplateMutation.mutate(template.id);
                    }
                  }}
                  disabled={applyTemplateMutation.isPending}
                  className="rounded-lg border border-border bg-surface-hover/50 p-4 text-left hover:border-accent/50 hover:bg-surface-hover transition-colors disabled:opacity-50"
                >
                  <div className="text-sm font-medium text-text-primary">{template.name}</div>
                  <div className="text-xs text-text-secondary mt-1">{template.assetClass}</div>
                  {template.description && (
                    <div className="text-xs text-text-muted mt-2 line-clamp-2">{template.description}</div>
                  )}
                </button>
              ))}
            </div>
          )}
          <div className="mt-3">
            <button onClick={() => setShowTemplateSelector(false)} className="text-sm text-text-secondary hover:text-text-primary">Cancel</button>
          </div>
        </div>
      )}

      {showForm && (
        <div className="rounded-xl border border-border bg-surface/50 p-4 shadow-lg">
          <form onSubmit={handleSubmit} className="space-y-3">
            <div className="text-sm text-text-secondary">
              {parentNode
                ? <>Adding child under: <span className="font-medium text-accent">{parentNode.code} — {parentNode.name}</span></>
                : "Adding top-level WBS node"
              }
            </div>
            <div className="flex gap-2">
              <input
                type="text"
                placeholder="Code (e.g., PHASE-1)"
                value={formData.code}
                onChange={(e) => setFormData({ ...formData, code: e.target.value })}
                className="flex-1 rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none"
                required
                autoFocus
              />
              <input
                type="text"
                placeholder="Name (e.g., Design Phase)"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                className="flex-1 rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none"
                required
              />
              <button
                type="submit"
                disabled={createMutation.isPending}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
              >
                {createMutation.isPending ? "Creating..." : "Create"}
              </button>
              <button
                type="button"
                onClick={() => { setShowForm(false); setParentNode(null); }}
                className="rounded-md border border-border px-3 py-2 text-sm text-text-secondary hover:bg-surface-hover"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {editingNodeId && (
        <div className="rounded-xl border border-border bg-surface/50 p-4 shadow-lg">
          <form onSubmit={handleEditSubmit} className="space-y-3">
            <div className="text-sm font-medium text-text-primary">Edit WBS Node</div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium mb-1 text-text-secondary">Name</label>
                <input
                  type="text"
                  value={editForm.name}
                  onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
                  required
                  autoFocus
                />
              </div>
              <div>
                <label className="block text-xs font-medium mb-1 text-text-secondary">
                  Cost Account
                </label>
                <select
                  value={editForm.costAccountId}
                  onChange={(e) => setEditForm({ ...editForm, costAccountId: e.target.value })}
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary"
                >
                  <option value="">— None —</option>
                  {costAccounts.map((ca) => (
                    <option key={ca.id} value={ca.id}>
                      {ca.code} — {ca.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="flex gap-2">
              <button
                type="submit"
                disabled={updateMutation.isPending}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
              >
                {updateMutation.isPending ? "Saving..." : "Save"}
              </button>
              <button
                type="button"
                onClick={() => setEditingNodeId(null)}
                className="rounded-md border border-border px-3 py-2 text-sm text-text-secondary hover:bg-surface-hover"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {view === "list" ? (
        <WorkPackagesListView projectId={projectId} />
      ) : wbsTree.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border py-12 text-center">
          <h3 className="text-lg font-medium text-text-primary">No WBS Structure</h3>
          <p className="mt-2 text-text-muted">
            {canEditWbs
              ? "Click \"Add WBS Node\" above to create your first work package, or use \"Generate with AI\" for a quick start."
              : "No work breakdown structure has been set up for this project yet. Ask the project manager or scheduler to create it."}
          </p>
        </div>
      ) : (
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h2 className="mb-4 text-lg font-semibold text-text-primary">Work Breakdown Structure</h2>
          <WbsTree
            nodes={wbsTree}
            canEdit={canEditWbs}
            onAddChild={handleAddChild}
            onDelete={handleDelete}
            onEdit={handleEditOpen}
            onSelect={(node) => setSelectedWbs(selectedWbs?.id === node.id ? null : { id: node.id, code: node.code, name: node.name })}
            selectedId={selectedWbs?.id ?? null}
          />
        </div>
      )}

      {view === "tree" && selectedWbs && (
        <div className="space-y-2">
          <div className="text-sm text-text-secondary">
            Custom fields for: <span className="font-medium text-accent">{selectedWbs.code} — {selectedWbs.name}</span>
          </div>
          <UdfSection entityId={selectedWbs.id} subject="WBS" projectId={projectId} />
        </div>
      )}

      <WbsAiGenerateDialog
        open={showAiDialog}
        onOpenChange={setShowAiDialog}
        project={project}
      />
    </div>
  );
}

function WbsTree({
  nodes,
  level = 0,
  canEdit = true,
  onAddChild,
  onDelete,
  onEdit,
  onSelect,
  selectedId,
  isLast = [],
}: {
  nodes: WbsNodeResponse[];
  level?: number;
  canEdit?: boolean;
  onAddChild: (node: WbsNodeResponse) => void;
  onDelete: (node: WbsNodeResponse) => void;
  onEdit?: (node: WbsNodeResponse) => void;
  onSelect?: (node: WbsNodeResponse) => void;
  selectedId?: string | null;
  isLast?: boolean[];
}) {
  const [expanded, setExpanded] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {};
    const expand = (items: WbsNodeResponse[]) => {
      for (const n of items) {
        if (n.children?.length) {
          initial[n.id] = true;
          expand(n.children);
        }
      }
    };
    if (level === 0) expand(nodes);
    return initial;
  });

  const toggle = (id: string) => {
    setExpanded((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  return (
    <div className={level === 0 ? "font-mono text-sm" : ""}>
      {nodes.map((node, index) => {
        const hasChildren = node.children && node.children.length > 0;
        const isOpen = expanded[node.id] ?? false;
        const isLastNode = index === nodes.length - 1;

        return (
          <div key={node.id}>
            <div
              onClick={() => onSelect?.(node)}
              className={`group flex items-center hover:bg-surface-hover/50 rounded-md py-1 pr-2 transition-colors cursor-pointer ${selectedId === node.id ? "bg-accent/10 ring-1 ring-blue-500/30" : ""}`}
            >
              {/* Tree guide lines */}
              {Array.from({ length: level }).map((_, i) => (
                <span key={i} className="inline-flex w-6 justify-center flex-shrink-0">
                  {isLast[i] ? (
                    <span className="w-px" />
                  ) : (
                    <span className="w-px bg-surface-active h-full min-h-[28px]" />
                  )}
                </span>
              ))}

              {/* Branch connector */}
              {level > 0 && (
                <span className="inline-flex w-6 items-center justify-center flex-shrink-0 text-text-muted">
                  {isLastNode ? "└" : "├"}
                  <span className="inline-block w-2 h-px bg-surface-active" />
                </span>
              )}

              {/* Expand/collapse toggle */}
              <button
                onClick={() => hasChildren && toggle(node.id)}
                className={`flex-shrink-0 w-5 h-5 flex items-center justify-center rounded ${
                  hasChildren
                    ? "text-text-secondary hover:text-text-primary hover:bg-surface-active cursor-pointer"
                    : "text-transparent cursor-default"
                }`}
              >
                {hasChildren ? (
                  isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />
                ) : null}
              </button>

              {/* Folder / File icon */}
              <span className="flex-shrink-0 mr-2">
                {hasChildren ? (
                  isOpen ? (
                    <FolderOpen size={16} className="text-warning" />
                  ) : (
                    <Folder size={16} className="text-amber-500" />
                  )
                ) : (
                  <File size={16} className="text-text-muted" />
                )}
              </span>

              {/* Node content */}
              <span className="font-semibold text-accent mr-2 flex-shrink-0">{node.code}</span>
              <span className="text-text-secondary truncate">{node.name}</span>

              {/* Duration / percent badges */}
              {node.summaryDuration != null && (
                <span className="ml-2 rounded-full bg-surface-hover px-2 py-0.5 text-xs text-text-secondary flex-shrink-0">
                  {node.summaryDuration}d
                </span>
              )}
              {node.summaryPercentComplete != null && (
                <span className="ml-1 rounded-full bg-emerald-900/40 px-2 py-0.5 text-xs text-success flex-shrink-0">
                  {node.summaryPercentComplete}%
                </span>
              )}

              {/* Action buttons */}
              {canEdit && (
                <span className="ml-auto flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0">
                  {onEdit && (
                    <button
                      onClick={(e) => { e.stopPropagation(); onEdit(node); }}
                      className="rounded p-1 text-text-muted hover:bg-accent/10 hover:text-accent"
                      title="Edit node"
                    >
                      <Pencil size={14} />
                    </button>
                  )}
                  <button
                    onClick={() => onAddChild(node)}
                    className="rounded p-1 text-text-muted hover:bg-success/10 hover:text-success"
                    title="Add child node"
                  >
                    <Plus size={14} />
                  </button>
                  <button
                    onClick={() => onDelete(node)}
                    className="rounded p-1 text-text-muted hover:bg-danger/10 hover:text-danger"
                    title="Delete node"
                  >
                    <Trash2 size={14} />
                  </button>
                </span>
              )}
            </div>

            {/* Render children if expanded */}
            {hasChildren && isOpen && (
              <WbsTree
                nodes={node.children}
                level={level + 1}
                canEdit={canEdit}
                onAddChild={onAddChild}
                onDelete={onDelete}
                onEdit={onEdit}
                onSelect={onSelect}
                selectedId={selectedId}
                isLast={[...isLast, isLastNode]}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}


function BaselinesTab({
  projectId,
  baselines,
  activeBaselineId,
  isLoading,
  onCreateBaseline,
  isCreating,
  onDeleteBaseline,
  isDeleting,
  onSetActiveBaseline,
  isActivating,
  activatingBaselineId,
  onRestoreBaseline,
  isRestoring,
  restoringBaselineId,
  onUpdateBaseline,
  isUpdatingBaseline,
  updatingBaselineId,
}: {
  projectId: string;
  baselines: BaselineResponse[];
  activeBaselineId: string | null;
  isLoading: boolean;
  onCreateBaseline: (data: { name: string; baselineType: string; description?: string }) => void;
  isCreating: boolean;
  onDeleteBaseline: (baselineId: string) => void;
  isDeleting: boolean;
  onSetActiveBaseline: (baselineId: string) => void;
  onRestoreBaseline: (baselineId: string) => void;
  isRestoring: boolean;
  restoringBaselineId: string | null;
  onUpdateBaseline: (baselineId: string) => void;
  isUpdatingBaseline: boolean;
  updatingBaselineId: string | null;
  isActivating: boolean;
  activatingBaselineId: string | null;
}) {
  const [varianceTab, setVarianceTab] = useState<"schedule" | "cost">("schedule");
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState({ name: "", description: "", baselineType: "PROJECT" });
  const [expandedBaselineId, setExpandedBaselineId] = useState<string | null>(null);
  const [varianceData, setVarianceData] = useState<Record<string, BaselineVarianceRow[]>>({});
  const [loadingVarianceId, setLoadingVarianceId] = useState<string | null>(null);
  const [comparisonBaselineId, setComparisonBaselineId] = useState<string | null>(null);
  const [comparisonData, setComparisonData] = useState<Record<string, any[]>>({});
  const [loadingComparisonId, setLoadingComparisonId] = useState<string | null>(null);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (formData.name.trim()) {
      onCreateBaseline({
        name: formData.name,
        baselineType: formData.baselineType,
        description: formData.description.trim() || undefined,
      });
      setFormData({ name: "", description: "", baselineType: "PROJECT" });
      setShowForm(false);
    }
  };

  const handleViewVariance = async (baselineId: string) => {
    if (expandedBaselineId === baselineId) {
      setExpandedBaselineId(null);
      return;
    }

    if (varianceData[baselineId]) {
      setExpandedBaselineId(baselineId);
      return;
    }

    setLoadingVarianceId(baselineId);
    try {
      const response = await baselineApi.getVariance(projectId, baselineId);
      if (response?.data) {
        setVarianceData((prev) => ({
          ...prev,
          [baselineId]: response.data!,
        }));
        setExpandedBaselineId(baselineId);
      }
    } catch (error) {
      console.error("Failed to load variance data:", error);
    } finally {
      setLoadingVarianceId(null);
    }
  };

  const handleCompareSchedule = async (baselineId: string) => {
    if (comparisonBaselineId === baselineId) {
      setComparisonBaselineId(null);
      return;
    }

    if (comparisonData[baselineId]) {
      setComparisonBaselineId(baselineId);
      return;
    }

    setLoadingComparisonId(baselineId);
    try {
      const response = await baselineApi.getScheduleComparison(projectId, baselineId);
      if (response?.data) {
        setComparisonData((prev) => ({
          ...prev,
          [baselineId]: response.data!,
        }));
        setComparisonBaselineId(baselineId);
      }
    } catch (error) {
      console.error("Failed to load schedule comparison data:", error);
    } finally {
      setLoadingComparisonId(null);
    }
  };

  if (isLoading) {
    return <div className="text-center text-text-muted">Loading baselines...</div>;
  }

  return (
    <div className="space-y-6">
      {!showForm && (
        <div className="flex flex-wrap items-center gap-3">
          <button
            onClick={() => setShowForm(true)}
            className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
          >
            <Plus size={16} />
            Create Baseline
          </button>
          <Link
            href={`/projects/${projectId}/baselines/assign`}
            className="inline-flex items-center gap-2 rounded-md border border-border bg-surface/60 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
            title="Assign baselines to PRIMARY / SECONDARY / TERTIARY slots (P6-style)"
          >
            Assign Baselines
          </Link>
        </div>
      )}

      {showForm && (
        <div className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
          <h3 className="mb-4 text-lg font-semibold text-text-primary">Create New Baseline</h3>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Name</label>
              <input
                type="text"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none"
                placeholder="Baseline name"
                required
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">Description</label>
              <textarea
                value={formData.description}
                onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                rows={3}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none"
                placeholder="Optional notes — e.g. 'after VO-12 approved', 'pre-monsoon plan'"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">Type</label>
              <select
                value={formData.baselineType}
                onChange={(e) => setFormData({ ...formData, baselineType: e.target.value })}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
              >
                <option value="PROJECT">PROJECT</option>
                <option value="PRIMARY">PRIMARY</option>
                <option value="SECONDARY">SECONDARY</option>
                <option value="TERTIARY">TERTIARY</option>
              </select>
            </div>
            <div className="flex gap-3">
              <button
                type="submit"
                disabled={isCreating}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
              >
                {isCreating ? "Creating..." : "Create"}
              </button>
              <button
                type="button"
                onClick={() => setShowForm(false)}
                className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {baselines.length === 0 ? (
        <EmptyState
          icon={ListTodo}
          title="No baselines"
          description="This project has no baselines yet. Create a baseline to start tracking project variance."
        />
      ) : (
        <div className="space-y-4">
          {baselines.map((baseline) => {
            const isActive = activeBaselineId === baseline.id;
            return (
            <div key={baseline.id} className="rounded-xl border border-border bg-surface/50 p-6 shadow-lg">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-2">
                    <h3 className="text-lg font-semibold text-text-primary">{baseline.name}</h3>
                    {isActive && (
                      <span className="inline-flex items-center gap-1 rounded-md border border-gold/40 bg-gold-tint px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-gold-ink">
                        <span className="h-1.5 w-1.5 rounded-full bg-gold" />
                        Active
                      </span>
                    )}
                  </div>
                  <div className="mt-2 space-y-1 text-sm text-text-secondary">
                    <p>Type: {baseline.baselineType}</p>
                    <p>Date: {new Date(baseline.baselineDate).toLocaleDateString()}</p>
                    <p>Activities: {baseline.totalActivities}</p>
                    {baseline.totalCost > 0 && <p>Total Cost: {formatDefaultCurrency(baseline.totalCost)}</p>}
                  </div>
                </div>
                <div className="flex flex-wrap justify-end gap-2">
                  {!isActive && (
                    <button
                      onClick={() => onSetActiveBaseline(baseline.id)}
                      disabled={isActivating && activatingBaselineId === baseline.id}
                      className="inline-flex items-center gap-2 rounded-md border border-gold/40 bg-gold-tint px-3 py-2 text-sm font-medium text-gold-ink hover:bg-gold/20 disabled:opacity-50"
                    >
                      {isActivating && activatingBaselineId === baseline.id ? "Setting…" : "Set as active"}
                    </button>
                  )}
                  <button
                    onClick={() => handleViewVariance(baseline.id)}
                    disabled={loadingVarianceId === baseline.id}
                    className="inline-flex items-center gap-2 rounded-md bg-surface-hover/50 px-3 py-2 text-sm font-medium text-text-secondary hover:bg-surface-active/50 disabled:opacity-50"
                  >
                    <Eye size={16} />
                    {expandedBaselineId === baseline.id ? "Hide Variance" : "View Variance"}
                  </button>
                  <button
                    onClick={() => handleCompareSchedule(baseline.id)}
                    disabled={loadingComparisonId === baseline.id}
                    className="inline-flex items-center gap-2 rounded-md bg-accent/10 px-3 py-2 text-sm font-medium text-accent hover:bg-accent-hover/20 disabled:opacity-50"
                  >
                    <Eye size={16} />
                    {comparisonBaselineId === baseline.id ? "Hide Compare" : "Compare"}
                  </button>
                  <button
                    onClick={() => {
                      if (
                        window.confirm(
                          `Refresh "${baseline.name}" with the current project's dates, durations, costs, and relationships?\n\nThis is the P6 "Update Baseline" action. Existing snapshot entries are overwritten; new activities are inserted.`
                        )
                      ) {
                        onUpdateBaseline(baseline.id);
                      }
                    }}
                    disabled={isUpdatingBaseline && updatingBaselineId === baseline.id}
                    className="inline-flex items-center gap-2 rounded-md bg-accent/10 px-3 py-2 text-sm font-medium text-accent hover:bg-accent-hover/20 disabled:opacity-50"
                    title="Update Baseline: re-snapshot the current project state into this baseline. Use the API directly for selective filters."
                  >
                    {isUpdatingBaseline && updatingBaselineId === baseline.id ? "Updating…" : "Update"}
                  </button>
                  <button
                    onClick={() => {
                      if (
                        window.confirm(
                          `Restore the project's planned dates, durations, and relationships from "${baseline.name}"?\n\nActual progress (start/finish dates, % complete) will be preserved. This action is audit-logged but not undoable.`
                        )
                      ) {
                        onRestoreBaseline(baseline.id);
                      }
                    }}
                    disabled={isRestoring && restoringBaselineId === baseline.id}
                    className="inline-flex items-center gap-2 rounded-md bg-warning/10 px-3 py-2 text-sm font-medium text-warning hover:bg-warning/20 disabled:opacity-50"
                    title="Restore: overwrite planned dates / durations / relationships from this baseline. Actuals preserved."
                  >
                    {isRestoring && restoringBaselineId === baseline.id ? "Restoring…" : "Restore"}
                  </button>
                  <button
                    onClick={() => {
                      if (window.confirm("Are you sure you want to delete this baseline?")) {
                        onDeleteBaseline(baseline.id);
                      }
                    }}
                    disabled={isDeleting}
                    className="inline-flex items-center gap-2 rounded-md bg-danger/10 px-3 py-2 text-sm font-medium text-danger hover:bg-danger/20 disabled:opacity-50"
                  >
                    <Trash2 size={16} />
                    Delete
                  </button>
                </div>
              </div>

              {expandedBaselineId === baseline.id && varianceData[baseline.id] && (
                <div className="mt-6 border-t border-border pt-6">
                  <h4 className="mb-4 font-semibold text-text-primary">Variance Dashboard</h4>
                  <VarianceDashboard data={varianceData[baseline.id]} />
                </div>
              )}

              {comparisonBaselineId === baseline.id && comparisonData[baseline.id] && (
                <div className="mt-6 border-t border-border pt-6">
                  <h4 className="mb-4 font-semibold text-text-primary">Schedule Comparison</h4>
                  <ScheduleComparisonTable data={comparisonData[baseline.id]} />
                </div>
              )}
            </div>
            );
          })}
        </div>
      )}

      {activeBaselineId && (
        <div className="mt-8 space-y-4">
          <div className="flex flex-wrap items-end justify-between gap-3 border-t border-hairline pt-6">
            <div>
              <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1">
                Variance · vs active baseline
              </div>
              <h2 className="font-display text-2xl font-semibold tracking-tight text-charcoal">
                Schedule &amp; cost variance
              </h2>
            </div>
            <div className="inline-flex rounded-lg border border-hairline bg-ivory p-0.5">
              <button
                type="button"
                onClick={() => setVarianceTab("schedule")}
                className={`rounded-md px-4 py-2 text-sm font-semibold transition-colors ${
                  varianceTab === "schedule"
                    ? "bg-paper text-charcoal shadow-sm"
                    : "text-slate hover:text-charcoal"
                }`}
              >
                Schedule
              </button>
              <button
                type="button"
                onClick={() => setVarianceTab("cost")}
                className={`rounded-md px-4 py-2 text-sm font-semibold transition-colors ${
                  varianceTab === "cost"
                    ? "bg-paper text-charcoal shadow-sm"
                    : "text-slate hover:text-charcoal"
                }`}
              >
                Cost
              </button>
            </div>
          </div>
          {varianceTab === "schedule" ? (
            <ScheduleVarianceSection projectId={projectId} baselineId={activeBaselineId} />
          ) : (
            <CostVarianceSection projectId={projectId} baselineId={activeBaselineId} />
          )}
        </div>
      )}
    </div>
  );
}

function ComingSoonTab({ tabName }: { tabName: string }) {
  return (
    <div className="rounded-lg border border-dashed border-border py-12 text-center">
      <h3 className="text-lg font-medium text-text-primary capitalize">{tabName}</h3>
      <p className="mt-2 text-text-muted">This feature is coming soon.</p>
    </div>
  );
}

