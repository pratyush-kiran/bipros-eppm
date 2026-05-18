"use client";

import { useMemo } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { dashboardApi } from "@/lib/api/dashboardApi";
import { raBillApi } from "@/lib/api/raBillApi";
import { budgetApi } from "@/lib/api/budgetApi";
import {
  reportDataApi,
  type ResourceUtilRow,
  type WbsProgressRow,
} from "@/lib/api/reportDataApi";
import {
  CircleDollarSign,
  Layers,
  PackageSearch,
  Wrench,
} from "lucide-react";
import { formatMoney } from "@/lib/hooks/useCurrency";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { ManpowerKpiSection } from "@/components/dashboards/ManpowerKpiSection";
import { EquipmentKpiSection } from "@/components/dashboards/EquipmentKpiSection";
import { EvmKpiSection } from "@/components/dashboards/EvmKpiSection";
import { MaterialKpiSection } from "@/components/dashboards/MaterialKpiSection";
import { AiInsightsPanel } from "@/components/ai/AiInsightsPanel";

interface RaBillRow {
  id: string;
  billNumber: string;
  billPeriodFrom: string;
  billPeriodTo: string;
  netAmount: number;
  status: string;
}

interface ResourceUtilizationGroup {
  resourceType: string;
  allocated: number;
  utilized: number;
  percentage: number;
}

function billStatusBadge(status: string): BadgeVariant {
  switch (status) {
    case "PAID":
      return "success";
    case "APPROVED":
      return "info";
    case "CERTIFIED":
      return "gold";
    case "SUBMITTED":
      return "warning";
    case "DRAFT":
      return "neutral";
    default:
      return "neutral";
  }
}

function activityStatusBadge(status: string): BadgeVariant {
  switch (status) {
    case "COMPLETED":
      return "success";
    case "IN_PROGRESS":
      return "info";
    case "PENDING":
      return "neutral";
    default:
      return "neutral";
  }
}

function utilBarClass(percentage: number) {
  if (percentage >= 90) return "bg-burgundy";
  if (percentage >= 75) return "bg-bronze-warn";
  return "bg-emerald";
}

export default function ProjectOperationalInsightsPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  const { isLoading: isLoadingConfig } = useQuery({
    queryKey: ["dashboard-config", "OPERATIONAL"],
    queryFn: () => dashboardApi.getDashboardByTier("OPERATIONAL"),
  });

  const { data: budgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    enabled: !!projectId,
    staleTime: 5 * 60 * 1000,
  });
  const projectCurrency = budgetData?.data?.budgetCurrency ?? "INR";

  const { data: raBillsData, isLoading: isLoadingRaBills } = useQuery({
    queryKey: ["ra-bills", projectId],
    queryFn: () => raBillApi.getRaBillsByProject(projectId),
    enabled: !!projectId,
  });

  const { data: resourceUtilizationRaw } = useQuery({
    queryKey: ["report-resource-utilization", projectId],
    queryFn: () => reportDataApi.getResourceUtilization(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const { data: wbsProgress } = useQuery({
    queryKey: ["project-wbs-progress", projectId],
    queryFn: () => reportDataApi.getWbsProgress(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const raBills = Array.isArray(raBillsData?.data) ? raBillsData.data : [];

  const resourceUtilization: ResourceUtilizationGroup[] = useMemo(() => {
    const rows = (resourceUtilizationRaw?.resources ?? []) as ResourceUtilRow[];
    const groups = new Map<string, { allocated: number; utilized: number }>();
    rows.forEach((r) => {
      const key = r.type || "Other";
      const g = groups.get(key) ?? { allocated: 0, utilized: 0 };
      g.allocated += r.plannedHours ?? 0;
      g.utilized += r.actualHours ?? 0;
      groups.set(key, g);
    });
    return Array.from(groups.entries()).map(([resourceType, g]) => ({
      resourceType,
      allocated: g.allocated,
      utilized: g.utilized,
      percentage: g.allocated > 0 ? (g.utilized / g.allocated) * 100 : 0,
    }));
  }, [resourceUtilizationRaw]);

  const wbsRows: WbsProgressRow[] = wbsProgress ?? [];

  const totalBillAmount = raBills.reduce(
    (sum: number, b: RaBillRow) => sum + (b.netAmount ?? 0),
    0
  );
  const paidBills = raBills.filter((b: RaBillRow) => b.status === "PAID").length;
  // Project-wide progress = the root WBS node's already-weighted rollup (level === 1).
  const rootWbs = wbsRows.find((r) => r.level === 1) ?? wbsRows[0];
  const wbsAvgPlanned = rootWbs?.plannedPct ?? 0;
  const wbsAvgActual = rootWbs?.actualPct ?? 0;

  if (isLoadingConfig) {
    return (
      <div className="flex items-center justify-center p-12">
        <div className="text-sm text-slate">Loading dashboard…</div>
      </div>
    );
  }

  return (
    <div>
      <section className="mb-7 rounded-xl border border-hairline bg-ivory p-5">
        <ManpowerKpiSection projectId={projectId} density="full" />
      </section>

      <section className="mb-7 rounded-xl border border-hairline bg-ivory p-5">
        <EquipmentKpiSection projectId={projectId} density="full" />
      </section>

      <section className="mb-7 rounded-xl border border-hairline bg-ivory p-5">
        <MaterialKpiSection projectId={projectId} density="full" />
      </section>

      <section className="mb-7 rounded-xl border border-hairline bg-ivory p-5">
        <EvmKpiSection projectId={projectId} density="full" />
      </section>

      <div className="mb-7 grid grid-cols-2 gap-3.5 lg:grid-cols-4">
        <KpiCard label="RA bills" value={raBills.length} accent="gold" />
        <KpiCard
          label="Bill volume"
          value={formatMoney(totalBillAmount, projectCurrency, 0)}
          accent="default"
        />
        <KpiCard label="Paid" value={paidBills} accent="emerald" />
        <KpiCard
          label="WBS progress"
          value={`${wbsAvgActual.toFixed(0)}% / ${wbsAvgPlanned.toFixed(0)}%`}
          accent={wbsAvgActual >= wbsAvgPlanned ? "emerald" : "burgundy"}
        />
      </div>

      <section className="mb-6">
        <SectionHeading
          kicker="Cash flow"
          title="RA bills status"
          icon={<CircleDollarSign size={14} strokeWidth={1.75} />}
        />
        <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
          {isLoadingRaBills ? (
            <div className="space-y-2 p-5">
              {[...Array(3)].map((_, i) => (
                <div key={i} className="h-12 animate-pulse rounded-md bg-parchment/60" />
              ))}
            </div>
          ) : raBills.length === 0 ? (
            <EmptyState label="No RA bills available" />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full border-collapse text-sm">
                <thead className="border-b border-hairline bg-ivory">
                  <tr>
                    <th className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">Bill number</th>
                    <th className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">Period</th>
                    <th className="px-5 py-3 text-right text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">Amount</th>
                    <th className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {raBills.map((bill: RaBillRow) => (
                    <tr key={bill.id} className="border-b border-hairline transition-colors last:border-b-0 hover:bg-ivory">
                      <td className="px-5 py-3.5 font-semibold text-charcoal">{bill.billNumber}</td>
                      <td className="px-5 py-3.5 text-slate">
                        {new Date(bill.billPeriodFrom).toLocaleDateString()} —{" "}
                        {new Date(bill.billPeriodTo).toLocaleDateString()}
                      </td>
                      <td className="px-5 py-3.5 text-right font-medium text-charcoal tabular-nums">
                        {formatMoney(bill.netAmount, projectCurrency, 0)}
                      </td>
                      <td className="px-5 py-3.5">
                        <Badge variant={billStatusBadge(bill.status)} withDot>
                          {bill.status}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

      <section className="mb-6">
        <SectionHeading
          kicker="Capacity"
          title="Resource utilisation"
          subtitle="Actual vs planned hours rolled up by resource category"
          icon={<Wrench size={14} strokeWidth={1.75} />}
        />
        <div className="rounded-2xl border border-hairline bg-paper p-5">
          {resourceUtilization.length === 0 ? (
            <EmptyState label="No resource data for this project" />
          ) : (
            <div className="space-y-5">
              {resourceUtilization.map((resource) => (
                <div key={resource.resourceType}>
                  <div className="mb-1.5 flex flex-wrap items-baseline justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <PackageSearch size={14} className="text-gold-deep" />
                      <span className="font-semibold text-charcoal">{resource.resourceType}</span>
                    </div>
                    <span className="text-xs text-slate tabular-nums">
                      <span className="font-semibold text-charcoal">{resource.utilized.toFixed(0)}</span>
                      {" "}/ {resource.allocated.toFixed(0)} hrs ·{" "}
                      <span className="font-semibold text-charcoal">{resource.percentage.toFixed(1)}%</span>
                    </span>
                  </div>
                  <div className="h-2 w-full overflow-hidden rounded-full bg-parchment">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${utilBarClass(resource.percentage)}`}
                      style={{ width: `${Math.min(100, resource.percentage)}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      <section>
        <SectionHeading
          kicker="Schedule"
          title="Activity progress by WBS"
          subtitle="Planned vs actual percent complete per work-breakdown node"
          icon={<Layers size={14} strokeWidth={1.75} />}
        />
        <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
          {wbsRows.length === 0 ? (
            <EmptyState label="No WBS nodes for this project" />
          ) : (
            <ul className="divide-y divide-hairline">
              {wbsRows.map((row) => {
                const status =
                  row.actualPct >= 100 ? "COMPLETED" : row.actualPct > 0 ? "IN_PROGRESS" : "PENDING";
                return (
                  <li key={row.wbsCode} className="p-5">
                    <div className="mb-3 flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                          {row.wbsCode}
                        </div>
                        <h3 className="truncate font-semibold text-charcoal">{row.wbsName}</h3>
                      </div>
                      <Badge variant={activityStatusBadge(status)} withDot>
                        {status.replace("_", " ")}
                      </Badge>
                    </div>
                    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                      <ProgressRow
                        label="Planned"
                        value={`${row.plannedPct.toFixed(1)}%`}
                        percent={row.plannedPct}
                        barClass="bg-steel"
                      />
                      <ProgressRow
                        label="Actual"
                        value={`${row.actualPct.toFixed(1)}%`}
                        percent={row.actualPct}
                        barClass={
                          row.actualPct >= row.plannedPct ? "bg-emerald" : "bg-bronze-warn"
                        }
                      />
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </section>

      <section className="mt-7 rounded-xl border border-hairline bg-ivory p-5">
        <div className="mb-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
          Optional · Narrative analysis
        </div>
        <AiInsightsPanel
          projectId={projectId}
          endpoint={`/v1/projects/${projectId}/insights/manpower-kpi`}
          autoLoad={false}
          defaultCollapsed
        />
        <div className="h-4" />
        <AiInsightsPanel
          projectId={projectId}
          endpoint={`/v1/projects/${projectId}/insights/equipment-kpi`}
          autoLoad={false}
          defaultCollapsed
        />
      </section>
    </div>
  );
}

// ----------------------- shared local primitives -----------------------

function KpiCard({
  label,
  value,
  accent = "default",
}: {
  label: string;
  value: number | string;
  accent?: "default" | "emerald" | "burgundy" | "gold";
}) {
  const rail =
    accent === "emerald"
      ? "border-l-[3px] border-l-emerald"
      : accent === "burgundy"
        ? "border-l-[3px] border-l-burgundy"
        : accent === "gold"
          ? "border-l-[3px] border-l-gold"
          : "";
  return (
    <div className={`rounded-xl border border-hairline bg-paper p-5 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_4px_20px_rgba(28,28,28,0.05)] ${rail}`}>
      <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-2">{label}</div>
      <div
        className="font-display text-[28px] font-semibold leading-none tracking-tight text-charcoal"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {value}
      </div>
    </div>
  );
}

function SectionHeading({
  kicker,
  title,
  subtitle,
  icon,
}: {
  kicker: string;
  title: string;
  subtitle?: string;
  icon?: React.ReactNode;
}) {
  return (
    <div className="mb-3.5 flex items-baseline justify-between gap-3">
      <div>
        <div className="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
          {icon && <span>{icon}</span>}
          {kicker}
        </div>
        <h2 className="mt-0.5 font-display text-xl font-semibold tracking-tight text-charcoal">
          {title}
        </h2>
        {subtitle && <p className="mt-0.5 text-xs text-slate">{subtitle}</p>}
      </div>
    </div>
  );
}

function ProgressRow({
  label,
  value,
  percent,
  barClass,
}: {
  label: string;
  value: string;
  percent: number;
  barClass: string;
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between">
        <span className="text-[11px] font-medium uppercase tracking-wide text-slate">{label}</span>
        <span className="text-xs font-semibold text-charcoal">{value}</span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-parchment">
        <div
          className={`h-full rounded-full transition-all duration-500 ${barClass}`}
          style={{ width: `${Math.min(100, Math.max(0, percent))}%` }}
        />
      </div>
    </div>
  );
}

function EmptyState({ label }: { label: string }) {
  return (
    <div className="flex items-center justify-center rounded-xl border border-dashed border-hairline bg-ivory/50 p-8 text-sm text-slate">
      {label}
    </div>
  );
}
