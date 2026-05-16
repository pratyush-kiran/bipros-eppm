"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogBody,
  DialogFooter,
} from "@/components/ui/dialog";
import { MultiSelect } from "@/components/common/MultiSelect";
import { activityApi, type ActivityResponse, type SupervisorEntry } from "@/lib/api/activityApi";
import { userApi } from "@/lib/api/userApi";
import { getErrorMessage } from "@/lib/utils/error";

/**
 * Roles eligible to supervise an activity. Server-side filter on {@code /v1/users?roles=...}
 * (Phase 4.6) returns only users carrying at least one of these. Keep in sync with the
 * DPR page's supervisorOptions source and SupervisorAssignmentTab.
 */
const SUPERVISOR_ROLES = ["SUPERVISOR", "FOREMAN", "SITE_ENGINEER", "SITE_MANAGER"];

interface Props {
  open: boolean;
  onClose: () => void;
  projectId: string;
  activity: ActivityResponse | null;
}

export function SetSupervisorDialog({ open, onClose, projectId, activity }: Props) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-w-md">
        {activity && (
          <DialogInner
            // Re-mount whenever we point at a different activity so local state initialises
            // fresh from the activity's current supervisor set — no setState-in-effect needed.
            key={activity.id}
            projectId={projectId}
            activity={activity}
            onClose={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function DialogInner({
  projectId,
  activity,
  onClose,
}: {
  projectId: string;
  activity: ActivityResponse;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();

  // Initial selection: prefer the new {@code supervisors} list; fall back to the legacy
  // single-supervisor cache so activities seeded before the multi-supervisor rollout still
  // show their current assignment.
  const initialIds = useMemo<string[]>(() => {
    if (activity.supervisors && activity.supervisors.length > 0) {
      return activity.supervisors.map((s) => s.userId);
    }
    if (activity.supervisorUserId) return [activity.supervisorUserId];
    return [];
  }, [activity]);
  const [pickedIds, setPickedIds] = useState<string[]>(initialIds);

  const { data: users, isLoading: isLoadingPool } = useQuery({
    queryKey: ["users", "by-roles", SUPERVISOR_ROLES],
    queryFn: () => userApi.listByRoles(SUPERVISOR_ROLES),
  });

  const supervisorOptions = useMemo(() => {
    return (users ?? []).map((u) => {
      const prefix = u.employeeCode || u.username;
      return {
        value: u.id,
        label: prefix && prefix !== u.name ? `${prefix} — ${u.name}` : u.name,
      };
    });
  }, [users]);

  const mutation = useMutation({
    mutationFn: () => {
      const supervisors: SupervisorEntry[] = pickedIds.map((id) => {
        const matched = users?.find((u) => u.id === id);
        return { userId: id, userName: matched?.name ?? null };
      });
      return activityApi.setSupervisors(projectId, activity.id, { supervisors });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activity.id] });
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
      queryClient.invalidateQueries({ queryKey: ["wbs", projectId] });
      toast.success(
        pickedIds.length === 0
          ? "Supervisors cleared"
          : pickedIds.length === 1
            ? "Supervisor updated"
            : `${pickedIds.length} supervisors saved`,
      );
      onClose();
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to update supervisors"));
    },
  });

  const isUnchanged =
    pickedIds.length === initialIds.length &&
    pickedIds.every((id) => initialIds.includes(id));

  return (
    <>
      <DialogHeader>
        <DialogTitle>Set supervisors</DialogTitle>
        <p className="mt-1 text-xs text-slate">
          {activity.code} — {activity.name}
        </p>
      </DialogHeader>
      <DialogBody>
        <label className="block text-sm font-medium text-text-secondary">
          Supervisors
        </label>
        <p className="mt-0.5 mb-1 text-xs text-text-muted">
          Add everyone who supervises this activity. All entries are equal — there is no
          primary.
        </p>
        {isLoadingPool ? (
          <p className="mt-1 text-xs text-text-muted">Loading users…</p>
        ) : supervisorOptions.length === 0 ? (
          <p className="mt-1 text-xs text-amber-600">
            No users with a supervisor / foreman / site-engineer / site-manager role were
            found. Grant one of those roles in User Administration to make a user eligible.
          </p>
        ) : (
          <div className="mt-1">
            <MultiSelect
              options={supervisorOptions}
              value={pickedIds}
              onChange={setPickedIds}
              placeholder="Search supervisor candidates…"
            />
          </div>
        )}
      </DialogBody>
      <DialogFooter>
        <button
          type="button"
          onClick={onClose}
          className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending || isUnchanged}
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
        >
          {mutation.isPending ? "Saving…" : "Save"}
        </button>
      </DialogFooter>
    </>
  );
}
