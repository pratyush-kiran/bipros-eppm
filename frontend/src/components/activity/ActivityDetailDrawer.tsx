"use client";

import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { X, ExternalLink, FilePlus2, RefreshCw, Lock, AlertTriangle } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { activityApi } from "@/lib/api/activityApi";
import { resourceApi } from "@/lib/api/resourceApi";
import { StatusBadge } from "@/components/common/StatusBadge";
import { ActivityEditStatusBadge } from "@/components/activity/ActivityEditStatusBadge";
import { RoleDemandSections } from "@/components/activity/RoleDemandSections";
import { RoleDemandOverview } from "@/components/activity/RoleDemandOverview";
import { SetSupervisorDialog } from "@/components/activity/SetSupervisorDialog";
import { LinkOrCreateWorkActivityDialog, type DialogMode } from "@/components/activity/LinkOrCreateWorkActivityDialog";
import { WorkActivityCoverageChip } from "@/components/activity/WorkActivityCoverageChip";
import { useActivityMasterStatus } from "@/lib/hooks/useActivityMasterStatus";
import { getErrorMessage } from "@/lib/utils/error";
import type { ActivityResponse } from "@/lib/api/activityApi";

interface Props {
  open: boolean;
  onClose: () => void;
  projectId: string;
  activityId: string | null;
}

const formatDate = (value: string | null | undefined) => {
  if (!value) return "—";
  const d = new Date(value);
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
};

export function ActivityDetailDrawer({ open, onClose, projectId, activityId }: Props) {
  // Close on Escape.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Body data attr lets the floating Ask AI FAB hide via CSS while the drawer is open.
  useEffect(() => {
    if (!open) return;
    document.body.dataset.activityDrawerOpen = "true";
    return () => {
      delete document.body.dataset.activityDrawerOpen;
    };
  }, [open]);

  const { data: activityData, isLoading: isLoadingActivity } = useQuery({
    queryKey: ["activity", projectId, activityId],
    queryFn: () => activityApi.getActivity(projectId, activityId!),
    enabled: open && !!activityId,
  });

  const activity = activityData?.data ?? null;

  if (!open || !activityId) return null;

  return (
    <aside
      className="fixed right-0 top-0 z-40 flex h-screen w-full flex-col border-l border-border bg-surface shadow-xl md:w-[640px] lg:w-[760px]"
      role="dialog"
      aria-modal="false"
      aria-label="Activity detail"
    >
      <DrawerInner
        // Re-mount on activity change so any local state (e.g. inline form open/closed)
        // resets cleanly without an effect.
        key={activityId}
        activity={activity}
        isLoadingActivity={isLoadingActivity}
        projectId={projectId}
        activityId={activityId}
        onClose={onClose}
      />
    </aside>
  );
}

