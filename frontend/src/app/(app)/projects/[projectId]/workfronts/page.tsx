"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import {
  workfrontApi,
  type WorkfrontResponse,
  type WorkfrontStatus,
  type CreateWorkfrontRequest,
  type UpdateWorkfrontRequest,
} from "@/lib/api/workfrontApi";
import { useAuthStore } from "@/lib/state/store";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import { getErrorMessage } from "@/lib/utils/error";

interface WorkfrontFormState {
  wbsCode: string;
  locationCode: string;
  status: WorkfrontStatus;
  blockers: string;
  notes: string;
}

const emptyForm: WorkfrontFormState = {
  wbsCode: "",
  locationCode: "",
  status: "PLANNED",
  blockers: "",
  notes: "",
};

const statusBadgeClass = (s: WorkfrontStatus): string => {
  switch (s) {
    case "PLANNED":
      return "bg-surface-active/50 text-text-secondary ring-1 ring-border/50";
    case "READY":
      return "bg-warning/10 text-warning ring-1 ring-warning/20";
    case "RELEASED":
      return "bg-success/10 text-success ring-1 ring-success/20";
    case "HANDED_OVER":
      return "bg-accent/10 text-accent ring-1 ring-accent/20";
  }
};

const fmtDate = (iso: string | null | undefined): string => {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
};

