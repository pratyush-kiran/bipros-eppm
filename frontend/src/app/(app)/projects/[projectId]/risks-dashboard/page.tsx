"use client";

import React, { useMemo } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";

import { riskApi, type RiskResponse, type RiskProbability, type RiskImpact } from "@/lib/api/riskApi";
import { dprIssueApi } from "@/lib/api/dprIssueApi";

import { KpiCard } from "@/components/ui/kpi-card";
import { DashboardBreadcrumb } from "@/components/dashboards/command/DashboardBreadcrumb";
import {
  RiskHeatmap,
  type RiskMarker,
} from "@/components/dashboards/command/RiskHeatmap";
import {
  RiskRegisterTable,
  type RiskRegisterRow,
} from "@/components/dashboards/command/RiskRegisterTable";
import {
  IssuesActivityList,
  type IssueListItem,
} from "@/components/dashboards/command/IssuesActivityList";
import {
  MitigationActionsTimeline,
  type MitigationAction,
} from "@/components/dashboards/command/MitigationActionsTimeline";

const PROBABILITY_SCALE: Record<RiskProbability, number> = {
  VERY_LOW: 1,
  LOW: 2,
  MEDIUM: 3,
  HIGH: 4,
  VERY_HIGH: 5,
};

const IMPACT_SCALE: Record<RiskImpact, number> = {
  VERY_LOW: 1,
  LOW: 2,
  MEDIUM: 3,
  HIGH: 4,
  VERY_HIGH: 5,
};

function impactToCategory(impact: number): string {
  return ["Low", "Low", "Medium", "High", "Critical"][Math.min(4, Math.max(0, impact - 1))];
}

function probToCategory(p: number): string {
  return ["Rare", "Low", "Medium", "High", "Very High"][Math.min(4, Math.max(0, p - 1))];
}

export default function ProjectRisksDashboardPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  const { data: risksEnv } = useQuery({
    queryKey: ["risks", projectId],
    queryFn: () => riskApi.listRisks(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: dprIssuesEnv } = useQuery({
    queryKey: ["dpr-issues", projectId],
    queryFn: () => dprIssueApi.list(projectId, {}),
    enabled: !!projectId,
    retry: false,
  });

  const risks: RiskResponse[] = useMemo(() => {
    const data = risksEnv?.data;
    return Array.isArray(data) ? data : [];
  }, [risksEnv]);
  const dprIssues = dprIssuesEnv?.data ?? [];

  const totals = useMemo(() => {
    const total = risks.length;
    const high = risks.filter(
      (r) => r.probability === "HIGH" || r.probability === "VERY_HIGH"
    ).length;
    const mitigating = risks.filter((r) => r.status === "MITIGATING").length;
    const closed = risks.filter((r) => r.status === "CLOSED" || r.status === "RESOLVED").length;
    const openIssues = dprIssues.filter((i) => {
      const s = (i.status as string | undefined)?.toUpperCase();
      return s !== "CLOSED" && s !== "RESOLVED";
    }).length;
    const resolvedIssues = dprIssues.filter((i) => {
      const s = (i.status as string | undefined)?.toUpperCase();
      return s === "CLOSED" || s === "RESOLVED";
    }).length;
    return { total, high, mitigating, closed, openIssues, resolvedIssues };
  }, [risks, dprIssues]);

  const markers: RiskMarker[] = useMemo(() => {
    return risks.slice(0, 20).map((r) => ({
      id: r.id,
      label: r.code ?? r.id.slice(0, 4),
      probability: PROBABILITY_SCALE[r.probability] ?? 1,
      impact: r.impact ? IMPACT_SCALE[r.impact] ?? 1 : Math.max(1, Math.round((r.impactCost + r.impactSchedule) / 2)),
    }));
  }, [risks]);

  const registerRows: RiskRegisterRow[] = useMemo(() => {
    return risks.slice(0, 8).map((r) => {
      const probScore = PROBABILITY_SCALE[r.probability] ?? 1;
      const impactScore = r.impact
        ? IMPACT_SCALE[r.impact]
        : Math.max(1, Math.round((r.impactCost + r.impactSchedule) / 2));
      return {
        id: r.id,
        code: r.code ?? "—",
        description: r.title,
        category: r.category?.name ?? "Uncategorised",
        probability: probToCategory(probScore),
        impact: impactToCategory(impactScore),
        score: r.riskScore ?? probScore * impactScore,
        mitigation: r.responseDescription ?? r.cause ?? undefined,
      };
    });
  }, [risks]);

  const activeIssues: IssueListItem[] = useMemo(() => {
    return dprIssues
      .filter((i) => {
        const s = (i.status as string | undefined)?.toUpperCase();
        return s !== "CLOSED" && s !== "RESOLVED";
      })
      .slice(0, 7)
      .map((i, idx) => ({
        id: i.id ?? `iss-${idx}`,
        code: `ISS-${String(idx + 1).padStart(2, "0")}`,
        title: i.title ?? "Issue",
        severity: (i.severity as string | undefined) ?? "INFO",
      }));
  }, [dprIssues]);

  const mitigationActions: MitigationAction[] = useMemo(() => {
    return risks
      .filter((r) => r.dueDate)
      .sort((a, b) => new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime())
      .slice(0, 6)
      .map((r) => {
        const tone: MitigationAction["tone"] =
          r.rag === "CRIMSON" || r.rag === "RED"
            ? "danger"
            : r.rag === "AMBER"
              ? "warning"
              : r.rag === "OPPORTUNITY"
                ? "info"
                : "success";
        return {
          id: r.id,
          title: r.responseDescription ?? r.title,
          due: r.dueDate,
          tone,
        };
      });
  }, [risks]);

  return (
    <div className="space-y-6 pb-12">
      <header className="space-y-2">
        <DashboardBreadcrumb label="Risk & Issues" />
        <p className="text-xs text-text-secondary">
          Risk assessment, mitigation tracking and issue management
        </p>
      </header>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
        <KpiCard label="Total Risks" value={totals.total} accent="warning" rail="top" />
        <KpiCard label="High Severity" value={totals.high} accent="danger" rail="top" />
        <KpiCard label="Active Mitigations" value={totals.mitigating} accent="info" rail="top" />
        <KpiCard label="Closed Risks" value={totals.closed} accent="success" rail="top" />
        <KpiCard label="Open Issues" value={totals.openIssues} accent="danger" rail="top" />
        <KpiCard label="Resolved Issues" value={totals.resolvedIssues} accent="success" rail="top" />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Risk Probability-Impact Matrix
          </h2>
          <RiskHeatmap markers={markers} />
        </section>
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Active Issues
          </h2>
          <IssuesActivityList issues={activeIssues} />
        </section>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <section className="rounded-2xl border border-hairline bg-ivory p-5 lg:col-span-2">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Risk Register
          </h2>
          <RiskRegisterTable rows={registerRows} />
        </section>
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Upcoming Mitigation Actions
          </h2>
          <MitigationActionsTimeline actions={mitigationActions} />
        </section>
      </div>
    </div>
  );
}
