"use client";

import { Suspense, lazy, useState, useMemo } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { SimpleTable } from "@/components/common/SimpleTable";
import { getErrorMessage } from "@/lib/utils/error";
import toast from "react-hot-toast";
import { activityNotifications, notificationHelpers } from "@/lib/notificationHelpers";
import { PageHeader } from "@/components/common/PageHeader";
import { activityApi } from "@/lib/api/activityApi";
import type { ActivityResponse, UpdateActivityRequest, ConstraintType } from "@/lib/api/activityApi";
import { workActivityApi } from "@/lib/api/workActivityApi";
import type { WorkActivityResponse } from "@/lib/api/workActivityApi";
import { calendarApi, type CalendarResponse } from "@/lib/api/calendarApi";
import { projectApi } from "@/lib/api/projectApi";
import { resourceApi } from "@/lib/api/resourceApi";
import { RoleDemandOverview } from "@/components/activity/RoleDemandOverview";
import { RoleDemandSections } from "@/components/activity/RoleDemandSections";
import { WorkActivityCoverageChip } from "@/components/activity/WorkActivityCoverageChip";
import { LinkOrCreateWorkActivityDialog, type DialogMode } from "@/components/activity/LinkOrCreateWorkActivityDialog";
import { useActivityMasterStatus } from "@/lib/hooks/useActivityMasterStatus";
import type { ResourceAssignmentResponse } from "@/lib/api/resourceApi";
import { projectResourceApi } from "@/lib/api/projectResourceApi";
import type { ProjectResourceResponse } from "@/lib/api/projectResourceApi";
import { userApi } from "@/lib/api/userApi";
import { costApi } from "@/lib/api/costApi";
import type { CostAccount } from "@/lib/api/costApi";
import { evmApi } from "@/lib/api/evmApi";
import type { ActivityEvmResponse } from "@/lib/api/evmApi";
import { activityStepApi } from "@/lib/api/activityStepApi";
import { useAuthStore } from "@/lib/state/store";
import type { ActivityStepResponse, CreateActivityStepRequest } from "@/lib/api/activityStepApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { StatusBadge } from "@/components/common/StatusBadge";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { ActivityAssignmentsByRole } from "@/components/activity/ActivityAssignmentsByRole";
import { ActivityEditStatusBadge } from "@/components/activity/ActivityEditStatusBadge";
import { ResourceAssignmentForm } from "@/components/resource/ResourceAssignmentForm";
import { SetSupervisorDialog } from "@/components/activity/SetSupervisorDialog";
import type { ExpenseResponse } from "@/lib/types";
import { AlertTriangle, Lock, RefreshCw, Unlock } from "lucide-react";

// Heavy children deferred so the initial paint of the detail page is cheap —
// when arriving here from /activities (especially WBS Tree view) the router
// transition can starve out behind synchronous render of these subtrees, which
// shows as Chrome's "Page Unresponsive" dialog.
const ActivityDependencies = lazy(() =>
  import("@/components/activity/ActivityDependencies").then((m) => ({
    default: m.ActivityDependencies,
  })),
);
const UdfSection = lazy(() =>
  import("@/components/udf/UdfSection").then((m) => ({ default: m.UdfSection })),
);

const CONSTRAINT_TYPE_LABELS: Record<ConstraintType, string> = {
  START_ON: "Start On",
  START_ON_OR_AFTER: "Start On or After",
  START_ON_OR_BEFORE: "Start On or Before",
  FINISH_ON: "Finish On",
  FINISH_ON_OR_AFTER: "Finish On or After",
  FINISH_ON_OR_BEFORE: "Finish On or Before",
  AS_LATE_AS_POSSIBLE: "As Late As Possible",
};

const CONSTRAINT_TYPES: ConstraintType[] = [
  "START_ON",
  "START_ON_OR_AFTER",
  "START_ON_OR_BEFORE",
  "FINISH_ON",
  "FINISH_ON_OR_AFTER",
  "FINISH_ON_OR_BEFORE",
  "AS_LATE_AS_POSSIBLE",
];

type EditData = Omit<UpdateActivityRequest, "originalDuration" | "percentComplete"> & {
  originalDuration?: number | "";
  percentComplete?: number | "";
  calendarId?: string;
};

