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

  return (
    <section
      aria-label="Project sections"
      className="grid grid-cols-2 gap-4 max-[900px]:grid-cols-1"
    >
      <PlanCard projectId={projectId} />
      <ExecuteCard projectId={projectId} />
      {canReadCost ? <CostFinanceCard projectId={projectId} /> : null}
      {canReadRisk ? <RiskIssuesCard projectId={projectId} /> : null}
      <PeopleCapacityCard projectId={projectId} />
      <ScheduleAnalysisCard projectId={projectId} />
      <InsightsMapCard projectId={projectId} />
    </section>
  );
}
