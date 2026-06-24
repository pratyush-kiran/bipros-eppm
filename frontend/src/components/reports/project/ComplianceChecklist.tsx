"use client";

import { useQuery } from "@tanstack/react-query";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";

function tick(b: boolean): React.ReactNode {
  return b ? <span className="text-success">✓</span> : <span className="text-danger">✗</span>;
}

export function ComplianceChecklist({ projectId }: { projectId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["project-compliance", projectId],
    queryFn: () => projectInsightsApi.getCompliance(projectId),
  });

  const currency = useProjectCurrencyOptional();
  const money = (v: number | null | undefined) =>
    currency ? currency.money(v) : formatMoney(v, { code: "INR" });
  const moneyCompact = (v: number | null | undefined) =>
    currency ? currency.moneyCompact(v) : formatMoney(v, { code: "INR" }, { compact: true });

  if (isLoading)
    return (
      <div className="rounded-lg border border-border bg-surface/50 p-6 text-center text-text-muted">
        Loading compliance…
      </div>
    );
  if (isError || !data)
    return (
      <div className="rounded-lg border border-dashed border-border p-6 text-center text-text-muted">
        Compliance unavailable
      </div>
    );

  const c = data;

  return (
    <div className="space-y-6">
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold text-text-primary">Overall compliance score</h3>
          <span
            className={`rounded-full px-4 py-1 text-xl font-bold ${
              c.overallScore >= 80
                ? "bg-success/20 text-success"
                : c.overallScore >= 50
                  ? "bg-warning/20 text-warning"
                  : "bg-danger/20 text-danger"
            }`}
          >
            {c.overallScore.toFixed(0)}%
          </span>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        <Block title="PFMS (Funding)">
          <Row label="Sanction OK">{tick(c.pfms.sanctionOk)}</Row>
          <Row label="Last release">{c.pfms.lastRelease ?? "—"}</Row>
          <Row label="Pending amount">{money(c.pfms.pendingAmount)}</Row>
        </Block>
        <Block title="GSTN (Contractors)">
          <Row label="Total contractors">{c.gstn.contractorCount}</Row>
          <Row label="Verified">{c.gstn.verifiedCount}</Row>
          <Row label="Expired">{c.gstn.expiredCount}</Row>
        </Block>
        <Block title="GeM (Orders)">
          <Row label="Linked orders">{c.gem.linkedOrders}</Row>
          <Row label="Total value">{moneyCompact(c.gem.totalValueCrores * 1e7)}</Row>
        </Block>
        <Block title="CPPP (Tenders)">
          <Row label="Published">{c.cppp.publishedTenders}</Row>
          <Row label="Open bids">{c.cppp.openBids}</Row>
        </Block>
        <Block title="PARIVESH">
          <Row label="Clearances">{c.parivesh.clearancesObtained}</Row>
          <Row label="Pending">{c.parivesh.pendingClearances}</Row>
        </Block>
        <Block title="HSE">
          <Row label="Last 30 days incidents">{c.hse.last30DaysIncidents}</Row>
          <Row label="Open NCRs">{c.hse.openNCRs}</Row>
        </Block>
      </div>
    </div>
  );
}

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-border bg-surface/50 p-4">
      <h4 className="mb-3 text-sm font-semibold text-text-secondary">{title}</h4>
      <div className="space-y-1 text-sm">{children}</div>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-text-muted">{label}</span>
      <span className="font-medium text-text-primary">{children}</span>
    </div>
  );
}
