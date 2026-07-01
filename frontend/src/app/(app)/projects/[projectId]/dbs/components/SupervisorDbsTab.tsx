"use client";

import { useEffect, useMemo } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Users } from "lucide-react";
import toast from "react-hot-toast";

import { SearchableSelect, type SelectOption } from "@/components/common/SearchableSelect";
import { EmptyState } from "@/components/common/EmptyState";
import {
  dbsApi,
  type DbsPeriodType,
  type DbsSupervisorDayResponse,
} from "@/lib/api/dbsApi";
import { dprApi } from "@/lib/api/dprApi";
import { getErrorMessage } from "@/lib/utils/error";
import { formatCurrency, formatPercent } from "@/lib/utils/format";

import { DailyBreakdownTable } from "./DailyBreakdownTable";
import { SectionCard } from "./SectionCard";
import { TotalsPanel } from "./TotalsPanel";

/**
 * Supervisor tab — mirrors the per-supervisor sheets in the client workbook
 * (Anbazhagan-TS, …). Picker is the supervisors who actually have data for
 * the selected date (from {@link dbsApi.listSupervisorsForDay}), so users don't
 * see an empty page for someone who didn't submit a DPR.
 *
 * Selected supervisor + period-type drives either {@link dbsApi.getSupervisorDay}
 * (DAY) or {@link dbsApi.getSupervisorPeriod} (WEEK / MONTH). The DAY response
 * carries the per-section line arrays; for WEEK / MONTH we render the totals'
 * section arrays (backend rolls them up) plus a small daily breakdown table.
 */
export interface SupervisorDbsTabProps {
  projectId: string;
  date: string;
  periodType: DbsPeriodType;
  /** Controlled supervisor selection — owned by the page so it survives tab swaps. */
  supervisorUserId: string;
  onSupervisorChange: (value: string) => void;
  /** Project currency from `budgetApi.getBudgetSummary`. */
  currency?: string | null;
}

