"use client";

import { useEffect, useState } from "react";
import { useRouter, useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { List, FolderTree, Play, AlertTriangle, Sparkles, Columns3, UserCheck, BarChart2, GitBranch, ListTodo, ArrowRight } from "lucide-react";
import toast from "react-hot-toast";
import { PageHeader } from "@/components/common/PageHeader";
import { activityApi } from "@/lib/api/activityApi";
import type { ActivityResponse } from "@/lib/api/activityApi";
import { projectApi } from "@/lib/api/projectApi";
import { baselineApi } from "@/lib/api/baselineApi";
import type { BaselineActivityResponse } from "@/lib/api/baselineApi";
import { costApi } from "@/lib/api/costApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { StatusBadge } from "@/components/common/StatusBadge";
import { EmptyState } from "@/components/common/EmptyState";
import { GanttChart } from "@/components/schedule/GanttChart";
import { NetworkDiagram } from "@/components/schedule/NetworkDiagram";
import { ActivityWbsTreeView } from "@/components/activity/ActivityWbsTreeView";
import { ActivityAiGenerateDialog } from "@/components/activity/ActivityAiGenerateDialog";
import { ActivityDetailDrawer } from "@/components/activity/ActivityDetailDrawer";
import {
  ActivityRowContextMenu,
  type ContextMenuPosition,
} from "@/components/activity/ActivityRowContextMenu";
import {
  QuickAssignResourceDialog,
  type ResourceKind,
} from "@/components/activity/QuickAssignResourceDialog";
import { SetSupervisorDialog } from "@/components/activity/SetSupervisorDialog";
import { AssignSupervisorDrawer } from "@/components/activity/AssignSupervisorDrawer";
import { ActivityMasterStatusIndicator } from "@/components/activity/ActivityMasterStatusIndicator";
import { LinkOrCreateWorkActivityDialog } from "@/components/activity/LinkOrCreateWorkActivityDialog";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogBody,
  DialogFooter,
} from "@/components/ui/dialog";
import { getErrorMessage } from "@/lib/utils/error";
import { notificationHelpers } from "@/lib/notificationHelpers";
import Link from "next/link";
import { useScheduleStaleStore } from "@/lib/state/scheduleStaleStore";
import {
  schedulePercentComplete,
  scheduleVarianceBucket,
} from "@/lib/utils/schedulePercent";
import { ScheduleLogPanel } from "@/components/schedule/ScheduleLogPanel";
import type { ScheduleResultResponse } from "@/lib/api/scheduleApi";
import { useAuthStore } from "@/lib/state/store";
import type { ProjectResponse, WbsNodeResponse } from "@/lib/types";

const todayIso = () => new Date().toISOString().slice(0, 10);

/**
 * Optional columns on the activities list grid (Phase 1.2 of the baseline-progress roadmap).
 * Off by default so the existing grid layout doesn't shift on first load — the planner opts in
 * via the Columns menu, and the choice persists in localStorage.
 */
type OptionalColumns = {
  actualDates: boolean;
  baselineDates: boolean;
  schedulePct: boolean;
  actualCost: boolean;
  /** Phase 2: budget variance = actualCost - budgetedCost. Positive = overrun (red). */
  budgetVariance: boolean;
};
const DEFAULT_OPTIONAL_COLS: OptionalColumns = {
  actualDates: false,
  baselineDates: false,
  schedulePct: false,
  actualCost: false,
  budgetVariance: false,
};
const OPTIONAL_COLS_KEY = "activities-grid-optional-cols";

