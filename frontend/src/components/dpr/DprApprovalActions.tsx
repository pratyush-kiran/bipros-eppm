"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle, XCircle, RotateCcw } from "lucide-react";
import toast from "react-hot-toast";
import { dprApi } from "@/lib/api/dprApi";
import type { DprSummaryRow } from "@/lib/types/dpr";
import { useAuthStore } from "@/lib/state/store";
import { getErrorMessage } from "@/lib/utils/error";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogBody,
  DialogFooter,
} from "@/components/ui/dialog";

interface Props {
  projectId: string;
  row: DprSummaryRow;
}

type ActionKind = "approve" | "reject" | "revoke";

/**
 * Approval action strip for DPR cards. Self-hides when:
 * - the current user does not hold DPR.APPROVE, OR
 * - approvalStatus is DRAFT / REJECTED (nothing to approve/revoke yet).
 *
 * Shown inline below the row header so it doesn't break the existing layout.
 */
export function DprApprovalActions({ projectId, row }: Props) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canApprove = hasPermission("DPR.APPROVE");

  const status = row.approvalStatus ?? "DRAFT";

  // Only render for SUBMITTED or APPROVED statuses, and only for approvers.
  if (!canApprove) return null;
  if (status !== "SUBMITTED" && status !== "APPROVED") return null;

  return <ApprovalActions projectId={projectId} row={row} status={status} />;
}

// ─── Internal action component (separated to keep JSX clean) ──────────────────

interface InternalProps {
  projectId: string;
  row: DprSummaryRow;
  status: "SUBMITTED" | "APPROVED";
}

function ApprovalActions({ projectId, row, status }: InternalProps) {
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<ActionKind | null>(null);
  const [reason, setReason] = useState("");

  const invalidateDpr = () => {
    // Invalidate all DPR list queries for this project (date range keys vary, so match prefix)
    queryClient.invalidateQueries({ queryKey: ["dpr", projectId] });
    // Also invalidate the detail cache for this specific record
    queryClient.invalidateQueries({ queryKey: ["dpr-detail", projectId, row.id] });
    // Invalidate approval-queue queries if they exist
    queryClient.invalidateQueries({ queryKey: ["dpr-pending-approvals", projectId] });
    queryClient.invalidateQueries({ queryKey: ["dpr-unassigned-approvals", projectId] });
  };

  const approveMutation = useMutation({
    mutationFn: (r?: string) => dprApi.approve(projectId, row.id, r || undefined),
    onSuccess: () => {
      toast.success("DPR approved");
      invalidateDpr();
      closeDialog();
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to approve DPR"));
    },
  });

  const rejectMutation = useMutation({
    mutationFn: (r: string) => dprApi.reject(projectId, row.id, r),
    onSuccess: () => {
      toast.success("DPR rejected");
      invalidateDpr();
      closeDialog();
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to reject DPR"));
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (r?: string) => dprApi.revoke(projectId, row.id, r || undefined),
    onSuccess: () => {
      toast.success("DPR approval revoked");
      invalidateDpr();
      closeDialog();
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to revoke DPR approval"));
    },
  });

  const isPending =
    approveMutation.isPending || rejectMutation.isPending || revokeMutation.isPending;

  const openDialog = (kind: ActionKind) => {
    setReason("");
    setDialog(kind);
  };

  const closeDialog = () => {
    setDialog(null);
    setReason("");
  };

  const handleConfirm = () => {
    if (dialog === "approve") {
      approveMutation.mutate(reason || undefined);
    } else if (dialog === "reject") {
      if (!reason.trim()) return; // guard — Submit button is also disabled
      rejectMutation.mutate(reason.trim());
    } else if (dialog === "revoke") {
      revokeMutation.mutate(reason || undefined);
    }
  };

  const dialogTitle =
    dialog === "approve"
      ? "Approve DPR"
      : dialog === "reject"
        ? "Reject DPR"
        : "Revoke DPR Approval";

  const confirmLabel =
    dialog === "approve" ? "Approve" : dialog === "reject" ? "Reject" : "Revoke";

  const confirmClass =
    dialog === "reject" || dialog === "revoke"
      ? "rounded-md bg-burgundy/90 px-4 py-2 text-sm font-semibold text-white hover:bg-burgundy disabled:opacity-50"
      : "rounded-md bg-gold px-4 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep disabled:opacity-50";

  const isConfirmDisabled =
    isPending || (dialog === "reject" && !reason.trim());

  return (
    <>
      {/* Action buttons — inline strip */}
      <div className="flex items-center gap-1.5 px-3 pb-2 pt-0 md:px-4">
        {status === "SUBMITTED" && (
          <>
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); openDialog("approve"); }}
              disabled={isPending}
              className="inline-flex items-center gap-1 rounded-md border border-emerald-600/30 bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-emerald-700 hover:bg-emerald-100 disabled:opacity-50"
            >
              <CheckCircle className="h-3.5 w-3.5" />
              Approve
            </button>
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); openDialog("reject"); }}
              disabled={isPending}
              className="inline-flex items-center gap-1 rounded-md border border-burgundy/30 bg-burgundy/5 px-2.5 py-1 text-xs font-semibold text-burgundy hover:bg-burgundy/10 disabled:opacity-50"
            >
              <XCircle className="h-3.5 w-3.5" />
              Reject
            </button>
          </>
        )}
        {status === "APPROVED" && (
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); openDialog("revoke"); }}
            disabled={isPending}
            className="inline-flex items-center gap-1 rounded-md border border-slate/30 bg-ivory px-2.5 py-1 text-xs font-semibold text-slate hover:bg-parchment disabled:opacity-50"
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Revoke approval
          </button>
        )}
      </div>

      {/* Reason dialog */}
      <Dialog open={dialog !== null} onOpenChange={(open) => { if (!open) closeDialog(); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{dialogTitle}</DialogTitle>
          </DialogHeader>
          <DialogBody>
            {dialog === "approve" && (
              <p className="mb-3 text-sm text-slate">
                Approving this DPR locks it from further edits. You may optionally add a note.
              </p>
            )}
            {dialog === "revoke" && (
              <p className="mb-3 text-sm text-slate">
                Revoking approval moves the DPR back to SUBMITTED so it can be edited and
                re-approved. You may optionally add a note.
              </p>
            )}
            {dialog === "reject" && (
              <p className="mb-3 text-sm text-slate">
                Provide a reason so the supervisor can fix and resubmit.
              </p>
            )}
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1">
              {dialog === "reject" ? "Reason (required)" : "Note (optional)"}
            </label>
            <textarea
              rows={3}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={dialog === "reject" ? "Enter rejection reason…" : "Enter note…"}
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal placeholder:text-ash focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40 resize-none"
            />
            {dialog === "reject" && !reason.trim() && (
              <p className="mt-1 text-xs text-burgundy">A reason is required to reject.</p>
            )}
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={closeDialog}
              className="rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-semibold text-slate hover:bg-ivory"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleConfirm}
              disabled={isConfirmDisabled}
              className={confirmClass}
            >
              {isPending ? "…" : confirmLabel}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
