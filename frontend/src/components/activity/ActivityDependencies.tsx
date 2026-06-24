"use client";

import { useState, useMemo, useCallback } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { activityApi } from "@/lib/api/activityApi";
import type { RelationshipResponse, RelationshipType, ActivityResponse } from "@/lib/api/activityApi";
import { SimpleTable } from "@/components/common/SimpleTable";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { getErrorMessage } from "@/lib/utils/error";
import { Plus, Trash2, ArrowRight, ArrowLeft } from "lucide-react";
import Link from "next/link";
import toast from "react-hot-toast";
import { useScheduleStaleStore } from "@/lib/state/scheduleStaleStore";

const RELATIONSHIP_TYPE_LABELS: Record<RelationshipType, string> = {
  FINISH_TO_START: "Finish to Start (FS)",
  FINISH_TO_FINISH: "Finish to Finish (FF)",
  START_TO_START: "Start to Start (SS)",
  START_TO_FINISH: "Start to Finish (SF)",
};

const RELATIONSHIP_TYPE_SHORT: Record<string, string> = {
  FINISH_TO_START: "FS",
  FINISH_TO_FINISH: "FF",
  START_TO_START: "SS",
  START_TO_FINISH: "SF",
};

interface ActivityDependenciesProps {
  projectId: string;
  activityId: string;
  activityName: string;
}

