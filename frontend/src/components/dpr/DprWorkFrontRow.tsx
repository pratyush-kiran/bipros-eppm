"use client";

import { memo, useState } from "react";
import {
  AlertTriangle,
  Briefcase,
  ChevronDown,
  ChevronRight,
  Eye,
  HardHat,
  Image as ImageIcon,
  MapPin,
  Package,
  Pencil,
  Trash2,
} from "lucide-react";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { useAuthStore } from "@/lib/state/store";
import { ResourceAvatar } from "@/components/resource/supervisor-assign/ResourceAvatar";
import { chainageLabel } from "@/lib/format/chainage";
import type { DprApprovalStatus, DprSummaryRow } from "@/lib/types/dpr";
import { DprApprovalActions } from "./DprApprovalActions";
import { DprDetailModal } from "./DprDetailModal";

interface Props {
  row: DprSummaryRow;
  projectId: string;
  index: number;
  total: number;
  onEdit: () => void;
  onDelete: () => void;
}

const STATUS_VARIANT: Record<DprApprovalStatus, BadgeVariant> = {
  DRAFT: "neutral",
  SUBMITTED: "info",
  APPROVED: "success",
  REJECTED: "danger",
};

const SIDE_LABEL: Record<string, string> = {
  LHS: "LHS",
  RHS: "RHS",
  CENTER: "Center",
};

const fmt = (n: number | null | undefined, digits = 2) =>
  typeof n === "number" && Number.isFinite(n)
    ? n.toLocaleString(undefined, { maximumFractionDigits: digits })
    : "—";

const lengthLabel = (from: number | null | undefined, to: number | null | undefined): string | null => {
  if (typeof from !== "number" || typeof to !== "number") return null;
  const span = Math.abs(to - from);
  if (!Number.isFinite(span) || span === 0) return null;
  if (span >= 1000) return `${(span / 1000).toLocaleString(undefined, { maximumFractionDigits: 2 })} km`;
  return `${span.toLocaleString(undefined, { maximumFractionDigits: 0 })} m`;
};

/**
 * One work-front (DPR row) inside a Site-Ledger activity card. Renders supervisor
 * avatar + name, chainage, side / status badges, qty, and resource-count chips.
 * Click the row or the eye icon to open the read-only {@link DprDetailModal}
 * (full crew/issue detail + Approve/Reject/Revoke for approvers).
 */
