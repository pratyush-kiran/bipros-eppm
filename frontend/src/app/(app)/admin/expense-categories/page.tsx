"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Search, Trash2 } from "lucide-react";
import toast from "react-hot-toast";

import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { Badge } from "@/components/ui/badge";
import {
  expenseCategoryApi,
  type ExpenseCategory,
  type ExpenseCategoryRequest,
} from "@/lib/api/expenseCategoryApi";
import { getErrorMessage } from "@/lib/utils/error";

const DBS_SECTIONS: Array<{ letter: string; label: string }> = [
  { letter: "A", label: "A · Manpower" },
  { letter: "B", label: "B · Admin / Catering" },
  { letter: "C", label: "C · Machinery" },
  { letter: "D", label: "D · Fuel" },
  { letter: "E", label: "E · Material" },
  { letter: "F", label: "F · Sub-Contractor" },
  { letter: "G", label: "G · Other Expenses" },
];

const sectionBadgeVariant = (
  section: string,
): import("@/components/ui/badge").BadgeVariant => {
  switch (section) {
    case "A":
      return "info";
    case "B":
      return "info";
    case "C":
      return "danger";
    case "D":
      return "warning";
    case "E":
      return "success";
    case "F":
      return "neutral";
    case "G":
    default:
      return "gold";
  }
};

interface CategoryForm {
  code: string;
  name: string;
  description: string;
  dbsSection: string;
  defaultTaxable: boolean;
  defaultTaxRatePct: string;
  sortOrder: string;
  active: boolean;
}

const blankForm = (): CategoryForm => ({
  code: "",
  name: "",
  description: "",
  dbsSection: "G",
  defaultTaxable: false,
  defaultTaxRatePct: "",
  sortOrder: "",
  active: true,
});

const formFromRow = (c: ExpenseCategory): CategoryForm => ({
  code: c.code,
  name: c.name,
  description: c.description ?? "",
  dbsSection: c.dbsSection,
  defaultTaxable: c.defaultTaxable,
  defaultTaxRatePct: c.defaultTaxRatePct == null ? "" : String(c.defaultTaxRatePct),
  sortOrder: c.sortOrder == null ? "" : String(c.sortOrder),
  active: c.active,
});

const toRequest = (f: CategoryForm): ExpenseCategoryRequest => ({
  code: f.code.trim().toUpperCase(),
  name: f.name.trim(),
  description: f.description.trim() || null,
  dbsSection: f.dbsSection,
  defaultTaxable: f.defaultTaxable,
  defaultTaxRatePct:
    f.defaultTaxRatePct.trim() === "" ? null : Number(f.defaultTaxRatePct),
  sortOrder: f.sortOrder.trim() === "" ? null : Number(f.sortOrder),
  active: f.active,
});

