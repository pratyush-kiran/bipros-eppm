"use client";
import { useQuery } from "@tanstack/react-query";
import { riskApi, type RiskRag } from "@/lib/api/riskApi";
import { useAuthStore } from "@/lib/state/store";
import { CardShell } from "./CardShell";
import { Skeleton, StatusPill, cardQueryOpts } from "./_shared";

/**
 * Risk & Issues card — top 3 open risks ranked by RAG severity.
 *
 * Gated by RISK.READ at BentoGrid level; we also guard the query with
 * {@code enabled} so unauthorised users don't fire a request.
 */
export function RiskIssuesCard({ projectId }: { projectId: string }) {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canReadRisk = hasPermission("RISK.READ");

  // Same key as useHubBadges → shared cache
  const risksQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "risks-open"],
    queryFn: () => riskApi.listRisks(projectId, "OPEN"),
    enabled: canReadRisk,
    ...cardQueryOpts,
  });

  const risks = risksQuery.data?.data ?? [];
  const ragOrder: Record<RiskRag, number> = {
    CRIMSON: 0,
    RED: 1,
    AMBER: 2,
    GREEN: 3,
    OPPORTUNITY: 4,
  };
  const top = [...risks]
    .sort((a, b) => (ragOrder[a.rag] ?? 99) - (ragOrder[b.rag] ?? 99))
    .slice(0, 3);

  const criticalCount = risks.filter((r) => r.rag === "CRIMSON" || r.rag === "RED").length;

  const statusPill =
    criticalCount > 0 ? (
      <StatusPill tone="danger">{criticalCount} critical</StatusPill>
    ) : risksQuery.data ? (
      <StatusPill tone="success">No critical</StatusPill>
    ) : undefined;

  const ragChip = (rag: RiskRag): { className: string; label: string } => {
    switch (rag) {
      case "CRIMSON":
        return { className: "bg-red-200 text-red-900", label: "Crimson" };
      case "RED":
        return { className: "bg-red-100 text-red-800", label: "Red" };
      case "AMBER":
        return { className: "bg-amber-100 text-amber-800", label: "Amber" };
      case "GREEN":
        return { className: "bg-emerald-100 text-emerald-800", label: "Green" };
      case "OPPORTUNITY":
        return { className: "bg-blue-100 text-blue-800", label: "Opp." };
    }
  };

  return (
    <CardShell
      groupId="risk"
      title="Risk & Issues"
      subtitle="What could go wrong, what already has"
      projectId={projectId}
      statusPill={statusPill}
      isLoading={risksQuery.isLoading}
    >
      {risksQuery.isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
          <Skeleton className="h-4 w-4/6" />
        </div>
      ) : top.length === 0 ? (
        <p className="text-xs text-text-secondary">No active risks</p>
      ) : (
        <ul className="space-y-1.5">
          {top.map((r) => {
            const chip = ragChip(r.rag);
            return (
              <li key={r.id} className="flex items-center gap-2 text-xs">
                <span
                  className={`inline-flex items-center rounded px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-[0.08em] ${chip.className}`}
                >
                  {chip.label}
                </span>
                <span className="truncate text-text-primary">{r.title}</span>
              </li>
            );
          })}
        </ul>
      )}
    </CardShell>
  );
}
