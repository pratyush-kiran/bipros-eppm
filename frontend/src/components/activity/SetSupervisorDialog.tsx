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
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { activityApi, type ActivityResponse } from "@/lib/api/activityApi";
import { userApi } from "@/lib/api/userApi";
import { getErrorMessage } from "@/lib/utils/error";

/**
 * Roles eligible to supervise an activity. Server-side filter on {@code /v1/users?roles=...}
 * (Phase 4.6) returns only users carrying at least one of these. Keep in sync with the
 * DPR page's supervisorOptions source and SupervisorAssignmentTab.
 */
const SUPERVISOR_ROLES = ["SUPERVISOR", "FOREMAN", "SITE_ENGINEER", "SITE_MANAGER"];

const CLEAR_VALUE = "__clear__";

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
            // fresh from the activity's current supervisor — no setState-in-effect needed.
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
  const [pickedId, setPickedId] = useState<string>(activity.responsibleResourceId ?? "");

  // Phase 4.4 RBAC: supervisor candidates now come from the User pool (filtered by role on
  // the server), not from the project Resource pool. The picker value is a User UUID.
  const { data: users, isLoading: isLoadingPool } = useQuery({
    queryKey: ["users", "by-roles", SUPERVISOR_ROLES],
    queryFn: () => userApi.listByRoles(SUPERVISOR_ROLES),
  });

  const supervisorOptions = useMemo(() => {
    return (users ?? []).map((u) => {
      // Prefer the personnel master employeeCode for the row prefix; fall back to username
      // so admin / legacy users without a code still render something useful.
      const prefix = u.employeeCode || u.username;
      return {
        value: u.id,
        label: prefix && prefix !== u.name ? `${prefix} — ${u.name}` : u.name,
      };
    });
  }, [users]);

  const optionsWithClear = useMemo(
    () => [{ value: CLEAR_VALUE, label: "— Clear supervisor —" }, ...supervisorOptions],
    [supervisorOptions]
  );

  const mutation = useMutation({
    mutationFn: () => {
      const isClear = !pickedId || pickedId === CLEAR_VALUE;
      const matched = users?.find((u) => u.id === pickedId) ?? null;
      return activityApi.setSupervisor(projectId, activity.id, {
        supervisorUserId: isClear ? null : pickedId,
        supervisorName: isClear ? null : (matched?.name ?? null),
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["activity", projectId, activity.id] });
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
      queryClient.invalidateQueries({ queryKey: ["wbs", projectId] });
      toast.success("Supervisor updated");
      onClose();
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to update supervisor"));
    },
  });

  const isClearing = pickedId === CLEAR_VALUE;
  const currentId = activity.responsibleResourceId ?? "";
  const isUnchanged = !isClearing && pickedId === currentId;
  const cantClear = isClearing && !currentId;

  return (
    <>
      <DialogHeader>
        <DialogTitle>Set supervisor</DialogTitle>
        <p className="mt-1 text-xs text-slate">
          {activity.code} — {activity.name}
        </p>
      </DialogHeader>
      <DialogBody>
        <label className="block text-sm font-medium text-text-secondary">
          Supervisor
        </label>
        {isLoadingPool ? (
          <p className="mt-1 text-xs text-text-muted">Loading users…</p>
        ) : supervisorOptions.length === 0 ? (
          <p className="mt-1 text-xs text-amber-600">
            No users with a supervisor / foreman / site-engineer / site-manager role were
            found. Grant one of those roles in User Administration to make a user eligible.
          </p>
        ) : (
          <div className="mt-1">
            <SearchableSelect
              value={pickedId}
              onChange={setPickedId}
              placeholder="Search supervisor candidates…"
              options={optionsWithClear}
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
          disabled={mutation.isPending || isUnchanged || cantClear}
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
        >
          {mutation.isPending ? "Saving…" : isClearing ? "Clear" : "Save"}
        </button>
      </DialogFooter>
    </>
  );
}
