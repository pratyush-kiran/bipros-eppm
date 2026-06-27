"use client";

import { useEffect, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AudioLines,
  Check,
  ChevronLeft,
  ChevronRight,
  Image as ImageIcon,
  Package,
  Truck,
  Users,
  X,
} from "lucide-react";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { useAuthStore } from "@/lib/state/store";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { chainageLabel } from "@/lib/format/chainage";
import { dprApi } from "@/lib/api/dprApi";
import type {
  DailyProgressReportResponse,
  DprApprovalStatus,
  DprSummaryRow,
  DprVoiceNote,
} from "@/lib/types/dpr";
import {
  SEVERITY_VARIANT,
  STATUS_VARIANT as ISSUE_STATUS_VARIANT,
  categoryLabel,
} from "./IssueBadges";
import { DprApprovalActions } from "./DprApprovalActions";
import { Dialog, DialogContent } from "@/components/ui/dialog";

// ─── Small helpers ──────────────────────────────────────────────────────────────

const STATUS_VARIANT: Record<DprApprovalStatus, BadgeVariant> = {
  DRAFT: "neutral",
  SUBMITTED: "info",
  APPROVED: "success",
  REJECTED: "danger",
};

const STATUS_PILL: Record<DprApprovalStatus, { dot: string; text: string }> = {
  DRAFT: { dot: "bg-ash", text: "text-parchment" },
  SUBMITTED: { dot: "bg-sky-300", text: "text-sky-100" },
  APPROVED: { dot: "bg-emerald-300", text: "text-emerald-100" },
  REJECTED: { dot: "bg-rose-300", text: "text-rose-100" },
};

const SIDE_LABEL: Record<string, string> = { LHS: "LHS", RHS: "RHS", CENTER: "Center" };

const num = (n: number | null | undefined, digits = 2) =>
  typeof n === "number" && Number.isFinite(n)
    ? n.toLocaleString(undefined, { maximumFractionDigits: digits })
    : "—";

const sum = (xs: Array<number | null | undefined>) =>
  xs.reduce<number>((a, b) => a + (typeof b === "number" && Number.isFinite(b) ? b : 0), 0);

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

type TabKey = "details" | "manpower" | "equipment" | "material" | "sub" | "issues";

interface Props {
  projectId: string;
  row: DprSummaryRow;
  open: boolean;
  onClose: () => void;
}

/**
 * Read-only DPR detail modal — a professional preview (hero, KPI strip, tabbed
 * detail, photos + voice, approval trail) with the Approve/Reject/Revoke actions
 * in the footer. Shared by the Daily-Reports work-front rows and the Approvals
 * queue. Lazy-loads the full {@link DailyProgressReportResponse} on open.
 */
export function DprDetailModal({ projectId, row, open, onClose }: Props) {
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="flex max-h-[90vh] w-full max-w-5xl flex-col p-0">
        {/* Body lives in its own component so it mounts fresh on each open —
            the active tab resets to Details with no setState-in-effect. */}
        <DprDetailBody projectId={projectId} row={row} />
      </DialogContent>
    </Dialog>
  );
}

function DprDetailBody({ projectId, row }: { projectId: string; row: DprSummaryRow }) {
  const [tab, setTab] = useState<TabKey>("details");
  const cur = useProjectCurrencyOptional();
  const money = (n: number | null | undefined) =>
    n == null ? "—" : cur ? cur.money(n) : num(n);

  const {
    data: detailData,
    isLoading,
    isError,
    refetch,
  } = useQuery({
    queryKey: ["dpr-detail", projectId, row.id],
    queryFn: () => dprApi.get(projectId, row.id),
    staleTime: 1000 * 60 * 5,
  });
  const detail = detailData?.data ?? undefined;

  const status = (detail?.approvalStatus ?? row.approvalStatus ?? "DRAFT") as DprApprovalStatus;
  const canApprove = useAuthStore((s) => s.hasPermission)("DPR.APPROVE");
  const showApprovalActions =
    canApprove && (status === "SUBMITTED" || status === "APPROVED");

  return (
    <>
      <div className="flex-1 overflow-y-auto">
        <div className="space-y-4 p-4 sm:p-5">
          <Hero row={row} detail={detail} status={status} />
          <KpiStrip row={row} detail={detail} money={money} />
          <DetailCard
            row={row}
            detail={detail}
            isLoading={isLoading}
            isError={isError}
            onRetry={() => refetch()}
            tab={tab}
            setTab={setTab}
            money={money}
          />
          {detail && <MediaRow projectId={projectId} detail={detail} />}
          {detail && <ApprovalTrail detail={detail} status={status} />}
        </div>
      </div>

      {showApprovalActions && (
        <div className="shrink-0 rounded-b-2xl border-t border-hairline bg-ivory px-3 py-2">
          <DprApprovalActions projectId={projectId} row={row} />
        </div>
      )}
    </>
  );
}

