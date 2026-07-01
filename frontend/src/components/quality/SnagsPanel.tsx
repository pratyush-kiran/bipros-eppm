"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import {
  snagApi,
  type SnagResponse,
  type SnagSeverity,
  type SnagStatus,
  type CreateSnagRequest,
  type UpdateSnagRequest,
} from "@/lib/api/snagApi";
import { useAuthStore } from "@/lib/state/store";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import { getErrorMessage } from "@/lib/utils/error";

interface SnagFormState {
  activityId: string;
  locationCode: string;
  description: string;
  severity: SnagSeverity;
  status: SnagStatus;
}

const emptyForm: SnagFormState = {
  activityId: "",
  locationCode: "",
  description: "",
  severity: "MEDIUM",
  status: "OPEN",
};

const severityBadgeClass = (s: SnagSeverity): string => {
  switch (s) {
    case "LOW":
      return "bg-surface-active/50 text-text-secondary ring-1 ring-border/50";
    case "MEDIUM":
      return "bg-warning/10 text-warning ring-1 ring-warning/20";
    case "HIGH":
      return "bg-danger/10 text-danger ring-1 ring-danger/20";
  }
};

const statusBadgeClass = (s: SnagStatus): string => {
  switch (s) {
    case "OPEN":
      return "bg-danger/10 text-danger ring-1 ring-danger/20";
    case "IN_PROGRESS":
      return "bg-warning/10 text-warning ring-1 ring-warning/20";
    case "CLOSED":
      return "bg-success/10 text-success ring-1 ring-success/20";
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

export function SnagsPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();

  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canCreate = hasPermission("SNAG.CREATE");
  const canUpdate = hasPermission("SNAG.UPDATE");
  const canClose = hasPermission("SNAG.CLOSE");

  const [statusFilter, setStatusFilter] = useState<SnagStatus | "">("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<SnagResponse | null>(null);
  const [form, setForm] = useState<SnagFormState>(emptyForm);
  const [closing, setClosing] = useState<SnagResponse | null>(null);
  const [closureNote, setClosureNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["snags", projectId, statusFilter || "ALL"],
    queryFn: () => snagApi.list(projectId, statusFilter || undefined),
    enabled: Boolean(projectId),
  });

  const rows: SnagResponse[] = useMemo(() => data?.data ?? [], [data]);

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ["snags", projectId] });

  const createMutation = useMutation({
    mutationFn: (payload: CreateSnagRequest) =>
      snagApi.create(projectId, payload),
    onSuccess: () => {
      invalidate();
      closeDrawer();
    },
    onError: (err: unknown) =>
      setError(getErrorMessage(err, "Failed to raise snag")),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateSnagRequest }) =>
      snagApi.update(projectId, id, payload),
    onSuccess: () => {
      invalidate();
      closeDrawer();
    },
    onError: (err: unknown) =>
      setError(getErrorMessage(err, "Failed to update snag")),
  });

  const closeMutation = useMutation({
    mutationFn: ({ id, note }: { id: string; note: string }) =>
      snagApi.close(projectId, id, { closureNote: note || undefined }),
    onSuccess: () => {
      invalidate();
      setClosing(null);
      setClosureNote("");
    },
    onError: (err: unknown) =>
      setError(getErrorMessage(err, "Failed to close snag")),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setError(null);
    setDrawerOpen(true);
  };

  const openEdit = (row: SnagResponse) => {
    setEditing(row);
    setForm({
      activityId: row.activityId ?? "",
      locationCode: row.locationCode ?? "",
      description: row.description,
      severity: row.severity,
      status: row.status,
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
    if (editing) {
      const payload: UpdateSnagRequest = {
        activityId: form.activityId || undefined,
        locationCode: form.locationCode || undefined,
        description: form.description,
        severity: form.severity,
        status: form.status === "CLOSED" ? undefined : form.status,
      };
      updateMutation.mutate({ id: editing.id, payload });
    } else {
      const payload: CreateSnagRequest = {
        activityId: form.activityId || undefined,
        locationCode: form.locationCode || undefined,
        description: form.description,
        severity: form.severity,
      };
      createMutation.mutate(payload);
    }
  };

  const columns = useMemo<ColumnDef<SnagResponse>[]>(
    () => [
      {
        accessorKey: "description",
        header: "Description",
        cell: ({ row }) => (
          <span className="text-text-primary">{row.original.description}</span>
        ),
      },
      {
        accessorKey: "locationCode",
        header: "Location",
        cell: ({ row }) => row.original.locationCode ?? "—",
      },
      {
        accessorKey: "severity",
        header: "Severity",
        cell: ({ row }) => (
          <span
            className={`px-2 py-1 rounded text-sm ${severityBadgeClass(row.original.severity)}`}
          >
            {row.original.severity}
          </span>
        ),
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
        accessorKey: "raisedAt",
        header: "Raised At",
        cell: ({ row }) => fmtDate(row.original.raisedAt),
      },
      {
        accessorKey: "closedAt",
        header: "Closed At",
        cell: ({ row }) => fmtDate(row.original.closedAt),
      },
      {
        id: "actions",
        header: "Actions",
        cell: ({ row }) => {
          const snag = row.original;
          return (
            <div className="flex gap-2">
              {canUpdate && snag.status !== "CLOSED" && (
                <button
                  type="button"
                  onClick={() => openEdit(snag)}
                  className="px-2 py-1 text-xs rounded bg-surface-active/50 text-text-secondary hover:bg-surface-active"
                >
                  Edit
                </button>
              )}
              {canClose && snag.status !== "CLOSED" && (
                <button
                  type="button"
                  onClick={() => {
                    setClosing(snag);
                    setClosureNote("");
                  }}
                  className="px-2 py-1 text-xs rounded bg-success/10 text-success ring-1 ring-success/20 hover:bg-success/20"
                >
                  Close
                </button>
              )}
            </div>
          );
        },
      },
    ],
    [canUpdate, canClose],
  );

  return (
    <>
      <PageHeader
        title="Snags (Punch List)"
        description="Defects and rework items raised on site. Track severity, owner, and closure."
        actions={
          canCreate ? (
            <button
              type="button"
              onClick={openCreate}
              className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
            >
              Raise Snag
            </button>
          ) : null
        }
      />

      <div className="mb-4 flex items-center gap-2">
        <label className="text-sm text-text-secondary">Filter status:</label>
        <select
          value={statusFilter}
          onChange={(e) =>
            setStatusFilter((e.target.value || "") as SnagStatus | "")
          }
          className="px-3 py-1 border border-border bg-surface-hover text-text-primary rounded-md text-sm"
        >
          <option value="">All</option>
          <option value="OPEN">Open</option>
          <option value="IN_PROGRESS">In Progress</option>
          <option value="CLOSED">Closed</option>
        </select>
      </div>

      {error && <div className="text-danger mb-4">{error}</div>}

      {!isLoading && rows.length === 0 ? (
        <EmptyState
          title="No snags"
          description="No defects have been raised under this filter."
          action={
            canCreate ? { label: "Raise Snag", onClick: openCreate } : undefined
          }
        />
      ) : (
        <VirtualDataTable
          columns={columns}
          data={rows}
          sortable
          resizable
          isLoading={isLoading}
          emptyMessage="No snags for this project."
        />
      )}

      {drawerOpen && (
        <div className="fixed inset-0 z-40 flex justify-end bg-black/40">
          <div className="w-full max-w-xl h-full overflow-y-auto bg-surface border-l border-border p-6 shadow-xl">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-semibold text-text-primary">
                {editing ? "Edit Snag" : "Raise Snag"}
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
                  Description<span className="text-danger"> *</span>
                </label>
                <textarea
                  rows={3}
                  value={form.description}
                  onChange={(e) =>
                    setForm({ ...form, description: e.target.value })
                  }
                  required
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
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
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Activity ID (optional)
                </label>
                <input
                  type="text"
                  value={form.activityId}
                  onChange={(e) =>
                    setForm({ ...form, activityId: e.target.value })
                  }
                  placeholder="UUID of linked activity"
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Severity
                </label>
                <select
                  value={form.severity}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      severity: e.target.value as SnagSeverity,
                    })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="LOW">Low</option>
                  <option value="MEDIUM">Medium</option>
                  <option value="HIGH">High</option>
                </select>
              </div>
              {editing && (
                <div>
                  <label className="block text-sm font-medium mb-1 text-text-secondary">
                    Status
                  </label>
                  <select
                    value={form.status}
                    onChange={(e) =>
                      setForm({ ...form, status: e.target.value as SnagStatus })
                    }
                    className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  >
                    <option value="OPEN">Open</option>
                    <option value="IN_PROGRESS">In Progress</option>
                  </select>
                  <p className="mt-1 text-xs text-text-muted">
                    Use the inline Close action to close a snag.
                  </p>
                </div>
              )}
              <div className="flex gap-2 pt-2">
                <button
                  type="submit"
                  disabled={
                    createMutation.isPending || updateMutation.isPending
                  }
                  className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover disabled:opacity-50"
                >
                  {editing ? "Save" : "Raise"}
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

      {closing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="w-full max-w-md bg-surface border border-border rounded-lg p-6 shadow-xl">
            <h3 className="text-lg font-semibold text-text-primary mb-3">
              Close Snag
            </h3>
            <p className="text-sm text-text-secondary mb-3">
              {closing.description}
            </p>
            <label className="block text-sm font-medium mb-1 text-text-secondary">
              Closure Note
            </label>
            <textarea
              rows={3}
              value={closureNote}
              onChange={(e) => setClosureNote(e.target.value)}
              className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
            <div className="flex gap-2 mt-4 justify-end">
              <button
                type="button"
                onClick={() => {
                  setClosing(null);
                  setClosureNote("");
                }}
                className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() =>
                  closeMutation.mutate({ id: closing.id, note: closureNote })
                }
                disabled={closeMutation.isPending}
                className="px-4 py-2 bg-success/10 text-success ring-1 ring-success/20 rounded-lg hover:bg-success/20 disabled:opacity-50"
              >
                Confirm Close
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