export default function ActivityDetailPage() {
  const params = useParams();
  const router = useRouter();
  const queryClient = useQueryClient();
  const projectId = params.projectId as string;
  const activityId = params.activityId as string;
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canEditActivity = hasPermission("ACTIVITY.UPDATE");
  const canLockActivity = hasPermission("ACTIVITY.LOCK");
  const canUnlockActivity = hasPermission("ACTIVITY.UNLOCK");

  const [isEditing, setIsEditing] = useState(false);
  const [error, setError] = useState("");
  /**
   * Confirm dialog for the lock / unlock action. Single piece of state because the two
   * actions are mutually exclusive (you can only lock something that's DRAFT and vice versa).
   */
  const [lockConfirm, setLockConfirm] = useState<"lock" | "unlock" | null>(null);

  const [editData, setEditData] = useState<EditData>({
    name: "",
    percentComplete: 0,
    percentCompleteType: "DURATION",
    actualStartDate: "",
    actualFinishDate: "",
    workActivityId: "",
    calendarId: "",
    costAccountId: null,
  });

  const [usePert, setUsePert] = useState(false);
  const [pertData, setPertData] = useState({
    optimisticDuration: 0 as number | "",
    mostLikelyDuration: 0 as number | "",
    pessimisticDuration: 0 as number | "",
    expectedDuration: 0,
    standardDeviation: 0,
  });

  const { data: activityData, isLoading } = useQuery({
    queryKey: ["activity", projectId, activityId],
    queryFn: () => activityApi.getActivity(projectId, activityId),
  });

  const activity = activityData?.data;

  const { data: workActivitiesData } = useQuery({
    queryKey: ["work-activities", "active"],
    queryFn: () => workActivityApi.list(true),
  });
  const workActivities: WorkActivityResponse[] = workActivitiesData?.data ?? [];

  const { data: costAccountsData } = useQuery({
    queryKey: ["cost-accounts"],
    queryFn: () => costApi.listCostAccounts(),
  });
  const costAccounts = costAccountsData?.data ?? [];

  const { data: calendarsData, isLoading: isLoadingCalendars } = useQuery({
    queryKey: ["calendars", "all"],
    queryFn: () => calendarApi.listCalendars(),
  });
  const projectCalendars = calendarsData?.data ?? [];

  // Phase 4.4 RBAC: supervisor picker is sourced from the User pool, scoped to the
  // supervisor-eligible roles. The legacy project resource pool is no longer the source
  // of truth — see SetSupervisorDialog for the canonical mirror.
  const { data: supervisorUsers, isLoading: isLoadingSupervisorPool } = useQuery({
    queryKey: ["users-by-role", "supervisor-pool"],
    queryFn: () =>
      userApi.listByRoles(["SUPERVISOR", "FOREMAN", "SITE_ENGINEER", "SITE_MANAGER"]),
  });
  const supervisorOptions = (supervisorUsers ?? []).map((u) => ({
    value: u.id,
    label: u.employeeCode ? `${u.employeeCode} — ${u.name}` : u.name,
  }));

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
  });
  const projectCalendarId = projectData?.data?.calendarId;
  const linkedWorkActivity = activity?.workActivityId
    ? workActivities.find((w) => w.id === activity.workActivityId) ?? null
    : null;

  const updateMutation = useMutation({
    mutationFn: (data: UpdateActivityRequest) =>
      activityApi.updateActivity(projectId, activityId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
      setIsEditing(false);
      setError("");
      activityNotifications.updated();
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to update activity");
      setError(msg);
      notificationHelpers.handleApiError(err, "Failed to update activity");
    },
  });

  /**
   * Edit-lifecycle toggles. Lock makes inputs read-only and unblocks DPR submission; unlock
   * does the reverse. The backend rejects {@code UpdateActivityRequest} against a LOCKED row
   * with {@code ACTIVITY_LOCKED}, so we mirror that gate on the client by disabling inputs.
   */
  const lockMutation = useMutation({
    mutationFn: () => activityApi.lock(projectId, activityId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
      setLockConfirm(null);
      setIsEditing(false);
      setError("");
    },
    onError: (err: unknown) => {
      setError(getErrorMessage(err, "Failed to lock activity"));
      notificationHelpers.handleApiError(err, "Failed to lock activity");
      setLockConfirm(null);
    },
  });

  const unlockMutation = useMutation({
    mutationFn: () => activityApi.unlock(projectId, activityId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
      setLockConfirm(null);
      setError("");
    },
    onError: (err: unknown) => {
      setError(getErrorMessage(err, "Failed to unlock activity"));
      notificationHelpers.handleApiError(err, "Failed to unlock activity");
      setLockConfirm(null);
    },
  });

  const handleEditChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value } = e.target;
    if (error) setError("");
    setEditData((prev) => ({
      ...prev,
      [name]:
        name === "percentComplete" || name === "originalDuration" || name === "remainingDuration"
          ? (value === "" ? "" : parseFloat(value))
          : name === "costAccountId"
            ? (value === "" ? null : value)
            : value,
    }));
  };

  const handlePertChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    const numValue = value === "" ? "" : parseFloat(value);
    const updated = {
      ...pertData,
      [name]: numValue,
    };

    const o = updated.optimisticDuration === "" ? NaN : updated.optimisticDuration;
    const m = updated.mostLikelyDuration === "" ? NaN : updated.mostLikelyDuration;
    const p = updated.pessimisticDuration === "" ? NaN : updated.pessimisticDuration;
    if (!Number.isNaN(o) && !Number.isNaN(m) && !Number.isNaN(p)) {
      updated.expectedDuration = (o + 4 * m + p) / 6;
      updated.standardDeviation = (p - o) / 6;
    }
    setPertData(updated);
  };

  const handleSaveEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");

    if (!editData.name) {
      setError("Name is required");
      return;
    }

    // Effective type is what the user is *submitting* (editData), not what's on the server,
    // so flipping DURATION→PHYSICAL in the same save call lets us include percentComplete.
    const effectiveType = editData.percentCompleteType ?? activity?.percentCompleteType ?? "DURATION";
    const isManualPercent = effectiveType === "PHYSICAL";
    // Phase 4.4: supervisor is owned by `PUT .../{activityId}/supervisor` (User-based RBAC),
    // not the generic activity-update endpoint. Strip the picker fields off the body so they
    // don't reach the deprecated UpdateActivityRequest path on the backend.
    const {
      percentComplete: _editPct,
      supervisorUserId: nextSupervisorUserId,
      supervisorUserName: nextSupervisorUserName,
      ...rest
    } = editData;
    const sanitizedData: UpdateActivityRequest = {
      ...rest,
      originalDuration: editData.originalDuration === "" ? 0 : editData.originalDuration,
      ...(isManualPercent
        ? { percentComplete: editData.percentComplete === "" ? 0 : editData.percentComplete }
        : {}),
    };

    const supervisorChanged =
      (nextSupervisorUserId ?? null) !== (activity?.responsibleResourceId ?? null);
    if (supervisorChanged) {
      try {
        await activityApi.setSupervisor(projectId, activityId, {
          supervisorUserId: nextSupervisorUserId ?? null,
          supervisorName: nextSupervisorUserName ?? null,
        });
      } catch (err: unknown) {
        const msg = getErrorMessage(err, "Failed to update supervisor");
        setError(msg);
        notificationHelpers.handleApiError(err, "Failed to update supervisor");
        return;
      }
    }

    updateMutation.mutate(sanitizedData);
  };

  const handleStartEdit = () => {
    if (activity) {
      setEditData({
        name: activity.name,
        percentComplete: activity.percentComplete,
        percentCompleteType:
          (activity.percentCompleteType as "DURATION" | "UNITS" | "PHYSICAL" | null | undefined) ??
          "DURATION",
        originalDuration: activity.duration,
        plannedStartDate: activity.plannedStartDate || "",
        plannedFinishDate: activity.plannedFinishDate || "",
        actualStartDate: activity.actualStartDate || "",
        actualFinishDate: activity.actualFinishDate || "",
        workActivityId: activity.workActivityId || "",
        calendarId: activity.calendarId || "",
        costAccountId: activity.costAccountId ?? null,
        supervisorUserId: activity.responsibleResourceId ?? null,
        supervisorUserName: activity.responsibleResourceName ?? null,
        primaryConstraintType: activity.primaryConstraintType ?? undefined,
        primaryConstraintDate: activity.primaryConstraintDate || "",
        secondaryConstraintType: activity.secondaryConstraintType ?? undefined,
        secondaryConstraintDate: activity.secondaryConstraintDate || "",
      });
      setIsEditing(true);
    }
  };

  const handleWorkActivityChange = (value: string) => {
    setEditData((prev) => ({ ...prev, workActivityId: value }));
  };

  const handleSupervisorChange = (value: string) => {
    const picked = (supervisorUsers ?? []).find((u) => u.id === value);
    setEditData((prev) => ({
      ...prev,
      supervisorUserId: value || null,
      supervisorUserName: picked?.name ?? null,
    }));
  };

  if (isLoading) {
    return <div className="text-center text-text-muted">Loading activity...</div>;
  }

  if (!activity) {
    return <div className="text-center text-red-500">Activity not found</div>;
  }

  // LOCKED activities accept DPRs but reject manual edits. We mirror that backend rule by
  // disabling all input fields and the Save button below. The auth-level {@code canEditActivity}
  // gate still applies — a viewer never gets to edit regardless of lock state.
  const isLocked = activity.editStatus === "LOCKED";

  return (
    <div>
      <PageHeader
        title={activity.code}
        description={activity.name}
        actions={
          <div className="flex items-center gap-2">
            <ActivityEditStatusBadge editStatus={activity.editStatus} />
            <button
              type="button"
              onClick={() =>
                router.push(`/projects/${projectId}/dpr?new=1&activityId=${activityId}`)
              }
              className="rounded-md border border-border bg-surface px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-hover"
              title="Create a Daily Progress Report pre-filled with this activity"
            >
              Create DPR
            </button>
            {isLocked && canUnlockActivity && (
              <button
                type="button"
                onClick={() => setLockConfirm("unlock")}
                disabled={unlockMutation.isPending}
                className="inline-flex items-center gap-1.5 rounded-md border border-border bg-surface px-3 py-2 text-sm font-medium text-text-primary hover:bg-surface-hover disabled:opacity-60"
                title="Unlock this activity to re-enable manual edits"
              >
                <Unlock size={14} />
                {unlockMutation.isPending ? "Unlocking…" : "Unlock"}
              </button>
            )}
            {!isLocked && canLockActivity && (
              <button
                type="button"
                onClick={() => setLockConfirm("lock")}
                disabled={lockMutation.isPending}
                className="inline-flex items-center gap-1.5 rounded-md bg-warning px-3 py-2 text-sm font-medium text-text-primary hover:bg-warning/80 disabled:opacity-60"
                title="Lock this activity so DPRs can be submitted against it"
              >
                <Lock size={14} />
                {lockMutation.isPending ? "Locking…" : "Lock"}
              </button>
            )}
            {canEditActivity && (
              <button
                onClick={handleStartEdit}
                disabled={isEditing || isLocked}
                title={isLocked ? "Unlock the activity to edit fields" : undefined}
                className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border disabled:cursor-not-allowed"
              >
                {isEditing ? "Editing..." : "Edit"}
              </button>
            )}
          </div>
        }
      />

      {isLocked && (
        <div className="mb-4 flex items-center gap-2 rounded-md border border-border bg-surface-hover/30 px-4 py-3 text-sm text-text-secondary">
          <Lock size={16} className="shrink-0 text-text-muted" />
          <span>
            <strong className="text-text-primary">This activity is locked.</strong> Click Unlock to
            edit.
          </span>
        </div>
      )}

      {!isLocked && (
        <div className="mb-4 flex items-center gap-2 rounded-md border border-warning/40 bg-warning/10 px-4 py-3 text-sm text-warning">
          <AlertTriangle size={16} className="shrink-0" />
          <span>
            <strong>Draft</strong> — inputs are editable but DPRs can&apos;t be submitted until you
            lock this activity.
          </span>
        </div>
      )}

      {error && (
        <div className="mb-6 rounded-md bg-danger/10 p-4 text-sm text-danger">{error}</div>
      )}

      <ConfirmDialog
        open={lockConfirm === "lock"}
        title="Lock this activity?"
        message="Lock this activity? Manual edits will be disabled until you unlock it. DPRs can be submitted against locked activities."
        confirmLabel={lockMutation.isPending ? "Locking…" : "Lock"}
        variant="warning"
        onConfirm={() => lockMutation.mutate()}
        onCancel={() => setLockConfirm(null)}
      />
      <ConfirmDialog
        open={lockConfirm === "unlock"}
        title="Unlock this activity?"
        message="Unlock this activity? Inputs will become editable again, but DPRs can't be submitted while it's in Draft."
        confirmLabel={unlockMutation.isPending ? "Unlocking…" : "Unlock"}
        variant="info"
        onConfirm={() => unlockMutation.mutate()}
        onCancel={() => setLockConfirm(null)}
      />

      {isEditing ? (
        <EditForm
          data={editData}
          onChange={handleEditChange}
          onSubmit={handleSaveEdit}
          onCancel={() => setIsEditing(false)}
          isSubmitting={updateMutation.isPending}
          // Defense-in-depth: even though the Edit button is disabled while locked, if the
          // activity flips to LOCKED while this form is open we still want every input to
          // refuse keystrokes — otherwise the user will spend a minute typing and then have
          // the server reject the save with {@code ACTIVITY_LOCKED}.
          disabled={!canEditActivity || isLocked}
          usePert={usePert}
          onTogglePert={() => setUsePert(!usePert)}
          pertData={pertData}
          onPertChange={handlePertChange}
          workActivities={workActivities}
          onWorkActivityChange={handleWorkActivityChange}
          projectCalendars={projectCalendars}
          isLoadingCalendars={isLoadingCalendars}
          projectCalendarId={projectCalendarId}
          costAccounts={costAccounts}
          percentCompleteType={activity.percentCompleteType}
          supervisorOptions={supervisorOptions}
          isLoadingSupervisorPool={isLoadingSupervisorPool}
          onSupervisorChange={handleSupervisorChange}
        />
      ) : (
        <ViewMode activity={activity} projectId={projectId} workActivity={linkedWorkActivity} projectCalendars={projectCalendars} projectCalendarId={projectCalendarId} costAccounts={costAccounts} />
      )}
    </div>
  );
}

