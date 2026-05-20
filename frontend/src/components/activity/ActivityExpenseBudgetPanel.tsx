"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import toast from "react-hot-toast";

import {
  activityExpenseBudgetApi,
  type ActivityExpenseBudget,
  type ActivityExpenseBudgetRequest,
} from "@/lib/api/activityExpenseBudgetApi";
import { expenseCategoryApi } from "@/lib/api/expenseCategoryApi";
import { dbsExpenseApi } from "@/lib/api/dbsExpenseApi";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { formatCurrency } from "@/lib/utils/format";
import { getErrorMessage } from "@/lib/utils/error";

export interface ActivityExpenseBudgetPanelProps {
  projectId: string;
  activityId: string;
  /** When the activity is LOCKED, budget edits are disabled (matches Resource Plan). */
  locked: boolean;
  currency?: string | null;
}

interface BudgetForm {
  budgetId: string | null;
  categoryId: string;
  plannedAmount: string;
  notes: string;
}

const blank = (): BudgetForm => ({
  budgetId: null,
  categoryId: "",
  plannedAmount: "",
  notes: "",
});

export function ActivityExpenseBudgetPanel({
  projectId,
  activityId,
  locked,
  currency,
}: ActivityExpenseBudgetPanelProps) {
  const qc = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<BudgetForm>(blank);

  const budgetsQuery = useQuery({
    queryKey: ["activity-expense-budgets", projectId, activityId],
    queryFn: () => activityExpenseBudgetApi.list(projectId, activityId),
  });
  const expensesQuery = useQuery({
    queryKey: ["dbs-expenses-by-activity", projectId, activityId],
    queryFn: () => dbsExpenseApi.listForActivity(projectId, activityId),
  });
  const categoriesQuery = useQuery({
    queryKey: ["expense-categories", { activeOnly: true }],
    queryFn: () => expenseCategoryApi.list(true),
    enabled: dialogOpen,
  });

  const budgets = useMemo(
    () => budgetsQuery.data?.data ?? [],
    [budgetsQuery.data],
  );

  // Sum APPROVED expenses by category (matches what the backend rolls into activity AC).
  const actualByCategory = useMemo(() => {
    const map = new Map<string, number>();
    for (const e of expensesQuery.data?.data ?? []) {
      if (e.approvalStatus !== "APPROVED") continue;
      map.set(e.categoryId, (map.get(e.categoryId) ?? 0) + Number(e.amount));
    }
    return map;
  }, [expensesQuery.data]);

  // Categories with actuals but no budget → "Unbudgeted" rows.
  const unbudgetedCategories = useMemo(() => {
    const budgetedIds = new Set(budgets.map((b) => b.categoryId));
    const rows: { categoryId: string; categoryName: string; dbsSection: string | null; actual: number }[] = [];
    for (const e of expensesQuery.data?.data ?? []) {
      if (e.approvalStatus !== "APPROVED") continue;
      if (budgetedIds.has(e.categoryId)) continue;
      const existing = rows.find((r) => r.categoryId === e.categoryId);
      if (existing) {
        existing.actual += Number(e.amount);
      } else {
        rows.push({
          categoryId: e.categoryId,
          categoryName: e.categoryName ?? "(unknown)",
          dbsSection: e.dbsSection,
          actual: Number(e.amount),
        });
      }
    }
    return rows;
  }, [budgets, expensesQuery.data]);

  const totalPlanned = budgets.reduce((s, b) => s + Number(b.plannedAmount), 0);
  const totalActual =
    budgets.reduce((s, b) => s + (actualByCategory.get(b.categoryId) ?? 0), 0) +
    unbudgetedCategories.reduce((s, r) => s + r.actual, 0);
  const totalVariance = totalPlanned - totalActual;

  const upsertMutation = useMutation({
    mutationFn: (req: ActivityExpenseBudgetRequest) =>
      activityExpenseBudgetApi.upsert(projectId, activityId, req),
    onSuccess: () => {
      toast.success("Budget saved");
      qc.invalidateQueries({ queryKey: ["activity-expense-budgets", projectId, activityId] });
      setDialogOpen(false);
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const deleteMutation = useMutation({
    mutationFn: (b: ActivityExpenseBudget) =>
      activityExpenseBudgetApi.remove(projectId, activityId, b.id),
    onSuccess: () => {
      toast.success("Budget deleted");
      qc.invalidateQueries({ queryKey: ["activity-expense-budgets", projectId, activityId] });
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const openCreate = () => {
    setForm(blank());
    setDialogOpen(true);
  };

  const openEdit = (b: ActivityExpenseBudget) => {
    setForm({
      budgetId: b.id,
      categoryId: b.categoryId,
      plannedAmount: String(b.plannedAmount),
      notes: b.notes ?? "",
    });
    setDialogOpen(true);
  };

  const handleSubmit = () => {
    if (!form.categoryId) {
      toast.error("Pick a category");
      return;
    }
    const planned = Number(form.plannedAmount);
    if (!Number.isFinite(planned) || planned < 0) {
      toast.error("Planned amount must be ≥ 0");
      return;
    }
    upsertMutation.mutate({
      categoryId: form.categoryId,
      plannedAmount: planned,
      notes: form.notes.trim() || null,
    });
  };

  return (
    <section className="mt-4 rounded-lg border border-border bg-surface/30 p-4">
      <header className="mb-3 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold uppercase tracking-wide text-text-secondary">
            Other Expenses
          </h3>
          <p className="text-xs text-text-muted">
            Plan a budget per category and watch variance accrue from DBS manual expenses.
          </p>
        </div>
        <button
          type="button"
          onClick={openCreate}
          disabled={locked}
          className="inline-flex items-center gap-1 rounded-md border border-border bg-surface px-3 py-1.5 text-xs font-semibold disabled:cursor-not-allowed disabled:opacity-50"
          title={locked ? "Activity is locked — unlock to edit budgets" : "Add a budget line"}
        >
          <Plus size={12} /> Budget
        </button>
      </header>

      {locked ? (
        <div className="mb-3 rounded-md border border-amber-500/40 bg-amber-500/5 px-3 py-2 text-xs text-amber-700 dark:text-amber-300">
          <strong>Activity is locked</strong> — budget rows are frozen and the
          <code className="mx-1 rounded bg-amber-500/10 px-1 py-0.5">+ Budget</code>
          button is disabled. Actuals still accrue from DBS manual expenses; only the
          planned envelope can&apos;t change. Unlock the activity to add or edit budgets.
        </div>
      ) : null}

      <table className="w-full text-sm">
        <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
          <tr>
            <th className="px-3 py-2">Category</th>
            <th className="px-3 py-2 text-right">Planned</th>
            <th className="px-3 py-2 text-right">Actual</th>
            <th className="px-3 py-2 text-right">Variance</th>
            <th className="px-3 py-2 text-right">Actions</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {budgetsQuery.isLoading ? (
            <tr>
              <td colSpan={5} className="px-3 py-4 text-center text-text-muted">
                Loading…
              </td>
            </tr>
          ) : budgets.length === 0 && unbudgetedCategories.length === 0 ? (
            <tr>
              <td colSpan={5} className="px-3 py-4 text-center text-text-muted">
                No budgets or actuals yet. Add a budget, or log expenses on DBS.
              </td>
            </tr>
          ) : (
            <>
              {budgets.map((b) => {
                const actual = actualByCategory.get(b.categoryId) ?? 0;
                const planned = Number(b.plannedAmount);
                const variance = planned - actual;
                return (
                  <tr key={b.id}>
                    <td className="px-3 py-2">
                      <div className="text-text-primary">
                        {b.categoryName ?? "(category)"}
                      </div>
                      <div className="text-xs text-text-muted">
                        {b.categoryCode} · Section {b.dbsSection}
                      </div>
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatCurrency(planned, currency)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatCurrency(actual, currency)}
                    </td>
                    <td
                      className={`px-3 py-2 text-right font-mono ${
                        variance >= 0 ? "text-emerald-600" : "text-rose-600"
                      }`}
                    >
                      {variance >= 0 ? "+" : ""}
                      {formatCurrency(variance, currency)}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <button
                        type="button"
                        disabled={locked}
                        onClick={() => openEdit(b)}
                        className="mr-2 inline-flex h-6 w-6 items-center justify-center rounded text-text-secondary hover:bg-surface-hover disabled:opacity-50"
                      >
                        <Pencil size={12} />
                      </button>
                      <button
                        type="button"
                        disabled={locked}
                        onClick={() => deleteMutation.mutate(b)}
                        className="inline-flex h-6 w-6 items-center justify-center rounded text-rose-500 hover:bg-rose-500/10 disabled:opacity-50"
                      >
                        <Trash2 size={12} />
                      </button>
                    </td>
                  </tr>
                );
              })}
              {unbudgetedCategories.map((r) => (
                <tr key={`unb-${r.categoryId}`}>
                  <td className="px-3 py-2">
                    <div className="text-text-primary">{r.categoryName}</div>
                    <div className="text-xs text-amber-500">
                      Unbudgeted ⚠
                      {r.dbsSection ? ` · Section ${r.dbsSection}` : ""}
                    </div>
                  </td>
                  <td className="px-3 py-2 text-right text-text-muted">—</td>
                  <td className="px-3 py-2 text-right font-mono">
                    {formatCurrency(r.actual, currency)}
                  </td>
                  <td className="px-3 py-2 text-right font-mono text-amber-500">
                    Unbudgeted
                  </td>
                  <td className="px-3 py-2"></td>
                </tr>
              ))}
              <tr className="border-t-2 border-border font-semibold">
                <td className="px-3 py-2">Total</td>
                <td className="px-3 py-2 text-right font-mono">
                  {formatCurrency(totalPlanned, currency)}
                </td>
                <td className="px-3 py-2 text-right font-mono">
                  {formatCurrency(totalActual, currency)}
                </td>
                <td
                  className={`px-3 py-2 text-right font-mono ${
                    totalVariance >= 0 ? "text-emerald-600" : "text-rose-600"
                  }`}
                >
                  {totalVariance >= 0 ? "+" : ""}
                  {formatCurrency(totalVariance, currency)}
                </td>
                <td className="px-3 py-2"></td>
              </tr>
            </>
          )}
        </tbody>
      </table>

      <Dialog open={dialogOpen} onOpenChange={(o) => !o && setDialogOpen(false)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {form.budgetId ? "Edit budget line" : "Add budget line"}
            </DialogTitle>
          </DialogHeader>
          <DialogBody className="space-y-3">
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Category *
              </label>
              <select
                value={form.categoryId}
                onChange={(e) =>
                  setForm({ ...form, categoryId: e.target.value })
                }
                disabled={form.budgetId !== null}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm disabled:opacity-60"
              >
                <option value="">— pick a category —</option>
                {(categoriesQuery.data?.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.code} — {c.name} · Section {c.dbsSection}
                  </option>
                ))}
              </select>
              {form.budgetId !== null ? (
                <p className="mt-1 text-xs text-text-muted">
                  Category fixed on existing budget — delete + re-add to change.
                </p>
              ) : null}
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Planned amount (₹) *
              </label>
              <input
                type="number"
                step="0.01"
                min="0"
                value={form.plannedAmount}
                onChange={(e) =>
                  setForm({ ...form, plannedAmount: e.target.value })
                }
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
                Notes (optional)
              </label>
              <input
                type="text"
                value={form.notes}
                onChange={(e) => setForm({ ...form, notes: e.target.value })}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm"
                maxLength={500}
              />
            </div>
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
              disabled={upsertMutation.isPending}
              className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground disabled:opacity-50"
            >
              {form.budgetId ? "Save changes" : "Add budget"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