export default function WorkfrontsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();

  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCreate = hasPermission("WORKFRONT.CREATE");
  const canUpdate = hasPermission("WORKFRONT.UPDATE");
  const canRelease = hasPermission("WORKFRONT.RELEASE");

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<WorkfrontResponse | null>(null);
  const [form, setForm] = useState<WorkfrontFormState>(emptyForm);
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["workfronts", projectId],
    queryFn: () => workfrontApi.list(projectId),
    enabled: Boolean(projectId),
  });

  const rows: WorkfrontResponse[] = useMemo(() => data?.data ?? [], [data]);

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ["workfronts", projectId] });

  const createMutation = useMutation({
    mutationFn: (payload: CreateWorkfrontRequest) =>
      workfrontApi.create(projectId, payload),
    onSuccess: () => {
      invalidate();
      closeDrawer();
    },
    onError: (err: unknown) =>
      setError(getErrorMessage(err, "Failed to create workfront")),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateWorkfrontRequest }) =>
      workfrontApi.update(projectId, id, payload),
    onSuccess: () => {
      invalidate();
      closeDrawer();
    },
    onError: (err: unknown) =>
      setError(getErrorMessage(err, "Failed to update workfront")),
  });

  const releaseMutation = useMutation({
    mutationFn: (id: string) => workfrontApi.release(projectId, id),
    onSuccess: () => invalidate(),
    onError: (err: unknown) =>
      setError(getErrorMessage(err, "Failed to release workfront")),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setError(null);
    setDrawerOpen(true);
  };

  const openEdit = (row: WorkfrontResponse) => {
    setEditing(row);
    setForm({
      wbsCode: row.wbsCode ?? "",
      locationCode: row.locationCode ?? "",
      status: row.status,
      blockers: row.blockers ?? "",
      notes: row.notes ?? "",
    });
    setError(null);
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditing(null);
    setForm(emptyForm);
    setError(null);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const payload = {
      wbsCode: form.wbsCode || undefined,
      locationCode: form.locationCode || undefined,
      status: form.status,
      blockers: form.blockers || undefined,
      notes: form.notes || undefined,
    };
    if (editing) {
      updateMutation.mutate({ id: editing.id, payload });
    } else {
      createMutation.mutate(payload);
    }
  };

  const columns = useMemo<ColumnDef<WorkfrontResponse>[]>(
    () => [
      {
        accessorKey: "locationCode",
        header: "Location",
        cell: ({ row }) => row.original.locationCode ?? "—",
      },
      {
        accessorKey: "wbsCode",
        header: "WBS",
        cell: ({ row }) => row.original.wbsCode ?? "—",
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: ({ row }) => (
          <span
            className={`px-2 py-1 rounded text-sm ${statusBadgeClass(row.original.status)}`}
          >
            {row.original.status.replace("_", " ")}
          </span>
        ),
      },
      {
        accessorKey: "readyAt",
        header: "Ready At",
        cell: ({ row }) => fmtDate(row.original.readyAt),
      },
      {
        accessorKey: "notes",
        header: "Notes",
        cell: ({ row }) => row.original.notes ?? "—",
      },
      {
        id: "actions",
        header: "Actions",
        cell: ({ row }) => {
          const wf = row.original;
          return (
            <div className="flex gap-2">
              {canUpdate && wf.status !== "HANDED_OVER" && (
                <button
                  type="button"
                  onClick={() => openEdit(wf)}
                  className="px-2 py-1 text-xs rounded bg-surface-active/50 text-text-secondary hover:bg-surface-active"
                >
                  Edit
                </button>
              )}
              {canRelease && wf.status === "READY" && (
                <button
                  type="button"
                  onClick={() => releaseMutation.mutate(wf.id)}
                  disabled={releaseMutation.isPending}
                  className="px-2 py-1 text-xs rounded bg-success/10 text-success ring-1 ring-success/20 hover:bg-success/20 disabled:opacity-50"
                >
                  Release
                </button>
              )}
            </div>
          );
        },
      },
    ],
    [canUpdate, canRelease, releaseMutation],
  );

  return (
    <div className="p-6">
      <PageHeader
        title="Workfronts"
        description="Track planned, ready, and released work-fronts handed to crews."
        actions={
          canCreate ? (
            <button
              type="button"
              onClick={openCreate}
              className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
            >
              Add Workfront
            </button>
          ) : null
        }
      />

      {error && <div className="text-danger mb-4">{error}</div>}

      {!isLoading && rows.length === 0 ? (
        <EmptyState
          title="No workfronts yet"
          description="Create a workfront to mark a zone of work ready for release."
          action={
            canCreate
              ? { label: "Add Workfront", onClick: openCreate }
              : undefined
          }
        />
      ) : (
        <VirtualDataTable
          columns={columns}
          data={rows}
          sortable
          resizable
          isLoading={isLoading}
          emptyMessage="No workfronts for this project."
        />
      )}

      {drawerOpen && (
        <div className="fixed inset-0 z-40 flex justify-end bg-black/40">
          <div className="w-full max-w-xl h-full overflow-y-auto bg-surface border-l border-border p-6 shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-semibold text-text-primary">
                {editing ? "Edit Workfront" : "Add Workfront"}
              </h2>
              <button
                type="button"
                onClick={closeDrawer}
                className="text-text-secondary hover:text-text-primary"
              >
                ✕
              </button>
            </div>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Location Code
                </label>
                <input
                  type="text"
                  value={form.locationCode}
                  onChange={(e) =>
                    setForm({ ...form, locationCode: e.target.value })
                  }
                  placeholder="e.g. Zone-A / Pier-3"
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  WBS Code
                </label>
                <input
                  type="text"
                  value={form.wbsCode}
                  onChange={(e) => setForm({ ...form, wbsCode: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Status
                </label>
                <select
                  value={form.status}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      status: e.target.value as WorkfrontStatus,
                    })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="PLANNED">Planned</option>
                  <option value="READY">Ready</option>
                  {editing && <option value="RELEASED">Released</option>}
                  {editing && <option value="HANDED_OVER">Handed Over</option>}
                </select>
                {editing && (
                  <p className="mt-1 text-xs text-text-muted">
                    Status transitions follow PLANNED → READY → RELEASED → HANDED_OVER.
                    Use the inline Release action for the RELEASED transition.
                  </p>
                )}
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Blockers
                </label>
                <textarea
                  rows={2}
                  value={form.blockers}
                  onChange={(e) =>
                    setForm({ ...form, blockers: e.target.value })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Notes
                </label>
                <textarea
                  rows={3}
                  value={form.notes}
                  onChange={(e) => setForm({ ...form, notes: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div className="flex gap-2 pt-2">
                <button
                  type="submit"
                  disabled={
                    createMutation.isPending || updateMutation.isPending
                  }
                  className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover disabled:opacity-50"
                >
                  {editing ? "Save" : "Create"}
                </button>
                <button
                  type="button"
                  onClick={closeDrawer}
                  className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
