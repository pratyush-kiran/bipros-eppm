"use client";

import { TrendingUp, Wallet, CheckCircle2, AlertOctagon } from "lucide-react";
import { format, parseISO } from "date-fns";
import { KpiTile } from "@/components/common/KpiTile";
import { formatDelta } from "./dashboardDerivations";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";
import type {
  ProjectStatusSnapshot,
  SnapshotDeltas,
} from "@/lib/api/projectInsightsApi";

interface KpiRowProps {
  snapshot: ProjectStatusSnapshot | null | undefined;
  deltas: SnapshotDeltas | null | undefined;
  tasks: { done: number; total: number };
  tasksDelta: number | null | undefined;
  openIssueCount: number;
  criticalIssueCount: number;
  onTasksClick?: () => void;
  onIssuesClick?: () => void;
}

export function KpiRow({
  snapshot,
  deltas,
  tasks,
  tasksDelta,
  openIssueCount,
  criticalIssueCount,
  onTasksClick,
  onIssuesClick,
}: KpiRowProps) {
  // acCrores is the Actual Cost in crore units (raw ÷ 1e7); recover raw and render in the
  // project currency. Optional hook + INR fallback so this is safe if the row is
  // ever rendered outside a project route.
  const cur = useProjectCurrencyOptional();
  const moneyCompact = cur
    ? cur.moneyCompact
    : (v: number | null | undefined) => formatMoney(v, { code: "INR" }, { compact: true });
  const isIndian = cur?.isIndian ?? true;
  const physicalPct = snapshot?.physicalPct ?? 0;
  const acCrores = snapshot?.acCrores ?? 0;

  // parseISO keeps a date-only string (e.g. "2026-05-31") in local time; `new Date(...)` would
  // treat it as UTC midnight and render the prior day in negative-UTC-offset timezones.
  const asOfHint = snapshot?.dataDate
    ? `as of ${format(parseISO(snapshot.dataDate), "d MMM yyyy")}`
    : undefined;

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <KpiTile
        label="Overall Progress"
        value={`${Math.min(physicalPct, 100).toFixed(0)}%`}
        valueTitle={physicalPct > 100 ? `Earned ${physicalPct.toFixed(0)}% — capped at 100%` : undefined}
        tone="success"
        icon={<TrendingUp size={14} />}
        delta={formatDelta(physicalPct, deltas?.physicalPctDelta, {
          unit: "%",
          digits: 1,
        })}
        hint={asOfHint}
      />
      <KpiTile
        label="Budget Utilised"
        value={moneyCompact(acCrores * 1e7)}
        tone="accent"
        icon={<Wallet size={14} />}
        delta={formatDelta(acCrores * (isIndian ? 1 : 10), deltas?.acCroresDelta ?? null, {
          unit: isIndian ? " Cr" : " M",
          digits: 1,
        })}
      />
      <KpiTile
        label="Tasks Completed"
        value={`${tasks.done}`}
        hint={`of ${tasks.total} total`}
        tone="default"
        icon={<CheckCircle2 size={14} />}
        delta={formatDelta(tasks.done, tasksDelta, { unit: "", digits: 0 })}
        onClick={onTasksClick}
      />
      <KpiTile
        label="Open Issues"
        value={`${openIssueCount}`}
        hint={`${criticalIssueCount} critical`}
        tone="danger"
        icon={<AlertOctagon size={14} />}
        onClick={onIssuesClick}
      />
    </div>
  );
}