export function SupervisorDbsTab({
  projectId,
  date,
  periodType,
  supervisorUserId,
  onSupervisorChange,
  currency,
}: SupervisorDbsTabProps) {
  const qc = useQueryClient();

  // Roster of supervisors with data on the chosen date. Backend returns a
  // summary row per supervisor; we feed the picker from this.
  const { data: rosterData, isLoading: rosterLoading } = useQuery({
    // periodType included in the key so WEEK / MONTH share their own cache slot — and so
    // the roster widens to "supervisors with activity in the period" when not in DAY mode.
    // Without this, the focal-date roster was empty whenever the day itself had no DPRs
    // even if the week/month had data.
    queryKey: ["dbs-supervisor-roster", projectId, date, periodType],
    queryFn: () => dbsApi.listSupervisorsForDay(projectId, date, periodType),
    enabled: !!projectId && !!date,
  });
  const roster = useMemo(() => rosterData?.data ?? [], [rosterData]);

  // When the DBS roster is empty, the underlying DPR list may still have rows —
  // the DBS aggregate is just stale. We only probe this when the roster is
  // empty so we don't hit the DPR endpoint on every render.
  const rosterEmpty = !rosterLoading && roster.length === 0;
  const { data: dprData } = useQuery({
    queryKey: ["dpr-list-for-dbs-empty-state", projectId, date],
    queryFn: () => dprApi.list(projectId, { from: date, to: date }),
    enabled: !!projectId && !!date && rosterEmpty,
  });
  const dprsForDate = dprData?.data?.items ?? [];

  // Manual Recompute trigger for the roster-empty / DPRs-exist case. We do
  // NOT auto-fire — the user must opt in (the PM admin Recompute on the PM tab
  // is the canonical one; this is a supervisor-tab convenience).
  const recomputeMutation = useMutation({
    mutationFn: () => dbsApi.recompute(projectId, date),
    onSuccess: () => {
      toast.success("Recompute triggered. Refreshing aggregates…");
      qc.invalidateQueries({ queryKey: ["dbs-supervisor-roster", projectId, date] });
      qc.invalidateQueries({ queryKey: ["dbs-supervisor-day", projectId] });
      qc.invalidateQueries({ queryKey: ["dbs-engineer-day", projectId] });
      qc.invalidateQueries({ queryKey: ["dbs-project-day", projectId, date] });
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Recompute failed"));
    },
  });

  // Auto-select the first supervisor when the roster loads and nothing is picked
  // yet — saves a click in the common "open the page → see today's numbers" flow.
  useEffect(() => {
    if (!supervisorUserId && roster.length > 0 && roster[0].supervisorUserId) {
      onSupervisorChange(roster[0].supervisorUserId);
    }
  }, [roster, supervisorUserId, onSupervisorChange]);

  const supervisorOptions: SelectOption[] = useMemo(
    () =>
      roster
        .filter((s) => !!s.supervisorUserId)
        .map((s) => ({
          value: s.supervisorUserId as string,
          label: `${s.supervisorName ?? "Unnamed"} — ${s.dprCount} DPR${s.dprCount === 1 ? "" : "s"}`,
        })),
    [roster],
  );

  // Day vs period query — both run conditionally on `periodType` so we never
  // pay for a query the active mode doesn't need.
  const dayQuery = useQuery({
    queryKey: ["dbs-supervisor-day", projectId, supervisorUserId, date],
    queryFn: () => dbsApi.getSupervisorDay(projectId, supervisorUserId, date),
    enabled: !!projectId && !!supervisorUserId && !!date && periodType === "DAY",
  });

  const periodQuery = useQuery({
    queryKey: ["dbs-supervisor-period", projectId, supervisorUserId, date, periodType],
    queryFn: () =>
      dbsApi.getSupervisorPeriod(projectId, supervisorUserId, date, periodType),
    enabled: !!projectId && !!supervisorUserId && !!date && periodType !== "DAY",
  });

  const boqSummaryQuery = useQuery({
    queryKey: ["dbs-boq-summary", projectId, date, periodType, supervisorUserId],
    queryFn: () => dbsApi.getBoqExecutedSummary(projectId, date, periodType, supervisorUserId),
    enabled: !!projectId && !!date && !!supervisorUserId,
  });

  const day: DbsSupervisorDayResponse | null =
    periodType === "DAY"
      ? dayQuery.data?.data ?? null
      : periodQuery.data?.data?.totals ?? null;

  const dailyRows = periodQuery.data?.data?.dailyRows ?? [];

  const isLoading = periodType === "DAY" ? dayQuery.isLoading : periodQuery.isLoading;

  if (rosterLoading) {
    return <div className="text-center text-text-muted">Loading supervisors…</div>;
  }

  if (roster.length === 0) {
    // When DPRs exist for the date but the DBS aggregate is empty, the
    // event-driven recompute hasn't fired yet (or backfill is pending).
    // Surface a one-click Recompute affordance — clearer than telling the
    // user the date is empty when it isn't.
    if (dprsForDate.length > 0) {
      return (
        <EmptyState
          title="DBS not computed for this date"
          description="No DBS data computed for this date — click Recompute to build the aggregates from the underlying DPRs."
          action={{
            label: recomputeMutation.isPending ? "Recomputing…" : "Recompute now",
            onClick: () => {
              if (recomputeMutation.isPending) return;
              recomputeMutation.mutate();
            },
          }}
        />
      );
    }
    return (
      <EmptyState
        title="No supervisor activity on this date"
        description="No DPRs were submitted by supervisors for the chosen date. Pick another date or check that DPRs have been recorded."
      />
    );
  }

  return (
    <div className="space-y-6">
      {/* Picker row */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="w-full max-w-md">
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
            Supervisor
          </label>
          <SearchableSelect
            options={supervisorOptions}
            value={supervisorUserId}
            onChange={onSupervisorChange}
            placeholder="Pick a supervisor…"
          />
        </div>
        <div className="flex items-center gap-2 text-xs text-text-muted">
          <Users size={14} />
          {roster.length} supervisor{roster.length === 1 ? "" : "s"} with activity
          {periodType !== "DAY" ? ` · ${periodType.toLowerCase()} view` : ""}
        </div>
      </div>

      {!supervisorUserId ? (
        <EmptyState
          title="Choose a supervisor"
          description="Pick a supervisor from the dropdown to see their daily balance sheet."
        />
      ) : isLoading ? (
        <div className="text-center text-text-muted">Loading DBS…</div>
      ) : !day ? (
        <EmptyState
          title="No data"
          description="No DBS data for this selection. The backend may not have computed yet — try the Recompute button from the PM tab."
        />
      ) : (
        <>
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
            boqItemsExecuted={boqSummaryQuery.data?.data?.boqItemsExecuted}
            boqQtyExecuted={boqSummaryQuery.data?.data?.boqQtyExecuted}
          />

          {/* Section accordions — order mirrors the client workbook. */}
          <div className="space-y-3">
            <SectionCard
              title="A. Manpower"
              lines={day.manpowerLines}
              total={day.manpowerAmount}
              currency={currency}
            />
            <SectionCard
              title="B. Admin / Catering"
              lines={day.adminLines}
              total={day.adminAmount}
              currency={currency}
            />
            <SectionCard
              title="C. Machinery"
              lines={day.machineryLines}
              total={day.machineryAmount}
              currency={currency}
            />
            <SectionCard
              title="D. Fuel"
              lines={day.fuelLines}
              total={day.fuelAmount}
              currency={currency}
            />
            <SectionCard
              title="E. Material"
              lines={day.materialLines}
              total={day.materialAmount}
              currency={currency}
            />
            {/* F. Sub-Contractor is intentionally hidden on the Supervisor tab.
                SC is a separate domain entity, tracked at the project level
                only — see the PM tab for the SC breakdown. */}
            <SectionCard
              title="BOQ Work executed"
              lines={day.boqLines}
              total={day.boqAchievedAmount}
              currency={currency}
            />
          </div>

          {/* Period-mode daily breakdown — cumulative columns + period totals. */}
          {periodType !== "DAY" && dailyRows.length > 0 ? (
            <DailyBreakdownTable
              rows={dailyRows}
              currency={currency}
              periodLabel={periodType.toLowerCase()}
            />
          ) : null}
        </>
      )}
    </div>
  );
}
