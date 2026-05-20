"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";

import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  expenseCategoryApi,
  type ExpenseCategory,
} from "@/lib/api/expenseCategoryApi";
import {
  dbsExpenseApi,
  type DbsManualExpenseRequest,
} from "@/lib/api/dbsExpenseApi";
import { activityApi } from "@/lib/api/activityApi";
import { getErrorMessage } from "@/lib/utils/error";

const SECTIONS: Array<{ letter: string; label: string }> = [
  { letter: "A", label: "A · Manpower" },
  { letter: "B", label: "B · Admin / Catering" },
  { letter: "C", label: "C · Machinery" },
  { letter: "D", label: "D · Fuel" },
  { letter: "E", label: "E · Material" },
  { letter: "F", label: "F · Sub-Contractor" },
  { letter: "G", label: "G · Other Expenses" },
];

export interface AddExpenseDialogProps {
  projectId: string;
  defaultDate: string;
  /** Pre-fill section letter (A–G) when the user clicked a section quick-add. */
  defaultSection?: string | null;
  /** Pre-fill the activity if the user opened the dialog from an activity context. */
  defaultActivityId?: string | null;
  open: boolean;
  onClose: () => void;
  /** Called after a successful save so the parent can refetch DBS totals. */
  onSaved?: () => void;
}

interface FormState {
  section: string;
  categoryId: string;
  activityId: string;
  reportDate: string;
  description: string;
  amount: string;
  vendorName: string;
  receiptNo: string;
  taxRatePct: string;
  taxAmount: string;
  notes: string;
  spreadDays: string;
}

const blank = (defaultDate: string, defaultSection?: string | null): FormState => ({
  section: defaultSection ?? "G",
  categoryId: "",
  activityId: "",
  reportDate: defaultDate,
  description: "",
  amount: "",
  vendorName: "",
  receiptNo: "",
  taxRatePct: "",
  taxAmount: "",
  notes: "",
  spreadDays: "1",
});