export default function ExpenseCategoriesPage() {
  const qc = useQueryClient();
  const [search, setSearch] = useState("");
  const [sectionFilter, setSectionFilter] = useState<string>("ALL");
  const [activeOnly, setActiveOnly] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<ExpenseCategory | null>(null);
  const [form, setForm] = useState<CategoryForm>(blankForm);
  const [confirmDelete, setConfirmDelete] = useState<ExpenseCategory | null>(null);

  const listQuery = useQuery({
    queryKey: ["expense-categories", { activeOnly }],
    queryFn: () => expenseCategoryApi.list(activeOnly),
  });

  const rows = useMemo(() => listQuery.data?.data ?? [], [listQuery.data]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return rows.filter((r) => {
      if (sectionFilter !== "ALL" && r.dbsSection !== sectionFilter) return false;
      if (!q) return true;
      return (
        r.code.toLowerCase().includes(q) ||
        r.name.toLowerCase().includes(q) ||
        (r.description ?? "").toLowerCase().includes(q)
      );
    });
  }, [rows, search, sectionFilter]);

  const openCreate = () => {
    setEditing(null);
    setForm(blankForm());
    setDialogOpen(true);
  };

  const openEdit = (c: ExpenseCategory) => {
    setEditing(c);
    setForm(formFromRow(c));
    setDialogOpen(true);
  };

  const createMutation = useMutation({
    mutationFn: (req: ExpenseCategoryRequest) => expenseCategoryApi.create(req),
    onSuccess: () => {
      toast.success("Expense category created");
      qc.invalidateQueries({ queryKey: ["expense-categories"] });
      setDialogOpen(false);
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const updateMutation = useMutation({
    mutationFn: (args: { id: string; req: ExpenseCategoryRequest }) =>
      expenseCategoryApi.update(args.id, args.req),
    onSuccess: () => {
      toast.success("Expense category updated");
      qc.invalidateQueries({ queryKey: ["expense-categories"] });
      setDialogOpen(false);
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => expenseCategoryApi.remove(id),
    onSuccess: () => {
      toast.success("Expense category deleted");
      qc.invalidateQueries({ queryKey: ["expense-categories"] });
      setConfirmDelete(null);
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const handleSubmit = () => {
    const req = toRequest(form);
    if (editing) {
      updateMutation.mutate({ id: editing.id, req });
    } else {
      createMutation.mutate(req);
    }
  };

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Expense Categories</h1>
          <p className="mt-1 max-w-2xl text-sm text-text-muted">
            Master data driving the DBS ad-hoc expense capture. Each category routes to
            a DBS section letter (A–G) so manually-entered expenses bucket into the
            right section at read time.
          </p>
        </div>
        <button
          type="button"
          onClick={openCreate}
          className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground hover:bg-accent/90"
        >
          <Plus size={16} /> Add Category
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-surface/50 p-3">
        <div className="relative flex-1 min-w-[240px]">
          <Search
            size={14}
            className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
          />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by code, name, description..."
            className="w-full rounded-md border border-border bg-surface px-9 py-2 text-sm"
          />
        </div>
        <select
          value={sectionFilter}
          onChange={(e) => setSectionFilter(e.target.value)}
          className="rounded-md border border-border bg-surface px-3 py-2 text-sm"
        >
          <option value="ALL">All sections</option>
          {DBS_SECTIONS.map((s) => (
            <option key={s.letter} value={s.letter}>
              {s.label}
            </option>
          ))}
        </select>
        <label className="flex items-center gap-2 text-sm text-text-secondary">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(e) => setActiveOnly(e.target.checked)}
          />
          Active only
        </label>
      </div>

      <div className="overflow-x-auto rounded-lg border border-border bg-surface/50 shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
            <tr>
              <th className="px-4 py-2">Code</th>
              <th className="px-4 py-2">Name</th>
              <th className="px-4 py-2">DBS Section</th>
              <th className="px-4 py-2">Default tax</th>
              <th className="px-4 py-2 text-right">Sort</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {listQuery.isLoading ? (
              <tr>
                <td colSpan={7} className="px-4 py-6 text-center text-text-muted">
                  Loading categories…
                </td>
              </tr>
            ) : filtered.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-6 text-center text-text-muted">
                  No expense categories match the current filter.
                </td>
              </tr>
            ) : (
              filtered.map((c) => (
                <tr key={c.id} className={c.active ? "" : "opacity-60"}>
                  <td className="px-4 py-2 font-mono text-text-primary">
                    {c.code}
                  </td>
                  <td className="px-4 py-2">
                    <div className="text-text-primary">{c.name}</div>
                    {c.description ? (
                      <div className="text-xs text-text-muted">{c.description}</div>
                    ) : null}
                  </td>
                  <td className="px-4 py-2">
                    <Badge variant={sectionBadgeVariant(c.dbsSection)}>
                      {DBS_SECTIONS.find((s) => s.letter === c.dbsSection)?.label ??
                        c.dbsSection}
                    </Badge>
                  </td>
                  <td className="px-4 py-2 font-mono">
                    {c.defaultTaxable
                      ? `${c.defaultTaxRatePct ?? 0}%`
                      : "—"}
                  </td>
                  <td className="px-4 py-2 text-right font-mono">{c.sortOrder}</td>
                  <td className="px-4 py-2">
                    {c.active ? (
                      <Badge variant="success">Active</Badge>
                    ) : (
                      <Badge variant="neutral">Inactive</Badge>
                    )}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      type="button"
                      aria-label="Edit category"
                      onClick={() => openEdit(c)}
                      className="mr-2 inline-flex h-7 w-7 items-center justify-center rounded-md text-text-secondary hover:bg-surface-hover hover:text-text-primary"
                    >
                      <Pencil size={14} />
                    </button>
                    <button
                      type="button"
                      aria-label="Delete category"
                      onClick={() => setConfirmDelete(c)}
                      className="inline-flex h-7 w-7 items-center justify-center rounded-md text-rose-500 hover:bg-rose-500/10"
                    >
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <Dialog open={dialogOpen} onOpenChange={(o) => !o && setDialogOpen(false)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {editing ? "Edit Expense Category" : "New Expense Category"}
            </DialogTitle>
          </DialogHeader>
          <DialogBody className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                  Code *
                </label>
                <input
                  type="text"
                  value={form.code}
                  onChange={(e) => setForm({ ...form, code: e.target.value })}
                  placeholder="TRANSPORT"
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
                  maxLength={50}
                />
                <p className="mt-1 text-xs text-text-muted">
                  Uppercase letters, digits, underscores.
                </p>
              </div>
              <div>
                <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                  Name *
                </label>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="Transport / Vehicle hire"
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
                  maxLength={100}
                />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Description
              </label>
              <input
                type="text"
                value={form.description}
                onChange={(e) =>
                  setForm({ ...form, description: e.target.value })
                }
                placeholder="What kind of expenses fall in this bucket"
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
                maxLength={500}
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                  DBS Section *
                </label>
                <select
                  value={form.dbsSection}
                  onChange={(e) =>
                    setForm({ ...form, dbsSection: e.target.value })
                  }
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
                >
                  {DBS_SECTIONS.map((s) => (
                    <option key={s.letter} value={s.letter}>
                      {s.label}
                    </option>
                  ))}
                </select>
                <p className="mt-1 text-xs text-text-muted">
                  Expenses in this category bucket under this DBS section.
                </p>
              </div>
              <div>
                <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                  Sort order
                </label>
                <input
                  type="number"
                  value={form.sortOrder}
                  onChange={(e) =>
                    setForm({ ...form, sortOrder: e.target.value })
                  }
                  placeholder="Display order in pickers"
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
                />
              </div>
            </div>
            <div className="rounded-md border border-border bg-surface/50 p-3">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={form.defaultTaxable}
                  onChange={(e) =>
                    setForm({ ...form, defaultTaxable: e.target.checked })
                  }
                />
                Taxable by default
              </label>
              {form.defaultTaxable ? (
                <div className="mt-2">
                  <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                    Default tax rate (%)
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    max="100"
                    value={form.defaultTaxRatePct}
                    onChange={(e) =>
                      setForm({ ...form, defaultTaxRatePct: e.target.value })
                    }
                    placeholder="18.00"
                    className="w-32 rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
                  />
                </div>
              ) : null}
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(e) => setForm({ ...form, active: e.target.checked })}
              />
              Active
            </label>
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setDialogOpen(false)}
              className="rounded-md border border-border bg-surface px-4 py-2 text-sm"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={createMutation.isPending || updateMutation.isPending}
              className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground disabled:opacity-50"
            >
              {editing ? "Save changes" : "Create"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!confirmDelete}
        title="Delete expense category?"
        message={
          confirmDelete
            ? `Delete '${confirmDelete.code}' (${confirmDelete.name})? Categories in use by historical expense entries can't be deleted — consider toggling Active off instead.`
            : ""
        }
        confirmLabel={deleteMutation.isPending ? "Deleting…" : "Delete"}
        cancelLabel="Cancel"
        variant="danger"
        onConfirm={() =>
          confirmDelete && deleteMutation.mutate(confirmDelete.id)
        }
        onCancel={() => setConfirmDelete(null)}
      />
    </div>
  );
}
