"use client";

/**
 * @deprecated Superseded by {@link ./DprActivityGroup} + {@link ./DprWorkFrontRow},
 * which together provide the Day → Activity → Work-front "Site Ledger" view.
 * Kept here only because deleting it expands the diff; remove in a follow-up PR once
 * no IDE / dead-code analyser still references it. Confirmed not imported anywhere:
 * {@code grep -rn "DprActivityCard" frontend/src} returns only this file.
 */

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
import { chainageLabel } from "@/lib/format/chainage";
import type { DailyProgressReportResponse, DprApprovalStatus } from "@/lib/types/dpr";
import { SEVERITY_VARIANT, STATUS_VARIANT as ISSUE_STATUS_VARIANT, categoryLabel } from "./IssueBadges";

interface Props {
  row: DailyProgressReportResponse;
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
  typeof n === "number" && isFinite(n)
    ? n.toLocaleString(undefined, { maximumFractionDigits: digits })
    : "—";

export function DprActivityCard({ row, onEdit, onDelete }: Props) {
  const [open, setOpen] = useState(false);
  const status = row.approvalStatus ?? "DRAFT";
  const manpowerCount = (row.manpower ?? []).reduce((a, m) => a + (m.nos ?? 0), 0);
  const equipmentCount = (row.equipment ?? []).reduce((a, e) => a + (e.nos ?? 0), 0);
  const materialCount = (row.materials ?? []).length;
  const photoCount = (row.attachments ?? []).length;
  // Issue chip excludes CANCELLED — matches the AI's default list_issues view so the count
  // a PM sees on the card matches what the chat reports.
  const liveIssues = (row.issues ?? []).filter((i) => i.status !== "CANCELLED");
  const issueCount = liveIssues.length;
  const openIssueCount = liveIssues.filter(
    (i) => i.status !== "RESOLVED" && i.status !== "CLOSED"
  ).length;
  const hasCriticalOpen = liveIssues.some(
    (i) => i.severity === "CRITICAL" && i.status !== "RESOLVED" && i.status !== "CLOSED"
  );
  const issueChipClass = hasCriticalOpen
    ? "border-burgundy/30 bg-burgundy/10 text-burgundy"
    : openIssueCount > 0
      ? "border-bronze-warn/30 bg-bronze-warn/15 text-bronze-warn"
      : "border-hairline bg-ivory text-slate";
  const hasAnyChip =
    row.qtyExecuted != null ||
    manpowerCount > 0 ||
    equipmentCount > 0 ||
    materialCount > 0 ||
    photoCount > 0 ||
    issueCount > 0;

  return (
    <div className="rounded-lg border border-hairline bg-paper">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex flex-1 items-center gap-3 text-left"
        >
          <span className="flex-none text-slate">
            {open ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="truncate font-semibold text-charcoal">{row.activityName}</span>
              <Badge variant={STATUS_VARIANT[status]} withDot>
                {status}
              </Badge>
              {row.side && <Badge variant="neutral">{SIDE_LABEL[row.side] ?? row.side}</Badge>}
              {row.boqItemNo && <Badge variant="gold">BOQ {row.boqItemNo}</Badge>}
              {(row.chainageFromM != null || row.chainageToM != null) && (
                <span className="inline-flex items-center gap-1 text-xs text-slate">
                  <MapPin className="h-3 w-3" />
                  {chainageLabel(row.chainageFromM)} → {chainageLabel(row.chainageToM)}
                </span>
              )}
            </div>
            {hasAnyChip && (
              <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                {row.qtyExecuted != null && (
                  <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-parchment px-2 py-0.5 text-xs font-semibold text-charcoal tabular-nums">
                    {fmt(row.qtyExecuted)} {row.unit}
                  </span>
                )}
                {manpowerCount > 0 && (
                  <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-ivory px-2 py-0.5 text-xs text-slate">
                    <HardHat className="h-3 w-3" /> {manpowerCount}
                  </span>
                )}
                {equipmentCount > 0 && (
                  <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-ivory px-2 py-0.5 text-xs text-slate">
                    <Briefcase className="h-3 w-3" /> {equipmentCount}
                  </span>
                )}
                {materialCount > 0 && (
                  <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-ivory px-2 py-0.5 text-xs text-slate">
                    <Package className="h-3 w-3" /> {materialCount}
                  </span>
                )}
                {photoCount > 0 && (
                  <span className="inline-flex items-center gap-1 rounded-full border border-hairline bg-ivory px-2 py-0.5 text-xs text-slate">
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
            )}
          </div>
        </button>
        <div className="flex flex-none items-center gap-1">
          <button
            type="button"
            onClick={onEdit}
            className="rounded-md p-1.5 text-slate hover:bg-ivory hover:text-charcoal"
            aria-label="Edit"
          >
            <Pencil className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={onDelete}
            className="rounded-md p-1.5 text-slate hover:bg-burgundy/10 hover:text-burgundy"
            aria-label="Delete"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>
      {open && (
        <div className="space-y-3 border-t border-hairline px-4 py-3">
          {row.cumulativeQty != null && (
            <div className="text-xs text-slate">
              <span className="font-semibold text-charcoal">Cumulative:</span>{" "}
              <span className="tabular-nums">{fmt(row.cumulativeQty)} {row.unit}</span>
            </div>
          )}
          {row.landmark && (
            <div className="text-xs text-slate">
              <span className="font-semibold text-charcoal">Landmark:</span> {row.landmark}
            </div>
          )}
          {row.remarks && (
            <div className="rounded-md bg-ivory/60 p-2 text-xs text-charcoal">
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
          <DetailTable
            title="Sub-Contractor"
            empty="No sub-contractor"
            headers={["Sub-Contractor"]}
            rows={(row.subContractors ?? []).map((s) => [
              s.subContractorName ?? "—",
            ])}
          />

          {liveIssues.length > 0 && (
            <div>
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate">Issues</div>
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

          {(row.delayReason || row.safetyObservation || row.safetyIncidentType === "INCIDENT" ||
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

function DetailTable({
  title,
  empty,
  headers,
  rows,
}: {
  title: string;
  empty: string;
  headers: string[];
  rows: Array<Array<string | number>>;
}) {
  if (rows.length === 0) {
    return (
      <div className="text-xs text-ash">
        <span className="font-semibold text-slate">{title}:</span> {empty}
      </div>
    );
  }
  return (
    <div>
      <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate">{title}</div>
      <div className="overflow-x-auto rounded-md border border-hairline">
        <table className="w-full text-xs">
          <thead className="bg-ivory/60">
            <tr>
              {headers.map((h) => (
                <th key={h} className="px-2 py-1 text-left font-semibold text-slate">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="border-t border-hairline">
                {r.map((c, j) => (
                  <td key={j} className="px-2 py-1 text-charcoal">
                    {c}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
