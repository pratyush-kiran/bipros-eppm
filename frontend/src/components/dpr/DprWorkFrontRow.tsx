"use client";

import { useState } from "react";
import {
  AlertTriangle,
  Briefcase,
  ChevronDown,
  ChevronRight,
  HardHat,
  Image as ImageIcon,
  MapPin,
  Package,
  Pencil,
  Trash2,
} from "lucide-react";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { ResourceAvatar } from "@/components/resource/supervisor-assign/ResourceAvatar";
import { chainageLabel } from "@/lib/format/chainage";
import type { DailyProgressReportResponse, DprApprovalStatus } from "@/lib/types/dpr";
import {
  SEVERITY_VARIANT,
  STATUS_VARIANT as ISSUE_STATUS_VARIANT,
  categoryLabel,
} from "./IssueBadges";
import { DetailTable } from "./DetailTable";

interface Props {
  row: DailyProgressReportResponse;
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
 * Click anywhere on the body to expand the manpower / equipment / material detail
 * panel (the same content that used to live inside the legacy DprActivityCard).
 *
 * <p>Designed for the "Day → Activity → Work fronts" layout: each row answers
 * "who did this stretch of this activity, with what crew?" at a glance.
 */
export function DprWorkFrontRow({ row, index, total, onEdit, onDelete }: Props) {
  const [open, setOpen] = useState(false);
  const status = row.approvalStatus ?? "DRAFT";

  const manpowerCount = (row.manpower ?? []).reduce((a, m) => a + (m.nos ?? 0), 0);
  const equipmentCount = (row.equipment ?? []).reduce((a, e) => a + (e.nos ?? 0), 0);
  const materialCount = (row.materials ?? []).length;
  const photoCount = (row.attachments ?? []).length;
  const liveIssues = (row.issues ?? []).filter((i) => i.status !== "CANCELLED");
  const issueCount = liveIssues.length;
  const openIssueCount = liveIssues.filter(
    (i) => i.status !== "RESOLVED" && i.status !== "CLOSED",
  ).length;
  const hasCriticalOpen = liveIssues.some(
    (i) => i.severity === "CRITICAL" && i.status !== "RESOLVED" && i.status !== "CLOSED",
  );
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
          onClick={() => setOpen((v) => !v)}
          className="flex flex-1 items-center gap-3 text-left"
          aria-expanded={open}
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

        {/* Edit / delete (revealed on hover) */}
        <div className="flex flex-none items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
          <button
            type="button"
            onClick={onEdit}
            className="rounded-md p-1.5 text-slate hover:bg-ivory hover:text-charcoal"
            aria-label="Edit work front"
          >
            <Pencil className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={onDelete}
            className="rounded-md p-1.5 text-slate hover:bg-burgundy/10 hover:text-burgundy"
            aria-label="Delete work front"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

      {open && (
        <div className="space-y-3 border-t border-hairline bg-ivory/30 px-3 py-3 md:px-4">
          {row.cumulativeQty != null && (
            <div className="text-xs text-slate">
              <span className="font-semibold text-charcoal">Cumulative:</span>{" "}
              <span className="tabular-nums">
                {fmt(row.cumulativeQty)} {row.unit}
              </span>
            </div>
          )}
          {row.landmark && (
            <div className="text-xs text-slate">
              <span className="font-semibold text-charcoal">Landmark:</span> {row.landmark}
            </div>
          )}
          {row.remarks && (
            <div className="rounded-md bg-paper/80 p-2 text-xs text-charcoal">
              <span className="font-semibold">Remarks: </span>
              {row.remarks}
            </div>
          )}

          <DetailTable
            title="Manpower"
            empty="No manpower"
            headers={["Role · Category / Grade", "Nos", "Hours"]}
            rows={(row.manpower ?? []).map((m) => [
              m.trade,
              fmt(m.nos, 0),
              fmt(m.workingHours),
            ])}
          />
          <DetailTable
            title="Equipment / PMV"
            empty="No equipment"
            headers={["Equipment · Make / Model", "Fleet #", "Nos", "Hours"]}
            rows={(row.equipment ?? []).map((e) => [
              e.equipmentType,
              e.fleetNo ?? "—",
              fmt(e.nos, 0),
              fmt(e.workingHours),
            ])}
          />
          <DetailTable
            title="Material"
            empty="No material"
            headers={["Material · Spec / Grade", "Qty"]}
            rows={(row.materials ?? []).map((m) => [
              m.materialName,
              fmt(m.quantity, 3),
            ])}
          />

          {liveIssues.length > 0 && (
            <div>
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate">
                Issues
              </div>
              <div className="overflow-x-auto rounded-md border border-hairline">
                <table className="w-full text-xs">
                  <thead className="bg-ivory/60">
                    <tr>
                      <th className="px-2 py-1 text-left font-semibold text-slate">Title</th>
                      <th className="px-2 py-1 text-left font-semibold text-slate">Reason</th>
                      <th className="px-2 py-1 text-left font-semibold text-slate">Severity</th>
                      <th className="px-2 py-1 text-left font-semibold text-slate">Status</th>
                      <th className="px-2 py-1 text-left font-semibold text-slate">Assigned</th>
                    </tr>
                  </thead>
                  <tbody>
                    {liveIssues.map((i) => (
                      <tr key={i.id ?? i.title} className="border-t border-hairline">
                        <td className="px-2 py-1 text-charcoal">{i.title}</td>
                        <td className="px-2 py-1 text-charcoal">{categoryLabel(i.category)}</td>
                        <td className="px-2 py-1">
                          <Badge variant={SEVERITY_VARIANT[i.severity]}>{i.severity}</Badge>
                        </td>
                        <td className="px-2 py-1">
                          <Badge variant={ISSUE_STATUS_VARIANT[i.status]} withDot>
                            {i.status}
                          </Badge>
                        </td>
                        <td className="px-2 py-1 text-charcoal">{i.assignedToName ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {(row.delayReason ||
            row.safetyObservation ||
            row.safetyIncidentType === "INCIDENT" ||
            row.safetyIncidentType === "NEAR_MISS") && (
            <div className="rounded-md border border-burgundy/20 bg-burgundy/5 p-3 text-xs text-charcoal">
              <div className="mb-1 font-semibold text-burgundy">Safety & Delay</div>
              {row.safetyIncidentType && row.safetyIncidentType !== "NONE" && (
                <div>Incident: {row.safetyIncidentType.replace("_", " ")}</div>
              )}
              {row.delayReason && <div>Delay: {row.delayReason}</div>}
              {row.safetyObservation && <div>Observation: {row.safetyObservation}</div>}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
