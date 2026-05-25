"use client";

import { TrendingUp, IndianRupee, CheckCircle2, AlertOctagon } from "lucide-react";
import { KpiTile } from "@/components/common/KpiTile";
import { formatDelta } from "./dashboardDerivations";
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
        value={`₹${bacCrores.toFixed(1)} Cr`}
        tone="accent"
        icon={<IndianRupee size={14} />}
        delta={formatDelta(bacCrores, deltas?.bacCroresDelta, {
          unit: " Cr",
          digits: 1,
          invertColor: true,
        })}
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