export function ActivityDependencies({ projectId, activityId, activityName }: ActivityDependenciesProps) {
  const queryClient = useQueryClient();
  const markScheduleStale = useScheduleStaleStore((s) => s.markScheduleStale);
  const [showAddForm, setShowAddForm] = useState(false);
  const [addDirection, setAddDirection] = useState<"predecessor" | "successor">("predecessor");

  const { data: predecessorsData, isLoading: isLoadingPred } = useQuery({
    queryKey: ["predecessors", projectId, activityId],
    queryFn: () => activityApi.getPredecessors(projectId, activityId),
  });

  const { data: successorsData, isLoading: isLoadingSucc } = useQuery({
    queryKey: ["successors", projectId, activityId],
    queryFn: () => activityApi.getSuccessors(projectId, activityId),
  });

  // Share cache with the activities listing page (which uses ["activities",
  // projectId]); avoids a duplicate 209-row fetch when the user clicks View
  // from /activities and lands here.
  const { data: allActivitiesData } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 500),
  });

  const allActivities = useMemo(
    () => allActivitiesData?.data?.content ?? [],
    [allActivitiesData]
  );
  const predecessors = useMemo(
    () => predecessorsData?.data ?? [],
    [predecessorsData]
  );
  const successors = useMemo(
    () => successorsData?.data ?? [],
    [successorsData]
  );

  const deleteMutation = useMutation({
    mutationFn: (relationshipId: string) =>
      activityApi.deleteRelationship(projectId, relationshipId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["predecessors", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["successors", projectId, activityId] });
      queryClient.invalidateQueries({ queryKey: ["relationships", projectId] });
      markScheduleStale(projectId);
      toast.success("Dependency removed");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to remove dependency"));
    },
  });

  const activityById = useMemo(() => {
    const m = new Map<string, (typeof allActivities)[number]>();
    for (const a of allActivities) m.set(a.id, a);
    return m;
  }, [allActivities]);

  const getActivityName = useCallback(
    (id: string) => {
      const act = activityById.get(id);
      return act ? `${act.code} - ${act.name}` : id;
    },
    [activityById]
  );

  const handleOpenAdd = (direction: "predecessor" | "successor") => {
    setAddDirection(direction);
    setShowAddForm(true);
  };

  const predecessorColumns = useMemo<ColumnDef<RelationshipResponse>[]>(
    () => [
      {
        header: "Activity",
        accessorKey: "predecessorActivityId",
        cell: ({ getValue }) => {
          const id = String(getValue());
          return (
            <Link
              href={`/projects/${projectId}/activities/${id}`}
              className="text-text-primary hover:text-accent hover:underline"
            >
              {getActivityName(id)}
            </Link>
          );
        },
      },
      {
        header: "Type",
        accessorKey: "relationshipType",
        cell: ({ getValue }) => (
          <span className="text-text-secondary">
            {RELATIONSHIP_TYPE_SHORT[String(getValue())] ?? String(getValue())}
          </span>
        ),
      },
      {
        header: "Lag",
        accessorKey: "lag",
        cell: ({ getValue }) => (
          <span className="text-text-secondary">{Number(getValue() ?? 0)}d</span>
        ),
      },
      {
        header: "",
        id: "actions",
        cell: ({ row }) => (
          <button
            onClick={(e) => { e.stopPropagation(); deleteMutation.mutate(row.original.id); }}
            disabled={deleteMutation.isPending}
            className="text-danger hover:text-danger"
            title="Remove dependency"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        ),
      },
    ],
    [projectId, getActivityName, deleteMutation]
  );

  const successorColumns = useMemo<ColumnDef<RelationshipResponse>[]>(
    () => [
      {
        header: "Activity",
        accessorKey: "successorActivityId",
        cell: ({ getValue }) => {
          const id = String(getValue());
          return (
            <Link
              href={`/projects/${projectId}/activities/${id}`}
              className="text-text-primary hover:text-accent hover:underline"
            >
              {getActivityName(id)}
            </Link>
          );
        },
      },
      {
        header: "Type",
        accessorKey: "relationshipType",
        cell: ({ getValue }) => (
          <span className="text-text-secondary">
            {RELATIONSHIP_TYPE_SHORT[String(getValue())] ?? String(getValue())}
          </span>
        ),
      },
      {
        header: "Lag",
        accessorKey: "lag",
        cell: ({ getValue }) => (
          <span className="text-text-secondary">{Number(getValue() ?? 0)}d</span>
        ),
      },
      {
        header: "",
        id: "actions",
        cell: ({ row }) => (
          <button
            onClick={(e) => { e.stopPropagation(); deleteMutation.mutate(row.original.id); }}
            disabled={deleteMutation.isPending}
            className="text-danger hover:text-danger"
            title="Remove dependency"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        ),
      },
    ],
    [projectId, getActivityName, deleteMutation]
  );

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold text-text-primary">Dependencies</h3>

      {/* Predecessors */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ArrowLeft className="h-4 w-4 text-accent" />
            <h4 className="text-sm font-medium text-text-secondary">
              Predecessors ({predecessors.length})
            </h4>
          </div>
          <button
            onClick={() => handleOpenAdd("predecessor")}
            className="flex items-center gap-1 rounded-md bg-accent/20 px-3 py-1.5 text-xs font-medium text-accent hover:bg-accent/30"
          >
            <Plus className="h-3 w-3" />
            Add
          </button>
        </div>

        {isLoadingPred ? (
          <div className="text-sm text-text-muted">Loading...</div>
        ) : predecessors.length === 0 ? (
          <div className="text-sm text-text-muted">No predecessors. This activity can start independently.</div>
        ) : (
          <SimpleTable
            data={predecessors}
            columns={predecessorColumns}
            sortable={false}
            className="rounded-lg border-0"
          />
        )}

        {showAddForm && addDirection === "predecessor" && (
          <AddDependencyForm
            projectId={projectId}
            activityId={activityId}
            activityName={activityName}
            direction={addDirection}
            allActivities={allActivities}
            existingPredecessorIds={predecessors.map((r) => r.predecessorActivityId)}
            existingSuccessorIds={successors.map((r) => r.successorActivityId)}
            onClose={() => setShowAddForm(false)}
            onSuccess={() => {
              queryClient.invalidateQueries({ queryKey: ["predecessors", projectId, activityId] });
              queryClient.invalidateQueries({ queryKey: ["successors", projectId, activityId] });
              queryClient.invalidateQueries({ queryKey: ["relationships", projectId] });
              setShowAddForm(false);
            }}
          />
        )}
      </div>

      {/* Successors */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ArrowRight className="h-4 w-4 text-success" />
            <h4 className="text-sm font-medium text-text-secondary">
              Successors ({successors.length})
            </h4>
          </div>
          <button
            onClick={() => handleOpenAdd("successor")}
            className="flex items-center gap-1 rounded-md bg-green-600/20 px-3 py-1.5 text-xs font-medium text-success hover:bg-green-600/30"
          >
            <Plus className="h-3 w-3" />
            Add
          </button>
        </div>

        {isLoadingSucc ? (
          <div className="text-sm text-text-muted">Loading...</div>
        ) : successors.length === 0 ? (
          <div className="text-sm text-text-muted">No successors. No activities depend on this one.</div>
        ) : (
          <SimpleTable
            data={successors}
            columns={successorColumns}
            sortable={false}
            className="rounded-lg border-0"
          />
        )}

        {showAddForm && addDirection === "successor" && (
          <AddDependencyForm
            projectId={projectId}
            activityId={activityId}
            activityName={activityName}
            direction={addDirection}
            allActivities={allActivities}
            existingPredecessorIds={predecessors.map((r) => r.predecessorActivityId)}
            existingSuccessorIds={successors.map((r) => r.successorActivityId)}
            onClose={() => setShowAddForm(false)}
            onSuccess={() => {
              queryClient.invalidateQueries({ queryKey: ["predecessors", projectId, activityId] });
              queryClient.invalidateQueries({ queryKey: ["successors", projectId, activityId] });
              queryClient.invalidateQueries({ queryKey: ["relationships", projectId] });
              setShowAddForm(false);
            }}
          />
        )}
      </div>
    </div>
  );
}

