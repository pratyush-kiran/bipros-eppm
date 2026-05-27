"use client";
import { useAuthStore } from "@/lib/state/store";
import { PlanCard } from "./cards/PlanCard";
import { ExecuteCard } from "./cards/ExecuteCard";
import { CostFinanceCard } from "./cards/CostFinanceCard";
import { RiskIssuesCard } from "./cards/RiskIssuesCard";
import { PeopleCapacityCard } from "./cards/PeopleCapacityCard";
import { ScheduleAnalysisCard } from "./cards/ScheduleAnalysisCard";
import { InsightsMapCard } from "./cards/InsightsMapCard";

/**
 * Phase C orchestrator — renders the seven bento group cards on a uniform
 * 2-column grid (single column under 900px). Every card has equal width so
 * the hub feels balanced even when the per-card content differs.
 *
 * Permission gates: Cost & Finance is hidden for users without {@code COST.READ};
 * Risk & Issues for users without {@code RISK.READ}. Cards self-disable
 * permission-gated section chips inside their footers via {@link CardShell}.
 */
export function BentoGrid({ projectId }: { projectId: string }) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canReadCost = hasPermission("COST.READ");
  const canReadRisk = hasPermission("RISK.READ");

  // Asymmetric bento: 1 col (mobile) → 2 (tablet) → 3 (desktop). Cost & Finance
  // and Schedule Analysis are wide "feature" cards (col-span-2 on xl); the rest
  // are standard. `grid-auto-flow:dense` backfills gaps when a permission-gated
  // card is hidden. Each wrapper forces its card's <article> to fill the cell
  // so rows stay flush even with varied content heights.
  return (
    <section
      aria-label="Project sections"
      className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3 xl:[grid-auto-flow:dense]"
    >
      {canReadCost ? (
        <div className="xl:col-span-2 [&>article]:h-full">
          <CostFinanceCard projectId={projectId} />
        </div>
      ) : null}
      <div className="[&>article]:h-full">
        <PlanCard projectId={projectId} />
      </div>
      <div className="[&>article]:h-full">
        <ExecuteCard projectId={projectId} />
      </div>
      {canReadRisk ? (
        <div className="[&>article]:h-full">
          <RiskIssuesCard projectId={projectId} />
        </div>
      ) : null}
      <div className="[&>article]:h-full">
        <PeopleCapacityCard projectId={projectId} />
      </div>
      <div className="xl:col-span-2 [&>article]:h-full">
        <ScheduleAnalysisCard projectId={projectId} />
      </div>
      <div className="[&>article]:h-full">
        <InsightsMapCard projectId={projectId} />
      </div>
    </section>
  );
}