function DprWorkFrontRowImpl({ row, projectId, index, total, onEdit, onDelete }: Props) {
  const [open, setOpen] = useState(false);

  const status = row.approvalStatus ?? "DRAFT";

  // Only the submitter (or an admin) may edit/delete. Everyone else gets preview +
  // the approval actions below. APPROVED is locked for all (backend DPR_LOCKED).
  // Rows with no submitter stamped (legacy/DRAFT) fall back to permission-based access.
  const userId = useAuthStore((s) => s.user?.id);
  const isAdmin = useAuthStore((s) => s.isAdmin());
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const isSubmitter = !!row.submittedByUserId && row.submittedByUserId === userId;
  const canEdit =
    status !== "APPROVED" &&
    (isAdmin || isSubmitter || (!row.submittedByUserId && hasPermission("DPR.UPDATE")));
  const canDelete =
    status !== "APPROVED" &&
    (isAdmin || isSubmitter || (!row.submittedByUserId && hasPermission("DPR.DELETE")));

  const manpowerCount = row.manpowerNos;
  const equipmentCount = row.equipmentNos;
  const materialCount = row.materialCount;
  const photoCount = row.photoCount;
  const issueCount = row.issueCount;
  const openIssueCount = row.openIssueCount;
  const hasCriticalOpen = row.hasCriticalOpen;
  const issueChipClass = hasCriticalOpen
    ? "border-burgundy/30 bg-burgundy/10 text-burgundy"
    : openIssueCount > 0
      ? "border-bronze-warn/30 bg-bronze-warn/15 text-bronze-warn"
      : "border-hairline bg-ivory text-slate";

  const length = lengthLabel(row.chainageFromM, row.chainageToM);
  const avatarKey = row.supervisorUserId ?? row.supervisorName ?? `front-${index}`;

  return (
    <div className="group transition-colors hover:bg-ivory/40">
      <div className="flex flex-wrap items-center gap-3 px-3 py-2.5 md:px-4">
        {/* FRONT n / N anchor */}
        <span className="w-[64px] shrink-0 select-none text-[10px] font-semibold uppercase tracking-widest text-ash">
          Front {index + 1} / {total}
        </span>

        <button
          type="button"
          onClick={() => setOpen(true)}
          className="flex flex-1 items-center gap-3 text-left"
        >
          <span className="flex-none text-slate">
            {open ? (
              <ChevronDown className="h-4 w-4 transition-transform" />
            ) : (
              <ChevronRight className="h-4 w-4 transition-transform" />
            )}
          </span>

          {/* Supervisor block */}
          <div className="flex min-w-0 shrink-0 items-center gap-2">
            <ResourceAvatar id={avatarKey} name={row.supervisorName || "Unknown"} size="sm" />
            <div className="min-w-0">
              <div className="truncate font-semibold text-charcoal">
                {row.supervisorName || <span className="text-ash">Unspecified</span>}
              </div>
              <div className="text-[10px] uppercase tracking-wide text-ash">
                Site supervisor
              </div>
            </div>
          </div>

          {/* Chainage + side + status */}
          <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
            {(row.chainageFromM != null || row.chainageToM != null) && (
              <span className="inline-flex items-center gap-1 rounded-full bg-ivory/70 px-2 py-0.5 text-xs text-slate">
                <MapPin className="h-3 w-3 text-gold-deep" />
                <span className="tabular-nums text-charcoal">
                  {chainageLabel(row.chainageFromM)} → {chainageLabel(row.chainageToM)}
                </span>
                {length && (
                  <span className="text-ash">· {length}</span>
                )}
              </span>
            )}
            {row.side && (
              <Badge variant="neutral">{SIDE_LABEL[row.side] ?? row.side}</Badge>
            )}
            <Badge variant={STATUS_VARIANT[status]} withDot>
              {status}
            </Badge>
          </div>

          {/* Qty + resource chips */}
          <div className="flex shrink-0 items-center gap-2">
            {row.qtyExecuted != null && (
              <span className="font-display text-base font-semibold tabular-nums text-gold-ink">
                {fmt(row.qtyExecuted)}
                <span className="ml-1 text-xs font-normal text-slate">{row.unit}</span>
              </span>
            )}
            {manpowerCount > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-paper px-2 py-0.5 text-xs text-slate">
                <HardHat className="h-3 w-3" /> {manpowerCount}
              </span>
            )}
            {equipmentCount > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-paper px-2 py-0.5 text-xs text-slate">
                <Briefcase className="h-3 w-3" /> {equipmentCount}
              </span>
            )}
            {materialCount > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-paper px-2 py-0.5 text-xs text-slate">
                <Package className="h-3 w-3" /> {materialCount}
              </span>
            )}
            {photoCount > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-paper px-2 py-0.5 text-xs text-slate">
                <ImageIcon className="h-3 w-3" /> {photoCount}
              </span>
            )}
            {issueCount > 0 && (
              <span
                className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-semibold ${issueChipClass}`}
                title={`${openIssueCount} open / ${issueCount} total`}
              >
                <AlertTriangle className="h-3 w-3" /> {issueCount}
                {openIssueCount > 0 && openIssueCount !== issueCount && (
                  <span className="opacity-70">({openIssueCount} open)</span>
                )}
              </span>
            )}
          </div>
        </button>

        {/* Approval actions + preview (always) + edit/delete (submitter or admin only). */}
        <div className="ml-auto flex flex-none items-center gap-1.5">
          {/* Approve / Reject / Revoke — inline on the right (self-hides for non-approvers). */}
          <DprApprovalActions
            projectId={projectId}
            row={row}
            className="flex items-center gap-1.5"
          />
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="rounded-md p-1.5 text-slate hover:bg-ivory hover:text-charcoal"
            aria-label="Preview details"
            title="Preview details"
          >
            <Eye className="h-4 w-4" />
          </button>
          <div className="flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
            {canEdit && (
              <button
                type="button"
                onClick={onEdit}
                className="rounded-md p-1.5 text-slate hover:bg-ivory hover:text-charcoal"
                aria-label="Edit work front"
              >
                <Pencil className="h-4 w-4" />
              </button>
            )}
            {canDelete && (
              <button
                type="button"
                onClick={onDelete}
                className="rounded-md p-1.5 text-slate hover:bg-burgundy/10 hover:text-burgundy"
                aria-label="Delete work front"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            )}
          </div>
        </div>
      </div>

      <DprDetailModal
        projectId={projectId}
        row={row}
        open={open}
        onClose={() => setOpen(false)}
      />
    </div>
  );
}

export const DprWorkFrontRow = memo(DprWorkFrontRowImpl);