interface AddDependencyFormProps {
  projectId: string;
  activityId: string;
  activityName: string;
  direction: "predecessor" | "successor";
  allActivities: ActivityResponse[];
  existingPredecessorIds: string[];
  existingSuccessorIds: string[];
  onClose: () => void;
  onSuccess: () => void;
}

function AddDependencyForm({
  projectId,
  activityId,
  direction,
  allActivities,
  existingPredecessorIds,
  existingSuccessorIds,
  onClose,
  onSuccess,
}: AddDependencyFormProps) {
  const markScheduleStale = useScheduleStaleStore((s) => s.markScheduleStale);
  const [selectedActivityId, setSelectedActivityId] = useState("");
  const [relationshipType, setRelationshipType] = useState<RelationshipType>("FINISH_TO_START");
  const [lag, setLag] = useState<number | "">(0);
  const [error, setError] = useState("");

  // Filter out current activity and already-linked activities
  const excludeIds = new Set([
    activityId,
    ...(direction === "predecessor" ? existingPredecessorIds : existingSuccessorIds),
  ]);

  const availableActivities = allActivities
    .filter((a) => !excludeIds.has(a.id))
    .map((a) => ({ value: a.id, label: `${a.code} - ${a.name}` }));

  const createMutation = useMutation({
    mutationFn: () => {
      const numericLag = lag === "" ? 0 : lag;
      const data = direction === "predecessor"
        ? { predecessorActivityId: selectedActivityId, successorActivityId: activityId, relationshipType, lag: numericLag }
        : { predecessorActivityId: activityId, successorActivityId: selectedActivityId, relationshipType, lag: numericLag };
      return activityApi.createRelationship(projectId, data);
    },
    onSuccess: () => {
      markScheduleStale(projectId);
      toast.success("Dependency added");
      onSuccess();
    },
    onError: (err: unknown) => {
      const msg = getErrorMessage(err, "Failed to add dependency");
      setError(msg);
      toast.error(msg);
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    if (!selectedActivityId) {
      setError("Please select an activity");
      return;
    }
    createMutation.mutate();
  };

  return (
    <div className="rounded-lg border border-blue-800/50 bg-blue-900/10 p-4">
      <h4 className="mb-3 text-sm font-medium text-text-primary">
        Add {direction === "predecessor" ? "Predecessor" : "Successor"}
      </h4>

      {error && (
        <div className="mb-3 rounded-md bg-danger/10 p-2 text-xs text-danger">{error}</div>
      )}

      <form onSubmit={handleSubmit} className="space-y-3">
        <div>
          <label className="mb-1 block text-xs text-text-secondary">Activity</label>
          <SearchableSelect
            options={availableActivities}
            value={selectedActivityId}
            onChange={(val) => {
              setSelectedActivityId(val);
              if (error) setError("");
            }}
            placeholder="Search activities..."
          />
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs text-text-secondary">Relationship Type</label>
            <select
              value={relationshipType}
              onChange={(e) => setRelationshipType(e.target.value as RelationshipType)}
              className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
            >
              {(Object.entries(RELATIONSHIP_TYPE_LABELS) as [RelationshipType, string][]).map(
                ([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                )
              )}
            </select>
          </div>

          <div>
            <label className="mb-1 block text-xs text-text-secondary">Lag (days)</label>
            <input
              type="number"
              value={lag}
              onChange={(e) => setLag(e.target.value === "" ? "" : parseFloat(e.target.value))}
              min="0"
              step="1"
              className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
            />
          </div>
        </div>

        <div className="flex gap-2 pt-1">
          <button
            type="submit"
            disabled={createMutation.isPending}
            className="rounded-md bg-accent px-4 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
          >
            {createMutation.isPending ? "Adding..." : "Add Dependency"}
          </button>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md bg-surface-active/50 px-4 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-active"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}
