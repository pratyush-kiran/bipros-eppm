"use client";

import { useEffect, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { HardHat, Users } from "lucide-react";

import { SearchableSelect, type SelectOption } from "@/components/common/SearchableSelect";
import { EmptyState } from "@/components/common/EmptyState";
import { KpiTile } from "@/components/common/KpiTile";
import {
  dbsApi,
  type DbsCmDayResponse,
  type DbsPeriodType,
} from "@/lib/api/dbsApi";
import { userApi, type UserSummary } from "@/lib/api/userApi";
import { formatCurrency, formatPercent } from "@/lib/utils/format";

import { SectionCard } from "./SectionCard";
import { TotalsPanel } from "./TotalsPanel";

/**
 * Phase 8 — Construction Manager DBS tab.
 *
 * Mirrors {@link EngineerDbsTab} but for the CM tier (PM → CM → SM → Engineer
 * → Supervisor). CMs are picked from `dbsApi.listCms`, which returns one row
 * per CM with activity on the chosen date plus pre-rolled cost / contribution
 * fields used to label the picker and seed the totals panel before the per-CM
 * detail query lands.
 *
 * Layout:
 *   1. CM picker + "N CMs with activity" hint
 *   2. Prelim-aware KPI tiles (Direct, Prelim, Total incl Prelims, % Achieved)
 *   3. Standard {@link TotalsPanel} (P&L summary)
 *   4. Section accordions for Material, Manpower, Admin, Machinery, Fuel —
 *      the CM-tier response carries SECTION TOTALS only (no line-level detail);
 *      we render each section card with an empty `lines` array so the user
 *      sees the totals but no spurious lines.
 *   5. Chip list of Site Managers / Engineers under this CM.
 */
export interface CmDbsTabProps {
  projectId: string;
  date: string;
  /**
   * Period type from the parent date/period selector. V1 only implements DAY;
   * WEEK / MONTH are accepted but currently rendered as the day rollup. Wired
   * up here so future period support doesn't require a prop-shape change.
   */
  periodType: DbsPeriodType;
  cmUserId: string;
  onCmChange: (value: string) => void;
  currency?: string | null;
}

export function CmDbsTab({
  projectId,
  date,
  periodType,
  cmUserId,
  onCmChange,
  currency,
}: CmDbsTabProps) {
  // V1 ignores period (DAY only); referenced so the prop is consumed and the
  // intent is documented.
  void periodType;
  // CM roster — backend returns CMs that had activity on the chosen date.
  const { data: cmsData, isLoading: cmsLoading } = useQuery({
    queryKey: ["dbs-cms-roster", projectId, date],
    queryFn: () => dbsApi.listCms(projectId, date),
    enabled: !!projectId && !!date,
  });
  const cms = useMemo(() => cmsData?.data ?? [], [cmsData]);

  // User directory — resolves Site Manager / Engineer chip labels under the
  // selected CM, and fills in CM names when the summary omits them.
  const { data: usersData } = useQuery({
    queryKey: [
      "users",
      "by-roles",
      ["CONSTRUCTION_MANAGER", "SITE_MANAGER", "ENGINEER", "SITE_ENGINEER", "SUPERVISOR"],
    ],
    queryFn: () =>
      userApi.listByRoles([
        "CONSTRUCTION_MANAGER",
        "SITE_MANAGER",
        "ENGINEER",
        "SITE_ENGINEER",
        "SUPERVISOR",
      ]),
  });
  const usersById = useMemo(() => {
    const m = new Map<string, UserSummary>();
    for (const u of usersData ?? []) m.set(u.id, u);
    return m;
  }, [usersData]);

  const cmOptions: SelectOption[] = useMemo(
    () =>
      cms.map((c) => {
        const fallback = usersById.get(c.cmUserId);
        const name = c.cmName || fallback?.name || c.cmUserId.slice(0, 8) + "…";
        const supLabel = `${c.supervisorCount ?? 0} sup`;
        return { value: c.cmUserId, label: `${name} — ${supLabel}` };
      }),
    [cms, usersById],
  );

  // Auto-pick the first CM once the roster is in.
  useEffect(() => {
    if (!cmUserId && cms.length > 0) {
      onCmChange(cms[0].cmUserId);
    }
  }, [cms, cmUserId, onCmChange]);

  const dayQuery = useQuery({
    queryKey: ["dbs-cm-day", projectId, cmUserId, date],
    queryFn: () => dbsApi.getCmDay(projectId, cmUserId, { date }),
    enabled: !!projectId && !!cmUserId && !!date,
  });

  const day: DbsCmDayResponse | null = dayQuery.data?.data ?? null;

  const downlineIds = useMemo(() => {
    if (!day) return [] as string[];
    const ids = new Set<string>();
    for (const id of day.siteManagerIds ?? []) ids.add(id);
    for (const id of day.engineerIds ?? []) ids.add(id);
    return Array.from(ids);
  }, [day]);

  if (cmsLoading) {
    return <div className="text-center text-text-muted">Loading construction managers…</div>;
  }

  if (cms.length === 0) {
    return (
      <EmptyState
        title="No CM activity on this date"
        description="No Construction Managers have downline DPR activity on the chosen date. Add a CM under Project → Team, or pick another date."
      />
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="w-full max-w-md">
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
            Construction Manager
          </label>
          <SearchableSelect
            options={cmOptions}
            value={cmUserId}
            onChange={onCmChange}
            placeholder="Pick a CM…"
          />
        </div>
        <div className="flex items-center gap-2 text-xs text-text-muted">
          <HardHat size={14} />
          {cms.length} CM{cms.length === 1 ? "" : "s"} with activity
        </div>
      </div>

      {!cmUserId ? (
        <EmptyState
          title="Choose a Construction Manager"
          description="Pick a CM from the dropdown to see their rolled-up daily balance sheet."
        />
      ) : dayQuery.isLoading ? (
        <div className="text-center text-text-muted">Loading CM rollup…</div>
      ) : !day ? (
        <EmptyState
          title="No data"
          description="No DBS data computed for this CM on this date. Try the Recompute button on the PM tab or pick another date."
        />
      ) : (
        <>
          {/* Prelim-aware KPI tiles — mirror the Excel "Summary-Financial" headline columns. */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <KpiTile
              label="Direct Cost"
              value={formatCurrency(day.directCost ?? 0, currency)}
              hint="Excl. preliminaries"
              tone="warning"
            />
            <KpiTile
              label="Prelim Cost"
              value={formatCurrency(day.prelimCost ?? 0, currency)}
              hint="BOQ Section 1 items"
              tone="default"
            />
            <KpiTile
              label="Cost incl Prelims"
              value={formatCurrency(day.totalCostInclPrelims ?? 0, currency)}
              hint="Direct + Prelim"
              tone="accent"
            />
            <KpiTile
              label="% Achieved"
              value={
                day.pctAchieved != null
                  ? formatPercent(day.pctAchieved)
                  : "—"
              }
              hint="BOQ achieved-to-date ÷ planned-to-date"
              tone={
                day.pctAchieved != null && day.pctAchieved >= 95
                  ? "success"
                  : day.pctAchieved != null && day.pctAchieved < 60
                    ? "danger"
                    : "default"
              }
            />
          </div>

          <TotalsPanel
            materialAmount={day.materialAmount}
            manpowerAmount={day.manpowerAmount}
            adminAmount={day.adminAmount}
            machineryAmount={day.machineryAmount}
            fuelAmount={day.fuelAmount}
            subcontractAmount={day.subcontractAmount}
            totalExpense={day.totalExpense}
            totalIncome={day.totalIncome}
            contribution={day.contribution}
            contributionPct={day.contributionPct * 100}
            currency={currency}
          />

          {/* Section totals — CM-tier response carries totals only, not lines.
              Each card shows the total in the header; the empty body just notes
              that drill-down lives one tier lower. */}
          <div className="space-y-3">
            <SectionCard
              title="A. Manpower"
              lines={[]}
              total={day.manpowerAmount}
              currency={currency}
            />
            <SectionCard
              title="B. Admin / Catering"
              lines={[]}
              total={day.adminAmount}
              currency={currency}
            />
            <SectionCard
              title="C. Machinery"
              lines={[]}
              total={day.machineryAmount}
              currency={currency}
            />
            <SectionCard
              title="D. Fuel"
              lines={[]}
              total={day.fuelAmount}
              currency={currency}
            />
            <SectionCard
              title="E. Material"
              lines={[]}
              total={day.materialAmount}
              currency={currency}
            />
            <SectionCard
              title="BOQ — Direct"
              lines={[]}
              total={day.directCost ?? 0}
              currency={currency}
            />
            <SectionCard
              title="BOQ — Preliminaries"
              lines={[]}
              total={day.prelimCost ?? 0}
              currency={currency}
            />
          </div>

          {/* Downline chips — Site Managers + Engineers reporting to this CM
              on this date. Click-through is informational only (the Engineer
              tab does its own picker); we surface the chain so the PM can see
              the org footprint at a glance. */}
          <section className="rounded-lg border border-border bg-surface/50 p-4 shadow-sm">
            <h3 className="mb-3 text-sm font-semibold text-text-primary">
              Downline ({downlineIds.length || 0})
              <span className="ml-2 text-xs font-normal text-text-muted">
                Site Manager · {(day.siteManagerIds ?? []).length} · Engineer ·{" "}
                {(day.engineerIds ?? []).length} · Supervisor ·{" "}
                {day.supervisorCount ?? 0}
              </span>
            </h3>
            {downlineIds.length === 0 ? (
              <p className="text-xs text-text-muted">
                <Users className="-mt-0.5 mr-1 inline" size={12} />
                No site managers or engineers reporting to this CM had activity
                on this date.
              </p>
            ) : (
              <div className="flex flex-wrap gap-2">
                {downlineIds.map((id) => {
                  const u = usersById.get(id);
                  const label = u?.name ?? id.slice(0, 8) + "…";
                  const isSm = (day.siteManagerIds ?? []).includes(id);
                  return (
                    <span
                      key={id}
                      className={
                        isSm
                          ? "inline-flex items-center gap-1 rounded-full border border-amber-500/30 bg-amber-500/10 px-3 py-1 text-xs font-medium text-amber-700"
                          : "inline-flex items-center gap-1 rounded-full border border-sky-500/30 bg-sky-500/10 px-3 py-1 text-xs font-medium text-sky-700"
                      }
                      title={isSm ? "Site Manager" : "Engineer"}
                    >
                      {isSm ? "SM" : "Eng"} · {label}
                    </span>
                  );
                })}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