export function AddExpenseDialog({
  projectId,
  defaultDate,
  defaultSection,
  defaultActivityId,
  open,
  onClose,
  onSaved,
}: AddExpenseDialogProps) {
  const qc = useQueryClient();
  // Reset form whenever the dialog re-opens by re-mounting via a key inside the parent.
  // (The parent toggles `open` with addExpenseSection state — when null, this component
  // unmounts and on next open it re-mounts with fresh state.)
  const [form, setForm] = useState<FormState>(() => ({
    ...blank(defaultDate, defaultSection),
    activityId: defaultActivityId ?? "",
  }));

  const categoriesQuery = useQuery({
    queryKey: ["expense-categories", { activeOnly: true }],
    queryFn: () => expenseCategoryApi.list(true),
    enabled: open,
  });

  const activitiesQuery = useQuery({
    queryKey: ["project-activities-for-expense", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 500),
    enabled: open,
  });

  const filteredCategories = useMemo<ExpenseCategory[]>(() => {
    const all = categoriesQuery.data?.data ?? [];
    return all.filter((c) => c.dbsSection === form.section);
  }, [categoriesQuery.data, form.section]);

  // Effective categoryId — empty if the chosen category no longer matches the section
  // (e.g. user changed Section after picking Category). Computed at render time instead
  // of via useEffect+setState to avoid cascading renders flagged by React Compiler.
  const effectiveCategoryId =
    form.categoryId && filteredCategories.find((c) => c.id === form.categoryId)
      ? form.categoryId
      : "";

  const createMutation = useMutation({
    mutationFn: (req: DbsManualExpenseRequest) =>
      dbsExpenseApi.create(projectId, req),
    onSuccess: (resp) => {
      toast.success(
        resp.data?.approvalStatus === "PENDING"
          ? "Expense saved — pending approval"
          : "Expense added",
      );
      qc.invalidateQueries({ queryKey: ["dbs"] });
      qc.invalidateQueries({ queryKey: ["dbs-expenses"] });
      onSaved?.();
      onClose();
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const handleSubmit = async () => {
    if (!effectiveCategoryId) {
      toast.error("Pick a category");
      return;
    }
    const amount = Number(form.amount);
    if (!Number.isFinite(amount) || amount === 0) {
      toast.error("Enter a non-zero amount");
      return;
    }
    if (!form.description.trim()) {
      toast.error("Description is required");
      return;
    }

    const spread = Math.max(1, Math.min(31, Number(form.spreadDays) || 1));
    const perDay = Math.round((amount / spread) * 100) / 100;
    const base: DbsManualExpenseRequest = {
      reportDate: form.reportDate,
      activityId: form.activityId || null,
      categoryId: effectiveCategoryId,
      description: form.description.trim(),
      amount: perDay,
      currency: "INR",
      vendorName: form.vendorName.trim() || null,
      receiptNo: form.receiptNo.trim() || null,
      taxRatePct: form.taxRatePct ? Number(form.taxRatePct) : null,
      taxAmount: form.taxAmount ? Number(form.taxAmount) : null,
      notes: form.notes.trim() || null,
    };
    try {
      const start = new Date(form.reportDate);
      for (let i = 0; i < spread; i++) {
        const d = new Date(start);
        d.setDate(start.getDate() + i);
        const iso = d.toISOString().slice(0, 10);
        const desc =
          spread > 1 ? `${base.description} (${i + 1}/${spread})` : base.description;
        // eslint-disable-next-line no-await-in-loop
        await createMutation.mutateAsync({ ...base, reportDate: iso, description: desc });
      }
    } catch {
      // toast already shown by onError
    }
  };

  const selectedCategory = filteredCategories.find((c) => c.id === effectiveCategoryId);
  const threshold = 10000; // mirror backend default for the UI hint

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Add Expense</DialogTitle>
        </DialogHeader>
        <DialogBody className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                DBS Section *
              </label>
              <select
                value={form.section}
                onChange={(e) => setForm({ ...form, section: e.target.value })}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
              >
                {SECTIONS.map((s) => (
                  <option key={s.letter} value={s.letter}>
                    {s.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Category *
              </label>
              <select
                value={effectiveCategoryId}
                onChange={(e) =>
                  setForm({ ...form, categoryId: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
              >
                <option value="">— pick a category —</option>
                {filteredCategories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.code} — {c.name}
                  </option>
                ))}
              </select>
              {filteredCategories.length === 0 ? (
                <p className="mt-1 text-xs text-amber-500">
                  No active categories for section {form.section}. Add one in
                  Admin → Expense Categories.
                </p>
              ) : null}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Activity (optional)
              </label>
              <select
                value={form.activityId}
                onChange={(e) =>
                  setForm({ ...form, activityId: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
              >
                <option value="">— project overhead (no activity) —</option>
                {(activitiesQuery.data?.data?.content ?? []).map(
                  (a: { id: string; code: string; name: string }) => (
                    <option key={a.id} value={a.id}>
                      {a.code} — {a.name}
                    </option>
                  ),
                )}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Date *
              </label>
              <input
                type="date"
                value={form.reportDate}
                onChange={(e) =>
                  setForm({ ...form, reportDate: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
              />
            </div>
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
              Description *
            </label>
            <input
              type="text"
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder="e.g. Diesel coupons issued to drivers"
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
              maxLength={500}
            />
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Amount (₹) *
              </label>
              <input
                type="number"
                step="0.01"
                value={form.amount}
                onChange={(e) => setForm({ ...form, amount: e.target.value })}
                placeholder="0.00"
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
              />
              {form.amount && Number(form.amount) >= threshold ? (
                <p className="mt-1 text-xs text-amber-500">
                  ≥ ₹{threshold.toLocaleString()} → will land as PENDING until a PM approves.
                </p>
              ) : null}
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Vendor (optional)
              </label>
              <input
                type="text"
                value={form.vendorName}
                onChange={(e) =>
                  setForm({ ...form, vendorName: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Receipt # (optional)
              </label>
              <input
                type="text"
                value={form.receiptNo}
                onChange={(e) =>
                  setForm({ ...form, receiptNo: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
              />
            </div>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Tax rate (%)
              </label>
              <input
                type="number"
                step="0.01"
                value={form.taxRatePct}
                onChange={(e) =>
                  setForm({ ...form, taxRatePct: e.target.value })
                }
                placeholder={
                  selectedCategory?.defaultTaxRatePct
                    ? String(selectedCategory.defaultTaxRatePct)
                    : "0"
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Tax amount
              </label>
              <input
                type="number"
                step="0.01"
                value={form.taxAmount}
                onChange={(e) =>
                  setForm({ ...form, taxAmount: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Spread over N days
              </label>
              <input
                type="number"
                min="1"
                max="31"
                value={form.spreadDays}
                onChange={(e) =>
                  setForm({ ...form, spreadDays: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
              />
              {Number(form.spreadDays) > 1 && form.amount ? (
                <p className="mt-1 text-xs text-text-muted">
                  ≈ ₹{(Number(form.amount) / Number(form.spreadDays)).toFixed(2)}/day
                </p>
              ) : null}
            </div>
          </div>

          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
              Notes (optional)
            </label>
            <textarea
              value={form.notes}
              onChange={(e) => setForm({ ...form, notes: e.target.value })}
              rows={2}
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
              maxLength={1000}
            />
          </div>
        </DialogBody>
        <DialogFooter>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-border bg-surface px-4 py-2 text-sm"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={createMutation.isPending}
            className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground disabled:opacity-50"
          >
            {createMutation.isPending ? "Saving…" : "Save expense"}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
