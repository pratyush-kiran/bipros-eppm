"use client";

import { memo, useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  AudioLines,
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
import { dprApi } from "@/lib/api/dprApi";
import type { DprApprovalStatus, DprSummaryRow, DprVoiceNote } from "@/lib/types/dpr";
import {
  SEVERITY_VARIANT,
  STATUS_VARIANT as ISSUE_STATUS_VARIANT,
  categoryLabel,
} from "./IssueBadges";
import { DetailTable } from "./DetailTable";
import { DprApprovalActions } from "./DprApprovalActions";

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
 * Click anywhere on the body to expand the manpower / equipment / material detail
 * panel (the same content that used to live inside the legacy DprActivityCard).
 *
 * <p>Designed for the "Day → Activity → Work fronts" layout: each row answers
 * "who did this stretch of this activity, with what crew?" at a glance.
 */
function DprWorkFrontRowImpl({ row, projectId, index, total, onEdit, onDelete }: Props) {
  const [open, setOpen] = useState(false);

  const {
    data: detailData,
    isLoading: detailLoading,
    isError: detailError,
    refetch: refetchDetail,
  } = useQuery({
    queryKey: ["dpr-detail", projectId, row.id],
    queryFn: () => dprApi.get(projectId, row.id),
    enabled: open,
    staleTime: 1000 * 60 * 5,
  });
  const detail = detailData?.data;
  const liveIssues = (detail?.issues ?? []).filter((i) => i.status !== "CANCELLED");

  const status = row.approvalStatus ?? "DRAFT";

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

        {/* Edit / delete — hidden (with tooltip) when APPROVED to mirror backend DPR_LOCKED guard */}
        <div className="flex flex-none items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
          {status === "APPROVED" ? (
            <span className="rounded-md px-2 py-1 text-xs text-ash" title="View-only — DPR is approved">
              View-only
            </span>
          ) : (
            <>
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
            </>
          )}
        </div>
      </div>

      {/* Approval actions — visible only to users holding DPR.APPROVE, for SUBMITTED/APPROVED */}
      <DprApprovalActions projectId={projectId} row={row} />

      {open && (
        <div className="space-y-3 border-t border-hairline bg-ivory/30 px-3 py-3 md:px-4">
          {detailLoading && (
            <div className="space-y-2">
              <div className="h-4 w-40 animate-pulse rounded bg-parchment" />
              <div className="h-16 animate-pulse rounded bg-parchment/60" />
              <div className="h-16 animate-pulse rounded bg-parchment/60" />
            </div>
          )}

          {detailError && (
            <div className="flex items-center gap-2 text-xs text-burgundy">
              Failed to load detail.
              <button
                type="button"
                onClick={() => refetchDetail()}
                className="rounded border border-burgundy/30 px-2 py-0.5 font-semibold hover:bg-burgundy/10"
              >
                Retry
              </button>
            </div>
          )}

          {detail && (
            <>
              {/* Rejection reason banner — prominent so the supervisor knows what to fix */}
              {detail.approvalStatus === "REJECTED" && detail.rejectionReason && (
                <div className="rounded-md border border-burgundy/25 bg-burgundy/8 px-3 py-2 text-xs text-charcoal">
                  <span className="font-semibold text-burgundy">Rejected: </span>
                  {detail.rejectionReason}
                </div>
              )}

              {detail.cumulativeQty != null && (
                <div className="text-xs text-slate">
                  <span className="font-semibold text-charcoal">Cumulative:</span>{" "}
                  <span className="tabular-nums">
                    {fmt(detail.cumulativeQty)} {detail.unit}
                  </span>
                </div>
              )}
              {detail.landmark && (
                <div className="text-xs text-slate">
                  <span className="font-semibold text-charcoal">Landmark:</span> {detail.landmark}
                </div>
              )}
              {detail.remarks && (
                <div className="rounded-md bg-paper/80 p-2 text-xs text-charcoal">
                  <span className="font-semibold">Remarks: </span>
                  {detail.remarks}
                </div>
              )}

              <DetailTable
                title="Manpower"
                empty="No manpower"
                headers={["Role · Category / Grade", "Nos", "Hours"]}
                rows={(detail.manpower ?? []).map((m) => [m.trade, fmt(m.nos, 0), fmt(m.workingHours)])}
                accent="emerald"
                numericFromIndex={1}
              />
              <DetailTable
                title="Equipment / PMV"
                empty="No equipment"
                headers={["Equipment · Make / Model", "Fleet #", "Nos", "Hours"]}
                rows={(detail.equipment ?? []).map((e) => [
                  e.equipmentType,
                  e.fleetNo ?? "—",
                  fmt(e.nos, 0),
                  fmt(e.workingHours),
                ])}
                accent="bronze"
                numericFromIndex={1}
              />
              <DetailTable
                title="Material"
                empty="No material"
                headers={["Material · Spec / Grade", "Qty"]}
                rows={(detail.materials ?? []).map((m) => [m.materialName, fmt(m.quantity, 3)])}
                accent="steel"
                numericFromIndex={1}
              />
              <DetailTable
                title="Sub-Contractor"
                empty="No sub-contractor"
                headers={["Sub-Contractor", "Qty", "Unit", "Rate", "Cost", "Remarks"]}
                rows={(detail.subContractors ?? []).map((s) => [
                  s.subContractorName ?? "—",
                  fmt(s.quantity, 3),
                  s.unit ?? "—",
                  fmt(s.ratePerUnit),
                  fmt(s.lineCost),
                  s.remarks?.trim() ? s.remarks : "—",
                ])}
                accent="slate"
                numericFromIndex={1}
              />

              {(detail.voiceNotes ?? []).length > 0 && (
                <div>
                  <div className="mb-1 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate">
                    <AudioLines className="h-3.5 w-3.5 text-gold" />
                    Voice notes
                  </div>
                  <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                    {(detail.voiceNotes ?? []).map((note) => (
                      <VoiceNotePlayer
                        key={note.id}
                        projectId={projectId}
                        dprId={detail.id}
                        note={note}
                      />
                    ))}
                  </div>
                </div>
              )}

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

              {(detail.delayReason ||
                detail.safetyObservation ||
                detail.safetyIncidentType === "INCIDENT" ||
                detail.safetyIncidentType === "NEAR_MISS") && (
                <div className="rounded-md border border-burgundy/20 bg-burgundy/5 p-3 text-xs text-charcoal">
                  <div className="mb-1 font-semibold text-burgundy">Safety & Delay</div>
                  {detail.safetyIncidentType && detail.safetyIncidentType !== "NONE" && (
                    <div>Incident: {detail.safetyIncidentType.replace("_", " ")}</div>
                  )}
                  {detail.delayReason && <div>Delay: {detail.delayReason}</div>}
                  {detail.safetyObservation && <div>Observation: {detail.safetyObservation}</div>}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Read-only voice-note player for the admin expand panel. Loads the JWT-protected audio stream
 * through an authenticated fetch + blob URL (mirrors {@code ExistingVoiceNoteTile} in
 * {@code DprVoiceNotesSection}); no delete affordance here.
 */
function VoiceNotePlayer({
  projectId,
  dprId,
  note,
}: {
  projectId: string;
  dprId: string;
  note: DprVoiceNote;
}) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [playError, setPlayError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let createdUrl: string | null = null;
    dprApi
      .fetchVoiceNoteBlobUrl(projectId, dprId, note.id)
      .then((url) => {
        if (cancelled) {
          URL.revokeObjectURL(url);
        } else {
          createdUrl = url;
          setSrc(url);
        }
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [projectId, dprId, note.id]);

  return (
    <div className="overflow-hidden rounded-md border border-hairline bg-paper">
      <div className="flex items-center gap-1.5 border-b border-hairline px-2 py-1.5 text-xs text-charcoal">
        <AudioLines className="h-3.5 w-3.5 shrink-0 text-gold" />
        <span className="truncate" title={note.fileName}>
          {note.fileName}
        </span>
        {note.durationSeconds != null && (
          <span className="shrink-0 text-slate">· {note.durationSeconds}s</span>
        )}
      </div>
      {src && !failed ? (
        <>
          <audio
            controls
            src={src}
            onError={() => setPlayError(true)}
            className="w-full px-2 py-2"
          />
          {playError && (
            <div className="px-2 pb-2 text-center text-xs text-slate">
              Your browser can&apos;t play this audio format.{" "}
              <a href={src} download={note.fileName} className="font-semibold text-gold underline">
                Download the file
              </a>{" "}
              to play it in another app.
            </div>
          )}
        </>
      ) : (
        <div className="px-2 py-3 text-center text-xs text-slate">
          {failed ? "Failed to load" : "Loading…"}
        </div>
      )}
      {note.caption && (
        <div className="border-t border-hairline px-2 py-1.5 text-xs text-charcoal">
          <span className="line-clamp-2">{note.caption}</span>
        </div>
      )}
    </div>
  );
}

export const DprWorkFrontRow = memo(DprWorkFrontRowImpl);
