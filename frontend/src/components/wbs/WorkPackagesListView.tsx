"use client";

import { Fragment, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ChevronRight, Download, Filter, Group, Package, Plus, SortAsc } from "lucide-react";

import { StatusBadge } from "@/components/common/StatusBadge";
import {
  workPackageApi,
  type WorkPackageRowResponse,
} from "@/lib/api/workPackageApi";
import { formatBudget } from "@/lib/utils/format";

type GroupKey = "none" | "phase" | "parent" | "contractor";

interface WorkPackagesListViewProps {
  projectId: string;
}

/**
 * Flat list of every leaf WBS / work package for a project, with activity-rolled metrics
 * (counts, weighted progress, days behind, SPI/CPI, float, derived status).
 *
 * Mounted inside the WBS tab as the "List" alternative to the tree view. Owns its own
 * React Query call against {@code /v1/projects/{id}/work-packages} so the tab can swap
 * between tree and list without re-fetching anything.
 */
export function WorkPackagesListView({ projectId }: WorkPackagesListViewProps) {
  const router = useRouter();
  const [groupBy, setGroupBy] = useState<GroupKey>("none");

  const { data, isLoading, error } = useQuery({
    queryKey: ["work-packages", projectId],
    queryFn: () => workPackageApi.listWorkPackages(projectId),
  });

  const rows = useMemo(() => data?.data ?? [], [data]);

  const counts = useMemo(() => {
    const c = { total: rows.length, done: 0, inProgress: 0, planned: 0, notStarted: 0, delayed: 0, atRisk: 0 };
    for (const r of rows) {
      switch (r.derivedStatus) {
        case "DONE": c.done++; break;
        case "IN_PROGRESS": c.inProgress++; break;
        case "PLANNED": c.planned++; break;
        case "NOT_STARTED": c.notStarted++; break;
        case "DELAYED": c.delayed++; break;
        case "AT_RISK": c.atRisk++; break;
      }
    }
    return c;
  }, [rows]);

  const grouped = useMemo(() => buildGroups(rows, groupBy), [rows, groupBy]);

  if (isLoading) {
    return <div className="text-sm text-text-muted">Loading work packages…</div>;
  }
  if (error) {
    return (
      <div className="text-sm text-danger">
        Failed to load work packages. {(error as Error).message}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <Toolbar groupBy={groupBy} onGroupChange={setGroupBy} counts={counts} />

      {rows.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="overflow-hidden rounded-xl border border-hairline bg-paper">
          <div className="overflow-auto">
            <table className="w-full border-collapse text-sm">
              <thead className="border-b border-hairline bg-ivory dark:bg-[#161616]">
                <tr>
                  {[
                    "#",
                    "Work Package / Task",
                    "Contractor",
                    "Start",
                    "Dur",
                    "Progress",
                    "Budget",
                    "Activities",
                    "SPI",
                    "CPI",
                    "Days Behind",
                    "Float",
                    "Status",
                    "",
                  ].map((h) => (
                    <th
                      key={h}
                      className="px-3 py-3 text-left text-[11px] font-semibold uppercase tracking-[0.10em] text-slate whitespace-nowrap dark:text-[#A1A1A6]"
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {grouped.map((g) => (
                  <Fragment key={g.key}>
                    {g.label !== null && (
                      <tr className="bg-ivory/60 dark:bg-[#1a1a1a]">
                        <td
                          colSpan={14}
                          className="px-3 py-2 text-[11px] font-semibold uppercase tracking-[0.10em] text-slate dark:text-[#A1A1A6]"
                        >
                          {g.label}{" "}
                          <span className="ml-2 text-[10px] text-slate/70">
                            ({g.rows.length})
                          </span>
                        </td>
                      </tr>
                    )}
                    {g.rows.map((row) => (
                      <WorkPackageRow
                        key={row.wbsNodeId}
                        row={row}
                        onOpenActivities={() =>
                          router.push(
                            `/projects/${projectId}/activities?viewMode=tree&wbsNodeId=${row.wbsNodeId}`,
                          )
                        }
                      />
                    ))}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Toolbar with summary chips
// ──────────────────────────────────────────────────────────────────────────────

function Toolbar({
  groupBy,
  onGroupChange,
  counts,
}: {
  groupBy: GroupKey;
  onGroupChange: (g: GroupKey) => void;
  counts: { total: number; done: number; inProgress: number; planned: number; notStarted: number; delayed: number; atRisk: number };
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <button
        type="button"
        className="inline-flex items-center gap-1.5 rounded-md bg-amber-flame px-3 py-1.5 text-sm font-semibold text-white hover:bg-amber-flame/90"
        title="New work package — wired to the WBS tree (creation UX TBD)"
      >
        <Plus size={14} /> New Package
      </button>
      <ToolbarButton icon={<Filter size={14} />} label="Filter" disabled />
      <ToolbarButton icon={<SortAsc size={14} />} label="Sort" disabled />
      <div className="flex items-center gap-1">
        <span className="inline-flex h-7 items-center gap-1.5 rounded-md border border-hairline bg-paper px-2 text-xs text-slate">
          <Group size={12} /> Group by
        </span>
        <select
          value={groupBy}
          onChange={(e) => onGroupChange(e.target.value as GroupKey)}
          className="h-7 rounded-md border border-hairline bg-paper px-2 text-xs text-charcoal focus:outline-none focus:ring-1 focus:ring-gold/40"
        >
          <option value="none">— none —</option>
          <option value="phase">Phase</option>
          <option value="parent">Parent WBS</option>
          <option value="contractor">Contractor</option>
        </select>
      </div>
      <ToolbarButton icon={<Download size={14} />} label="Export" disabled />

      <div className="ml-auto flex flex-wrap items-center gap-1.5">
        <Chip label="TOTAL" value={counts.total} tone="default" />
        <Chip label="DONE" value={counts.done} tone="success" />
        <Chip label="IN PROGRESS" value={counts.inProgress} tone="accent" />
        <Chip label="PLANNED" value={counts.planned + counts.notStarted} tone="default" />
        <Chip label="AT RISK" value={counts.atRisk} tone="warning" />
        <Chip label="DELAYED" value={counts.delayed} tone="danger" />
      </div>
    </div>
  );
}

function ToolbarButton({ icon, label, disabled }: { icon: React.ReactNode; label: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={disabled ? "Coming soon" : label}
      className="inline-flex h-7 items-center gap-1.5 rounded-md border border-hairline bg-paper px-2.5 text-xs text-charcoal hover:bg-gold-tint/20 disabled:cursor-not-allowed disabled:opacity-50"
    >
      {icon} {label}
    </button>
  );
}

function Chip({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: "default" | "success" | "warning" | "danger" | "accent";
}) {
  const toneCls: Record<typeof tone, string> = {
    default: "border-hairline bg-paper text-charcoal",
    success: "border-success/30 bg-success/10 text-success",
    warning: "border-warning/40 bg-warning/12 text-warning",
    danger: "border-danger/30 bg-danger/10 text-danger",
    accent: "border-accent/30 bg-accent/10 text-accent",
  };
  return (
    <span
      className={`inline-flex h-7 items-center gap-1 rounded-full border px-3 text-[11px] font-semibold uppercase tracking-wider ${toneCls[tone]}`}
    >
      {label}: <span className="tabular-nums">{value}</span>
    </span>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Row
// ──────────────────────────────────────────────────────────────────────────────

function WorkPackageRow({
  row,
  onOpenActivities,
}: {
  row: WorkPackageRowResponse;
  onOpenActivities: () => void;
}) {
  const pct = row.weightedPercentComplete;
  const tone: "danger" | "warning" | "default" | "success" =
    row.derivedStatus === "DELAYED"
      ? "danger"
      : row.derivedStatus === "AT_RISK"
        ? "warning"
        : row.derivedStatus === "DONE"
          ? "success"
          : "default";

  return (
    <tr className="border-b border-hairline/50 transition-colors hover:bg-gold-tint/10 dark:hover:bg-gold-tint/5">
      <td className="px-3 py-2 font-mono text-[12px] font-semibold tracking-tight text-slate whitespace-nowrap">
        {row.code}
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <div className="font-medium text-charcoal dark:text-[#F5F2E8]">{row.name}</div>
        {row.parentName && (
          <div className="text-[11px] text-slate/80">{row.parentName}</div>
        )}
      </td>
      <td className="px-3 py-2 text-slate whitespace-nowrap">{row.contractorName ?? "—"}</td>
      <td className="px-3 py-2 text-slate whitespace-nowrap">{formatShortDate(row.derivedPlannedStart)}</td>
      <td className="px-3 py-2 text-slate whitespace-nowrap">
        {row.derivedDurationDays != null ? `${row.derivedDurationDays}d` : "—"}
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <ProgressBar pct={pct} tone={tone} />
      </td>
      <td className="px-3 py-2 whitespace-nowrap text-charcoal dark:text-[#F5F2E8] tabular-nums">
        {row.budgetCrores != null ? formatBudget(row.budgetCrores, "INR") : "—"}
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <ActivityCounts row={row} />
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <PerfIndex value={row.spi} />
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <PerfIndex value={row.cpi} />
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <DaysBehind value={row.daysBehindSchedule} />
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <Float value={row.minTotalFloat} critical={row.onCriticalPath ?? false} />
      </td>
      <td className="px-3 py-2 whitespace-nowrap">
        <StatusBadge status={row.derivedStatus} variant="gantt" />
      </td>
      <td className="px-3 py-2 whitespace-nowrap text-right">
        <button
          type="button"
          onClick={onOpenActivities}
          title="Open activities for this work package"
          className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-hairline text-slate hover:bg-gold-tint/20"
        >
          <ChevronRight size={14} />
        </button>
      </td>
    </tr>
  );
}

function ProgressBar({ pct, tone }: { pct: number | null; tone: "default" | "success" | "warning" | "danger" }) {
  const clamped = Math.max(0, Math.min(100, pct ?? 0));
  const barCls =
    tone === "danger"
      ? "from-burgundy to-burgundy/70"
      : tone === "warning"
        ? "from-amber-flame to-amber-flame/70"
        : tone === "success"
          ? "from-emerald to-emerald/70"
          : "from-gold to-gold-deep";
  return (
    <div className="flex w-[130px] items-center gap-2">
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-ivory">
        <div
          className={`h-full rounded-full bg-gradient-to-r ${barCls}`}
          style={{ width: `${clamped}%` }}
        />
      </div>
      <span className="w-9 text-right text-[11px] font-semibold tabular-nums text-charcoal dark:text-[#F5F2E8]">
        {pct == null ? "—" : `${Math.round(clamped)}%`}
      </span>
    </div>
  );
}

function ActivityCounts({ row }: { row: WorkPackageRowResponse }) {
  const total = row.activityCountTotal ?? 0;
  const done = row.activityCountDone ?? 0;
  const inProgress = row.activityCountInProgress ?? 0;
  const delayed = row.activityCountDelayed ?? 0;

  if (total === 0) {
    return <span className="text-slate/70">—</span>;
  }
  return (
    <div className="flex items-center gap-1.5">
      <span className="font-medium tabular-nums text-charcoal dark:text-[#F5F2E8]">
        {done}/{total}
      </span>
      {inProgress > 0 && (
        <span
          title={`${inProgress} in progress`}
          className="inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-accent/15 px-1.5 text-[10px] font-semibold text-accent tabular-nums"
        >
          {inProgress}
        </span>
      )}
      {delayed > 0 && (
        <span
          title={`${delayed} delayed`}
          className="inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-danger/15 px-1.5 text-[10px] font-semibold text-danger tabular-nums"
        >
          {delayed}
        </span>
      )}
    </div>
  );
}

function PerfIndex({ value }: { value: number | null }) {
  if (value == null) return <span className="text-slate/70">—</span>;
  const cls =
    value >= 1
      ? "text-success"
      : value >= 0.9
        ? "text-warning"
        : "text-danger";
  return (
    <span className={`font-semibold tabular-nums ${cls}`}>{value.toFixed(2)}</span>
  );
}

function DaysBehind({ value }: { value: number | null }) {
  if (value == null) return <span className="text-slate/70">—</span>;
  if (value <= 0) {
    return <span className="text-[12px] font-medium text-success">On time</span>;
  }
  return (
    <span className="text-[12px] font-semibold text-danger tabular-nums">
      +{value}d
    </span>
  );
}

function Float({ value, critical }: { value: number | null; critical: boolean }) {
  if (value == null) return <span className="text-slate/70">—</span>;
  if (critical) {
    return (
      <span className="inline-flex items-center gap-1 rounded-md bg-danger/10 px-1.5 py-0.5 text-[11px] font-semibold text-danger">
        Critical
      </span>
    );
  }
  return (
    <span className="tabular-nums text-charcoal dark:text-[#F5F2E8]">
      {value.toFixed(1)}d
    </span>
  );
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

function formatShortDate(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(`${iso}T00:00:00`);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "2-digit" });
}

type Bucket = { key: string; label: string | null; rows: WorkPackageRowResponse[] };

function buildGroups(rows: WorkPackageRowResponse[], groupBy: GroupKey): Bucket[] {
  if (groupBy === "none") {
    return [{ key: "all", label: null, rows }];
  }
  const map = new Map<string, Bucket>();
  for (const row of rows) {
    const labelRaw =
      groupBy === "phase"
        ? row.phase ?? "No phase"
        : groupBy === "parent"
          ? row.parentName ?? "No parent"
          : row.contractorName ?? "Unassigned";
    const label = String(labelRaw);
    const bucket = map.get(label) ?? { key: label, label, rows: [] };
    bucket.rows.push(row);
    map.set(label, bucket);
  }
  return Array.from(map.values()).sort((a, b) =>
    (a.label ?? "").localeCompare(b.label ?? ""),
  );
}

function EmptyState() {
  return (
    <div className="rounded-xl border border-dashed border-hairline bg-paper p-10 text-center">
      <Package size={36} className="mx-auto text-slate/40" />
      <p className="mt-3 text-sm text-text-secondary">
        No work packages yet. Switch to the Tree view to add WBS nodes — leaf nodes will appear here once they exist.
      </p>
    </div>
  );
}
