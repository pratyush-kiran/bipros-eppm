"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { activityApi, type ActivityResponse } from "@/lib/api/activityApi";
import { userApi } from "@/lib/api/userApi";
import { getErrorMessage } from "@/lib/utils/error";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { SupervisorList } from "./supervisor-assign/SupervisorList";
import {
  ActivityPickerTable,
  type ActivityFilter,
} from "./supervisor-assign/ActivityPickerTable";
import { BulkActionFooter } from "./supervisor-assign/BulkActionFooter";
import type { SupervisorOption } from "./supervisor-assign/SupervisorRow";

interface Props {
  projectId: string;
  onCancel?: () => void;
}

/**
 * Bulk-assign one supervisor (Manpower / Labor) across many activities. Sister to the
 * per-activity Supervisor field on the activity create / edit form — both write to
 * Activity.responsibleResourceId.
 */
export function SupervisorAssignmentTab({ projectId, onCancel }: Props) {
  const queryClient = useQueryClient();

  const [supervisorId, setSupervisorId] = useState<string>("");
  const [supervisorSearch, setSupervisorSearch] = useState<string>("");
  const [checkedActivityIds, setCheckedActivityIds] = useState<Set<string>>(
    new Set()
  );
  const [userTouched, setUserTouched] = useState(false);
  const [confirmReplaceOpen, setConfirmReplaceOpen] = useState(false);
  const [activitySearch, setActivitySearch] = useState("");
  const [activityFilter, setActivityFilter] = useState<ActivityFilter>("all");

  // Phase 4.4 RBAC: supervisor candidates now come from the User pool, scoped by role
  // (SUPERVISOR / FOREMAN / SITE_ENGINEER / SITE_MANAGER). The legacy project-resource
  // pool was unable to express "this person can supervise" cleanly and didn't survive the
  // user/resource split.
  const { data: supervisorUsers, isLoading: isLoadingPool } = useQuery({
    queryKey: ["users-by-role", "supervisor-pool"],
    queryFn: () =>
      userApi.listByRoles(["SUPERVISOR", "FOREMAN", "SITE_ENGINEER", "SITE_MANAGER"]),
    enabled: !!projectId,
  });

  const { data: activitiesData, isLoading: isLoadingActivities } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 1000),
    enabled: !!projectId,
  });

  const activities: ActivityResponse[] = useMemo(
    () => activitiesData?.data?.content ?? [],
    [activitiesData]
  );
  const sortedActivities = useMemo(
    () =>
      [...activities].sort((a, b) =>
        a.code.localeCompare(b.code, undefined, { numeric: true })
      ),
    [activities]
  );

  const supervisorOptions: SupervisorOption[] = useMemo(() => {
    return (supervisorUsers ?? [])
      .map((u) => ({
        value: u.id,
        // The User pool exposes an employee code (Personnel Master) rather than a
        // resource code; the picker UI renders whichever is present.
        code: u.employeeCode ?? u.username,
        name: u.name,
        // Role chip is now redundant (everyone in this list already carries one of
        // the supervisor roles); leave null so the row stays compact.
        role: null,
      }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [supervisorUsers]);

  const filteredSupervisorOptions = useMemo(() => {
    const q = supervisorSearch.trim().toLowerCase();
    if (!q) return supervisorOptions;
    return supervisorOptions.filter(
      (o) =>
        o.name.toLowerCase().includes(q) ||
        o.code.toLowerCase().includes(q) ||
        (o.role ?? "").toLowerCase().includes(q)
    );
  }, [supervisorOptions, supervisorSearch]);

  const workloadById = useMemo(() => {
    const map = new Map<string, number>();
    for (const a of activities) {
      if (a.responsibleResourceId) {
        map.set(
          a.responsibleResourceId,
          (map.get(a.responsibleResourceId) ?? 0) + 1
        );
      }
    }
    return map;
  }, [activities]);

  const conflictIds = useMemo(() => {
    if (!supervisorId) return new Set<string>();
    const out = new Set<string>();
    for (const a of sortedActivities) {
      if (
        a.responsibleResourceId &&
        a.responsibleResourceId !== supervisorId
      ) {
        out.add(a.id);
      }
    }
    return out;
  }, [sortedActivities, supervisorId]);

  const filterCounts = useMemo(() => {
    let unassigned = 0;
    let hasSupervisor = 0;
    for (const a of sortedActivities) {
      if (a.responsibleResourceId) hasSupervisor++;
      else unassigned++;
    }
    return {
      all: sortedActivities.length,
      unassigned,
      hasSupervisor,
      conflicts: conflictIds.size,
    };
  }, [sortedActivities, conflictIds]);

  const filteredActivities = useMemo(() => {
    const q = activitySearch.trim().toLowerCase();
    return sortedActivities.filter((a) => {
      if (activityFilter === "unassigned" && a.responsibleResourceId) return false;
      if (activityFilter === "has-supervisor" && !a.responsibleResourceId)
        return false;
      if (activityFilter === "conflicts" && !conflictIds.has(a.id)) return false;
      if (q) {
        if (
          !a.code.toLowerCase().includes(q) &&
          !a.name.toLowerCase().includes(q)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [sortedActivities, activitySearch, activityFilter, conflictIds]);

  const handleSupervisorChange = (val: string) => {
    setSupervisorId(val);
    if (!userTouched) {
      const next = new Set<string>();
      for (const a of activities) {
        if (a.responsibleResourceId === val) next.add(a.id);
      }
      setCheckedActivityIds(next);
    }
  };

  const handleToggleActivity = (activityId: string) => {
    setUserTouched(true);
    setCheckedActivityIds((prev) => {
      const next = new Set(prev);
      if (next.has(activityId)) next.delete(activityId);
      else next.add(activityId);
      return next;
    });
  };

  const handleToggleAll = () => {
    setUserTouched(true);
    setCheckedActivityIds((prev) => {
      const allSelected = filteredActivities.every((a) => prev.has(a.id));
      const next = new Set(prev);
      if (allSelected) {
        for (const a of filteredActivities) next.delete(a.id);
      } else {
        for (const a of filteredActivities) next.add(a.id);
      }
      return next;
    });
  };

  const selectedSupervisor = useMemo(() => {
    const opt = supervisorOptions.find((o) => o.value === supervisorId);
    return opt
      ? { id: opt.value, name: opt.name, code: opt.code }
      : null;
  }, [supervisorOptions, supervisorId]);

  const replaceCount = useMemo(() => {
    if (!supervisorId) return 0;
    let n = 0;
    for (const id of checkedActivityIds) {
      if (conflictIds.has(id)) n++;
    }
    return n;
  }, [checkedActivityIds, conflictIds, supervisorId]);

  const noOpCount = useMemo(() => {
    if (!supervisorId) return 0;
    let n = 0;
    for (const a of sortedActivities) {
      if (
        checkedActivityIds.has(a.id) &&
        a.responsibleResourceId === supervisorId
      ) {
        n++;
      }
    }
    return n;
  }, [sortedActivities, checkedActivityIds, supervisorId]);

  const newAssignmentCount =
    checkedActivityIds.size - replaceCount - noOpCount;

  const saveMutation = useMutation({
    mutationFn: () =>
      activityApi.bulkSetSupervisor(projectId, {
        // Phase 4.4 RBAC: the picker is sourced from `userApi.listByRoles([...])`
        // so `supervisorId` is a User UUID (not a Resource UUID). The body field
        // names mirror the per-activity setSupervisor contract.
        supervisorUserId: supervisorId,
        supervisorName: selectedSupervisor?.name ?? null,
        activityIds: Array.from(checkedActivityIds),
      }),
    onSuccess: (resp) => {
      const updated = resp.data?.updated ?? checkedActivityIds.size;
      toast.success(
        `Supervisor set on ${updated} ${updated === 1 ? "activity" : "activities"}`
      );
      queryClient.invalidateQueries({ queryKey: ["activities", projectId] });
      queryClient.invalidateQueries({
        queryKey: ["resource-assignments", projectId],
      });
      setUserTouched(false);
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to assign supervisor"));
    },
  });

  const handleSave = () => {
    if (replaceCount > 0) {
      setConfirmReplaceOpen(true);
      return;
    }
    saveMutation.mutate();
  };

  const canSave =
    !!supervisorId && checkedActivityIds.size > 0 && !saveMutation.isPending;

  return (
    <div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[340px_1fr] items-start">
        <div>
          <SupervisorList
            options={supervisorOptions}
            filteredOptions={filteredSupervisorOptions}
            selectedId={supervisorId}
            onSelect={handleSupervisorChange}
            search={supervisorSearch}
            onSearchChange={setSupervisorSearch}
            workloadById={workloadById}
            isLoading={isLoadingPool}
          />
        </div>

        <div>
          <ActivityPickerTable
            activities={sortedActivities}
            filteredActivities={filteredActivities}
            totalActivityCount={sortedActivities.length}
            filterCounts={filterCounts}
            selectedSupervisorId={supervisorId}
            checkedActivityIds={checkedActivityIds}
            onToggleActivity={handleToggleActivity}
            onToggleAll={handleToggleAll}
            search={activitySearch}
            onSearchChange={setActivitySearch}
            filter={activityFilter}
            onFilterChange={setActivityFilter}
            isLoading={isLoadingActivities}
          />
        </div>
      </div>

      <BulkActionFooter
        selectedSupervisor={selectedSupervisor}
        selectedCount={checkedActivityIds.size}
        newAssignmentCount={Math.max(0, newAssignmentCount)}
        replaceCount={replaceCount}
        noOpCount={noOpCount}
        isSaving={saveMutation.isPending}
        canSave={canSave}
        onCancel={onCancel}
        onSave={handleSave}
      />

      <Dialog
        open={confirmReplaceOpen}
        onOpenChange={(next) => setConfirmReplaceOpen(next)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Replace existing supervisor?</DialogTitle>
          </DialogHeader>
          <DialogBody>
            <p>
              {replaceCount}{" "}
              {replaceCount === 1 ? "activity has" : "activities have"} a
              different supervisor already. Saving will replace{" "}
              {replaceCount === 1 ? "it" : "them"} with{" "}
              <span className="font-semibold text-charcoal">
                {selectedSupervisor?.name}
              </span>
              .
            </p>
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setConfirmReplaceOpen(false)}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirmReplaceOpen(false);
                saveMutation.mutate();
              }}
              className="rounded-md bg-warning px-4 py-2 text-sm font-medium text-text-primary hover:bg-warning/80"
            >
              Replace &amp; save
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
