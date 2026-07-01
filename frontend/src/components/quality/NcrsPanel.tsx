"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { Drawer } from "@/components/common/Drawer";
import { EmptyState } from "@/components/common/EmptyState";
import { PageHeader } from "@/components/common/PageHeader";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useAuthStore } from "@/lib/state/store";
import { getErrorMessage } from "@/lib/utils/error";
import {
  ncrApi,
  type CreateNcrRequest,
  type NcrCategory,
  type NcrResponse,
  type NcrSeverity,
  type NcrStatus,
} from "@/lib/api/ncrApi";

export function NcrsPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((s) => s.hasPermission);

  const canCreate = hasPermission("NCR.CREATE");
  const canUpdate = hasPermission("NCR.UPDATE");
  const canApprove = hasPermission("NCR.APPROVE");

  const [statusFilter, setStatusFilter] = useState<NcrStatus | "">("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [closeDrawerOpen, setCloseDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<NcrResponse | null>(null);
  const [closingNcr, setClosingNcr] = useState<NcrResponse | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["ncrs", projectId, statusFilter],
    queryFn: () => ncrApi.list(projectId, statusFilter || undefined),
    enabled: !!projectId,
  });
  const rows = data?.data ?? [];

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["ncrs", projectId] });

  const rejectMutation = useMutation({
    mutationFn: (id: string) => ncrApi.reject(projectId, id),
    onSuccess: invalidate,
    onError: (e) => setPageError(getErrorMessage(e, "Failed to reject NCR")),
  });

  const openNew = () => {
    setEditing(null);
    setPageError(null);
    setDrawerOpen(true);
  };

  const openEdit = (row: NcrResponse) => {
    if (row.status === "CLOSED") return;
    setEditing(row);
    setPageError(null);
    setDrawerOpen(true);
  };

  const openClose = (row: NcrResponse) => {
    setClosingNcr(row);
    setPageError(null);
    setCloseDrawerOpen(true);
  };

  return (
    <>
      <PageHeader
        title="Non-Conformance Reports"
        description="Raise, track, and close non-conformances against quality / safety / workmanship standards."
        actions={
          canCreate ? (
            <button
              onClick={openNew}
              className="inline-flex items-center gap-1.5 rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:bg-accent-hover"
            >
              <Plus className="h-4 w-4" /> Raise NCR
            </button>
          ) : undefined
        }
      />

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div>
          <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
            Status
          </label>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as NcrStatus | "")}
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
          >
            <option value="">All</option>
            <option value="OPEN">Open</option>
            <option value="IN_REVIEW">In Review</option>
            <option value="CLOSED">Closed</option>
            <option value="REJECTED">Rejected</option>
          </select>
        </div>
      </div>

      {pageError && (
        <div className="mb-4 rounded-md border border-danger/20 bg-danger/10 px-4 py-2 text-sm text-danger">
          {pageError}
        </div>
      )}

      {isLoading ? (
        <div className="text-sm text-text-muted">Loading…</div>
      ) : rows.length === 0 ? (
        <EmptyState
          title="No NCRs yet"
          description={
            canCreate
              ? "Raise an NCR to log a non-conformance against this project."
              : "NCRs raised on this project will appear here."
          }
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface-hover text-left text-xs font-semibold uppercase tracking-wide text-text-secondary">
              <tr>
                <th className="px-4 py-2">NCR No</th>
                <th className="px-4 py-2">Title</th>
                <th className="px-4 py-2">Category</th>
                <th className="px-4 py-2">Severity</th>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2">Raised At</th>
                <th className="px-4 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  key={row.id}
                  className="border-t border-border hover:bg-surface-hover/50"
                >
                  <td className="px-4 py-2 font-medium text-text-primary">
                    <button
                      type="button"
                      className="text-accent hover:underline disabled:text-text-primary disabled:no-underline"
                      onClick={() => openEdit(row)}
                      disabled={row.status === "CLOSED" || !canUpdate}
                    >
                      {row.ncrNo}
                    </button>
                  </td>
                  <td className="px-4 py-2 text-text-primary">{row.title}</td>
                  <td className="px-4 py-2 text-text-secondary">{row.category}</td>
                  <td className="px-4 py-2">
                    <StatusBadge status={row.severity} />
                  </td>
                  <td className="px-4 py-2">
                    <StatusBadge status={row.status} />
                  </td>
                  <td className="px-4 py-2 text-text-secondary">
                    {row.raisedAt ? new Date(row.raisedAt).toLocaleString() : "—"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {row.status !== "CLOSED" && row.status !== "REJECTED" && canApprove && (
                      <span className="flex justify-end gap-2">
                        <button
                          onClick={() => openClose(row)}
                          className="rounded-md bg-success/10 px-3 py-1 text-xs font-medium text-success hover:bg-success/20"
                        >
                          Close
                        </button>
                        <button
                          onClick={() => rejectMutation.mutate(row.id)}
                          disabled={rejectMutation.isPending}
                          className="rounded-md bg-danger/10 px-3 py-1 text-xs font-medium text-danger hover:bg-danger/20"
                        >
                          Reject
                        </button>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title={editing ? `Edit NCR — ${editing.ncrNo}` : "Raise NCR"}
        widthClass="max-w-2xl"
      >
        <NcrForm
          key={editing?.id ?? "new"}
          projectId={projectId}
          editing={editing}
          onSaved={() => {
            invalidate();
            setDrawerOpen(false);
          }}
          onError={setPageError}
        />
      </Drawer>

      <Drawer
        open={closeDrawerOpen}
        onClose={() => setCloseDrawerOpen(false)}
        title={closingNcr ? `Close NCR — ${closingNcr.ncrNo}` : "Close NCR"}
        widthClass="max-w-xl"
      >
        {closingNcr && (
          <CloseNcrForm
            key={closingNcr.id}
            projectId={projectId}
            ncr={closingNcr}
            onSaved={() => {
              invalidate();
              setCloseDrawerOpen(false);
            }}
            onError={setPageError}
          />
        )}
      </Drawer>
    </>
  );
}

// ─── Create / Edit form ──────────────────────────────────────────────────────

interface NcrFormProps {
  projectId: string;
  editing: NcrResponse | null;
  onSaved: () => void;
  onError: (msg: string) => void;
}

function NcrForm({ projectId, editing, onSaved, onError }: NcrFormProps) {
  const [title, setTitle] = useState(editing?.title ?? "");
  const [description, setDescription] = useState(editing?.description ?? "");
  const [category, setCategory] = useState<NcrCategory>(editing?.category ?? "QUALITY");
  const [severity, setSeverity] = useState<NcrSeverity>(editing?.severity ?? "MEDIUM");
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim()) return;
    setSaving(true);
    try {
      const payload: CreateNcrRequest = {
        title,
        description: description || null,
        category,
        severity,
      };
      if (editing) {
        await ncrApi.update(projectId, editing.id, payload);
      } else {
        await ncrApi.create(projectId, payload);
      }
      onSaved();
    } catch (err) {
      onError(getErrorMessage(err, "Failed to save NCR"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4 p-5">
      <div>
        <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
          Title <span className="text-danger">*</span>
        </label>
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          required
          className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
        />
      </div>
      <div>
        <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
          Description
        </label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={4}
          className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
        />
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
            Category
          </label>
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value as NcrCategory)}
            className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
          >
            <option value="QUALITY">Quality</option>
            <option value="SAFETY">Safety</option>
            <option value="MATERIAL">Material</option>
            <option value="WORKMANSHIP">Workmanship</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
        <div>
          <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
            Severity
          </label>
          <select
            value={severity}
            onChange={(e) => setSeverity(e.target.value as NcrSeverity)}
            className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
          >
            <option value="LOW">Low</option>
            <option value="MEDIUM">Medium</option>
            <option value="HIGH">High</option>
            <option value="CRITICAL">Critical</option>
          </select>
        </div>
      </div>
      <div className="flex justify-end gap-2 border-t border-border pt-4">
        <button
          type="button"
          onClick={() => onSaved()}
          className="rounded-md border border-border bg-surface px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-hover"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={!title.trim() || saving}
          className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
        >
          {saving ? "Saving…" : editing ? "Save" : "Raise NCR"}
        </button>
      </div>
    </form>
  );
}

// ─── Closure form ────────────────────────────────────────────────────────────

interface CloseFormProps {
  projectId: string;
  ncr: NcrResponse;
  onSaved: () => void;
  onError: (msg: string) => void;
}

function CloseNcrForm({ projectId, ncr, onSaved, onError }: CloseFormProps) {
  const [rootCause, setRootCause] = useState(ncr.rootCause ?? "");
  const [correctiveAction, setCorrectiveAction] = useState(ncr.correctiveAction ?? "");
  const [saving, setSaving] = useState(false);

  const valid = rootCause.trim() && correctiveAction.trim();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    setSaving(true);
    try {
      await ncrApi.approveClosure(projectId, ncr.id, { rootCause, correctiveAction });
      onSaved();
    } catch (err) {
      onError(getErrorMessage(err, "Failed to close NCR"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4 p-5">
      <div>
        <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
          Root Cause <span className="text-danger">*</span>
        </label>
        <textarea
          value={rootCause}
          onChange={(e) => setRootCause(e.target.value)}
          rows={3}
          required
          className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
        />
      </div>
      <div>
        <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
          Corrective Action <span className="text-danger">*</span>
        </label>
        <textarea
          value={correctiveAction}
          onChange={(e) => setCorrectiveAction(e.target.value)}
          rows={3}
          required
          className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
        />
      </div>
      <div className="flex justify-end gap-2 border-t border-border pt-4">
        <button
          type="button"
          onClick={() => onSaved()}
          className="rounded-md border border-border bg-surface px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface-hover"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={!valid || saving}
          className="rounded-md bg-success px-4 py-2 text-sm font-semibold text-white hover:bg-success/80 disabled:opacity-50"
        >
          {saving ? "Closing…" : "Approve Closure"}
        </button>
      </div>
    </form>
  );
}
