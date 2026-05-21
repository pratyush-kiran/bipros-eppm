"use client";

import React, { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";

import { equipmentKpiApi } from "@/lib/api/equipmentKpiApi";
import { reportDataApi, type ResourceUtilRow } from "@/lib/api/reportDataApi";

import { Tabs } from "@/components/ui/tabs";
import { GaugeCard } from "@/components/ui/gauge-card";
import { DashboardBreadcrumb } from "@/components/dashboards/command/DashboardBreadcrumb";
import {
  EquipmentRegisterTable,
  type EquipmentRow,
} from "@/components/dashboards/command/EquipmentRegisterTable";

type ResourceTab = "equipment" | "workforce" | "materials" | "subcontractors";

function isoDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

export default function ProjectResourceManagementPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const [tab, setTab] = useState<ResourceTab>("equipment");

  const from = isoDays(30);
  const to = isoDays(0);

  const { data: equipmentKpiEnv, isLoading: isLoadingEquipment } = useQuery({
    queryKey: ["equipment-kpis", projectId, from, to],
    queryFn: () => equipmentKpiApi.getKpis(projectId, from, to),
    enabled: !!projectId,
    retry: false,
  });

  const { data: resourceUtilRaw } = useQuery({
    queryKey: ["report-resource-utilization", projectId],
    queryFn: () => reportDataApi.getResourceUtilization(projectId),
    enabled: !!projectId,
    retry: false,
  });

  const kpis = equipmentKpiEnv?.data;
  const utilization = kpis?.utilization ?? [];

  const fleetStats = useMemo(() => {
    const total = utilization.length;
    const active = utilization.filter((u) => u.operatingHours > 0).length;
    const idle = utilization.filter((u) => u.operatingHours === 0 && u.breakdownHours === 0).length;
    const repair = utilization.filter((u) => u.breakdownHours > 0).length;
    const avgUtil =
      total > 0 ? utilization.reduce((s, u) => s + u.utilizationPct, 0) / total : 0;
    return { total, active, idle, repair, avgUtil };
  }, [utilization]);

  const equipmentRows: EquipmentRow[] = useMemo(() => {
    return utilization.slice(0, 20).map((u) => {
      const status =
        u.breakdownHours > 0 ? "REPAIR" : u.operatingHours > 0 ? "ACTIVE" : "IDLE";
      const condition =
        u.mechanicalAvailabilityPct >= 90
          ? "GOOD"
          : u.mechanicalAvailabilityPct >= 70
            ? "FAIR"
            : "POOR";
      return {
        id: u.resourceId,
        code: u.resourceCode,
        name: u.resourceName,
        type: undefined,
        assignedTo: undefined,
        location: undefined,
        hoursToday: u.operatingHours / 30, // rough daily average over the 30-day window
        totalHours: u.operatingHours,
        fuelPct: null,
        condition,
        status,
      };
    });
  }, [utilization]);

  const workforceRows: ResourceUtilRow[] = useMemo(
    () => (resourceUtilRaw?.resources ?? []) as ResourceUtilRow[],
    [resourceUtilRaw]
  );
  const workforceGroups = useMemo(() => {
    const grouped = new Map<string, { allocated: number; utilised: number }>();
    workforceRows.forEach((r) => {
      const key = r.type || "Other";
      const g = grouped.get(key) ?? { allocated: 0, utilised: 0 };
      g.allocated += r.plannedHours ?? 0;
      g.utilised += r.actualHours ?? 0;
      grouped.set(key, g);
    });
    return Array.from(grouped.entries()).map(([type, g]) => ({
      type,
      allocated: g.allocated,
      utilised: g.utilised,
      pct: g.allocated > 0 ? (g.utilised / g.allocated) * 100 : 0,
    }));
  }, [workforceRows]);

  return (
    <div className="space-y-6 pb-12">
      <header className="space-y-2">
        <DashboardBreadcrumb label="Resource Management" />
        <p className="text-xs text-text-secondary">Equipment, workforce and material tracking</p>
      </header>

      <Tabs
        items={[
          { id: "equipment", label: "Equipment", count: utilization.length || undefined },
          { id: "workforce", label: "Workforce", count: workforceGroups.length || undefined },
          { id: "materials", label: "Materials" },
          { id: "subcontractors", label: "Sub-contractors" },
        ]}
        value={tab}
        onChange={setTab}
      />

      {tab === "equipment" && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
            <GaugeCard
              label="Total Fleet"
              value={fleetStats.total}
              unit="units"
              percent={Math.min(100, fleetStats.total * 2)}
              accent="info"
            />
            <GaugeCard
              label="On-Site Active"
              value={fleetStats.active}
              unit="running"
              percent={fleetStats.total ? (fleetStats.active / fleetStats.total) * 100 : 0}
              accent="success"
            />
            <GaugeCard
              label="Under Repair"
              value={fleetStats.repair}
              unit="units"
              percent={fleetStats.total ? (fleetStats.repair / fleetStats.total) * 100 : 0}
              accent="danger"
            />
            <GaugeCard
              label="Idle / Standby"
              value={fleetStats.idle}
              unit="units"
              percent={fleetStats.total ? (fleetStats.idle / fleetStats.total) * 100 : 0}
              accent="warning"
            />
            <GaugeCard
              label="Avg Utilisation"
              value={fleetStats.avgUtil.toFixed(0)}
              unit="%"
              percent={fleetStats.avgUtil}
              accent="primary"
            />
          </div>

          <section className="rounded-2xl border border-hairline bg-ivory p-5">
            <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
              Equipment Register
            </h2>
            {isLoadingEquipment ? (
              <div className="space-y-2">
                {[...Array(6)].map((_, i) => (
                  <div key={i} className="h-12 animate-pulse rounded-md bg-parchment/60" />
                ))}
              </div>
            ) : (
              <EquipmentRegisterTable rows={equipmentRows} />
            )}
          </section>
        </>
      )}

      {tab === "workforce" && (
        <section className="rounded-2xl border border-hairline bg-ivory p-5">
          <h2 className="mb-4 text-[10px] font-semibold uppercase tracking-[0.16em] text-gold-deep">
            Workforce Utilisation
          </h2>
          {workforceGroups.length === 0 ? (
            <div className="rounded-xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary">
              No workforce data yet.
            </div>
          ) : (
            <div className="space-y-5">
              {workforceGroups.map((g) => (
                <div key={g.type}>
                  <div className="mb-1.5 flex items-baseline justify-between gap-2">
                    <span className="font-semibold text-text-primary">{g.type}</span>
                    <span className="text-xs text-text-secondary tabular-nums">
                      <span className="text-text-primary">{g.utilised.toFixed(0)}</span>
                      {" "}/ {g.allocated.toFixed(0)} hrs ·{" "}
                      <span className="text-text-primary">{g.pct.toFixed(1)}%</span>
                    </span>
                  </div>
                  <div className="h-2 w-full overflow-hidden rounded-full bg-parchment">
                    <div
                      className={
                        g.pct >= 90
                          ? "h-full rounded-full bg-burgundy"
                          : g.pct >= 75
                            ? "h-full rounded-full bg-bronze-warn"
                            : "h-full rounded-full bg-emerald"
                      }
                      style={{ width: `${Math.min(100, g.pct)}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {tab === "materials" && (
        <section className="rounded-2xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary">
          Materials view — to be wired to the stock register API.
        </section>
      )}

      {tab === "subcontractors" && (
        <section className="rounded-2xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary">
          Sub-contractors view — to be wired to the contract API.
        </section>
      )}
    </div>
  );
}
