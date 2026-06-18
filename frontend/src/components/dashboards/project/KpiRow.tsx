"use client";

import { TrendingUp, Wallet, CheckCircle2, AlertOctagon } from "lucide-react";
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
  criticalIssueCount: number;
}

export function KpiRow({
  snapshot,
  deltas,
  tasks,
  tasksDelta,
  criticalIssueCount,
}: KpiRowProps) {
  // bacCrores is the BAC in crore units (raw ÷ 1e7); recover raw and render in the
  // project currency. Optional hook + INR fallback so this is safe if the row is
  // ever rendered outside a project route.
  const cur = useProjectCurrencyOptional();
  const moneyCompact = cur
    ? cur.moneyCompact
    : (v: number | null | undefined) => formatMoney(v, { code: "INR" }, { compact: true });
  const isIndian = cur?.isIndian ?? true;
  const physicalPct = snapshot?.physicalPct ?? 0;
  const bacCrores = snapshot?.bacCrores ?? 0;
  const activeRisks = snapshot?.activeRisksCount ?? 0;

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <KpiTile
        label="Overall Progress"
        value={`${physicalPct.toFixed(0)}%`}
        tone="success"
        icon={<TrendingUp size={14} />}
        delta={formatDelta(physicalPct, deltas?.physicalPctDelta, {
          unit: "%",
          digits: 1,
        })}
      />
      <KpiTile
        label="Budget Utilised"
        value={moneyCompact(bacCrores * 1e7)}
        tone="accent"
        icon={<Wallet size={14} />}
        delta={formatDelta(
          bacCrores * (isIndian ? 1 : 10),
          deltas?.bacCroresDelta != null
            ? deltas.bacCroresDelta * (isIndian ? 1 : 10)
            : deltas?.bacCroresDelta,
          {
            // INR shows the delta in crores; other currencies in millions
            // (1 crore = 10 million), so the suffix matches the value's scale.
            unit: isIndian ? " Cr" : " M",
            digits: 1,
            invertColor: true,
          }
        )}
      />
      <KpiTile
        label="Tasks Completed"
        value={`${tasks.done}`}
        hint={`of ${tasks.total} total`}
        tone="default"
        icon={<CheckCircle2 size={14} />}
        delta={formatDelta(tasks.done, tasksDelta, { unit: "", digits: 0 })}
      />
      <KpiTile
        label="Open Issues"
        value={`${criticalIssueCount}`}
        hint={criticalIssueCount === 1 ? "critical" : "critical"}
        tone="danger"
        icon={<AlertOctagon size={14} />}
        delta={formatDelta(activeRisks, deltas?.activeRisksDelta, {
          unit: "",
          digits: 0,
          invertColor: true,
        })}
      />
    </div>
  );
}