export default function ActivitiesPage() {
  const router = useRouter();
  const params = useParams();
  const projectId = params.projectId as string;
  const qc = useQueryClient();

  const [lookAheadWeeks, setLookAheadWeeks] = useState<4 | 13 | null>(null);
  const [viewMode, setViewMode] = useState<"list" | "tree" | "gantt" | "network">("tree");
  const [scheduleError, setScheduleError] = useState("");
  const [showAiDialog, setShowAiDialog] = useState(false);
  // Row-specific inline editor state. Keyed by activity id; value = string
  // currently typed into the % input.
  const [progressEdit, setProgressEdit] = useState<Record<string, string>>({});
  const [pendingId, setPendingId] = useState<string | null>(null);

  // Row interaction state — left-click selects (opens drawer), right-click opens the menu.
  const [selectedActivityId, setSelectedActivityId] = useState<string | null>(null);
  const [contextMenu, setContextMenu] = useState<
    { activity: ActivityResponse; position: ContextMenuPosition } | null
  >(null);
  const [quickAssign, setQuickAssign] = useState<
    { activity: ActivityResponse; kind: ResourceKind } | null
  >(null);
  const [deleteTarget, setDeleteTarget] = useState<ActivityResponse | null>(null);
  const [supervisorTarget, setSupervisorTarget] = useState<ActivityResponse | null>(null);
  const [bulkSupervisorOpen, setBulkSupervisorOpen] = useState(false);
  const [masterDialog, setMasterDialog] = useState<{
    activity: ActivityResponse;
    mode: "LINK_OR_CREATE" | "CONFIGURE_NORMS_ONLY";
  } | null>(null);

  // ActivityController gates POST on ACTIVITY.CREATE and DELETE on ACTIVITY.DELETE.
  // Server is source of truth — these checks just hide affordances the user can't act on.
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCreateActivity = hasPermission("ACTIVITY.CREATE");
  const canDeleteActivity = hasPermission("ACTIVITY.DELETE");

  const markScheduleStale = useScheduleStaleStore((s) => s.markScheduleStale);
  const markScheduleFresh = useScheduleStaleStore((s) => s.markScheduleFresh);
  const isStale = useScheduleStaleStore((s) => s.isScheduleStale(projectId));

  const { data: activitiesData, isLoading: isLoadingActivities } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 500),
    enabled: !!projectId,
  });

  const { data: wbsData, isLoading: isLoadingWbs } = useQuery({
    queryKey: ["wbs", projectId],
    queryFn: () => projectApi.getWbsTree(projectId),
    enabled: !!projectId,
  });

  const { data: relationshipsData, isLoading: isLoadingRelationships } = useQuery({
    queryKey: ["relationships", projectId],
    queryFn: () => activityApi.getRelationships(projectId),
    enabled: !!projectId,
  });

  // Project provides dataDate (drives Schedule % calculation when rendering optional columns).
  const { data: projectData, isLoading: isLoadingProject } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });

  // Baselines list — pick the first PRIMARY baseline for the BL Start / BL Finish columns
  // and to feed the Schedule % calculation when present.
  const { data: baselinesData } = useQuery({
    queryKey: ["baselines", projectId],
    queryFn: () => baselineApi.listBaselines(projectId),
    enabled: !!projectId,
  });
  const primaryBaseline = baselinesData?.data?.find((b) => b.baselineType === "PRIMARY");

  const { data: baselineDetail } = useQuery({
    queryKey: ["baseline-detail", projectId, primaryBaseline?.id],
    queryFn: () => baselineApi.getBaseline(projectId, primaryBaseline!.id),
    enabled: !!projectId && !!primaryBaseline,
  });

  // Per-activity cost rollup. Backed by /v1/projects/{id}/activities/cost-summary which
  // shares its math with the baseline snapshot (ActivityCostCalculator).
  const { data: costSummaryData } = useQuery({
    queryKey: ["activity-cost-summary", projectId],
    queryFn: () => costApi.getActivityCostSummary(projectId),
    enabled: !!projectId,
  });

  const activities = (activitiesData?.data?.content || []) as ActivityResponse[];
  const wbsNodes = wbsData?.data ?? [];
  const relationships = relationshipsData?.data ?? [];
  const project = projectData?.data ?? null;
  const dataDate: string | null = project?.dataDate ?? null;

  const baselineByActivity = new Map<string, BaselineActivityResponse>();
  for (const ba of baselineDetail?.data?.activities ?? []) {
    baselineByActivity.set(ba.activityId, ba);
  }
  const actualCostByActivity = new Map<string, number>();
  const budgetedCostByActivity = new Map<string, number>();
  for (const row of costSummaryData?.data ?? []) {
    actualCostByActivity.set(row.activityId, row.actualCost);
    budgetedCostByActivity.set(row.activityId, row.budgetedCost);
  }

  // Optional column visibility (Phase 1.2). Persisted in localStorage so the planner doesn't
  // need to re-tick boxes every time they navigate back to the activities page.
  const [optionalCols, setOptionalCols] = useState<OptionalColumns>(DEFAULT_OPTIONAL_COLS);
  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(OPTIONAL_COLS_KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<OptionalColumns>;
        setOptionalCols({ ...DEFAULT_OPTIONAL_COLS, ...parsed });
      }
    } catch {
      // ignore — fall back to defaults
    }
  }, []);
  const updateOptionalCols = (next: OptionalColumns) => {
    setOptionalCols(next);
    if (typeof window !== "undefined") {
      window.localStorage.setItem(OPTIONAL_COLS_KEY, JSON.stringify(next));
    }
  };
  const [showColumnsMenu, setShowColumnsMenu] = useState(false);

  // Latest schedule result drives the schedule-log panel (Phase 1.5). Reset to null when the
  // user dismisses the panel; replaced when Run Schedule resolves.
  const [latestScheduleResult, setLatestScheduleResult] = useState<ScheduleResultResponse | null>(null);

  const scheduleMutation = useMutation({
    mutationFn: () => activityApi.triggerSchedule(projectId, "RETAINED_LOGIC"),
    onSuccess: (resp) => {
      qc.invalidateQueries({ queryKey: ["activities", projectId] });
      qc.invalidateQueries({ queryKey: ["critical-path", projectId] });
      markScheduleFresh(projectId);
      setScheduleError("");
      const result = resp?.data;
      if (result) setLatestScheduleResult(result);
      const warningCount = result?.warnings?.length ?? 0;
      toast.success(
        warningCount > 0
          ? `Schedule calculated — ${warningCount} warning${warningCount === 1 ? "" : "s"}`
          : "Schedule calculated successfully"
      );
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to trigger schedule");
      setScheduleError(msg);
      toast.error(msg);
    },
  });

  const progressMutation = useMutation({
    mutationFn: async (vars: {
      id: string;
      percentCompleteType?: string | null;
      percentComplete: number;
      actualStartDate?: string;
      actualFinishDate?: string;
    }) => {
      setPendingId(vars.id);
      const isManualPercent = (vars.percentCompleteType ?? "DURATION") === "PHYSICAL";
      // For DURATION/UNITS the backend rejects manual percentComplete writes —
      // status is derived from actual dates. Use the regular update endpoint
      // and pass only the dates; the calculator + status derivation handle the rest.
      if (!isManualPercent) {
        return activityApi.updateActivity(projectId, vars.id, {
          actualStartDate: vars.actualStartDate,
          actualFinishDate: vars.actualFinishDate,
        });
      }
      return activityApi.updateProgress(
        projectId,
        vars.id,
        vars.percentComplete,
        vars.actualStartDate,
        vars.actualFinishDate
      );
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["activities", projectId] });
      markScheduleStale(projectId);
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to update progress");
      notificationHelpers.handleApiError(err, msg);
    },
    onSettled: () => setPendingId(null),
  });

  const getFilteredActivities = () => {
    if (!lookAheadWeeks) return activities;
    const today = new Date();
    const endDate = new Date(today);
    endDate.setDate(endDate.getDate() + lookAheadWeeks * 7);
    return activities.filter((activity: ActivityResponse) => {
      const startDate = activity.earlyStartDate || activity.plannedStartDate;
      if (!startDate) return false;
      const activityStart = new Date(startDate);
      return activityStart >= today && activityStart <= endDate;
    });
  };

  const filteredActivities = getFilteredActivities();

  const canStart = (a: ActivityResponse) =>
    a.status === "NOT_STARTED" || (a.percentComplete ?? 0) === 0;
  const canComplete = (a: ActivityResponse) => a.status !== "COMPLETED";

  const start = (a: ActivityResponse) =>
    progressMutation.mutate({
      id: a.id,
      percentCompleteType: a.percentCompleteType,
      percentComplete: Math.max(a.percentComplete ?? 0, 1),
      actualStartDate: a.actualStartDate || todayIso(),
      actualFinishDate: a.actualFinishDate ?? undefined,
    });

  const complete = (a: ActivityResponse) =>
    progressMutation.mutate({
      id: a.id,
      percentCompleteType: a.percentCompleteType,
      percentComplete: 100,
      actualStartDate: a.actualStartDate || a.plannedStartDate || todayIso(),
      actualFinishDate: todayIso(),
    });

  const saveProgress = (a: ActivityResponse) => {
    const raw = progressEdit[a.id];
    const pct = Math.max(0, Math.min(100, parseFloat(raw ?? "") || 0));
    const actualStart =
      pct > 0 && !a.actualStartDate ? todayIso() : (a.actualStartDate ?? undefined);
    const actualFinish = pct >= 100 ? todayIso() : (a.actualFinishDate ?? undefined);
    progressMutation.mutate({
      id: a.id,
      percentCompleteType: a.percentCompleteType,
      percentComplete: pct,
      actualStartDate: actualStart,
      actualFinishDate: actualFinish,
    });
    setProgressEdit((s) => {
      const next = { ...s };
      delete next[a.id];
      return next;
    });
  };

  const deleteMutation = useMutation({
    mutationFn: (activityId: string) => activityApi.deleteActivity(projectId, activityId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["activities", projectId] });
      qc.invalidateQueries({ queryKey: ["wbs", projectId] });
      qc.invalidateQueries({ queryKey: ["relationships", projectId] });
      markScheduleStale(projectId);
      toast.success("Activity deleted");
      setDeleteTarget(null);
      // If the deleted activity was open in the drawer, close it.
      setSelectedActivityId((prev) => (prev && deleteTarget && prev === deleteTarget.id ? null : prev));
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to delete activity");
      notificationHelpers.handleApiError(err, msg);
    },
  });

  // Row click toggles the drawer for the same activity / switches to a new one.
  const handleRowClick = (a: ActivityResponse) => {
    setSelectedActivityId((prev) => (prev === a.id ? null : a.id));
  };

  const handleRowContextMenu = (a: ActivityResponse, x: number, y: number) => {
    setContextMenu({ activity: a, position: { x, y } });
  };

  const handleAssign = (a: ActivityResponse, kind: ResourceKind) => {
    setQuickAssign({ activity: a, kind });
  };

  const handleEditFromMenu = (a: ActivityResponse) => {
    setProgressEdit((s) => ({ ...s, [a.id]: String(a.percentComplete ?? 0) }));
  };

  const isLoading =
    isLoadingActivities ||
    isLoadingProject ||
    (viewMode === "tree" && isLoadingWbs) ||
    ((viewMode === "gantt" || viewMode === "network") && isLoadingRelationships);

  return (
    <div>
      <PageHeader
        title="Activities"
        description="View and manage project activities"
        actions={
          <div className="flex gap-2">
            {canCreateActivity && (
              <button
                onClick={() => setBulkSupervisorOpen(true)}
                className="inline-flex items-center gap-2 rounded-md border border-border bg-surface-hover px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-active"
                title="Bulk-assign one supervisor across many activities"
              >
                <UserCheck size={16} />
                Assign Supervisor
              </button>
            )}
            {canCreateActivity && (
              <button
                onClick={() => setShowAiDialog(true)}
                className="inline-flex items-center gap-2 rounded-md border border-gold/40 bg-gold-tint px-4 py-2 text-sm font-medium text-gold-ink hover:bg-gold/20"
              >
                <Sparkles size={16} />
                Generate with AI
              </button>
            )}
            {canCreateActivity && (
              <button
                onClick={() => router.push(`/projects/${projectId}/activities/new`)}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
              >
                New Activity
              </button>
            )}
          </div>
        }
      />

      {isStale && (
        <div className="mb-4 flex items-center justify-between gap-4 rounded-md border border-warning/40 bg-warning/10 px-4 py-3">
          <div className="flex items-center gap-2 text-sm text-warning">
            <AlertTriangle size={16} className="shrink-0" />
            <span>Schedule is out of date — early/late dates and critical path may not reflect recent changes.</span>
          </div>
          <button
            onClick={() => scheduleMutation.mutate()}
            disabled={scheduleMutation.isPending}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-warning px-3 py-1.5 text-xs font-medium text-text-primary hover:bg-warning/80 disabled:opacity-50"
          >
            <Play size={12} />
            Run now
          </button>
        </div>
      )}

      {/* Controls */}
      <div className="mb-6 flex flex-wrap items-center gap-4">
        {/* Run Schedule */}
        <div className="relative">
          <button
            onClick={() => scheduleMutation.mutate()}
            disabled={scheduleMutation.isPending}
            className={`inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium text-text-primary disabled:opacity-50 ${
              isStale
                ? "bg-warning hover:bg-warning/80"
                : "bg-success hover:bg-success/80"
            }`}
          >
            <Play size={16} />
            {scheduleMutation.isPending ? "Running..." : "Run Schedule"}
          </button>
          {isStale && (
            <span className="absolute -right-1 -top-1 h-2.5 w-2.5 rounded-full bg-warning ring-2 ring-surface" />
          )}
        </div>

        {/* Look-Ahead Filter — only meaningful in list/tree mode */}
        {viewMode !== "gantt" && viewMode !== "network" && (
          <div className="flex gap-2">
            <button
              onClick={() => setLookAheadWeeks(null)}
              className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                lookAheadWeeks === null
                  ? "bg-accent text-accent-foreground"
                  : "bg-surface-active/50 text-text-secondary hover:bg-surface-active"
              }`}
            >
              All Activities
            </button>
            <button
              onClick={() => setLookAheadWeeks(4)}
              className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                lookAheadWeeks === 4
                  ? "bg-accent text-accent-foreground"
                  : "bg-surface-active/50 text-text-secondary hover:bg-surface-active"
              }`}
            >
              4-Week Look-Ahead
            </button>
            <button
              onClick={() => setLookAheadWeeks(13)}
              className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                lookAheadWeeks === 13
                  ? "bg-accent text-accent-foreground"
                  : "bg-surface-active/50 text-text-secondary hover:bg-surface-active"
              }`}
            >
              13-Week Look-Ahead
            </button>
          </div>
        )}

        {/* View Mode Toggle */}
        <div className="inline-flex rounded-lg border border-border bg-surface/60 p-0.5">
          <button
            onClick={() => setViewMode("list")}
            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              viewMode === "list"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
            }`}
          >
            <List size={14} />
            List
          </button>
          <button
            onClick={() => setViewMode("tree")}
            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              viewMode === "tree"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
            }`}
          >
            <FolderTree size={14} />
            WBS Tree
          </button>
          <button
            onClick={() => setViewMode("gantt")}
            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              viewMode === "gantt"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
            }`}
          >
            <BarChart2 size={14} />
            Gantt
          </button>
          <button
            onClick={() => setViewMode("network")}
            className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              viewMode === "network"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
            }`}
          >
            <GitBranch size={14} />
            Network
          </button>
        </div>

        {/* Columns picker — only meaningful in list mode */}
        {viewMode === "list" && (
          <div className="relative">
            <button
              type="button"
              onClick={() => setShowColumnsMenu((s) => !s)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-surface/60 px-3 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-hover/50 hover:text-text-primary"
            >
              <Columns3 size={14} />
              Columns
            </button>
            {showColumnsMenu && (
              <div
                className="absolute right-0 z-20 mt-2 w-64 rounded-md border border-border bg-surface p-3 shadow-lg"
                onMouseLeave={() => setShowColumnsMenu(false)}
              >
                <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
                  Optional columns
                </div>
                <label className="flex items-center gap-2 py-1 text-sm text-text-primary">
                  <input
                    type="checkbox"
                    checked={optionalCols.actualDates}
                    onChange={(e) =>
                      updateOptionalCols({ ...optionalCols, actualDates: e.target.checked })
                    }
                  />
                  Actual Start / Actual Finish
                </label>
                <label className="flex items-center gap-2 py-1 text-sm text-text-primary">
                  <input
                    type="checkbox"
                    checked={optionalCols.baselineDates}
                    onChange={(e) =>
                      updateOptionalCols({ ...optionalCols, baselineDates: e.target.checked })
                    }
                    disabled={!primaryBaseline}
                  />
                  Baseline Start / Finish
                  {!primaryBaseline && (
                    <span className="text-xs text-text-muted">(no PRIMARY baseline)</span>
                  )}
                </label>
                <label className="flex items-center gap-2 py-1 text-sm text-text-primary">
                  <input
                    type="checkbox"
                    checked={optionalCols.schedulePct}
                    onChange={(e) =>
                      updateOptionalCols({ ...optionalCols, schedulePct: e.target.checked })
                    }
                    disabled={!dataDate}
                  />
                  Schedule % Complete
                  {!dataDate && (
                    <span className="text-xs text-text-muted">(set Data Date first)</span>
                  )}
                </label>
                <label className="flex items-center gap-2 py-1 text-sm text-text-primary">
                  <input
                    type="checkbox"
                    checked={optionalCols.actualCost}
                    onChange={(e) =>
                      updateOptionalCols({ ...optionalCols, actualCost: e.target.checked })
                    }
                  />
                  Actual Total Cost
                </label>
                <label className="flex items-center gap-2 py-1 text-sm text-text-primary">
                  <input
                    type="checkbox"
                    checked={optionalCols.budgetVariance}
                    onChange={(e) =>
                      updateOptionalCols({ ...optionalCols, budgetVariance: e.target.checked })
                    }
                  />
                  Budget Variance
                  <span className="text-xs text-text-muted">(actual − budget)</span>
                </label>
              </div>
            )}
          </div>
        )}
      </div>

      {scheduleError && (
        <div className="mb-4 rounded-md bg-danger/10 border border-danger/30 p-3 text-sm text-danger">
          {scheduleError}
        </div>
      )}

      {latestScheduleResult && (
        <div className="mb-4">
          <ScheduleLogPanel
            result={latestScheduleResult}
            onDismiss={() => setLatestScheduleResult(null)}
            onRerun={() => scheduleMutation.mutate()}
            isRerunning={scheduleMutation.isPending}
          />
        </div>
      )}

      {progressMutation.isError && (
        <div className="mb-4 rounded-md bg-danger/10 border border-danger/30 p-3 text-sm text-danger">
          {(progressMutation.error as Error)?.message ?? "Failed to update progress"}
        </div>
      )}

      {isLoading ? (
        <div className="text-center text-text-muted">
          {viewMode === "tree" ? "Loading activities and WBS..." : "Loading activities..."}
        </div>
      ) : activities.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface/80 p-8 text-center">
          <p className="text-text-muted">
            {lookAheadWeeks && viewMode !== "gantt" && viewMode !== "network"
              ? `No activities scheduled in the next ${lookAheadWeeks} weeks`
              : "No activities found"}
          </p>
        </div>
      ) : viewMode === "tree" ? (
        <ActivityWbsTreeView
          wbsNodes={wbsNodes}
          activities={filteredActivities}
          relationships={relationships}
          projectId={projectId}
          progressEdit={progressEdit}
          setProgressEdit={setProgressEdit}
          pendingId={pendingId}
          progressMutationIsPending={progressMutation.isPending}
          onSaveProgress={saveProgress}
          onStartActivity={start}
          onCompleteActivity={complete}
          selectedActivityId={selectedActivityId}
          onRowClick={handleRowClick}
          onRowContextMenu={handleRowContextMenu}
        />
      ) : viewMode === "list" ? (
        <ActivitiesListTable
          activities={filteredActivities}
          relationships={relationships}
          projectId={projectId}
          progressEdit={progressEdit}
          setProgressEdit={setProgressEdit}
          pendingId={pendingId}
          progressIsPending={progressMutation.isPending}
          saveProgress={saveProgress}
          start={start}
          complete={complete}
          canStart={canStart}
          canComplete={canComplete}
          optionalCols={optionalCols}
          dataDate={dataDate}
          baselineByActivity={baselineByActivity}
          actualCostByActivity={actualCostByActivity}
          budgetedCostByActivity={budgetedCostByActivity}
          selectedActivityId={selectedActivityId}
          onRowClick={handleRowClick}
          onRowContextMenu={handleRowContextMenu}
          onSetSupervisor={(a) => setSupervisorTarget(a)}
        />
      ) : viewMode === "gantt" ? (
        <ActivityGanttView
          activities={activities}
          wbsNodes={wbsNodes}
          relationships={relationships}
          baselineActivities={(baselineDetail?.data?.activities ?? []).map((a: BaselineActivityResponse) => ({
            activityId: a.activityId,
            baselineStartDate: a.earlyStart,
            baselineFinishDate: a.earlyFinish,
          }))}
          project={project}
          projectId={projectId}
          onRunSchedule={() => scheduleMutation.mutate()}
          isRunningSchedule={scheduleMutation.isPending}
          onActivityClick={(id) => {
            const a = activities.find((act) => act.id === id);
            if (a) handleRowClick(a);
          }}
          onActivityContextMenu={(id, x, y) => {
            const a = activities.find((act) => act.id === id);
            if (a) handleRowContextMenu(a, x, y);
          }}
        />
      ) : (
        <ActivityNetworkView
          activities={activities}
          relationships={relationships}
          projectId={projectId}
          onActivityClick={(id) => {
            const a = activities.find((act) => act.id === id);
            if (a) handleRowClick(a);
          }}
          onActivityContextMenu={(id, x, y) => {
            const a = activities.find((act) => act.id === id);
            if (a) handleRowContextMenu(a, x, y);
          }}
        />
      )}

      {lookAheadWeeks && viewMode !== "gantt" && viewMode !== "network" && (
        <div className="mt-6 rounded-lg bg-accent/10 p-4">
          <p className="text-sm text-blue-300">
            Showing {filteredActivities.length} of {activities.length} activities scheduled in the
            next {lookAheadWeeks} weeks.
          </p>
        </div>
      )}

      <ActivityAiGenerateDialog
        open={showAiDialog}
        onClose={() => setShowAiDialog(false)}
        projectId={projectId}
      />

      <ActivityDetailDrawer
        open={!!selectedActivityId}
        onClose={() => setSelectedActivityId(null)}
        projectId={projectId}
        activityId={selectedActivityId}
      />

      {contextMenu && (
        <ActivityRowContextMenu
          activity={contextMenu.activity}
          position={contextMenu.position}
          canStart={canStart(contextMenu.activity)}
          canComplete={canComplete(contextMenu.activity)}
          canDelete={canDeleteActivity}
          onClose={() => setContextMenu(null)}
          onOpenDetail={(a) => setSelectedActivityId(a.id)}
          onAssign={handleAssign}
          onStart={start}
          onComplete={complete}
          onEdit={handleEditFromMenu}
          onDelete={(a) => setDeleteTarget(a)}
          onSetSupervisor={(a) => setSupervisorTarget(a)}
          onCreateDpr={(a) =>
            router.push(`/projects/${projectId}/dpr?new=1&activityId=${a.id}`)
          }
          onOpenMasterDialog={(a, mode) => setMasterDialog({ activity: a, mode })}
        />
      )}

      {masterDialog && (
        <LinkOrCreateWorkActivityDialog
          open
          onClose={() => setMasterDialog(null)}
          mode={masterDialog.mode}
          projectId={projectId}
          activityId={masterDialog.activity.id}
          defaultCode={masterDialog.activity.code}
          defaultName={masterDialog.activity.name}
          existingMasterId={
            masterDialog.mode === "CONFIGURE_NORMS_ONLY"
              ? masterDialog.activity.workActivityId ?? undefined
              : undefined
          }
        />
      )}

      <SetSupervisorDialog
        open={!!supervisorTarget}
        onClose={() => setSupervisorTarget(null)}
        projectId={projectId}
        activity={supervisorTarget}
      />

      <AssignSupervisorDrawer
        open={bulkSupervisorOpen}
        onClose={() => setBulkSupervisorOpen(false)}
        projectId={projectId}
      />

      {quickAssign && (
        <QuickAssignResourceDialog
          open
          onClose={() => setQuickAssign(null)}
          projectId={projectId}
          activityId={quickAssign.activity.id}
          activityCode={quickAssign.activity.code}
          activityName={quickAssign.activity.name}
          kind={quickAssign.kind}
        />
      )}

      <Dialog
        open={!!deleteTarget}
        onOpenChange={(next) => {
          if (!next) setDeleteTarget(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete activity?</DialogTitle>
          </DialogHeader>
          <DialogBody>
            {deleteTarget && (
              <p>
                Delete <span className="font-semibold text-charcoal">{deleteTarget.code}</span> —{" "}
                {deleteTarget.name}? This also removes its relationships and resource assignments.
                This action cannot be undone.
              </p>
            )}
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setDeleteTarget(null)}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
              disabled={deleteMutation.isPending}
              className="rounded-md bg-danger px-4 py-2 text-sm font-medium text-text-primary hover:bg-danger/80 disabled:opacity-50"
            >
              {deleteMutation.isPending ? "Deleting…" : "Delete"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ActivitiesListTable({
  activities,
  relationships,
  projectId,
  progressEdit,
  setProgressEdit,
  pendingId,
  progressIsPending,
  saveProgress,
  start,
  complete,
  canStart,
  canComplete,
  optionalCols,
  dataDate,
  baselineByActivity,
  actualCostByActivity,
  budgetedCostByActivity,
  selectedActivityId,
  onRowClick,
  onRowContextMenu,
  onSetSupervisor,
}: {
  activities: ActivityResponse[];
  relationships: Array<{ id?: string; predecessorActivityId: string; successorActivityId: string; relationshipType: string }>;
  projectId: string;
  progressEdit: Record<string, string>;
  setProgressEdit: React.Dispatch<React.SetStateAction<Record<string, string>>>;
  pendingId: string | null;
  progressIsPending: boolean;
  saveProgress: (a: ActivityResponse) => void;
  start: (a: ActivityResponse) => void;
  complete: (a: ActivityResponse) => void;
  canStart: (a: ActivityResponse) => boolean;
  canComplete: (a: ActivityResponse) => boolean;
  optionalCols: OptionalColumns;
  dataDate: string | null;
  baselineByActivity: Map<string, BaselineActivityResponse>;
  actualCostByActivity: Map<string, number>;
  budgetedCostByActivity: Map<string, number>;
  selectedActivityId: string | null;
  onRowClick: (a: ActivityResponse) => void;
  onRowContextMenu: (a: ActivityResponse, x: number, y: number) => void;
  onSetSupervisor: (a: ActivityResponse) => void;
}) {
  // Build dependency count map
  const predCountMap = new Map<string, number>();
  const succCountMap = new Map<string, number>();
  for (const rel of relationships) {
    predCountMap.set(rel.successorActivityId, (predCountMap.get(rel.successorActivityId) ?? 0) + 1);
    succCountMap.set(rel.predecessorActivityId, (succCountMap.get(rel.predecessorActivityId) ?? 0) + 1);
  }

  const formatDate = (value: string | null | undefined) => {
    if (!value) return "—";
    const d = new Date(value);
    return d.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
  };

  // Money in the grid renders in the project's currency (no decimals).
  const { money } = useProjectCurrency();
  const formatCurrency = (value: number | null | undefined) => money(value, { decimals: 0 });

  return (
    <div className="rounded-lg border border-border bg-surface/50 shadow-sm">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead className="border-b border-border bg-surface/80">
            <tr>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Code</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Name</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Duration (days)</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">% Complete</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Status</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Float (days)</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Planned Start</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Planned Finish</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">ES</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">EF</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">LS</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">LF</th>
              {optionalCols.actualDates && (
                <>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Actual Start</th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Actual Finish</th>
                </>
              )}
              {optionalCols.baselineDates && (
                <>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">BL Start</th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">BL Finish</th>
                </>
              )}
              {optionalCols.schedulePct && (
                <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Schedule %</th>
              )}
              {optionalCols.actualCost && (
                <th className="px-4 py-3 text-right text-sm font-semibold text-text-secondary whitespace-nowrap">Actual Cost</th>
              )}
              {optionalCols.budgetVariance && (
                <th className="px-4 py-3 text-right text-sm font-semibold text-text-secondary whitespace-nowrap" title="Actual cost minus original Budget. Positive = over budget.">
                  Budget Variance
                </th>
              )}
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap" title="Field-accountable LABOR resource set on the activity">Supervisor</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Actions</th>
              <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary whitespace-nowrap">Deps</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border/50">
            {activities.map((activity: ActivityResponse) => {
              const editing = progressEdit[activity.id] !== undefined;
              const busy = pendingId === activity.id && progressIsPending;
              const predCount = predCountMap.get(activity.id) ?? 0;
              const succCount = succCountMap.get(activity.id) ?? 0;
              const isSelected = selectedActivityId === activity.id;
              return (
                <tr
                  key={activity.id}
                  className={`cursor-pointer hover:bg-surface/80 ${
                    isSelected ? "bg-surface-active/40" : ""
                  }`}
                  onClick={() => onRowClick(activity)}
                  onContextMenu={(e) => {
                    e.preventDefault();
                    onRowContextMenu(activity, e.clientX, e.clientY);
                  }}
                >
                  <td className="px-4 py-4 text-sm font-medium text-text-primary whitespace-nowrap">{activity.code}</td>
                  <td className="px-4 py-4 text-sm text-text-primary whitespace-nowrap">
                    <span className="inline-flex items-center gap-2">
                      <span>{activity.name}</span>
                      <ActivityMasterStatusIndicator workActivityId={activity.workActivityId} />
                    </span>
                  </td>
                  <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{activity.originalDuration ?? activity.duration ?? "—"}</td>
                  <td
                    className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap"
                    onClick={(e) => e.stopPropagation()}
                  >
                    {editing ? (
                      <div className="flex items-center gap-1">
                        <input
                          type="number"
                          min={0}
                          max={100}
                          step={1}
                          autoFocus
                          className="w-16 rounded-md border border-border bg-surface-hover px-2 py-1 text-sm text-text-primary"
                          value={progressEdit[activity.id]}
                          onChange={(e) =>
                            setProgressEdit({ ...progressEdit, [activity.id]: e.target.value })
                          }
                          onKeyDown={(e) => {
                            if (e.key === "Enter") saveProgress(activity);
                            if (e.key === "Escape")
                              setProgressEdit((s) => {
                                const n = { ...s };
                                delete n[activity.id];
                                return n;
                              });
                          }}
                          disabled={busy}
                        />
                        <button
                          type="button"
                          onClick={() => saveProgress(activity)}
                          disabled={busy}
                          className="rounded-md bg-success px-2 py-1 text-xs text-text-primary hover:bg-success/80 disabled:opacity-60"
                        >
                          Save
                        </button>
                        <button
                          type="button"
                          onClick={() =>
                            setProgressEdit((s) => {
                              const n = { ...s };
                              delete n[activity.id];
                              return n;
                            })
                          }
                          className="text-xs text-text-secondary hover:text-text-primary"
                        >
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        onClick={() =>
                          setProgressEdit({
                            ...progressEdit,
                            [activity.id]: String(activity.percentComplete ?? 0),
                          })
                        }
                        className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-surface-hover"
                        title="Click to edit % complete"
                      >
                        {activity.percentComplete ?? 0}%
                      </button>
                    )}
                  </td>
                  <td className="px-4 py-4 text-sm whitespace-nowrap">
                    <StatusBadge status={activity.status} />
                  </td>
                  <td className="px-4 py-4 text-sm whitespace-nowrap">
                    {activity.totalFloat != null ? (
                      <span
                        className={`inline-block rounded-full px-3 py-1 text-xs font-semibold ${
                          activity.totalFloat === 0
                            ? "bg-danger/10 text-danger"
                            : activity.totalFloat <= 5
                              ? "bg-warning/10 text-warning"
                              : "bg-success/10 text-success"
                        }`}
                      >
                        {activity.totalFloat.toFixed(1)}
                      </span>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.plannedStartDate)}</td>
                  <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.plannedFinishDate)}</td>
                  <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.earlyStartDate)}</td>
                  <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.earlyFinishDate)}</td>
                  <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.lateStartDate)}</td>
                  <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.lateFinishDate)}</td>
                  {optionalCols.actualDates && (
                    <>
                      <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.actualStartDate)}</td>
                      <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(activity.actualFinishDate)}</td>
                    </>
                  )}
                  {optionalCols.baselineDates && (() => {
                    const ba = baselineByActivity.get(activity.id);
                    return (
                      <>
                        <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(ba?.earlyStart)}</td>
                        <td className="px-4 py-4 text-sm text-text-secondary whitespace-nowrap">{formatDate(ba?.earlyFinish)}</td>
                      </>
                    );
                  })()}
                  {optionalCols.schedulePct && (() => {
                    // Reference dates: prefer the active PRIMARY baseline's snapshot, fall back to
                    // the activity's planned dates so this column still tells the planner something
                    // useful on projects that haven't taken a baseline yet.
                    const ba = baselineByActivity.get(activity.id);
                    const refStart = ba?.earlyStart ?? activity.plannedStartDate ?? null;
                    const refFinish = ba?.earlyFinish ?? activity.plannedFinishDate ?? null;
                    const sched = schedulePercentComplete(refStart, refFinish, dataDate);
                    const bucket = scheduleVarianceBucket(sched, activity.percentComplete ?? 0);
                    const cls =
                      bucket === "behind"
                        ? "bg-danger/10 text-danger"
                        : bucket === "ahead"
                          ? "bg-success/10 text-success"
                          : bucket === "on-track"
                            ? "bg-surface-active/40 text-text-secondary"
                            : "text-text-muted";
                    return (
                      <td className="px-4 py-4 text-sm whitespace-nowrap">
                        {sched == null ? (
                          <span className="text-text-muted">—</span>
                        ) : (
                          <span
                            className={`inline-block rounded-full px-3 py-1 text-xs font-semibold ${cls}`}
                            title={
                              bucket === "behind"
                                ? "Activity is behind schedule"
                                : bucket === "ahead"
                                  ? "Activity is ahead of schedule"
                                  : bucket === "on-track"
                                    ? "On track (within ±5%)"
                                    : "Schedule % unavailable"
                            }
                          >
                            {sched}%
                          </span>
                        )}
                      </td>
                    );
                  })()}
                  {optionalCols.actualCost && (
                    <td className="px-4 py-4 text-right text-sm text-text-secondary whitespace-nowrap">
                      {formatCurrency(actualCostByActivity.get(activity.id))}
                    </td>
                  )}
                  {optionalCols.budgetVariance && (() => {
                    // Phase 2: budget variance = actualCost - budgetedCost.
                    // Positive => over-budget (red). Negative => under-budget (green).
                    // Threshold ±1 to swallow rounding noise.
                    const actual = actualCostByActivity.get(activity.id);
                    const budget = budgetedCostByActivity.get(activity.id);
                    if (actual == null || budget == null) {
                      return (
                        <td className="px-4 py-4 text-right text-sm text-text-muted whitespace-nowrap">—</td>
                      );
                    }
                    const variance = actual - budget;
                    const cls =
                      variance > 1
                        ? "bg-danger/10 text-danger"
                        : variance < -1
                          ? "bg-success/10 text-success"
                          : "bg-surface-active/40 text-text-secondary";
                    const sign = variance > 0 ? "+" : "";
                    return (
                      <td className="px-4 py-4 text-right text-sm whitespace-nowrap">
                        <span
                          className={`inline-block rounded-full px-3 py-1 text-xs font-semibold ${cls}`}
                          title={
                            variance > 1
                              ? `Over budget by ${formatCurrency(variance)}`
                              : variance < -1
                                ? `Under budget by ${formatCurrency(Math.abs(variance))}`
                                : "On budget"
                          }
                        >
                          {sign}{formatCurrency(variance)}
                        </span>
                      </td>
                    );
                  })()}
                  <td
                    className="px-4 py-4 text-sm whitespace-nowrap"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <SupervisorCell activity={activity} onClick={() => onSetSupervisor(activity)} />
                  </td>
                  <td
                    className="px-4 py-4 text-sm whitespace-nowrap"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <div className="flex gap-2">
                      {canStart(activity) && (
                        <button
                          type="button"
                          onClick={() => start(activity)}
                          disabled={busy}
                          className="rounded-md bg-accent px-2 py-1 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-60"
                          title="Record actual start date as today"
                        >
                          Start
                        </button>
                      )}
                      {canComplete(activity) && (
                        <button
                          type="button"
                          onClick={() => complete(activity)}
                          disabled={busy}
                          className="rounded-md bg-success px-2 py-1 text-xs font-medium text-text-primary hover:bg-success/80 disabled:opacity-60"
                          title="Mark 100% complete and set actual finish to today"
                        >
                          Complete
                        </button>
                      )}
                      <Link
                        href={`/projects/${projectId}/activities/${activity.id}`}
                        className="rounded-md border border-border px-2 py-1 text-xs text-text-secondary hover:bg-surface-hover"
                      >
                        View
                      </Link>
                    </div>
                  </td>
                  <td className="px-4 py-4 text-sm whitespace-nowrap">
                    {predCount === 0 && succCount === 0 ? (
                      <span className="text-text-muted">—</span>
                    ) : (
                      <span className="text-xs text-text-secondary">
                        {predCount > 0 && <span className="text-accent">{predCount}P</span>}
                        {predCount > 0 && succCount > 0 && " / "}
                        {succCount > 0 && <span className="text-success">{succCount}S</span>}
                      </span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ActivityGanttView({
  activities,
  wbsNodes = [],
  relationships,
  baselineActivities = [],
  project,
  projectId,
  onRunSchedule,
  isRunningSchedule = false,
  onActivityClick,
  onActivityContextMenu,
}: {
  activities: ActivityResponse[];
  wbsNodes?: WbsNodeResponse[];
  relationships: Array<{ predecessorActivityId: string; successorActivityId: string; relationshipType: string }>;
  baselineActivities?: Array<{ activityId: string; baselineStartDate: string | null; baselineFinishDate: string | null }>;
  project: ProjectResponse | null;
  projectId: string;
  onRunSchedule?: () => void;
  isRunningSchedule?: boolean;
  onActivityClick?: (id: string) => void;
  onActivityContextMenu?: (id: string, x: number, y: number) => void;
}) {
  const isStale = useScheduleStaleStore((s) => s.isScheduleStale(projectId));
  const [spotlightOn, setSpotlightOn] = useState(false);
  const spotlightAvailable = !!project?.dataDate;

  if (activities.length === 0) {
    return (
      <EmptyState
        icon={ListTodo}
        title="No activities"
        description="This project has no activities yet. Create activities to display the Gantt chart."
      />
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3">
        <label
          className={`inline-flex items-center gap-2 rounded-lg border border-border bg-surface/60 px-3 py-1.5 text-xs font-medium ${
            spotlightAvailable ? "cursor-pointer text-text-secondary hover:text-text-primary" : "cursor-not-allowed text-text-muted"
          }`}
          title={
            spotlightAvailable
              ? "Highlight activities between project start and data date"
              : "Set Data Date on the project to enable Progress Spotlight"
          }
        >
          <input
            type="checkbox"
            disabled={!spotlightAvailable}
            checked={spotlightOn && spotlightAvailable}
            onChange={(e) => setSpotlightOn(e.target.checked)}
          />
          Progress Spotlight
        </label>
        {spotlightOn && spotlightAvailable && project && (
          <span className="text-xs text-text-muted">
            Showing {project.plannedStartDate} → {project.dataDate}
          </span>
        )}
      </div>
      <GanttChart
        activities={activities}
        wbsNodes={wbsNodes}
        relationships={relationships}
        baselineActivities={baselineActivities}
        isStale={isStale}
        onRunSchedule={onRunSchedule}
        isRunningSchedule={isRunningSchedule}
        onActivityClick={onActivityClick}
        onActivityContextMenu={onActivityContextMenu}
        spotlightStartDate={spotlightOn && spotlightAvailable ? project?.plannedStartDate ?? undefined : undefined}
        spotlightEndDate={spotlightOn && spotlightAvailable ? project?.dataDate ?? undefined : undefined}
      />
    </div>
  );
}

function ActivityNetworkView({
  activities,
  relationships,
  projectId,
  onActivityClick,
  onActivityContextMenu,
}: {
  activities: ActivityResponse[];
  relationships: Array<{ predecessorActivityId: string; successorActivityId: string; relationshipType: string }>;
  projectId: string;
  onActivityClick?: (id: string) => void;
  onActivityContextMenu?: (id: string, x: number, y: number) => void;
}) {
  const router = useRouter();

  if (activities.length === 0) {
    return (
      <EmptyState
        icon={ListTodo}
        title="No activities"
        description="This project has no activities yet. Create activities to display the network diagram."
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-text-secondary">
          {relationships.length} relationship(s) defined
        </p>
        <button
          onClick={() => router.push(`/projects/${projectId}/relationships`)}
          className="flex items-center gap-1 rounded-md bg-accent/20 px-4 py-2 text-sm font-medium text-accent hover:bg-accent/30 transition-colors"
        >
          Manage Relationships
          <ArrowRight size={14} />
        </button>
      </div>
      <NetworkDiagram
        activities={activities}
        relationships={relationships}
        onActivityClick={onActivityClick}
        onActivityContextMenu={onActivityContextMenu}
      />
    </div>
  );
}

/**
 * Supervisor column cell. Renders up to 2 supervisor name chips plus a "+N more" overflow
 * (with full list in a title tooltip) and falls back to the legacy single-supervisor cache
 * when an activity hasn't been touched since the multi-supervisor rollout. Clicking opens
 * the multi-select dialog.
 */
function SupervisorCell({
  activity,
  onClick,
}: {
  activity: ActivityResponse;
  onClick: () => void;
}) {
  const names: string[] = activity.supervisors && activity.supervisors.length > 0
    ? activity.supervisors.map((s) => s.userName || "Unknown")
    : activity.supervisorUserName
      ? [activity.supervisorUserName]
      : activity.responsibleResourceName
        ? [activity.responsibleResourceName]
        : [];

  if (names.length === 0) {
    return (
      <button
        type="button"
        onClick={onClick}
        className="rounded-md border border-border px-2 py-0.5 text-xs hover:bg-surface-hover"
        title="Click to set supervisors"
      >
        <span className="text-text-muted">— Set —</span>
      </button>
    );
  }

  const visible = names.slice(0, 2);
  const overflow = names.length - visible.length;
  return (
    <button
      type="button"
      onClick={onClick}
      className="inline-flex max-w-[260px] flex-wrap items-center gap-1 rounded-md border border-border px-2 py-0.5 text-xs hover:bg-surface-hover"
      title={`Supervisors: ${names.join(", ")}\nClick to edit`}
    >
      {visible.map((n) => (
        <span
          key={n}
          className="inline-flex items-center rounded bg-accent/15 px-1.5 py-0.5"
        >
          {n}
        </span>
      ))}
      {overflow > 0 && (
        <span className="text-text-muted">+{overflow}</span>
      )}
    </button>
  );
}
