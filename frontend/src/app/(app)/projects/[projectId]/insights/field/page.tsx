"use client";

import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { dashboardApi, type FieldSummary } from "@/lib/api/dashboardApi";
import { activityApi } from "@/lib/api/activityApi";
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle,
  Clock,
  HardHat,
  Package,
  ShieldCheck,
  Truck,
  Users,
} from "lucide-react";
import type { ActivityResponse } from "@/lib/types";
import { Badge, type BadgeVariant } from "@/components/ui/badge";

function activityBadge(status: string): BadgeVariant {
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

function getStatusIcon(status: string) {
  switch (status) {
    case "COMPLETED":
      return <CheckCircle size={14} className="text-emerald" strokeWidth={2} />;
    case "IN_PROGRESS":
      return <Clock size={14} className="text-steel" strokeWidth={2} />;
    default:
      return <AlertCircle size={14} className="text-ash" strokeWidth={2} />;
  }
}

export default function ProjectFieldInsightsPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  const { isLoading: isLoadingConfig } = useQuery({
    queryKey: ["dashboard-config", "FIELD"],
    queryFn: () => dashboardApi.getDashboardByTier("FIELD"),
  });

  const { data: activitiesData, isLoading: isLoadingActivities } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId),
    enabled: !!projectId,
  });

  const { data: fieldSummaryData, isLoading: isLoadingFieldSummary } = useQuery({
    queryKey: ["field-summary", projectId],
    queryFn: () => dashboardApi.getFieldSummary(projectId),
    enabled: !!projectId,
    retry: 1,
  });

  const fieldSummary: FieldSummary | null =
    (fieldSummaryData?.data as FieldSummary | undefined) ?? null;

  const activities = activitiesData?.data?.content ?? [];

  const dailyWorklogs = fieldSummary?.dailyWorklogs ?? [];
  const hasAnyWorklogData = dailyWorklogs.some(
    (d) => d.equipmentCount > 0 || d.headCount > 0,
  );

  const activeSites = fieldSummary?.activeSites ?? [];
  const totalWorkers = fieldSummary?.workersOnSite ?? 0;
  const totalEquipment = fieldSummary?.equipmentDeployed ?? 0;
  const totalIncidents = fieldSummary?.safetyIncidents ?? 0;
  const totalOperatingHours = fieldSummary?.operatingHours4d ?? 0;
  const stockAvailability = fieldSummary?.stockAvailabilityPct ?? 0;
  const reorderBreaches = fieldSummary?.reorderBreachCount ?? 0;
  const stockTracked = fieldSummary?.stockTrackedMaterialCount ?? 0;

  if (isLoadingConfig) {
    return (
      <div className="flex items-center justify-center p-12">
        <div className="text-sm text-slate">Loading dashboard…</div>
      </div>
    );
  }

  return (
    <div>
      {/* KPI strip */}
      <div className="mb-7 grid grid-cols-2 gap-3.5 lg:grid-cols-3 xl:grid-cols-6">
        <KpiCard label="Workers on site" value={totalWorkers} icon={<Users size={16} />} />
        <KpiCard
          label="Equipment deployed"
          value={totalEquipment}
          icon={<Truck size={16} />}
          accent="gold"
        />
        <KpiCard
          label="Operating hours · 4d"
          value={`${totalOperatingHours.toFixed(0)}h`}
          icon={<Clock size={16} />}
          accent="emerald"
        />
        <KpiCard
          label="Safety incidents"
          value={totalIncidents}
          icon={<ShieldCheck size={16} />}
          accent={totalIncidents > 0 ? "burgundy" : "emerald"}
        />
        <KpiCard
          label="Stock availability"
          value={stockTracked === 0 ? "—" : `${(stockAvailability * 100).toFixed(0)}%`}
          icon={<Package size={16} />}
          accent={stockAvailability >= 0.95 ? "emerald" : stockAvailability >= 0.8 ? "gold" : "burgundy"}
        />
        <KpiCard
          label="Re-order breaches"
          value={reorderBreaches}
          icon={<AlertTriangle size={16} />}
          accent={reorderBreaches > 0 ? "burgundy" : "emerald"}
        />
      </div>

      <section className="mb-6">
        <SectionHeading
          kicker="Last 4 shifts"
          title="Daily worklogs"
          icon={<Clock size={14} strokeWidth={1.75} />}
        />
        <div className="rounded-2xl border border-hairline bg-paper p-5">
          {isLoadingFieldSummary ? (
            <div className="grid grid-cols-1 gap-3.5 md:grid-cols-4">
              {[...Array(4)].map((_, i) => (
                <div key={i} className="h-36 animate-pulse rounded-xl bg-parchment/60" />
              ))}
            </div>
          ) : !hasAnyWorklogData ? (
            <EmptyState label="No daily worklogs for this project yet." />
          ) : (
            <div className="grid grid-cols-1 gap-3.5 md:grid-cols-4">
              {dailyWorklogs.map((log) => (
                <div key={log.date} className="rounded-xl border border-hairline bg-ivory p-4">
                  <div className="mb-3 flex items-center justify-between">
                    <div className="font-display text-base font-semibold tracking-tight text-charcoal">
                      {new Date(log.date + "T00:00:00Z").toLocaleDateString("en-US", {
                        month: "short",
                        day: "numeric",
                        timeZone: "UTC",
                      })}
                    </div>
                    <span className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
                      {new Date(log.date + "T00:00:00Z").toLocaleDateString("en-US", {
                        weekday: "short",
                        timeZone: "UTC",
                      })}
                    </span>
                  </div>
                  <DailyMetric icon={<Truck size={12} />} label="Equipment" value={log.equipmentCount} />
                  <DailyMetric icon={<Clock size={12} />} label="Op hours" value={`${log.operatingHours.toFixed(1)}h`} />
                  <DailyMetric icon={<Users size={12} />} label="Headcount" value={log.headCount} last />
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      <section className="mb-6">
        <SectionHeading
          kicker={fieldSummary?.asOfDate ? `As of ${fieldSummary.asOfDate}` : "Live"}
          title="Active sites"
          icon={<HardHat size={14} strokeWidth={1.75} />}
        />
        {isLoadingFieldSummary ? (
          <div className="grid grid-cols-1 gap-3.5 md:grid-cols-3">
            {[...Array(3)].map((_, i) => (
              <div key={i} className="h-32 animate-pulse rounded-2xl bg-parchment/60" />
            ))}
          </div>
        ) : activeSites.length === 0 ? (
          <EmptyState label="No active sites to show. A card appears when an activity is marked IN_PROGRESS and has a DPR filed against it in the last 7 days." />
        ) : (
          <div className="grid grid-cols-1 gap-3.5 md:grid-cols-3">
            {activeSites.map((site) => (
              <div key={site.activityId} className="rounded-2xl border border-hairline bg-paper p-5">
                <div className="mb-4 flex items-start justify-between gap-2">
                  <h3 className="font-display text-base font-semibold leading-snug tracking-tight text-charcoal">
                    {site.activityName}
                  </h3>
                  <Badge variant={site.safetyIncidents > 0 ? "danger" : "success"} withDot>
                    {site.safetyIncidents > 0 ? "Incident" : "All clear"}
                  </Badge>
                </div>
                <div className="grid grid-cols-3 gap-3">
                  <SiteStat icon={<Users size={14} />} label="Workers" value={site.workers} />
                  <SiteStat icon={<Truck size={14} />} label="Equipment" value={site.equipment} />
                  <SiteStat
                    icon={<ShieldCheck size={14} />}
                    label="Incidents"
                    value={site.safetyIncidents}
                    tone={site.safetyIncidents > 0 ? "burgundy" : "emerald"}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <section>
        <SectionHeading kicker="On the wire" title="Site activities" />
        <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
          {isLoadingActivities ? (
            <div className="space-y-2 p-5">
              {[...Array(3)].map((_, i) => (
                <div key={i} className="h-20 animate-pulse rounded-md bg-parchment/60" />
              ))}
            </div>
          ) : activities.length === 0 ? (
            <EmptyState label="No activities available" />
          ) : (
            <ul className="divide-y divide-hairline">
              {activities.slice(0, 10).map((activity: ActivityResponse) => (
                <li key={activity.id} className="p-5">
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div className="flex min-w-0 items-start gap-3">
                      <span className="mt-0.5">{getStatusIcon(activity.status)}</span>
                      <div className="min-w-0">
                        <h3 className="truncate font-semibold text-charcoal">{activity.name}</h3>
                        {activity.code && (
                          <p className="text-xs text-slate">Code · {activity.code}</p>
                        )}
                      </div>
                    </div>
                    <Badge variant={activityBadge(activity.status)} withDot>
                      {activity.status.replace("_", " ")}
                    </Badge>
                  </div>
                  <div className="ml-7">
                    <div className="mb-1 flex items-center justify-between">
                      <span className="text-[11px] font-medium uppercase tracking-wide text-slate">Progress</span>
                      <span className="text-xs font-semibold text-charcoal">
                        {activity.percentComplete || 0}%
                      </span>
                    </div>
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-parchment">
                      <div
                        className={`h-full rounded-full transition-all duration-500 ${
                          activity.status === "COMPLETED" ? "bg-emerald" : "bg-gold"
                        }`}
                        style={{ width: `${activity.percentComplete || 0}%` }}
                      />
                    </div>
                    <div className="mt-2 text-[11px] text-slate">
                      {activity.plannedStartDate
                        ? new Date(activity.plannedStartDate).toLocaleDateString()
                        : "TBD"}{" "}
                      —{" "}
                      {activity.plannedFinishDate
                        ? new Date(activity.plannedFinishDate).toLocaleDateString()
                        : "TBD"}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>
    </div>
  );
}

// ----------------------- shared local primitives -----------------------

function KpiCard({
  label,
  value,
  icon,
  accent = "default",
}: {
  label: string;
  value: number | string;
  icon?: React.ReactNode;
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
      <div className="mb-2 flex items-center justify-between">
        <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">{label}</div>
        {icon && <span className="text-gold-deep/70">{icon}</span>}
      </div>
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

function DailyMetric({
  icon,
  label,
  value,
  last = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: number | string;
  last?: boolean;
}) {
  return (
    <div className={`flex items-center justify-between py-1.5 ${last ? "" : "border-b border-hairline/60"}`}>
      <span className="inline-flex items-center gap-1.5 text-[11px] font-medium text-slate">
        <span className="text-ash">{icon}</span>
        {label}
      </span>
      <span className="text-base font-semibold tabular-nums text-charcoal">{value}</span>
    </div>
  );
}

function SiteStat({
  icon,
  label,
  value,
  tone = "default",
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  tone?: "default" | "emerald" | "burgundy";
}) {
  const valueClass =
    tone === "emerald"
      ? "text-emerald"
      : tone === "burgundy"
        ? "text-burgundy"
        : "text-charcoal";
  return (
    <div className="rounded-lg border border-hairline bg-ivory/60 p-3">
      <div className="mb-1 flex items-center gap-1.5 text-ash">
        {icon}
        <span className="text-[10px] font-semibold uppercase tracking-[0.14em] text-slate">{label}</span>
      </div>
      <div className={`font-display text-xl font-semibold tabular-nums tracking-tight ${valueClass}`}>
        {value}
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