function DrawerInner({
  activity,
  isLoadingActivity,
  projectId,
  activityId,
  onClose,
}: {
  activity: Awaited<ReturnType<typeof activityApi.getActivity>>["data"] | null;
  isLoadingActivity: boolean;
  projectId: string;
  activityId: string;
  onClose: () => void;
}) {
  const [supervisorOpen, setSupervisorOpen] = useState(false);
  const queryClient = useQueryClient();
  const router = useRouter();

  const recomputeMutation = useMutation({
    mutationFn: () => resourceApi.recomputeProjectAssignmentCosts(projectId),
    onSuccess: (resp) => {
      const updated = resp.data?.updated ?? 0;
      toast.success(
        updated > 0
          ? `Recomputed costs — ${updated} assignment${updated === 1 ? "" : "s"} updated`
          : "Recompute complete — every assignment was already in sync",
      );
      queryClient.invalidateQueries({ queryKey: ["activity-assignments", projectId] });
      queryClient.invalidateQueries({ queryKey: ["activity", projectId] });
      queryClient.invalidateQueries({ queryKey: ["resource-assignments", projectId] });
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to recompute costs"));
    },
  });

  // Lock is intentionally one-way from the UI: once the resource plan is finalized the user
  // shouldn't be able to re-open it from the drawer. activityApi.unlock still exists for
  // admin recovery via the backend endpoint.
  const lockMutation = useMutation({
    mutationFn: () => activityApi.lock(projectId, activityId),
    onSuccess: () => {
      toast.success("Activity locked — resource plan is now frozen");
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
    },
    onError: (err: unknown) => toast.error(getErrorMessage(err, "Failed to lock activity")),
  });

  const isLocked = activity?.editStatus === "LOCKED";

  return (
    <>
      <header className="flex items-start justify-between gap-3 border-b border-border px-5 py-4">
        <div className="min-w-0">
          {isLoadingActivity || !activity ? (
            <p className="text-sm text-text-muted">Loading…</p>
          ) : (
            <>
              <div className="flex items-center gap-2 text-sm font-medium text-text-secondary">
                <span>{activity.code}</span>
                <StatusBadge status={activity.status} />
                <ActivityEditStatusBadge editStatus={activity.editStatus} />
                {!isLocked && (
                  <button
                    type="button"
                    onClick={() => lockMutation.mutate()}
                    disabled={lockMutation.isPending}
                    title="Lock the resource plan. This is one-way — the plan can no longer be edited from this drawer."
                    className="inline-flex items-center gap-1 rounded-md border border-border bg-surface-hover px-2 py-0.5 text-xs font-medium text-text-secondary hover:bg-surface-active disabled:opacity-60"
                  >
                    <Lock size={12} />
                    Lock
                  </button>
                )}
              </div>
              <h2 className="mt-1 truncate text-lg font-semibold text-text-primary">
                {activity.name}
              </h2>
              {activity.editStatus === "DRAFT" && (
                <p className="mt-1 text-xs text-text-muted">
                  Draft — DPRs can&apos;t be submitted until this activity is locked.
                </p>
              )}
            </>
          )}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-text-primary"
          aria-label="Close drawer"
        >
          <X size={18} />
        </button>
      </header>

      <div className="flex-1 overflow-y-auto px-5 py-4">
        {isLoadingActivity || !activity ? (
          <p className="text-sm text-text-muted">Loading activity…</p>
        ) : (
          <div className="space-y-6">
            <section>
              <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
                Schedule
              </h3>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                <div>
                  <dt className="text-text-muted">% Complete</dt>
                  <dd className="text-text-primary">{activity.percentComplete ?? 0}%</dd>
                </div>
                <div>
                  <dt className="text-text-muted">Duration</dt>
                  <dd className="text-text-primary">
                    {activity.originalDuration ?? activity.duration ?? "—"} d
                  </dd>
                </div>
                <div>
                  <dt className="text-text-muted">Planned Start</dt>
                  <dd className="text-text-primary">{formatDate(activity.plannedStartDate)}</dd>
                </div>
                <div>
                  <dt className="text-text-muted">Planned Finish</dt>
                  <dd className="text-text-primary">{formatDate(activity.plannedFinishDate)}</dd>
                </div>
                <div>
                  <dt className="text-text-muted">Actual Start</dt>
                  <dd className="text-text-primary">{formatDate(activity.actualStartDate)}</dd>
                </div>
                <div>
                  <dt className="text-text-muted">Actual Finish</dt>
                  <dd className="text-text-primary">{formatDate(activity.actualFinishDate)}</dd>
                </div>
                <div>
                  <dt className="text-text-muted">Total Float</dt>
                  <dd className="text-text-primary">
                    {activity.totalFloat != null ? `${activity.totalFloat.toFixed(1)} d` : "—"}
                  </dd>
                </div>
                <div>
                  <dt className="text-text-muted">Supervisor</dt>
                  <dd>
                    <button
                      type="button"
                      onClick={() => setSupervisorOpen(true)}
                      className="rounded-md border border-border px-2 py-0.5 text-sm text-text-primary hover:bg-surface-hover"
                      title={
                        activity.supervisors && activity.supervisors.length > 1
                          ? activity.supervisors.map((s) => s.userName ?? s.userId).join(", ")
                          : "Click to set or change supervisor"
                      }
                    >
                      {(() => {
                        const svs = activity.supervisors;
                        if (svs && svs.length > 0) {
                          const first = svs[0].userName ?? svs[0].userId;
                          if (svs.length === 1) return first;
                          return `${first} +${svs.length - 1} more`;
                        }
                        return (
                          activity.supervisorUserName ??
                          activity.responsibleResourceName ?? (
                            <span className="text-text-muted">— Set —</span>
                          )
                        );
                      })()}
                    </button>
                  </dd>
                </div>
              </dl>
            </section>

            <DrawerMasterPanel
              activity={activity}
              projectId={projectId}
            />

            <section>
              <div className="mb-2 flex items-center justify-between gap-2">
                <h3 className="text-xs font-semibold uppercase tracking-wider text-text-muted">
                  Resource Demand
                </h3>
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
                activityId={activityId}
                locked={isLocked}
              />

              {/* Read-only plan summary sits at the bottom of the demand section,
                  after Material Requirements, so the user adds first and reviews
                  the rollup last (planned/actual/remaining units + cost). */}
              <div className="mt-4">
                <RoleDemandOverview
                  projectId={projectId}
                  activityId={activityId}
                  compact
                  title="Resource Plan"
                />
              </div>
            </section>
          </div>
        )}
      </div>

      <footer className="flex items-center justify-between gap-3 border-t border-border bg-surface/80 px-5 py-3">
        <Link
          href={`/projects/${projectId}/activities/${activityId}`}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-accent hover:underline"
        >
          Open full detail page
          <ExternalLink size={14} />
        </Link>
        {activity && isLocked && (
          <button
            type="button"
            onClick={() => {
              router.push(
                `/projects/${projectId}/dpr?new=1&activityId=${activityId}`,
              );
              onClose();
            }}
            className="inline-flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
            title="Create a Daily Progress Report pre-filled with this activity"
          >
            <FilePlus2 size={14} />
            Create DPR
          </button>
        )}
      </footer>

      <SetSupervisorDialog
        open={supervisorOpen}
        onClose={() => setSupervisorOpen(false)}
        projectId={projectId}
        activity={activity}
      />
    </>
  );
}

