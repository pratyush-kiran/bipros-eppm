"use client";

import { useState, useMemo } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  projectResourceApi,
  type ProjectResourceResponse,
} from "@/lib/api/projectResourceApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { Plus, Trash2, Users } from "lucide-react";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { displayResourceTypeName } from "@/lib/utils/resourceTypeLabel";
import { AddResourcesDrawer } from "./AddResourcesDrawer";

export function ProjectResourcePool({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const { money } = useProjectCurrency();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editValues, setEditValues] = useState<{
    rateOverride: string;
    availabilityOverride: string;
    customUnit: string;
    notes: string;
  }>({ rateOverride: "", availabilityOverride: "", customUnit: "", notes: "" });
  const [confirmDelete, setConfirmDelete] = useState<ProjectResourceResponse | null>(null);

  const { data: poolData, isLoading: isLoadingPool } = useQuery({
    queryKey: ["resource-pool", projectId],
    queryFn: () => projectResourceApi.listPool(projectId),
  });

  const updateMutation = useMutation({
    mutationFn: ({
      id,
      patch,
    }: {
      id: string;
      patch: {
        rateOverride?: number;
        availabilityOverride?: number;
        customUnit?: string;
        notes?: string;
      };
    }) => projectResourceApi.updatePoolEntry(projectId, id, patch),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resource-pool", projectId] });
      setEditingId(null);
    },
  });

  const removeMutation = useMutation({
    mutationFn: (id: string) => projectResourceApi.removeFromPool(projectId, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resource-pool", projectId] });
      queryClient.invalidateQueries({
        queryKey: ["resource-pool-available-drawer", projectId],
      });
      setConfirmDelete(null);
    },
    onError: (error: unknown) => {
      const err = error as { response?: { data?: { error?: { message?: string } } } };
      const msg =
        err?.response?.data?.error?.message ?? "Failed to remove resource from pool";
      setConfirmDelete(null);
      alert(msg);
    },
  });

  const pool = useMemo<ProjectResourceResponse[]>(() => {
    const raw = poolData?.data as unknown;
    return Array.isArray(raw) ? (raw as ProjectResourceResponse[]) : [];
  }, [poolData]);

  const typeBuckets = useMemo(() => {
    const counts = new Map<string, number>();
    for (const entry of pool) {
      const key = entry.resourceTypeName ?? "Other";
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
  }, [pool]);

  const visiblePool = useMemo(() => {
    if (!typeFilter) return pool;
    return pool.filter((p) => p.resourceTypeName === typeFilter);
  }, [pool, typeFilter]);

  const startEdit = (entry: ProjectResourceResponse) => {
    setEditingId(entry.id);
    setEditValues({
      rateOverride: entry.rateOverride?.toString() ?? "",
      availabilityOverride: entry.availabilityOverride?.toString() ?? "",
      customUnit: entry.customUnit ?? "",
      notes: entry.notes ?? "",
    });
  };

  const saveEdit = (id: string) => {
    updateMutation.mutate({
      id,
      patch: {
        rateOverride: editValues.rateOverride
          ? parseFloat(editValues.rateOverride)
          : undefined,
        availabilityOverride: editValues.availabilityOverride
          ? parseFloat(editValues.availabilityOverride)
          : undefined,
        customUnit: editValues.customUnit || undefined,
        notes: editValues.notes || undefined,
      },
    });
  };

  const poolColumns: ColumnDef<ProjectResourceResponse>[] = [
    { accessorKey: "resourceCode", header: "Code", enableSorting: true },
    { accessorKey: "resourceName", header: "Name", enableSorting: true },
    {
      accessorKey: "resourceTypeName",
      header: "Type",
      enableSorting: true,
      cell: (info) => displayResourceTypeName(info.getValue() as string | null),
    },
    {
      accessorKey: "roleName",
      header: "Role",
      enableSorting: true,
      cell: (info) => (info.getValue() as string) ?? "—",
    },
    {
      accessorKey: "masterRate",
      header: "Master Rate",
      enableSorting: true,
      cell: (info) =>
        info.getValue() != null
          ? money(Number(info.getValue()))
          : "—",
    },
    {
      accessorKey: "rateOverride",
      header: "Override Rate",
      cell: (info) => {
        const row = info.row.original;
        if (editingId === row.id) {
          return (
            <input
              type="number"
              value={editValues.rateOverride}
              onChange={(e) =>
                setEditValues({ ...editValues, rateOverride: e.target.value })
              }
              className="w-24 rounded border border-border bg-surface/50 px-2 py-1 text-sm text-text-primary"
              step="0.01"
              placeholder="—"
            />
          );
        }
        return info.getValue() != null
          ? money(Number(info.getValue()))
          : "—";
      },
    },
    {
      accessorKey: "availabilityOverride",
      header: "Override Avail.",
      cell: (info) => {
        const row = info.row.original;
        if (editingId === row.id) {
          return (
            <input
              type="number"
              value={editValues.availabilityOverride}
              onChange={(e) =>
                setEditValues({ ...editValues, availabilityOverride: e.target.value })
              }
              className="w-20 rounded border border-border bg-surface/50 px-2 py-1 text-sm text-text-primary"
              step="0.01"
              placeholder="—"
            />
          );
        }
        return info.getValue() != null ? `${Number(info.getValue())}%` : "—";
      },
    },
    {
      accessorKey: "notes",
      header: "Notes",
      cell: (info) => {
        const row = info.row.original;
        if (editingId === row.id) {
          return (
            <input
              type="text"
              value={editValues.notes}
              onChange={(e) =>
                setEditValues({ ...editValues, notes: e.target.value })
              }
              className="w-32 rounded border border-border bg-surface/50 px-2 py-1 text-sm text-text-primary"
              placeholder="Notes"
            />
          );
        }
        return (info.getValue() as string) ?? "—";
      },
    },
    {
      id: "actions",
      header: "Actions",
      cell: (info) => {
        const row = info.row.original;
        if (editingId === row.id) {
          return (
            <div className="flex gap-1">
              <button
                onClick={() => saveEdit(row.id)}
                disabled={updateMutation.isPending}
                className="rounded bg-accent px-2 py-1 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
              >
                Save
              </button>
              <button
                onClick={() => setEditingId(null)}
                className="rounded border border-border px-2 py-1 text-xs text-text-secondary hover:bg-surface-hover"
              >
                Cancel
              </button>
            </div>
          );
        }
        return (
          <div className="flex gap-1">
            <button
              onClick={() => startEdit(row)}
              className="rounded border border-border px-2 py-1 text-xs text-text-secondary hover:bg-surface-hover"
            >
              Edit
            </button>
            <button
              onClick={() => setConfirmDelete(row)}
              className="rounded border border-red-300 px-2 py-1 text-xs text-red-600 hover:bg-red-50"
            >
              <Trash2 size={12} />
            </button>
          </div>
        );
      },
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold text-text-primary">Project Team</h3>
          <p className="text-sm text-text-secondary">
            {pool.length} resource{pool.length !== 1 ? "s" : ""} on this project
          </p>
        </div>
        {pool.length > 0 && (
          <button
            onClick={() => setDrawerOpen(true)}
            className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
          >
            <Plus size={16} />
            Add Resources
          </button>
        )}
      </div>

      {pool.length > 0 && typeBuckets.length > 1 && (
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => setTypeFilter(null)}
            className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
              typeFilter === null
                ? "border-accent bg-accent/15 text-text-primary"
                : "border-border bg-surface text-text-secondary hover:border-accent/40 hover:text-text-primary"
            }`}
          >
            All <span className="ml-1 text-text-muted">{pool.length}</span>
          </button>
          {typeBuckets.map(([typeName, count]) => (
            <button
              key={typeName}
              onClick={() => setTypeFilter(typeFilter === typeName ? null : typeName)}
              className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                typeFilter === typeName
                  ? "border-accent bg-accent/15 text-text-primary"
                  : "border-border bg-surface text-text-secondary hover:border-accent/40 hover:text-text-primary"
              }`}
            >
              {displayResourceTypeName(typeName)}{" "}
              <span className="ml-1 text-text-muted">{count}</span>
            </button>
          ))}
        </div>
      )}

      <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-sm">
        {isLoadingPool ? (
          <div className="text-center text-text-secondary">Loading team...</div>
        ) : pool.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border py-12 text-center">
            <Users
              size={32}
              className="mx-auto mb-3 text-text-muted"
              strokeWidth={1.5}
            />
            <h3 className="text-lg font-medium text-text-primary">
              No team members assigned yet
            </h3>
            <p className="mx-auto mt-2 max-w-md text-sm text-text-secondary">
              Start by adding the people, equipment, and materials you&apos;ll need for
              this project.
            </p>
            <button
              onClick={() => setDrawerOpen(true)}
              className="mt-5 inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
            >
              <Plus size={16} />
              Add Resources
            </button>
          </div>
        ) : visiblePool.length === 0 ? (
          <div className="py-8 text-center text-sm text-text-secondary">
            No resources match the selected filter.
          </div>
        ) : (
          <VirtualDataTable
            columns={poolColumns}
            data={visiblePool}
            sortable
            resizable
          />
        )}
      </div>

      <AddResourcesDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        projectId={projectId}
      />

      {confirmDelete && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="mx-4 w-full max-w-md rounded-lg bg-surface p-6 shadow-xl">
            <h3 className="mb-2 text-lg font-semibold text-text-primary">
              Remove from Team
            </h3>
            <p className="mb-4 text-sm text-text-secondary">
              Remove{" "}
              <span className="font-medium text-text-primary">
                {confirmDelete.resourceName}
              </span>{" "}
              from this project&apos;s team? This will fail if the resource has active
              assignments.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setConfirmDelete(null)}
                className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover"
              >
                Cancel
              </button>
              <button
                onClick={() => removeMutation.mutate(confirmDelete.id)}
                disabled={removeMutation.isPending}
                className="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
              >
                {removeMutation.isPending ? "Removing..." : "Remove"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