function ViewMode({
  activity,
  projectId,
  workActivity,
  projectCalendars,
  projectCalendarId,
  costAccounts,
}: {
  activity: ActivityResponse;
  projectId: string;
  workActivity: WorkActivityResponse | null;
  projectCalendars: CalendarResponse[];
  projectCalendarId: string | null | undefined;
  costAccounts: CostAccount[];
}) {
  const stat = (label: string, value: React.ReactNode, tone?: "neutral" | "accent" | "success" | "warning" | "danger") => {
    const toneCls = {
      neutral: "text-text-primary",
      accent: "text-accent",
      success: "text-success",
      warning: "text-warning",
      danger: "text-danger",
    };
    return (
      <div className="rounded-lg border border-border bg-surface/50 p-3">
        <p className="text-xs text-text-secondary">{label}</p>
        <p className={`mt-0.5 text-base font-semibold ${tone ? toneCls[tone] : toneCls.neutral}`}>
          {value}
        </p>
      </div>
    );
  };

  const datePair = (label: string, value: string | null | undefined, fallback: string) => (
    <div className="flex items-center justify-between py-2 border-b border-border/50 last:border-0">
      <span className="text-sm text-text-secondary">{label}</span>
      <span className="text-sm font-medium text-text-primary">{value || fallback}</span>
    </div>
  );

  function getStatusTone(status: string): "neutral" | "accent" | "success" | "warning" | "danger" {
    switch (status) {
      case "IN_PROGRESS":
        return "accent";
      case "ACTIVE":
      case "COMPLETED":
        return "success";
      case "INACTIVE":
      case "ON_HOLD":
        return "warning";
      case "SUSPENDED":
      case "DELAYED":
      case "CANCELLED":
        return "danger";
      case "NOT_STARTED":
      case "PLANNED":
      default:
        return "neutral";
    }
  }

  const queryClient = useQueryClient();
  const isLocked = activity.editStatus === "LOCKED";

  const recomputeMutation = useMutation({
    mutationFn: () => resourceApi.recomputeProjectAssignmentCosts(projectId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["role-assignments", projectId, activity.id] });
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activity.id] });
      toast.success("Resource costs recomputed");
    },
    onError: (err) => {
      notificationHelpers.handleApiError(err, "Failed to recompute costs");
    },
  });

  const { data: assignmentsData } = useQuery({
    queryKey: ["resource-assignments", "activity", projectId, activity.id],
    queryFn: () => resourceApi.getAssignmentsByActivity(projectId, activity.id),
  });
  const assignments: ResourceAssignmentResponse[] = assignmentsData?.data ?? [];

  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogAssignment, setDialogAssignment] = useState<ResourceAssignmentResponse | null>(null);
  const [dialogMode, setDialogMode] = useState<"staff" | "swap">("staff");
  const [showAssignForm, setShowAssignForm] = useState(false);
  const [supervisorDialogOpen, setSupervisorDialogOpen] = useState(false);

  const openStaffDialog = (assignment: ResourceAssignmentResponse) => {
    setDialogAssignment(assignment);
    setDialogMode("staff");
    setDialogOpen(true);
  };

  const openSwapDialog = (assignment: ResourceAssignmentResponse) => {
    setDialogAssignment(assignment);
    setDialogMode("swap");
    setDialogOpen(true);
  };

  const { data: expensesData } = useQuery({
    queryKey: ["expenses", "activity", projectId, activity.id],
    queryFn: () => costApi.getActivityExpenses(projectId, activity.id),
  });
  const activityExpenses: ExpenseResponse[] = useMemo(
    () => expensesData?.data ?? [],
    [expensesData]
  );

  const { data: evmData, isLoading: isEvmLoading } = useQuery({
    queryKey: ["evm", "activity", projectId, activity.id],
    queryFn: () => evmApi.getActivityEvm(projectId, activity.id),
  });
  const activityEvm: ActivityEvmResponse | undefined = evmData?.data ?? undefined;

  const totalPlannedCost = assignments.reduce((sum, a) => sum + (a.plannedCost ?? 0), 0);
  const totalActualCost = assignments.reduce((sum, a) => sum + (a.actualCost ?? 0), 0);
  const totalExpenses = activityExpenses.reduce((sum, e) => sum + (e.actualCost ?? 0), 0);

  const fmt = (n: number) =>
    n.toLocaleString("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });

  type ExpenseRow =
    | ExpenseResponse
    | {
        id: "__TOTAL__";
        description: string;
        expenseCategory: string;
        actualStartDate: string | null;
        actualCost: number;
      };

  const expenseColumns = useMemo<ColumnDef<ExpenseRow>[]>(() => [
    {
      accessorKey: "description",
      header: "Description",
      cell: ({ row }) => {
        const isTotal = row.original.id === "__TOTAL__";
        return (
          <span className={isTotal ? "text-text-secondary font-semibold" : "text-text-primary"}>
            {row.original.description}
          </span>
        );
      },
    },
    {
      accessorKey: "expenseCategory",
      header: "Category",
      cell: ({ row }) => (
        <span className="text-text-secondary">{row.original.expenseCategory}</span>
      ),
    },
    {
      accessorKey: "actualStartDate",
      header: "Date",
      cell: ({ row }) => (
        <span className="text-text-secondary">
          {row.original.actualStartDate ?? "—"}
        </span>
      ),
    },
    {
      accessorKey: "actualCost",
      header: "Amount",
      cell: ({ row }) => {
        const isTotal = row.original.id === "__TOTAL__";
        return (
          <span className={`text-right block ${isTotal ? "text-accent font-semibold" : "text-text-primary"}`}>
            {fmt(row.original.actualCost ?? 0)}
          </span>
        );
      },
    },
  ], []);

  const expenseTableData = useMemo<ExpenseRow[]>(
    () =>
      activityExpenses.length > 0
        ? [
            ...activityExpenses,
            {
              id: "__TOTAL__",
              description: "Total Expenses",
              expenseCategory: "",
              actualStartDate: null,
              actualCost: totalExpenses,
            } as ExpenseRow,
          ]
        : [],
    [activityExpenses, totalExpenses]
  );

  return (
    <div className="space-y-5">
      {/* Key Metrics */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {stat("Status", <StatusBadge status={activity.status} />, getStatusTone(activity.status))}
        {(() => {
          const pctType = activity.percentCompleteType || "DURATION";
          return (
            <div className="rounded-lg border border-border bg-surface/50 p-3">
              <div className="flex items-center gap-2">
                <p className="text-xs text-text-secondary">% Complete</p>
                <span className={`inline-flex items-center rounded-md px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset ${
                  pctType === "PHYSICAL"
                    ? "text-text-secondary bg-surface ring-border"
                    : "text-accent bg-accent/10 ring-accent/20"
                }`}>{pctType}</span>
              </div>
              <p className={`mt-0.5 text-base font-semibold ${activity.percentComplete === 100 ? "text-success" : "text-text-primary"}`}>
                {activity.percentComplete}%
              </p>
            </div>
          );
        })()}
        {stat("Duration", `${activity.duration ?? activity.originalDuration ?? 0} days`)}
        {stat("Remaining", `${activity.remainingDuration ?? 0} days`)}
      </div>

      {/* Schedule Metrics */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {stat("Total Float", `${activity.totalFloat ?? 0} days`, (activity.totalFloat ?? 0) === 0 ? "danger" : "neutral")}
        {stat("Slack", `${activity.slack ?? 0} days`)}
        {stat("Free Float", `${activity.freeFloat ?? 0} days`)}
        {stat("Critical", activity.isCritical ? "Yes" : "No", activity.isCritical ? "danger" : "success")}
      </div>

      {/* Supervisor */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
        {stat(
          "Supervisor",
          <span className="flex items-center gap-2">
            <span
              className="truncate"
              title={
                activity.supervisors && activity.supervisors.length > 1
                  ? activity.supervisors.map((s) => s.userName ?? s.userId).join(", ")
                  : undefined
              }
            >
              {(() => {
                const svs = activity.supervisors;
                if (svs && svs.length > 0) {
                  const first = svs[0].userName ?? svs[0].userId;
                  if (svs.length === 1) return first;
                  return `${first} +${svs.length - 1} more`;
                }
                return activity.responsibleResourceName ?? (
                  <span className="text-text-muted text-sm font-normal">— not set —</span>
                );
              })()}
            </span>
            <button
              type="button"
              onClick={() => setSupervisorDialogOpen(true)}
              className="rounded-md border border-border px-2 py-0.5 text-xs font-medium text-text-secondary hover:bg-surface-hover"
            >
              Change
            </button>
          </span>
        )}
      </div>

      <SetSupervisorDialog
        open={supervisorDialogOpen}
        onClose={() => setSupervisorDialogOpen(false)}
        projectId={projectId}
        activity={activity}
      />

      {/* Dates Panel */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <h3 className="text-sm font-semibold text-text-primary mb-2">Dates</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-x-6">
          {datePair("Planned Start", activity.plannedStartDate, "Not set")}
          {datePair("Planned Finish", activity.plannedFinishDate, "Not set")}
          {datePair("Early Start", activity.earlyStartDate, "—")}
          {datePair("Early Finish", activity.earlyFinishDate, "—")}
          {datePair("Late Start", activity.lateStartDate, "—")}
          {datePair("Late Finish", activity.lateFinishDate, "—")}
          {datePair("Actual Start", activity.actualStartDate, "Not started")}
          {datePair("Actual Finish", activity.actualFinishDate, "Not finished")}
        </div>
      </div>

      {/* Resource Demand — full editor (Manpower / Equipment / Material) plus read-only rollup,
          mirroring ActivityDetailDrawer so users can manage demand from either surface. */}
      <section className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="mb-2 flex items-center justify-between gap-2">
          <h3 className="text-sm font-semibold text-text-primary">Resource Demand</h3>
          <button
            type="button"
            onClick={() => recomputeMutation.mutate()}
            disabled={recomputeMutation.isPending}
            title="Recompute planned costs from current role rates and project overrides."
            className="inline-flex items-center gap-1.5 rounded-md border border-border bg-surface-hover px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-surface-active disabled:opacity-60"
          >
            <RefreshCw size={14} className={recomputeMutation.isPending ? "animate-spin" : ""} />
            {recomputeMutation.isPending ? "Recomputing…" : "Recompute"}
          </button>
        </div>

        <RoleDemandSections
          projectId={projectId}
          activityId={activity.id}
          locked={isLocked}
        />

        <div className="mt-4">
          <RoleDemandOverview
            projectId={projectId}
            activityId={activity.id}
            title="Resource Plan"
          />
        </div>
      </section>

      {/* Constraints */}
      {(activity.primaryConstraintType || activity.secondaryConstraintType) && (
        <div className="rounded-lg border border-border bg-surface/50 p-4">
          <h3 className="text-sm font-semibold text-text-primary mb-2">Constraints</h3>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-x-6">
            {activity.primaryConstraintType && (
              <>
                {datePair("Primary Constraint", CONSTRAINT_TYPE_LABELS[activity.primaryConstraintType], "—")}
                {activity.primaryConstraintDate && datePair("Primary Constraint Date", activity.primaryConstraintDate, "—")}
              </>
            )}
            {activity.secondaryConstraintType && (
              <>
                {datePair("Secondary Constraint", CONSTRAINT_TYPE_LABELS[activity.secondaryConstraintType], "—")}
                {activity.secondaryConstraintDate && datePair("Secondary Constraint Date", activity.secondaryConstraintDate, "—")}
              </>
            )}
          </div>
        </div>
      )}

      {/* Cost Account */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <h3 className="text-sm font-semibold text-text-primary mb-2">Cost Account</h3>
        {(() => {
          const assignedCa = activity.costAccountId
            ? costAccounts.find((ca) => ca.id === activity.costAccountId)
            : null;
          if (assignedCa) {
            return (
              <div className="flex items-center gap-2 text-sm text-text-primary">
                <span className="font-mono text-xs text-accent">{assignedCa.code}</span>
                <span>{assignedCa.name}</span>
              </div>
            );
          }
          return (
            <p className="text-sm text-text-muted italic">
              None assigned — inherits from WBS node if set.
            </p>
          );
        })()}
      </div>

      {/* Cost & Earned Value */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <h3 className="text-sm font-semibold text-text-primary mb-3">Cost &amp; Earned Value</h3>

        {/* Resource demand summary is shown at the top of the page via RoleDemandOverview;
            this card now focuses on Expenses + EVM cost rollup tiles only. */}

        {activityExpenses.length > 0 && (
          <div className="mb-4">
            <p className="text-xs font-medium text-text-secondary uppercase tracking-wide mb-2">Expenses</p>
            <SimpleTable
              data={expenseTableData}
              columns={expenseColumns}
              sortable={false}
              className="border-0 rounded-none"
            />
          </div>
        )}

        {(assignments.length > 0 || activityExpenses.length > 0) && (
          <div className="mt-3 pt-3 border-t border-border">
            <p className="text-xs font-medium text-text-secondary uppercase tracking-wide mb-2">Cost Rollup</p>
            {isEvmLoading ? (
              <p className="text-sm text-text-muted">Loading EVM data...</p>
            ) : activityEvm ? (
              <>
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                  {stat("BAC", fmt(activityEvm.bac))}
                  {stat("AC", fmt(activityEvm.ac))}
                  {stat("EV", fmt(activityEvm.ev), "accent")}
                  {stat(
                    "CV",
                    fmt(activityEvm.cv),
                    activityEvm.cv >= 0 ? "success" : "danger"
                  )}
                </div>
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3">
                  {stat("PV", activityEvm.pv != null ? fmt(activityEvm.pv) : "—")}
                  {stat(
                    "SV",
                    activityEvm.sv != null ? fmt(activityEvm.sv) : "—",
                    activityEvm.sv != null ? (activityEvm.sv >= 0 ? "success" : "danger") : "neutral"
                  )}
                  {stat(
                    "CPI",
                    activityEvm.cpi != null ? activityEvm.cpi.toFixed(2) : "—",
                    activityEvm.cpi != null ? (activityEvm.cpi >= 1 ? "success" : "danger") : "neutral"
                  )}
                  {stat(
                    "SPI",
                    activityEvm.spi != null ? activityEvm.spi.toFixed(2) : "—",
                    activityEvm.spi != null ? (activityEvm.spi >= 1 ? "success" : "danger") : "neutral"
                  )}
                </div>
                <p className="mt-2 text-xs text-text-muted">
                  Technique: {activityEvm.earnedValueTechnique.replace(/_/g, " ")}
                </p>
              </>
            ) : (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                {stat("Budgeted (BAC)", fmt(totalPlannedCost))}
                {stat("Actual Cost (AC)", fmt(totalActualCost + totalExpenses))}
              </div>
            )}
          </div>
        )}

        {assignments.length === 0 && activityExpenses.length === 0 && (
          <p className="text-xs text-text-muted mt-1">
            Assign resources or tag expenses to this activity to see cost data here.
          </p>
        )}
      </div>

      {/* Activity Steps */}
      <ActivityStepsPanel activityId={activity.id} projectId={projectId} percentCompleteType={activity.percentCompleteType as string | undefined} />

      {/* Master Work Activity link */}
      <WorkActivityMasterPanel activity={activity} projectId={projectId} workActivity={workActivity} />

      {/* Calendar */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <h3 className="text-sm font-semibold text-text-primary mb-2">Calendar</h3>
        {activity.calendarId ? (
          (() => {
            const cal = projectCalendars.find((c) => c.id === activity.calendarId);
            return cal ? (
              <div className="flex flex-wrap items-center gap-3 text-sm text-text-primary">
                <span className="font-medium">{cal.name}</span>
                <span className="text-xs text-text-muted">
                  {cal.standardWorkHoursPerDay}h / {cal.standardWorkDaysPerWeek}d · {cal.calendarType}
                </span>
                {activity.calendarId === projectCalendarId && (
                  <span className="px-2 py-0.5 rounded bg-success/10 text-success ring-1 ring-success/20 text-xs">
                    Inherited
                  </span>
                )}
              </div>
            ) : (
              <p className="text-sm text-text-muted">Linked calendar not found in list.</p>
            );
          })()
        ) : (
          (() => {
            const inherited = projectCalendars.find((c) => c.id === projectCalendarId);
            return inherited ? (
              <div className="flex flex-wrap items-center gap-3 text-sm text-text-primary">
                <span className="font-medium">{inherited.name}</span>
                <span className="text-xs text-text-muted">
                  {inherited.standardWorkHoursPerDay}h / {inherited.standardWorkDaysPerWeek}d · {inherited.calendarType}
                </span>
                <span className="px-2 py-0.5 rounded bg-success/10 text-success ring-1 ring-success/20 text-xs">
                  Inherited from project
                </span>
              </div>
            ) : (
              <p className="text-sm text-text-muted">
                No calendar assigned. Edit the activity to assign a work schedule.
              </p>
            );
          })()
        )}
      </div>

      {/* Dependencies */}
      <Suspense fallback={<div className="rounded-lg border border-border bg-surface/50 p-4 text-sm text-text-muted">Loading dependencies…</div>}>
        <ActivityDependencies
          projectId={projectId}
          activityId={activity.id}
          activityName={activity.name}
        />
      </Suspense>

      {/* Custom Fields */}
      <Suspense fallback={<div className="rounded-xl border border-border bg-surface/50 p-6 text-sm text-text-muted">Loading custom fields…</div>}>
        <UdfSection entityId={activity.id} subject="ACTIVITY" projectId={projectId} />
      </Suspense>
    </div>
  );
}

function WorkActivityMasterPanel({
  activity,
  projectId,
  workActivity,
}: {
  activity: ActivityResponse;
  projectId: string;
  workActivity: WorkActivityResponse | null;
}) {
  const status = useActivityMasterStatus(activity.workActivityId);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogMode, setDialogMode] = useState<DialogMode>("LINK_OR_CREATE");

  const openDialog = (m: DialogMode) => {
    setDialogMode(m);
    setDialogOpen(true);
  };

  // The dialog is mounted once at the end and re-used across status transitions; otherwise
  // a state change from UNLINKED → LINKED_NO_NORMS mid-wizard would swap the parent JSX
  // branch, unmount the active dialog, and reset the user back to step 1.
  const dialog = (
    <LinkOrCreateWorkActivityDialog
      open={dialogOpen}
      onClose={() => setDialogOpen(false)}
      mode={dialogMode}
      projectId={projectId}
      activityId={activity.id}
      defaultCode={activity.code}
      defaultName={activity.name}
      existingMasterId={status.master?.id}
      existingMasterName={status.master?.name ?? undefined}
      existingMasterDefaultUnit={status.master?.defaultUnit ?? undefined}
    />
  );

  if (status.state === "UNLINKED") {
    return (
      <>
        <div className="rounded-lg border border-warning/40 bg-warning/5 p-4">
          <h3 className="text-sm font-semibold text-text-primary mb-2 flex items-center gap-2">
            <AlertTriangle size={16} className="text-warning" />
            Work Activity (master) — not mapped
          </h3>
          <p className="text-sm text-text-secondary">
            This activity is not mapped to any Master Work Activity. Productivity Norms are not
            configured for this activity, therefore Capacity Utilization calculations may be
            inaccurate.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => openDialog("LINK_OR_CREATE")}
              className="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover"
            >
              Link / Create Master
            </button>
          </div>
        </div>
        {dialog}
      </>
    );
  }

  if (status.state === "LINKED_NO_NORMS") {
    return (
      <>
        <div className="rounded-lg border border-danger/40 bg-danger/5 p-4">
          <h3 className="text-sm font-semibold text-text-primary mb-2 flex items-center gap-2">
            <AlertTriangle size={16} className="text-danger" />
            Work Activity (master) — no Productivity Norms
          </h3>
          <div className="flex flex-wrap items-center gap-3 text-sm text-text-primary">
            <span className="font-medium">{status.master?.name ?? workActivity?.name}</span>
            {status.master?.code && (
              <span className="font-mono text-xs text-text-muted">{status.master.code}</span>
            )}
            {status.master?.defaultUnit && (
              <span className="px-2 py-0.5 rounded bg-info/10 text-info ring-1 ring-info/20 text-xs">
                {status.master.defaultUnit}
              </span>
            )}
          </div>
          <p className="mt-2 text-sm text-text-secondary">
            No productivity norms are configured for this master. Capacity Utilization cannot
            compute expected output until at least one norm exists.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => openDialog("CONFIGURE_NORMS_ONLY")}
              className="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover"
            >
              Configure Productivity Norms
            </button>
          </div>
        </div>
        {dialog}
      </>
    );
  }

  // OK / LOADING — render the original linked summary + coverage chip.
  return (
    <>
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <h3 className="text-sm font-semibold text-text-primary mb-2">Work Activity (master)</h3>
        {workActivity ? (
          <div className="flex flex-wrap items-center gap-3 text-sm text-text-primary">
            <span className="font-medium">{workActivity.name}</span>
            <span className="font-mono text-xs text-text-muted">{workActivity.code}</span>
            {workActivity.defaultUnit && (
              <span className="px-2 py-0.5 rounded bg-info/10 text-info ring-1 ring-info/20 text-xs">
                {workActivity.defaultUnit}
              </span>
            )}
            {workActivity.discipline && (
              <span className="text-xs text-text-secondary">· {workActivity.discipline}</span>
            )}
          </div>
        ) : (
          <p className="text-sm text-text-muted">Resolving master…</p>
        )}
        <WorkActivityCoverageChip workActivityId={activity.workActivityId} />
      </div>
      {dialog}
    </>
  );
}

function ActivityStepsPanel({
  activityId,
  projectId,
  percentCompleteType,
}: {
  activityId: string;
  projectId: string;
  percentCompleteType?: string;
}) {
  const queryClient = useQueryClient();
  const [showAdd, setShowAdd] = useState(false);
  const [newStep, setNewStep] = useState<CreateActivityStepRequest>({ name: "", weight: undefined });
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingStep, setEditingStep] = useState<{ name: string; weight: number | ""; description?: string }>({ name: "", weight: "" });

  const { data: stepsData } = useQuery({
    queryKey: ["activity-steps", activityId],
    queryFn: () => activityStepApi.listSteps(activityId),
  });
  const steps: ActivityStepResponse[] = stepsData?.data ?? [];

  const createMutation = useMutation({
    mutationFn: (req: CreateActivityStepRequest) => activityStepApi.createStep(activityId, req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity-steps", activityId] });
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
      setShowAdd(false);
      setNewStep({ name: "", weight: undefined });
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ stepId, name, weight, description }: { stepId: string; name: string; weight: number; description?: string }) =>
      activityStepApi.updateStep(activityId, stepId, name, weight, description),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity-steps", activityId] });
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
      setEditingId(null);
    },
  });

  const completeMutation = useMutation({
    mutationFn: (stepId: string) => activityStepApi.completeStep(activityId, stepId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity-steps", activityId] });
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
    },
  });

  const uncompleteMutation = useMutation({
    mutationFn: (stepId: string) => activityStepApi.uncompleteStep(activityId, stepId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity-steps", activityId] });
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (stepId: string) => activityStepApi.deleteStep(activityId, stepId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity-steps", activityId] });
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
    },
  });

  const isWeightedSteps = percentCompleteType === "PHYSICAL";

  return (
    <div className={`rounded-lg border p-4 ${isWeightedSteps ? "border-accent bg-accent/5" : "border-border bg-surface/50"}`}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-text-primary">Steps</h3>
          {isWeightedSteps && (
            <span className="px-2 py-0.5 rounded bg-accent/20 text-accent ring-1 ring-accent/30 text-xs font-medium">
              Weighted Steps EVM
            </span>
          )}
          {steps.length > 0 && (
            <span className="text-xs text-text-muted">
              {steps.filter((s) => s.isCompleted).length}/{steps.length} complete
            </span>
          )}
        </div>
        <button
          onClick={() => setShowAdd(true)}
          className="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover"
        >
          + Add Step
        </button>
      </div>

      {showAdd && (
        <div className="mb-3 rounded-md border border-border bg-surface p-3 flex gap-2 items-end">
          <div className="flex-1">
            <label className="block text-xs font-medium text-text-secondary mb-1">Name *</label>
            <input
              type="text"
              value={newStep.name}
              onChange={(e) => setNewStep((p) => ({ ...p, name: e.target.value }))}
              className="block w-full rounded border border-border px-2 py-1.5 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              placeholder="Step name"
            />
          </div>
          <div className="w-24">
            <label className="block text-xs font-medium text-text-secondary mb-1">Weight</label>
            <input
              type="number"
              min="0"
              step="0.1"
              value={newStep.weight ?? ""}
              onChange={(e) => setNewStep((p) => ({ ...p, weight: e.target.value === "" ? undefined : parseFloat(e.target.value) }))}
              className="block w-full rounded border border-border px-2 py-1.5 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              placeholder="1.0"
            />
          </div>
          <button
            onClick={() => {
              if (!newStep.name) return;
              createMutation.mutate(newStep);
            }}
            disabled={createMutation.isPending || !newStep.name}
            className="rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
          >
            {createMutation.isPending ? "Adding..." : "Add"}
          </button>
          <button
            onClick={() => { setShowAdd(false); setNewStep({ name: "", weight: undefined }); }}
            className="rounded-md bg-surface-active/50 px-3 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-active"
          >
            Cancel
          </button>
        </div>
      )}

      {steps.length === 0 && !showAdd ? (
        <p className="text-sm text-text-muted">No steps defined. Add steps to track granular progress.</p>
      ) : (
        <div className="space-y-1">
          {steps.map((step) => (
            <div
              key={step.id}
              className={`rounded-md border px-3 py-2 flex items-center gap-3 ${step.isCompleted ? "border-success/30 bg-success/5" : "border-border bg-surface"}`}
            >
              {editingId === step.id ? (
                <div className="flex-1 flex gap-2 items-end">
                  <div className="flex-1">
                    <input
                      type="text"
                      value={editingStep.name}
                      onChange={(e) => setEditingStep((p) => ({ ...p, name: e.target.value }))}
                      className="block w-full rounded border border-border px-2 py-1 text-sm text-text-primary focus:border-accent focus:outline-none"
                    />
                  </div>
                  <div className="w-20">
                    <input
                      type="number"
                      min="0"
                      step="0.1"
                      value={editingStep.weight}
                      onChange={(e) => setEditingStep((p) => ({ ...p, weight: e.target.value === "" ? "" : parseFloat(e.target.value) }))}
                      className="block w-full rounded border border-border px-2 py-1 text-sm text-text-primary focus:border-accent focus:outline-none"
                      placeholder="Weight"
                    />
                  </div>
                  <button
                    onClick={() => {
                      if (!editingStep.name || editingStep.weight === "") return;
                      updateMutation.mutate({ stepId: step.id, name: editingStep.name, weight: editingStep.weight as number, description: editingStep.description });
                    }}
                    disabled={updateMutation.isPending}
                    className="text-xs px-2 py-1 rounded bg-accent text-accent-foreground hover:bg-accent-hover disabled:bg-border"
                  >
                    Save
                  </button>
                  <button
                    onClick={() => setEditingId(null)}
                    className="text-xs px-2 py-1 rounded bg-surface-active/50 text-text-secondary hover:bg-surface-active"
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                <>
                  <span className={`flex-1 text-sm ${step.isCompleted ? "line-through text-text-muted" : "text-text-primary"}`}>
                    {step.sortOrder != null && <span className="text-text-muted mr-1">{step.sortOrder}.</span>}
                    {step.name}
                  </span>
                  {step.weightPercent != null && (
                    <span className="text-xs text-text-secondary">{step.weightPercent.toFixed(1)}%</span>
                  )}
                  {step.isCompleted ? (
                    <>
                      <span className="text-xs px-1.5 py-0.5 rounded bg-success/20 text-success">Done</span>
                      <button
                        onClick={() => uncompleteMutation.mutate(step.id)}
                        disabled={uncompleteMutation.isPending}
                        className="text-xs px-2 py-0.5 rounded border border-warning/40 text-warning hover:bg-warning/10 disabled:opacity-50"
                      >
                        Undo
                      </button>
                    </>
                  ) : (
                    <button
                      onClick={() => completeMutation.mutate(step.id)}
                      disabled={completeMutation.isPending}
                      className="text-xs px-2 py-0.5 rounded border border-success/40 text-success hover:bg-success/10 disabled:opacity-50"
                    >
                      Complete
                    </button>
                  )}
                  {!step.isCompleted && (
                    <button
                      onClick={() => {
                        setEditingId(step.id);
                        setEditingStep({ name: step.name, weight: step.weight ?? "", description: step.description ?? undefined });
                      }}
                      className="text-xs px-2 py-0.5 rounded border border-border text-text-secondary hover:bg-surface-hover"
                    >
                      Edit
                    </button>
                  )}
                  <button
                    onClick={() => deleteMutation.mutate(step.id)}
                    disabled={deleteMutation.isPending}
                    className="text-xs px-2 py-0.5 rounded border border-danger/30 text-danger hover:bg-danger/10 disabled:opacity-50"
                  >
                    Delete
                  </button>
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

interface EditFormProps {
  data: EditData;
  onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => void;
  onSubmit: (e: React.FormEvent) => void;
  onCancel: () => void;
  isSubmitting: boolean;
  /**
   * Master disable switch. Set when the activity is LOCKED (DPR-flow stage) or the user
   * lacks {@code ACTIVITY.UPDATE}. Disables every input + the Save button so the form
   * matches the server-side {@code ACTIVITY_LOCKED} rejection behaviour.
   */
  disabled?: boolean;
  usePert: boolean;
  onTogglePert: () => void;
  pertData: {
    optimisticDuration: number | "";
    mostLikelyDuration: number | "";
    pessimisticDuration: number | "";
    expectedDuration: number;
    standardDeviation: number;
  };
  onPertChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  workActivities: WorkActivityResponse[];
  onWorkActivityChange: (value: string) => void;
  projectCalendars: CalendarResponse[];
  isLoadingCalendars: boolean;
  projectCalendarId: string | null | undefined;
  costAccounts: CostAccount[];
  percentCompleteType?: string | null;
  supervisorOptions: { value: string; label: string }[];
  isLoadingSupervisorPool: boolean;
  onSupervisorChange: (value: string) => void;
}

function EditForm({
  data,
  onChange,
  onSubmit,
  onCancel,
  isSubmitting,
  disabled = false,
  usePert,
  onTogglePert,
  pertData,
  onPertChange,
  workActivities,
  onWorkActivityChange,
  projectCalendars,
  isLoadingCalendars,
  projectCalendarId,
  costAccounts,
  percentCompleteType,
  supervisorOptions,
  isLoadingSupervisorPool,
  onSupervisorChange,
}: EditFormProps) {
  return (
    <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-sm">
      <form onSubmit={onSubmit} className="space-y-6">
        {/* Wrapping every input in a single <fieldset disabled> is the cheapest way to cascade
            the lock-state read-only mode through this large form. Native form controls (input,
            select, button) inherit the disabled attribute automatically; SearchableSelect calls
            below OR their existing disabled props with this same flag for the non-native picker. */}
        <fieldset disabled={disabled} className="space-y-6 disabled:opacity-70">
        <div>
          <label className="block text-sm font-medium text-text-secondary">Name *</label>
          <input
            type="text"
            name="name"
            value={data.name || ""}
            onChange={onChange}
            className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            placeholder="Activity name"
          />
        </div>

        <div className="grid grid-cols-2 gap-6">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Duration (days)</label>
            <input
              type="number"
              name="originalDuration"
              value={data.originalDuration ?? ""}
              onChange={onChange}
              min="0"
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          {(() => {
            // Live-bound to the dropdown below so the % Complete input flips
            // editable/read-only the moment the user changes mode.
            const pctType = (data.percentCompleteType as string | undefined) || percentCompleteType || "DURATION";
            const isManual = pctType === "PHYSICAL";
            const helperText = isManual
              ? null
              : pctType === "UNITS"
                ? "Derived from resource actuals — edit Daily Outputs to change."
                : "Derived from data date and original duration — edit Actual Start/Finish or the project's data date.";
            return (
              <div>
                <label className="block text-sm font-medium text-text-secondary">% Complete</label>
                <input
                  type="number"
                  name="percentComplete"
                  value={data.percentComplete ?? ""}
                  onChange={onChange}
                  min="0"
                  max="100"
                  disabled={!isManual}
                  className={`mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent ${!isManual ? "opacity-50 cursor-not-allowed" : ""}`}
                />
                {helperText && (
                  <p className="mt-1 text-xs text-text-muted">{helperText}</p>
                )}
              </div>
            );
          })()}
        </div>

        <div className="grid grid-cols-2 gap-6">
          <div>
            <label className="block text-sm font-medium text-text-secondary">% Complete Type</label>
            <select
              name="percentCompleteType"
              value={(data.percentCompleteType as string | undefined) ?? "DURATION"}
              onChange={onChange}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            >
              <option value="DURATION">Duration — auto from elapsed days</option>
              <option value="UNITS">Units — auto from Daily Output</option>
              <option value="PHYSICAL">Physical — manual / step-driven</option>
            </select>
            {percentCompleteType && data.percentCompleteType && data.percentCompleteType !== percentCompleteType && (
              <p className="mt-1 text-xs text-warning">
                Switching mode will recalculate % on next read.
              </p>
            )}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-6">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Planned Start Date</label>
            <input
              type="date"
              name="plannedStartDate"
              value={data.plannedStartDate || ""}
              onChange={onChange}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-text-secondary">Planned Finish Date</label>
            <input
              type="date"
              name="plannedFinishDate"
              value={data.plannedFinishDate || ""}
              onChange={onChange}
              className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-6">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Actual Start Date</label>
            <input
              type="date"
              name="actualStartDate"
              value={data.actualStartDate || ""}
              onChange={onChange}
              className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-text-secondary">
              Actual Finish Date
            </label>
            <input
              type="date"
              name="actualFinishDate"
              value={data.actualFinishDate || ""}
              onChange={onChange}
              className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
        </div>

        {/* Constraints Section */}
        <div className="rounded-lg border border-border/60 bg-surface-hover/20 p-4 space-y-4">
          <h4 className="text-sm font-semibold text-text-primary">Constraints</h4>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Primary Constraint</label>
              <select
                name="primaryConstraintType"
                value={data.primaryConstraintType ?? ""}
                onChange={onChange}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="">— None —</option>
                {CONSTRAINT_TYPES.map((ct) => (
                  <option key={ct} value={ct}>{CONSTRAINT_TYPE_LABELS[ct]}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">Primary Constraint Date</label>
              <input
                type="date"
                name="primaryConstraintDate"
                value={data.primaryConstraintDate || ""}
                onChange={onChange}
                disabled={!data.primaryConstraintType || data.primaryConstraintType === "AS_LATE_AS_POSSIBLE"}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-50"
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-text-secondary">Secondary Constraint</label>
              <select
                name="secondaryConstraintType"
                value={data.secondaryConstraintType ?? ""}
                onChange={onChange}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="">— None —</option>
                {CONSTRAINT_TYPES.map((ct) => (
                  <option key={ct} value={ct}>{CONSTRAINT_TYPE_LABELS[ct]}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-text-secondary">Secondary Constraint Date</label>
              <input
                type="date"
                name="secondaryConstraintDate"
                value={data.secondaryConstraintDate || ""}
                onChange={onChange}
                disabled={!data.secondaryConstraintType || data.secondaryConstraintType === "AS_LATE_AS_POSSIBLE"}
                className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-50"
              />
            </div>
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">
            Work Activity (master)
          </label>
          <SearchableSelect
            value={data.workActivityId ?? ""}
            onChange={onWorkActivityChange}
            placeholder="Search master library (optional)..."
            options={[
              { value: "", label: "— none —" },
              ...workActivities.map((wa) => ({
                value: wa.id,
                label: wa.defaultUnit ? `${wa.name} (${wa.defaultUnit})` : wa.name,
              })),
            ]}
            disabled={disabled}
          />
          <p className="mt-1 text-xs text-text-muted">
            Links this project activity to its master library entry. Optional — leave blank for
            activities that don't need productivity tracking (e.g. design / engineering / office
            work). When set, the productivity norms below drive DPR expected-vs-actual.
          </p>
          <WorkActivityCoverageChip workActivityId={data.workActivityId} />
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">
            Calendar
          </label>
          <select
            name="calendarId"
            value={data.calendarId ?? ""}
            onChange={onChange}
            className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            disabled={isLoadingCalendars}
          >
            {isLoadingCalendars && <option value="">Loading…</option>}
            <option value="">
              {(() => {
                const inherited = projectCalendars.find((c) => c.id === projectCalendarId);
                return inherited
                  ? `— Inherit from project: ${inherited.name} —`
                  : "— Inherit from project —";
              })()}
            </option>
            {projectCalendars.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.standardWorkHoursPerDay}h / {c.standardWorkDaysPerWeek}d)
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-text-muted">
            Leave empty to use the project&apos;s default calendar. Select a different calendar to override.
          </p>
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">
            Cost Account
          </label>
          <select
            name="costAccountId"
            value={data.costAccountId ?? ""}
            onChange={onChange}
            className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="">(Inherit from WBS)</option>
            {costAccounts.map((ca) => (
              <option key={ca.id} value={ca.id}>
                {ca.code} - {ca.name}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-text-muted">
            Leave empty to inherit the cost account from the WBS node.
          </p>
        </div>

        <div>
          <label className="block text-sm font-medium text-text-secondary">
            Supervisor
          </label>
          <SearchableSelect
            value={data.supervisorUserId ?? ""}
            onChange={onSupervisorChange}
            placeholder={
              isLoadingSupervisorPool
                ? "Loading users..."
                : supervisorOptions.length
                  ? "Search supervisors..."
                  : "No users with supervisor roles"
            }
            options={[{ value: "", label: "— none —" }, ...supervisorOptions]}
            disabled={disabled || isLoadingSupervisorPool || supervisorOptions.length === 0}
          />
          <p className="mt-1 text-xs text-text-muted">
            Field-accountable supervisor. Picker lists users carrying SUPERVISOR /
            FOREMAN / SITE_ENGINEER / SITE_MANAGER roles.
          </p>
        </div>

        <div className="border-t border-border pt-6">
          <div className="flex items-center gap-3">
            <input
              type="checkbox"
              id="usePert"
              checked={usePert}
              onChange={onTogglePert}
              className="rounded border-border"
            />
            <label htmlFor="usePert" className="text-sm font-medium text-text-secondary">
              Use PERT Estimation
            </label>
          </div>
        </div>

        {usePert && (
          <div className="space-y-6 rounded-lg border border-warning/30 bg-warning/10 p-4">
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Optimistic (days)
                </label>
                <input
                  type="number"
                  name="optimisticDuration"
                  value={pertData.optimisticDuration}
                  onChange={onPertChange}
                  min="0"
                  step="0.5"
                  className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  placeholder="Optimistic"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Most Likely (days)
                </label>
                <input
                  type="number"
                  name="mostLikelyDuration"
                  value={pertData.mostLikelyDuration}
                  onChange={onPertChange}
                  min="0"
                  step="0.5"
                  className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  placeholder="Most Likely"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-text-secondary">
                  Pessimistic (days)
                </label>
                <input
                  type="number"
                  name="pessimisticDuration"
                  value={pertData.pessimisticDuration}
                  onChange={onPertChange}
                  min="0"
                  step="0.5"
                  className="mt-1 block w-full rounded-md border border-border px-3 py-2 text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  placeholder="Pessimistic"
                />
              </div>
            </div>

            <div className="rounded-lg bg-surface/50 p-4">
              <div className="text-sm font-medium text-text-secondary">Calculated Values</div>
              <div className="mt-3 grid grid-cols-2 gap-4 text-sm">
                <div>
                  <span className="text-text-secondary">Expected Duration:</span>
                  <span className="ml-2 font-semibold text-text-primary">
                    {pertData.expectedDuration.toFixed(2)} days
                  </span>
                </div>
                <div>
                  <span className="text-text-secondary">Standard Deviation:</span>
                  <span className="ml-2 font-semibold text-text-primary">
                    {pertData.standardDeviation.toFixed(2)} days
                  </span>
                </div>
              </div>
            </div>
          </div>
        )}

        </fieldset>

        {/* Action row sits OUTSIDE the fieldset so Cancel is always clickable — even when the
            activity has been locked mid-edit, the user must be able to back out of the form. */}
        <div className="flex gap-3 pt-6">
          <button
            type="submit"
            disabled={isSubmitting || disabled}
            title={disabled ? "Unlock the activity to save edits" : undefined}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border disabled:cursor-not-allowed"
          >
            {isSubmitting ? "Saving..." : "Save Changes"}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md bg-surface-active/50 px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-active"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}

function StaffSwapDialog({
  projectId,
  activityId,
  open,
  onClose,
  assignment,
  mode,
}: {
  projectId: string;
  activityId: string;
  open: boolean;
  onClose: () => void;
  assignment: ResourceAssignmentResponse | null;
  mode: "staff" | "swap";
}) {
  const queryClient = useQueryClient();
  const [selectedResourceId, setSelectedResourceId] = useState("");
  const [override, setOverride] = useState(false);

  const roleId = assignment?.roleId ?? null;

  // Pull pooled resources carrying the same role as this assignment.
  const { data: poolByRoleData } = useQuery({
    queryKey: ["resource-pool-by-role", projectId, roleId],
    queryFn: () => projectResourceApi.listPoolByRole(projectId, roleId!),
    enabled: !!roleId && open,
  });

  const qualifiedResources: ProjectResourceResponse[] = useMemo(() => {
    const raw = poolByRoleData?.data as unknown;
    return Array.isArray(raw)
      ? (raw as ProjectResourceResponse[])
      : [];
  }, [poolByRoleData]);

  const staffMutation = useMutation({
    mutationFn: () =>
      resourceApi.staffAssignment(projectId, assignment!.id, {
        resourceId: selectedResourceId,
        override,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resource-assignments", "activity", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["resource-assignments", projectId] });
      onClose();
      setSelectedResourceId("");
      setOverride(false);
    },
  });

  const swapMutation = useMutation({
    mutationFn: () =>
      resourceApi.swapResource(projectId, assignment!.id, {
        resourceId: selectedResourceId,
        override,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resource-assignments", "activity", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["resource-assignments", projectId] });
      onClose();
      setSelectedResourceId("");
      setOverride(false);
    },
  });

  if (!open || !assignment) return null;

  const isPending = staffMutation.isPending || swapMutation.isPending;
  const canSubmit = selectedResourceId !== "";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-surface p-6 shadow-lg">
        <h3 className="text-lg font-semibold text-text-primary mb-4">
          {mode === "staff" ? "Staff Role" : "Swap Resource"}
        </h3>
        <p className="text-sm text-text-secondary mb-4">
          {mode === "staff"
            ? `Select a qualified resource to staff the "${assignment.roleName ?? assignment.roleId}" role.`
            : `Select a different qualified resource to replace "${assignment.resourceName ?? assignment.resourceId}".`}
        </p>

        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-text-secondary">Qualified Resource</label>
            <SearchableSelect
              value={selectedResourceId}
              onChange={(val) => setSelectedResourceId(val)}
              placeholder="Search pooled resources..."
              options={qualifiedResources.map((p) => ({
                value: p.resourceId,
                label: `${p.resourceCode ?? p.resourceId} - ${p.resourceName ?? "Unknown"}`,
              }))}
            />
            {qualifiedResources.length === 0 && (
              <p className="text-xs text-amber-600 mt-1">
                No pooled resources match this role. Add resources from the Pool sub-tab first.
              </p>
            )}
          </div>

          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="override"
              checked={override}
              onChange={(e) => setOverride(e.target.checked)}
              className="rounded border-border"
            />
            <label htmlFor="override" className="text-sm text-text-secondary">
              Override qualification check (admin only)
            </label>
          </div>

          <div className="flex gap-3">
            <button
              onClick={() =>
                mode === "staff" ? staffMutation.mutate() : swapMutation.mutate()
              }
              disabled={isPending || !canSubmit}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-surface-active"
            >
              {isPending ? "Saving..." : mode === "staff" ? "Staff" : "Swap"}
            </button>
            <button
              onClick={() => {
                onClose();
                setSelectedResourceId("");
                setOverride(false);
              }}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover"
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