// ─── Hero ─────────────────────────────────────────────────────────────────────

function Hero({
  row,
  detail,
  status,
}: {
  row: DprSummaryRow;
  detail?: DailyProgressReportResponse;
  status: DprApprovalStatus;
}) {
  const pill = STATUS_PILL[status];
  const chainage =
    row.chainageFromM != null || row.chainageToM != null
      ? `CH ${chainageLabel(row.chainageFromM)} → ${chainageLabel(row.chainageToM)}`
      : null;
  const contractor = detail?.contractorName ?? null;
  const shift = detail?.shift ? (detail.shift === "DAY" ? "Day shift" : "Night shift") : null;

  return (
    <div className="relative overflow-hidden rounded-2xl bg-charcoal px-6 py-6 text-paper">
      <div
        className="pointer-events-none absolute inset-0"
        style={{
          background:
            "radial-gradient(720px 280px at 88% -40%, rgba(0,88,202,0.45), transparent 60%)",
        }}
      />
      <div className="relative flex flex-wrap items-start justify-between gap-4 pr-8">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-parchment/80">
            <span className="h-1.5 w-1.5 rounded-full bg-gold" />
            Daily Progress Report
          </div>
          <h2 className="mt-2 font-display text-2xl font-bold leading-tight tracking-tight">
            {row.activityName || "Daily Progress Report"}
          </h2>
          <div className="mt-1.5 text-sm text-parchment/70">
            {row.reportDate}
            {shift && <span> · {shift}</span>}
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            {chainage && <HeroChip mono>{chainage}</HeroChip>}
            {row.side && <HeroChip>{SIDE_LABEL[row.side] ?? row.side} side</HeroChip>}
            {row.boqItemNo && <HeroChip mono>BOQ {row.boqItemNo}</HeroChip>}
            {contractor && <HeroChip>Contractor · {contractor}</HeroChip>}
          </div>
        </div>
        <div className="flex flex-col items-end gap-3">
          <span
            className={`inline-flex items-center gap-2 rounded-full bg-paper/10 px-3.5 py-1.5 text-xs font-bold ${pill.text}`}
          >
            <span className={`h-1.5 w-1.5 rounded-full ${pill.dot}`} />
            {status.charAt(0) + status.slice(1).toLowerCase()}
          </span>
          <div className="text-right">
            <div className="text-[10px] font-semibold uppercase tracking-wider text-parchment/60">
              Supervisor
            </div>
            <div className="mt-0.5 text-sm font-bold">{row.supervisorName || "—"}</div>
          </div>
        </div>
      </div>
    </div>
  );
}

