"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { Drawer } from "@/components/common/Drawer";
import { EmptyState } from "@/components/common/EmptyState";
import { PageHeader } from "@/components/common/PageHeader";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useAuthStore } from "@/lib/state/store";
import { getErrorMessage } from "@/lib/utils/error";
import {
  materialIndentApi,
  type CreateMaterialIndentRequest,
  type IndentStatus,
  type MaterialIndentItemDto,
  type MaterialIndentResponse,
} from "@/lib/api/materialIndentApi";

const todayIso = () => new Date().toISOString().split("T")[0];

type DraftItem = MaterialIndentItemDto & { _key: string };
const newKey = () => `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

const emptyItem = (): DraftItem => ({
  _key: newKey(),
  materialName: "",
  quantity: "",
  uom: "",
  remarks: "",
});

export default function MaterialIndentsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((s) => s.hasPermission);

  const canCreate = hasPermission("PROCUREMENT_REQUEST.CREATE");
  const canUpdate = hasPermission("PROCUREMENT_REQUEST.UPDATE");
  const canApprove = hasPermission("PROCUREMENT_REQUEST.APPROVE");

  const [statusFilter, setStatusFilter] = useState<IndentStatus | "">("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<MaterialIndentResponse | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["material-indents", projectId, statusFilter],
    queryFn: () => materialIndentApi.list(projectId, statusFilter || undefined),
    enabled: !!projectId,
  });
  const rows: MaterialIndentResponse[] = data?.data ?? [];

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ["material-indents", projectId] });

  const submitMutation = useMutation({
    mutationFn: (id: string) => materialIndentApi.submit(projectId, id),
    onSuccess: invalidate,
    onError: (e) => setPageError(getErrorMessage(e, "Failed to submit indent")),
  });
  const approveMutation = useMutation({
    mutationFn: (id: string) => materialIndentApi.approve(projectId, id, {}),
    onSuccess: invalidate,
    onError: (e) => setPageError(getErrorMessage(e, "Failed to approve indent")),
  });
  const rejectMutation = useMutation({
    mutationFn: (id: string) => materialIndentApi.reject(projectId, id, {}),
    onSuccess: invalidate,
    onError: (e) => setPageError(getErrorMessage(e, "Failed to reject indent")),
  });

  const openNew = () => {
    setEditing(null);
    setPageError(null);
    setDrawerOpen(true);
  };

  const openEdit = (row: MaterialIndentResponse) => {
    if (row.status !== "DRAFT") return;
    setEditing(row);
    setPageError(null);
    setDrawerOpen(true);
  };

  return (
    <div className="p-6">
      <PageHeader
        title="Material Indents"
        description="Site requests for materials to be procured or issued from stores."
        actions={
          canCreate ? (
            <button
              onClick={openNew}
              className="inline-flex items-center gap-1.5 rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:bg-accent-hover"
            >
              <Plus className="h-4 w-4" /> New Indent
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
            onChange={(e) => setStatusFilter(e.target.value as IndentStatus | "")}
            className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
          >
            <option value="">All</option>
            <option value="DRAFT">Draft</option>
            <option value="SUBMITTED">Submitted</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="PARTIALLY_FULFILLED">Partially Fulfilled</option>
            <option value="FULFILLED">Fulfilled</option>
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
          title="No material indents yet"
          description={
            canCreate
              ? "Create your first indent to record material requests for this project."
              : "Material indents will appear here once raised."
          }
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-surface-hover text-left text-xs font-semibold uppercase tracking-wide text-text-secondary">
              <tr>
                <th className="px-4 py-2">Indent No</th>
                <th className="px-4 py-2">Required By</th>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2">Items</th>
                <th className="px-4 py-2">Requested By</th>
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
                      disabled={row.status !== "DRAFT" || !canUpdate}
                      title={row.status === "DRAFT" ? "Edit draft" : "Read-only after submission"}
                    >
                      {row.indentNo}
                    </button>
                  </td>
                  <td className="px-4 py-2 text-text-secondary">{row.requiredBy}</td>
                  <td className="px-4 py-2">
                    <StatusBadge status={row.status} />
                  </td>
                  <td className="px-4 py-2 text-text-secondary">{row.itemsCount}</td>
                  <td className="px-4 py-2 text-text-secondary">
                    {row.requestedBy ? row.requestedBy.substring(0, 8) : "—"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    {row.status === "DRAFT" && canUpdate && (
                      <button
                        onClick={() => submitMutation.mutate(row.id)}
                        disabled={submitMutation.isPending}
                        className="rounded-md border border-border bg-surface px-3 py-1 text-xs font-medium text-text-primary hover:bg-surface-hover"
                      >
                        Submit
                      </button>
                    )}
                    {row.status === "SUBMITTED" && canApprove && (
                      <span className="flex justify-end gap-2">
                        <button
                          onClick={() => approveMutation.mutate(row.id)}
                          disabled={approveMutation.isPending}
                          className="rounded-md bg-success/10 px-3 py-1 text-xs font-medium text-success hover:bg-success/20"
                        >
                          Approve
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
        title={editing ? `Edit Indent — ${editing.indentNo}` : "New Material Indent"}
        widthClass="max-w-3xl"
      >
        <IndentForm
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
    </div>
  );
}

// ─── Drawer form ──────────────────────────────────────────────────────────────

interface IndentFormProps {
  projectId: string;
  editing: MaterialIndentResponse | null;
  onSaved: () => void;
  onError: (msg: string) => void;
}

function IndentForm({ projectId, editing, onSaved, onError }: IndentFormProps) {
  const [requiredBy, setRequiredBy] = useState(editing?.requiredBy ?? todayIso());
  const [notes, setNotes] = useState(editing?.notes ?? "");
  const [items, setItems] = useState<DraftItem[]>(() => {
    if (editing?.items?.length) {
      return editing.items.map((i) => ({
        _key: newKey(),
        materialName: i.materialName,
        quantity: i.quantity,
        uom: i.uom,
        remarks: i.remarks ?? "",
      }));
    }
    return [emptyItem()];
  });
  const [saving, setSaving] = useState(false);

  const valid = useMemo(() => {
    if (!requiredBy) return false;
    if (items.length === 0) return false;
    return items.every(
      (i) => i.materialName.trim() && i.uom.trim() && String(i.quantity).trim() !== ""
    );
  }, [requiredBy, items]);

  const updateItem = (idx: number, patch: Partial<DraftItem>) => {
    setItems((prev) => prev.map((it, i) => (i === idx ? { ...it, ...patch } : it)));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    setSaving(true);
    try {
      const payload: CreateMaterialIndentRequest = {
        requiredBy,
        notes: notes || null,
        items: items.map(({ _key, ...rest }) => ({
          ...rest,
          quantity: Number(rest.quantity),
        })),
      };
      if (editing) {
        await materialIndentApi.update(projectId, editing.id, payload);
      } else {
        await materialIndentApi.create(projectId, payload);
      }
      onSaved();
    } catch (err) {
      onError(getErrorMessage(err, "Failed to save indent"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-5 p-5">
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
            Required By <span className="text-danger">*</span>
          </label>
          <input
            type="date"
            value={requiredBy}
            onChange={(e) => setRequiredBy(e.target.value)}
            required
            className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
          />
        </div>
      </div>

      <div>
        <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-secondary">
          Notes
        </label>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
        />
      </div>

      <div>
        <div className="mb-2 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-text-primary">Items</h3>
          <button
            type="button"
            onClick={() => setItems((prev) => [...prev, emptyItem()])}
            className="inline-flex items-center gap-1 rounded-md border border-border bg-surface px-3 py-1.5 text-xs font-medium text-text-primary hover:bg-surface-hover"
          >
            <Plus className="h-3.5 w-3.5" /> Add Row
          </button>
        </div>
        <div className="space-y-2">
          {items.map((it, idx) => (
            <div
              key={it._key}
              className="grid grid-cols-[2fr_1fr_1fr_2fr_auto] items-end gap-2 rounded-md border border-border bg-surface/50 p-2"
            >
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-text-muted">
                  Material
                </label>
                <input
                  type="text"
                  value={it.materialName}
                  onChange={(e) => updateItem(idx, { materialName: e.target.value })}
                  required
                  className="w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text-primary"
                />
              </div>
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-text-muted">
                  Quantity
                </label>
                <input
                  type="number"
                  step="0.0001"
                  value={it.quantity}
                  onChange={(e) => updateItem(idx, { quantity: e.target.value })}
                  required
                  className="w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text-primary"
                />
              </div>
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-text-muted">
                  UoM
                </label>
                <input
                  type="text"
                  value={it.uom}
                  onChange={(e) => updateItem(idx, { uom: e.target.value })}
                  required
                  className="w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text-primary"
                />
              </div>
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-text-muted">
                  Remarks
                </label>
                <input
                  type="text"
                  value={it.remarks ?? ""}
                  onChange={(e) => updateItem(idx, { remarks: e.target.value })}
                  className="w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text-primary"
                />
              </div>
              <button
                type="button"
                onClick={() => setItems((prev) => prev.filter((_, i) => i !== idx))}
                disabled={items.length === 1}
                className="rounded-md p-1.5 text-text-muted hover:bg-danger/10 hover:text-danger disabled:opacity-30"
                title="Remove row"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          ))}
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
          disabled={!valid || saving}
          className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
        >
          {saving ? "Saving…" : editing ? "Save Changes" : "Create Draft"}
        </button>
      </div>
    </form>
  );
}