/**
 * Surfaces the Activity → Master Work Activity / Productivity Norm linkage inside the drawer.
 * Without this the drawer was silently happy with `workActivityId = null`, leaving the planner
 * no path to remediate from the same view they were inspecting.
 */
function DrawerMasterPanel({
  activity,
  projectId,
}: {
  activity: ActivityResponse;
  projectId: string;
}) {
  const status = useActivityMasterStatus(activity.workActivityId);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogMode, setDialogMode] = useState<DialogMode>("LINK_OR_CREATE");

  const openDialog = (m: DialogMode) => {
    setDialogMode(m);
    setDialogOpen(true);
  };

  // The dialog is rendered once at the bottom so it stays mounted across status-state
  // transitions (UNLINKED → LINKED_NO_NORMS after a master is created mid-flow). Otherwise
  // React would unmount the dialog in the previous branch and mount a fresh one, resetting
  // the user back to step 1 instead of advancing to the productivity-norm step.
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

  // All branches MUST render the same root tree shape (`<>section + dialog</>`) so that the
  // dialog component instance is preserved across status transitions. If we return `dialog`
  // alone in any branch (e.g. during a brief LOADING state right after master creation),
  // React unmounts the fragment subtree and remounts the dialog with fresh state —
  // dropping the "advance to NORM step" that createMasterMutation just performed.
  let section: ReactNode = null;
  if (status.state === "UNLINKED") {
    section = (
      <section className="rounded-lg border border-warning/40 bg-warning/5 p-4">
        <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold text-text-primary">
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
      </section>
    );
  } else if (status.state === "LINKED_NO_NORMS") {
    section = (
      <section className="rounded-lg border border-danger/40 bg-danger/5 p-4">
        <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold text-text-primary">
          <AlertTriangle size={16} className="text-danger" />
          Work Activity (master) — no Productivity Norms
        </h3>
        <div className="flex flex-wrap items-center gap-3 text-sm text-text-primary">
          <span className="font-medium">{status.master?.name ?? "—"}</span>
          {status.master?.code && (
            <span className="font-mono text-xs text-text-muted">{status.master.code}</span>
          )}
          {status.master?.defaultUnit && (
            <span className="rounded bg-info/10 px-2 py-0.5 text-xs text-info ring-1 ring-info/20">
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
      </section>
    );
  } else if (status.state === "OK" && status.master) {
    section = (
      <section className="rounded-lg border border-border bg-surface/50 p-4">
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
          Work Activity (master)
        </h3>
        <div className="flex flex-wrap items-center gap-3 text-sm text-text-primary">
          <span className="font-medium">{status.master.name}</span>
          <span className="font-mono text-xs text-text-muted">{status.master.code}</span>
          {status.master.defaultUnit && (
            <span className="rounded bg-info/10 px-2 py-0.5 text-xs text-info ring-1 ring-info/20">
              {status.master.defaultUnit}
            </span>
          )}
          {status.master.discipline && (
            <span className="text-xs text-text-secondary">· {status.master.discipline}</span>
          )}
        </div>
        <WorkActivityCoverageChip workActivityId={activity.workActivityId} />
      </section>
    );
  }

  return (
    <>
      {section}
      {dialog}
    </>
  );
}