function HeroChip({ children, mono }: { children: ReactNode; mono?: boolean }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-lg border border-paper/15 bg-paper/10 px-2.5 py-1 text-xs font-semibold text-parchment ${mono ? "font-mono tabular-nums" : ""}`}
    >
      {children}
    </span>
  );
}

// ─── KPI strip ──────────────────────────────────────────────────────────────────

function KpiStrip({
  row,
  detail,
  money,
}: {
  row: DprSummaryRow;
  detail?: DailyProgressReportResponse;
  money: (n: number | null | undefined) => string;
}) {
  const manpower = detail?.manpower ?? [];
  const workers = sum(manpower.map((m) => m.nos));
  const manHours = sum(manpower.map((m) => (m.nos ?? 0) * (m.workingHours ?? 0)));
  const dayCost =
    detail &&
    sum([
      ...(detail.manpower ?? []).map((r) => r.lineCost),
      ...(detail.equipment ?? []).map((r) => r.lineCost),
      ...(detail.materials ?? []).map((r) => r.lineCost),
      ...(detail.subContractors ?? []).map((r) => r.lineCost),
    ]);
  const length =
    row.chainageFromM != null && row.chainageToM != null
      ? Math.abs(row.chainageToM - row.chainageFromM)
      : null;

  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5">
      <Kpi
        accent
        label="Work Done"
        value={num(row.qtyExecuted ?? detail?.qtyExecuted)}
        unit={row.unit}
        sub="today"
      />
      <Kpi
        label="Cumulative"
        value={detail ? num(detail.cumulativeQty) : "…"}
        unit={row.unit}
        sub="to date"
      />
      <Kpi
        label="Workforce"
        value={detail ? num(workers, 0) : "…"}
        unit="ppl"
        sub={detail ? `${num(manHours, 1)} man-hours` : ""}
      />
      <Kpi
        label="Day Cost"
        value={detail ? money(dayCost ?? 0) : "…"}
        sub="all resources"
        valueMono={false}
      />
      <Kpi
        label="Chainage"
        value={length != null ? num(length, 0) : "—"}
        unit={length != null ? "m" : undefined}
        sub={
          row.chainageFromM != null || row.chainageToM != null
            ? `${chainageLabel(row.chainageFromM)} → ${chainageLabel(row.chainageToM)}`
            : ""
        }
      />
    </div>
  );
}

function Kpi({
  label,
  value,
  unit,
  sub,
  accent,
  valueMono = true,
}: {
  label: string;
  value: string;
  unit?: string;
  sub?: string;
  accent?: boolean;
  valueMono?: boolean;
}) {
  return (
    <div
      className={`rounded-2xl border p-4 ${accent ? "border-gold/30 bg-gold-tint" : "border-hairline bg-paper"}`}
    >
      <div className="text-[10px] font-bold uppercase tracking-wider text-slate">{label}</div>
      <div
        className={`mt-2 text-2xl font-bold tracking-tight ${valueMono ? "font-mono tabular-nums" : "font-display"} ${accent ? "text-gold-deep" : "text-charcoal"}`}
      >
        {value}
        {unit && <span className="ml-1 text-xs font-medium text-ash">{unit}</span>}
      </div>
      {sub && <div className="mt-1 text-[11px] font-medium text-ash">{sub}</div>}
    </div>
  );
}

// ─── Detail card (tabs) ─────────────────────────────────────────────────────────

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: "details", label: "Details" },
  { key: "manpower", label: "Manpower" },
  { key: "equipment", label: "Equipment" },
  { key: "material", label: "Material" },
  { key: "sub", label: "Sub-Contractor" },
  { key: "issues", label: "Issues" },
];

function DetailCard({
  row,
  detail,
  isLoading,
  isError,
  onRetry,
  tab,
  setTab,
  money,
}: {
  row: DprSummaryRow;
  detail?: DailyProgressReportResponse;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  tab: TabKey;
  setTab: (t: TabKey) => void;
  money: (n: number | null | undefined) => string;
}) {
  const liveIssues = (detail?.issues ?? []).filter((i) => i.status !== "CANCELLED");
  const counts: Record<TabKey, number | null> = {
    details: null,
    manpower: detail?.manpower?.length ?? row.manpowerNos ?? 0,
    equipment: detail?.equipment?.length ?? row.equipmentNos ?? 0,
    material: detail?.materials?.length ?? row.materialCount ?? 0,
    sub: detail?.subContractors?.length ?? 0,
    issues: detail ? liveIssues.length : row.issueCount ?? 0,
  };

  return (
    <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
      {/* Tab bar */}
      <div className="flex gap-1 overflow-x-auto border-b border-hairline px-2">
        {TABS.map((t) => {
          const active = tab === t.key;
          const count = counts[t.key];
          return (
            <button
              key={t.key}
              type="button"
              onClick={() => setTab(t.key)}
              className={`inline-flex shrink-0 items-center gap-2 whitespace-nowrap border-b-2 px-3 py-3 text-sm transition-colors ${
                active
                  ? "border-gold font-bold text-charcoal"
                  : "border-transparent font-semibold text-slate hover:text-charcoal"
              }`}
            >
              {t.label}
              {count != null && (
                <span
                  className={`rounded-full px-1.5 py-px font-mono text-[10px] font-bold ${
                    active
                      ? "bg-gold text-paper"
                      : count > 0
                        ? "bg-gold-tint text-gold-deep"
                        : "bg-parchment text-ash"
                  }`}
                >
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      <div className="p-5">
        {isError && (
          <div className="flex items-center gap-2 text-xs text-burgundy">
            Failed to load detail.
            <button
              type="button"
              onClick={onRetry}
              className="rounded border border-burgundy/30 px-2 py-0.5 font-semibold hover:bg-burgundy/10"
            >
              Retry
            </button>
          </div>
        )}

        {isLoading && !detail && (
          <div className="space-y-2">
            <div className="h-4 w-40 animate-pulse rounded bg-parchment" />
            <div className="h-20 animate-pulse rounded bg-parchment/60" />
            <div className="h-20 animate-pulse rounded bg-parchment/60" />
          </div>
        )}

        {tab === "details" && <DetailsTab row={row} detail={detail} money={money} />}
        {tab === "manpower" && <ManpowerTab detail={detail} money={money} />}
        {tab === "equipment" && <EquipmentTab detail={detail} money={money} />}
        {tab === "material" && <MaterialTab detail={detail} money={money} />}
        {tab === "sub" && <SubTab detail={detail} money={money} />}
        {tab === "issues" && <IssuesTab issues={liveIssues} hasDetail={!!detail} />}
      </div>
    </div>
  );
}

// ─── Details tab ────────────────────────────────────────────────────────────────

function Section({ color, title, children }: { color: string; title: string; children: ReactNode }) {
  return (
    <section>
      <div className="mb-4 flex items-center gap-2.5">
        <span className="h-1.5 w-1.5 rounded-sm" style={{ background: color }} />
        <h3 className="text-xs font-bold uppercase tracking-wider text-charcoal">{title}</h3>
      </div>
      {children}
    </section>
  );
}

function Field({ label, value, mono, muted }: { label: string; value: ReactNode; mono?: boolean; muted?: boolean }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-[10px] font-bold uppercase tracking-wider text-slate">{label}</span>
      <span
        className={`text-sm font-semibold ${mono ? "font-mono tabular-nums" : ""} ${muted ? "text-ash" : "text-charcoal"}`}
      >
        {value}
      </span>
    </div>
  );
}

function Grid({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-2 gap-x-7 gap-y-5 sm:grid-cols-4">{children}</div>;
}

function DetailsTab({
  row,
  detail,
  money,
}: {
  row: DprSummaryRow;
  detail?: DailyProgressReportResponse;
  money: (n: number | null | undefined) => string;
}) {
  const d = detail;
  const manHours = sum((d?.manpower ?? []).map((m) => (m.nos ?? 0) * (m.workingHours ?? 0)));
  const qty = d?.qtyExecuted ?? row.qtyExecuted ?? 0;
  const productivity = manHours > 0 ? qty / manHours : null;
  const dayCost = d
    ? sum([
        ...(d.manpower ?? []).map((r) => r.lineCost),
        ...(d.equipment ?? []).map((r) => r.lineCost),
        ...(d.materials ?? []).map((r) => r.lineCost),
        ...(d.subContractors ?? []).map((r) => r.lineCost),
      ])
    : null;
  const incidentOk = !d?.safetyIncidentType || d.safetyIncidentType === "NONE";

  return (
    <div className="flex flex-col gap-6">
      <Section color="#0058CA" title="Schedule & Conditions">
        <Grid>
          <Field label="Report Date" value={row.reportDate} />
          <Field label="Shift" value={d?.shift ? (d.shift === "DAY" ? "Day" : "Night") : "—"} />
          <Field label="Weather" value={d?.weatherCondition || row.weatherCondition || "—"} />
          <Field label="Start / End" value={`${d?.startTime || "—"} / ${d?.endTime || "—"}`} mono />
          <Field label="Supervisor" value={row.supervisorName || "—"} />
          <Field label="Contractor" value={d?.contractorName || "—"} />
        </Grid>
      </Section>

      <div className="h-px bg-hairline" />

      <Section color="#0FA3A3" title="Location & Activity">
        <Grid>
          <Field label="Activity" value={row.activityName || "—"} />
          <Field label="BOQ Item" value={row.boqItemNo || "—"} mono />
          <Field
            label="Chainage"
            value={`${chainageLabel(row.chainageFromM)} → ${chainageLabel(row.chainageToM)}`}
            mono
          />
          <Field label="Side" value={row.side ? SIDE_LABEL[row.side] ?? row.side : "—"} />
          <div className="col-span-2">
            <Field label="Landmark" value={d?.landmark || "—"} muted={!d?.landmark} />
          </div>
        </Grid>
      </Section>

      <div className="h-px bg-hairline" />

      <Section color="#0F8A4A" title="Progress & Cost">
        <Grid>
          <Field label="Qty Executed" value={`${num(qty)} ${row.unit}`} mono />
          <Field label="Cumulative" value={d ? `${num(d.cumulativeQty)} ${row.unit}` : "…"} mono />
          <Field
            label="Productivity"
            value={productivity != null ? `${num(productivity)} ${row.unit}/mh` : "—"}
            mono
          />
          <Field label="Day Cost" value={money(dayCost)} mono />
        </Grid>
      </Section>

      <div className="h-px bg-hairline" />

      <Section color="#C2392B" title="Safety, Delay & Remarks">
        <Grid>
          <div className="flex flex-col gap-2">
            <span className="text-[10px] font-bold uppercase tracking-wider text-slate">Safety Incident</span>
            {incidentOk ? (
              <span className="inline-flex items-center gap-1.5 self-start rounded-md border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-bold text-emerald-700">
                <Check className="h-3.5 w-3.5" /> No incidents
              </span>
            ) : (
              <Badge variant="danger" withDot>
                {d?.safetyIncidentType?.replace("_", " ")}
              </Badge>
            )}
          </div>
          <Field label="Safety Observation" value={d?.safetyObservation || "None recorded"} muted={!d?.safetyObservation} />
          <Field label="Delay Reason" value={d?.delayReason || "No delay"} muted={!d?.delayReason} />
          <div className="col-span-2 sm:col-span-4">
            <Field
              label="Remarks"
              value={d?.remarks || "No remarks were added to this report."}
              muted={!d?.remarks}
            />
          </div>
        </Grid>
      </Section>
    </div>
  );
}

// ─── Resource table ─────────────────────────────────────────────────────────────

function ResourceTable({
  headers,
  rows,
  footer,
}: {
  headers: Array<{ label: string; right?: boolean }>;
  rows: ReactNode[][];
  footer: ReactNode;
}) {
  return (
    <div className="overflow-hidden rounded-xl border border-hairline">
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead className="bg-ivory/70 text-[10px] uppercase tracking-wider text-slate">
            <tr>
              {headers.map((h, i) => (
                <th key={i} className={`px-3 py-2.5 font-bold ${h.right ? "text-right" : ""}`}>
                  {h.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="font-semibold text-charcoal">
            {rows.map((r, ri) => (
              <tr key={ri} className="border-t border-hairline">
                {r.map((c, ci) => (
                  <td
                    key={ci}
                    className={`px-3 py-3 ${headers[ci]?.right ? "text-right font-mono tabular-nums" : ""}`}
                  >
                    {c}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex items-center justify-between gap-3 border-t border-hairline bg-ivory/50 px-3 py-2.5 text-xs">
        {footer}
      </div>
    </div>
  );
}

function EmptyTab({ icon, title, sub }: { icon: ReactNode; title: string; sub: string }) {
  return (
    <div className="flex flex-col items-center justify-center px-5 py-14 text-center">
      <span className="mb-3.5 flex h-12 w-12 items-center justify-center rounded-xl bg-parchment text-ash">
        {icon}
      </span>
      <div className="text-sm font-bold text-charcoal">{title}</div>
      <div className="mt-1 text-xs text-ash">{sub}</div>
    </div>
  );
}

function ManpowerTab({
  detail,
  money,
}: {
  detail?: DailyProgressReportResponse;
  money: (n: number | null | undefined) => string;
}) {
  const rows = detail?.manpower ?? [];
  if (!detail) return null;
  if (rows.length === 0)
    return <EmptyTab icon={<Users className="h-5 w-5" />} title="No manpower recorded" sub="No crew was logged for this activity." />;
  const workers = sum(rows.map((r) => r.nos));
  const manHours = sum(rows.map((r) => (r.nos ?? 0) * (r.workingHours ?? 0)));
  const total = sum(rows.map((r) => r.lineCost));
  return (
    <ResourceTable
      headers={[
        { label: "Trade" },
        { label: "Shift" },
        { label: "Nos", right: true },
        { label: "Hours", right: true },
        { label: "OT", right: true },
        { label: "Rate", right: true },
        { label: "Line cost", right: true },
      ]}
      rows={rows.map((m) => [
        m.trade,
        m.shift ? (m.shift === "DAY" ? "Day" : "Night") : "—",
        num(m.nos, 0),
        num(m.workingHours, 1),
        m.otHours ? num(m.otHours, 1) : "—",
        m.unitRate != null ? money(m.unitRate) : "—",
        money(m.lineCost),
      ])}
      footer={
        <>
          <span className="font-semibold text-slate">
            {rows.length} trade{rows.length === 1 ? "" : "s"} · {num(workers, 0)} workers · {num(manHours, 1)} man-hours
          </span>
          <span className="font-mono font-bold text-charcoal">{money(total)}</span>
        </>
      }
    />
  );
}

function EquipmentTab({
  detail,
  money,
}: {
  detail?: DailyProgressReportResponse;
  money: (n: number | null | undefined) => string;
}) {
  const rows = detail?.equipment ?? [];
  if (!detail) return null;
  if (rows.length === 0)
    return <EmptyTab icon={<Truck className="h-5 w-5" />} title="No equipment recorded" sub="No plant or machinery was logged for this activity." />;
  const total = sum(rows.map((r) => r.lineCost));
  return (
    <ResourceTable
      headers={[
        { label: "Equipment" },
        { label: "Fleet #" },
        { label: "Nos", right: true },
        { label: "Hours", right: true },
        { label: "Rate", right: true },
        { label: "Line cost", right: true },
      ]}
      rows={rows.map((e) => [
        e.equipmentType,
        e.fleetNo || "—",
        num(e.nos, 0),
        num(e.workingHours, 1),
        e.unitRate != null ? money(e.unitRate) : "—",
        money(e.lineCost),
      ])}
      footer={
        <>
          <span className="font-semibold text-slate">{rows.length} item{rows.length === 1 ? "" : "s"}</span>
          <span className="font-mono font-bold text-charcoal">{money(total)}</span>
        </>
      }
    />
  );
}

function MaterialTab({
  detail,
  money,
}: {
  detail?: DailyProgressReportResponse;
  money: (n: number | null | undefined) => string;
}) {
  const rows = detail?.materials ?? [];
  if (!detail) return null;
  if (rows.length === 0)
    return <EmptyTab icon={<Package className="h-5 w-5" />} title="No materials recorded" sub="No material consumption was entered for this report." />;
  const total = sum(rows.map((r) => r.lineCost));
  return (
    <ResourceTable
      headers={[
        { label: "Material" },
        { label: "Unit" },
        { label: "Qty", right: true },
        { label: "Rate", right: true },
        { label: "Line cost", right: true },
      ]}
      rows={rows.map((m) => [
        m.materialName,
        m.unit || "—",
        num(m.quantity, 3),
        m.unitRate != null ? money(m.unitRate) : "—",
        money(m.lineCost),
      ])}
      footer={
        <>
          <span className="font-semibold text-slate">{rows.length} item{rows.length === 1 ? "" : "s"}</span>
          <span className="font-mono font-bold text-charcoal">{money(total)}</span>
        </>
      }
    />
  );
}

function SubTab({
  detail,
  money,
}: {
  detail?: DailyProgressReportResponse;
  money: (n: number | null | undefined) => string;
}) {
  const rows = detail?.subContractors ?? [];
  if (!detail) return null;
  if (rows.length === 0)
    return <EmptyTab icon={<Users className="h-5 w-5" />} title="No sub-contractors" sub="No sub-contractor work was recorded for this day." />;
  const total = sum(rows.map((r) => r.lineCost));
  return (
    <ResourceTable
      headers={[
        { label: "Sub-Contractor" },
        { label: "Unit" },
        { label: "Qty", right: true },
        { label: "Rate", right: true },
        { label: "Line cost", right: true },
      ]}
      rows={rows.map((s) => [
        s.subContractorName || "—",
        s.unit || "—",
        num(s.quantity, 3),
        s.ratePerUnit != null ? money(s.ratePerUnit) : "—",
        money(s.lineCost),
      ])}
      footer={
        <>
          <span className="font-semibold text-slate">{rows.length} sub-contractor{rows.length === 1 ? "" : "s"}</span>
          <span className="font-mono font-bold text-charcoal">{money(total)}</span>
        </>
      }
    />
  );
}

function IssuesTab({ issues, hasDetail }: { issues: DailyProgressReportResponse["issues"]; hasDetail: boolean }) {
  if (!hasDetail) return null;
  const list = issues ?? [];
  if (list.length === 0)
    return <EmptyTab icon={<Check className="h-5 w-5 text-emerald-600" />} title="No issues raised" sub="This report was filed with no open issues or blockers." />;
  return (
    <div className="overflow-hidden rounded-xl border border-hairline">
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead className="bg-ivory/70 text-[10px] uppercase tracking-wider text-slate">
            <tr>
              <th className="px-3 py-2.5 font-bold">Title</th>
              <th className="px-3 py-2.5 font-bold">Reason</th>
              <th className="px-3 py-2.5 font-bold">Severity</th>
              <th className="px-3 py-2.5 font-bold">Status</th>
              <th className="px-3 py-2.5 font-bold">Assigned</th>
            </tr>
          </thead>
          <tbody className="font-semibold text-charcoal">
            {list.map((i) => (
              <tr key={i.id ?? i.title} className="border-t border-hairline">
                <td className="px-3 py-3">{i.title}</td>
                <td className="px-3 py-3">{categoryLabel(i.category)}</td>
                <td className="px-3 py-3"><Badge variant={SEVERITY_VARIANT[i.severity]}>{i.severity}</Badge></td>
                <td className="px-3 py-3"><Badge variant={ISSUE_STATUS_VARIANT[i.status]} withDot>{i.status}</Badge></td>
                <td className="px-3 py-3">{i.assignedToName ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ─── Media row (photos + voice) ─────────────────────────────────────────────────

function MediaRow({ projectId, detail }: { projectId: string; detail: DailyProgressReportResponse }) {
  const photos = detail.attachments ?? [];
  const voice = detail.voiceNotes ?? [];
  if (photos.length === 0 && voice.length === 0) return null;

  return (
    <div className="grid gap-3 md:grid-cols-[1.55fr_1fr]">
      {photos.length > 0 && <PhotosPanel projectId={projectId} dprId={detail.id} photos={photos} />}
      {voice.length > 0 && (
        <div className="rounded-2xl border border-hairline bg-paper p-5">
          <PanelHeader
            icon={<AudioLines className="h-4 w-4" />}
            title="Voice Notes"
            sub={`${voice.length} recording${voice.length === 1 ? "" : "s"}`}
          />
          <div className="mt-4 grid grid-cols-1 gap-2">
            {voice.map((n) => (
              <VoiceNotePlayer key={n.id} projectId={projectId} dprId={detail.id} note={n} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function PanelHeader({ icon, title, sub }: { icon: ReactNode; title: string; sub: string }) {
  return (
    <div className="flex items-center gap-3">
      <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-gold-tint text-gold-deep">
        {icon}
      </span>
      <div>
        <div className="text-sm font-bold text-charcoal">{title}</div>
        <div className="text-xs text-ash">{sub}</div>
      </div>
    </div>
  );
}

function PhotosPanel({
  projectId,
  dprId,
  photos,
}: {
  projectId: string;
  dprId: string;
  photos: NonNullable<DailyProgressReportResponse["attachments"]>;
}) {
  const [urls, setUrls] = useState<Record<string, string>>({});
  const [lb, setLb] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    const created: string[] = [];
    photos.forEach((p) => {
      dprApi
        .fetchPhotoBlobUrl(projectId, dprId, p.id)
        .then((url) => {
          if (cancelled) URL.revokeObjectURL(url);
          else {
            created.push(url);
            setUrls((m) => ({ ...m, [p.id]: url }));
          }
        })
        .catch(() => {});
    });
    return () => {
      cancelled = true;
      created.forEach((u) => URL.revokeObjectURL(u));
    };
  }, [projectId, dprId, photos]);

  const close = () => setLb(null);
  const prev = () => setLb((i) => (i == null ? i : (i + photos.length - 1) % photos.length));
  const next = () => setLb((i) => (i == null ? i : (i + 1) % photos.length));
  const active = lb != null ? photos[lb] : null;

  return (
    <div className="rounded-2xl border border-hairline bg-paper p-5">
      <div className="flex items-center justify-between gap-3">
        <PanelHeader icon={<ImageIcon className="h-4 w-4" />} title="Site Photos" sub={`${photos.length} image${photos.length === 1 ? "" : "s"}`} />
        <span className="rounded-md bg-parchment px-2.5 py-1 text-xs font-semibold text-slate">Tap to enlarge</span>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3">
        {photos.map((p, i) => (
          <button
            key={p.id}
            type="button"
            onClick={() => setLb(i)}
            className="group relative aspect-[4/3] overflow-hidden rounded-xl border border-hairline bg-parchment"
          >
            {urls[p.id] ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={urls[p.id]} alt={p.caption ?? p.fileName} className="h-full w-full object-cover" />
            ) : (
              <span className="flex h-full w-full items-center justify-center text-ash">
                <ImageIcon className="h-6 w-6" />
              </span>
            )}
            {p.caption && (
              <span className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-charcoal/85 to-transparent px-2.5 py-2 text-left text-[11px] font-semibold text-paper">
                <span className="line-clamp-1">{p.caption}</span>
              </span>
            )}
          </button>
        ))}
      </div>

      {active && (
        <div
          onClick={close}
          className="fixed inset-0 z-[60] flex flex-col items-center justify-center gap-3 bg-charcoal/85 p-6 backdrop-blur-sm"
        >
          <div
            className="flex w-full max-w-3xl items-center justify-between text-paper"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="min-w-0">
              <div className="truncate text-sm font-bold">{active.caption ?? active.fileName}</div>
              <div className="font-mono text-xs text-parchment/70">
                {active.fileName}
                {active.capturedAt && <span> · {fmtDateTime(active.capturedAt)}</span>}
              </div>
            </div>
            <button
              type="button"
              onClick={close}
              aria-label="Close"
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-paper/20 bg-paper/10 text-paper"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
          <div
            className="relative flex w-full max-w-3xl items-center justify-center overflow-hidden rounded-xl bg-charcoal"
            style={{ aspectRatio: "16 / 10" }}
            onClick={(e) => e.stopPropagation()}
          >
            {urls[active.id] ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={urls[active.id]} alt={active.caption ?? active.fileName} className="max-h-full max-w-full object-contain" />
            ) : (
              <span className="text-parchment/50"><ImageIcon className="h-12 w-12" /></span>
            )}
            {photos.length > 1 && (
              <>
                <button
                  type="button"
                  onClick={prev}
                  aria-label="Previous"
                  className="absolute left-3 top-1/2 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full bg-charcoal/50 text-paper"
                >
                  <ChevronLeft className="h-5 w-5" />
                </button>
                <button
                  type="button"
                  onClick={next}
                  aria-label="Next"
                  className="absolute right-3 top-1/2 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full bg-charcoal/50 text-paper"
                >
                  <ChevronRight className="h-5 w-5" />
                </button>
              </>
            )}
          </div>
          <div className="font-mono text-xs text-parchment/70">{(lb ?? 0) + 1} / {photos.length}</div>
        </div>
      )}
    </div>
  );
}

// ─── Approval trail ─────────────────────────────────────────────────────────────

function ApprovalTrail({
  detail,
  status,
}: {
  detail: DailyProgressReportResponse;
  status: DprApprovalStatus;
}) {
  const terminal =
    status === "APPROVED"
      ? { label: "Approved", at: detail.approvedAt, tone: "text-emerald-700", chip: "border-emerald-200 bg-emerald-50 text-emerald-700", dot: "bg-emerald-500" }
      : status === "REJECTED"
        ? { label: "Rejected", at: detail.rejectedAt, tone: "text-burgundy", chip: "border-burgundy/25 bg-burgundy/10 text-burgundy", dot: "bg-burgundy" }
        : null;

  return (
    <div className="rounded-2xl border border-hairline bg-paper p-5">
      <div className="mb-4 flex items-center gap-2.5">
        <span className="h-1.5 w-1.5 rounded-sm bg-emerald-500" />
        <h3 className="text-xs font-bold uppercase tracking-wider text-charcoal">Approval Trail</h3>
      </div>
      <div className="flex flex-wrap items-center gap-4">
        <TrailStep
          dot="bg-gold-tint text-gold-deep"
          icon="↑"
          label="Submitted"
          at={detail.submittedAt}
        />
        {terminal && (
          <>
            <ChevronRight className="h-4 w-4 text-ash" />
            <TrailStep dot={`${terminal.dot} text-paper`} icon="✓" label={terminal.label} at={terminal.at} tone={terminal.tone} />
          </>
        )}
        <div className="ml-auto text-right">
          <div className="text-[10px] font-bold uppercase tracking-wider text-slate">Current status</div>
          <div className="mt-1.5 inline-flex">
            <Badge variant={STATUS_VARIANT[status]} withDot>{status}</Badge>
          </div>
        </div>
      </div>
      {status === "REJECTED" && detail.rejectionReason && (
        <div className="mt-4 rounded-md border border-burgundy/25 bg-burgundy/5 px-3 py-2 text-xs text-charcoal">
          <span className="font-semibold text-burgundy">Reason: </span>
          {detail.rejectionReason}
        </div>
      )}
    </div>
  );
}

function TrailStep({
  dot,
  icon,
  label,
  at,
  tone,
}: {
  dot: string;
  icon: string;
  label: string;
  at: string | null | undefined;
  tone?: string;
}) {
  return (
    <div className="flex items-start gap-3">
      <span className={`flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold ${dot}`}>{icon}</span>
      <div>
        <div className={`text-sm font-bold ${tone ?? "text-charcoal"}`}>{label}</div>
        <div className="mt-0.5 font-mono text-[11px] text-ash">{fmtDateTime(at)}</div>
      </div>
    </div>
  );
}

// ─── Voice-note player (auth blob → object URL) ─────────────────────────────────

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
    <div className="overflow-hidden rounded-xl border border-hairline bg-ivory/40">
      <div className="flex items-center gap-1.5 border-b border-hairline px-3 py-2 text-xs text-charcoal">
        <AudioLines className="h-3.5 w-3.5 shrink-0 text-gold" />
        <span className="truncate" title={note.fileName}>{note.fileName}</span>
        {note.durationSeconds != null && <span className="shrink-0 text-ash">· {note.durationSeconds}s</span>}
      </div>
      {src && !failed ? (
        <>
          <audio controls src={src} onError={() => setPlayError(true)} className="w-full px-2 py-2" />
          {playError && (
            <div className="px-2 pb-2 text-center text-xs text-slate">
              Your browser can&apos;t play this audio format.{" "}
              <a href={src} download={note.fileName} className="font-semibold text-gold underline">Download</a> instead.
            </div>
          )}
        </>
      ) : (
        <div className="px-2 py-3 text-center text-xs text-slate">{failed ? "Failed to load" : "Loading…"}</div>
      )}
      {note.caption && (
        <div className="border-t border-hairline px-3 py-2 text-xs text-charcoal">
          <span className="line-clamp-2">{note.caption}</span>
        </div>
      )}
    </div>
  );
}
