"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronDown, ChevronUp, X } from "lucide-react";
import toast from "react-hot-toast";

import { Badge } from "@/components/ui/badge";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { dbsExpenseApi, type DbsManualExpense } from "@/lib/api/dbsExpenseApi";
import { formatCurrency } from "@/lib/utils/format";
import { getErrorMessage } from "@/lib/utils/error";

export interface PendingExpensesPanelProps {
  projectId: string;
  currency?: string | null;
}

/**
 * PM inbox: every PENDING manual expense for the project. Renders collapsed by default
 * with a count badge in the header. Approve / Reject buttons mutate the row and the panel
 * refetches automatically.
 */
export function PendingExpensesPanel({
  projectId,
  currency,
}: PendingExpensesPanelProps) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(true);
  const [confirmReject, setConfirmReject] = useState<DbsManualExpense | null>(null);

  const pendingQuery = useQuery({
    queryKey: ["dbs-expenses-pending", projectId],
    queryFn: () => dbsExpenseApi.listPending(projectId),
    refetchInterval: 30_000,  // keep the inbox fresh — cheap
  });

  const items = pendingQuery.data?.data ?? [];

  const approveMutation = useMutation({
    mutationFn: (id: string) => dbsExpenseApi.approve(projectId, id),
    onSuccess: () => {
      toast.success("Expense approved");
      qc.invalidateQueries({ queryKey: ["dbs-expenses-pending", projectId] });
      qc.invalidateQueries({ queryKey: ["dbs"] });
      qc.invalidateQueries({ queryKey: ["activity-expense-budgets"] });
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const rejectMutation = useMutation({
    mutationFn: (id: string) => dbsExpenseApi.reject(projectId, id),
    onSuccess: () => {
      toast.success("Expense rejected");
      qc.invalidateQueries({ queryKey: ["dbs-expenses-pending", projectId] });
      setConfirmReject(null);
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  // Hide the panel entirely when there's nothing to action — keeps the DBS page tidy.
  if (!pendingQuery.isLoading && items.length === 0) return null;

  return (
    <section className="rounded-lg border border-amber-500/40 bg-amber-500/5 shadow-sm">
      <header
        className="flex cursor-pointer items-center justify-between px-4 py-3"
        onClick={() => setOpen(!open)}
      >
        <div className="flex items-center gap-2">
          <Badge variant="warning">{items.length}</Badge>
          <h3 className="text-sm font-semibold text-amber-700 dark:text-amber-300">
            Pending Approvals
          </h3>
          <span className="text-xs text-text-muted">
            Expenses above the project threshold — they do not count in totals until you act.
          </span>
        </div>
        {open ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
      </header>

      {open ? (
        <div className="overflow-x-auto border-t border-amber-500/30">
          <table className="w-full text-sm">
            <thead className="bg-amber-500/10 text-left text-xs uppercase tracking-wide text-text-muted">
              <tr>
                <th className="px-4 py-2">Date</th>
                <th className="px-4 py-2">Category · Section</th>
                <th className="px-4 py-2">Description</th>
                <th className="px-4 py-2">Vendor</th>
                <th className="px-4 py-2 text-right">Amount</th>
                <th className="px-4 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-amber-500/20">
              {pendingQuery.isLoading ? (
                <tr>
                  <td colSpan={6} className="px-4 py-4 text-center text-text-muted">
                    Loading pending approvals…
                  </td>
                </tr>
              ) : (
                items.map((e) => (
                  <tr key={e.id}>
                    <td className="px-4 py-2 font-mono text-xs">{e.reportDate}</td>
                    <td className="px-4 py-2">
                      <div className="text-text-primary">{e.categoryName ?? "?"}</div>
                      <div className="text-xs text-text-muted">
                        {e.categoryCode} · Section {e.dbsSection}
                      </div>
                    </td>
                    <td className="px-4 py-2">
                      <div className="text-text-primary">{e.description}</div>
                      {e.notes ? (
                        <div className="text-xs text-text-muted">{e.notes}</div>
                      ) : null}
                    </td>
                    <td className="px-4 py-2 text-text-secondary">
                      {e.vendorName ?? "—"}
                      {e.receiptNo ? (
                        <div className="text-xs text-text-muted">#{e.receiptNo}</div>
                      ) : null}
                    </td>
                    <td className="px-4 py-2 text-right font-mono font-semibold">
                      {formatCurrency(e.amount, currency ?? e.currency)}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <button
                        type="button"
                        onClick={() => approveMutation.mutate(e.id)}
                        disabled={approveMutation.isPending}
                        title="Approve"
                        className="mr-2 inline-flex h-7 items-center gap-1 rounded-md bg-emerald-600/90 px-2 text-xs font-semibold text-white hover:bg-emerald-600 disabled:opacity-50"
                      >
                        <Check size={12} /> Approve
                      </button>
                      <button
                        type="button"
                        onClick={() => setConfirmReject(e)}
                        title="Reject"
                        className="inline-flex h-7 items-center gap-1 rounded-md bg-rose-600/90 px-2 text-xs font-semibold text-white hover:bg-rose-600"
                      >
                        <X size={12} /> Reject
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      ) : null}

      <ConfirmDialog
        open={!!confirmReject}
        title="Reject this expense?"
        message={
          confirmReject
            ? `Reject the ${formatCurrency(
                confirmReject.amount,
                currency ?? confirmReject.currency,
              )} "${confirmReject.description}" entry? It stays in the ledger for audit but will not roll into any totals.`
            : ""
        }
        confirmLabel={rejectMutation.isPending ? "Rejecting…" : "Reject"}
        cancelLabel="Cancel"
        variant="danger"
        onConfirm={() => confirmReject && rejectMutation.mutate(confirmReject.id)}
        onCancel={() => setConfirmReject(null)}
      />
    </section>
  );
}
