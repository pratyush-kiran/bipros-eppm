"use client";

import { useState } from "react";
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
import { dbsExpenseApi } from "@/lib/api/dbsExpenseApi";
import { getErrorMessage } from "@/lib/utils/error";

export interface ExpenseThresholdDialogProps {
  projectId: string;
  open: boolean;
  onClose: () => void;
}

/**
 * PM-only dialog to set the per-project approval threshold for ad-hoc expenses.
 * Expenses at or above this amount land as PENDING and need approval before they
 * roll up into DBS / Activity AC. Empty / 0 clears the override and falls back to
 * the global default (currently ₹10,000).
 */
export function ExpenseThresholdDialog({
  projectId,
  open,
  onClose,
}: ExpenseThresholdDialogProps) {
  const qc = useQueryClient();
  // Local input state. Initialised lazily from the fetched current value below — we
  // intentionally don't sync on every query refresh; once the dialog opens the user is
  // in control of the field.
  const [value, setValue] = useState<string | null>(null);

  const currentQuery = useQuery({
    queryKey: ["dbs-approval-threshold", projectId],
    queryFn: () => dbsExpenseApi.getApprovalThreshold(projectId),
    enabled: open,
  });

  // Compute the field's effective value at render time. While the user hasn't typed
  // anything (value === null), show whatever the API returns; once they type, switch
  // to their input. Avoids the React Compiler setState-in-effect warning.
  const displayValue =
    value !== null
      ? value
      : currentQuery.data?.data != null
        ? String(currentQuery.data.data)
        : "";

  const setMutation = useMutation({
    mutationFn: (newValue: number | null) =>
      dbsExpenseApi.setApprovalThreshold(projectId, newValue),
    onSuccess: () => {
      toast.success("Threshold updated");
      qc.invalidateQueries({ queryKey: ["dbs-approval-threshold", projectId] });
      onClose();
    },
    onError: (err) => toast.error(getErrorMessage(err)),
  });

  const handleSave = () => {
    const raw = displayValue.trim();
    if (raw === "" || Number(raw) <= 0) {
      setMutation.mutate(null);
      return;
    }
    const parsed = Number(raw);
    if (!Number.isFinite(parsed)) {
      toast.error("Enter a positive number");
      return;
    }
    setMutation.mutate(parsed);
  };

  const handleClear = () => {
    setValue("");
    setMutation.mutate(null);
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Expense Approval Threshold</DialogTitle>
        </DialogHeader>
        <DialogBody className="space-y-3">
          <p className="text-sm text-text-secondary">
            Expenses with absolute amount <strong>at or above</strong> this value
            land as <span className="font-semibold text-amber-500">PENDING</span>{" "}
            until a project-update permission holder approves them. Pending entries
            are excluded from DBS section totals and Activity Actual Cost.
          </p>
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-text-muted">
              Threshold (₹)
            </label>
            <input
              type="number"
              step="0.01"
              min="0"
              value={displayValue}
              onChange={(e) => setValue(e.target.value)}
              placeholder="10000.00"
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm font-mono"
            />
            <p className="mt-1 text-xs text-text-muted">
              Empty or 0 = use the global default ({" "}
              <span className="font-mono">₹10,000</span>). Project-level value
              overrides global.
            </p>
          </div>
        </DialogBody>
        <DialogFooter>
          <button
            type="button"
            onClick={handleClear}
            className="rounded-md border border-border bg-surface px-4 py-2 text-sm"
          >
            Use global default
          </button>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-border bg-surface px-4 py-2 text-sm"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={setMutation.isPending}
            className="rounded-md bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground disabled:opacity-50"
          >
            {setMutation.isPending ? "Saving…" : "Save threshold"}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
