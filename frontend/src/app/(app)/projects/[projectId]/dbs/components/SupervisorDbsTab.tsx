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
    queryKey: ["dbs-supervisor-roster", projectId, date],
    queryFn: () => dbsApi.listSupervisorsForDay(projectId, date),
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
  const dprsForDate = dprData?.data ?? [];

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
            <SectionCard
              title="F. Sub-Contractor"
              lines={day.subcontractLines}
              total={day.subcontractAmount}
              currency={currency}
              comingSoon
            />
            <SectionCard
              title="BOQ Work executed"
              lines={day.boqLines}
              total={day.boqAchievedAmount}
              currency={currency}
            />
          </div>

          {/* Period-mode: daily breakdown table */}
          {periodType !== "DAY" && dailyRows.length > 0 ? (
            <section className="rounded-lg border border-border bg-surface/50 shadow-sm">
              <header className="border-b border-border px-4 py-3">
                <h3 className="text-sm font-semibold text-text-primary">
                  Daily breakdown ({periodType.toLowerCase()} view)
                </h3>
              </header>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
                    <tr>
                      <th className="px-4 py-2">Date</th>
                      <th className="px-4 py-2 text-right">Expense</th>
                      <th className="px-4 py-2 text-right">Income</th>
                      <th className="px-4 py-2 text-right">Contribution</th>
                      <th className="px-4 py-2 text-right">Contribution %</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {dailyRows.map((row) => (
                      <tr key={row.reportDate}>
                        <td className="px-4 py-2 text-text-primary">{row.reportDate}</td>
                        <td className="px-4 py-2 text-right font-mono text-text-secondary">
                          {formatCurrency(row.totalExpense, currency)}
                        </td>
                        <td className="px-4 py-2 text-right font-mono text-text-secondary">
                          {formatCurrency(row.totalIncome, currency)}
                        </td>
                        <td
                          className={`px-4 py-2 text-right font-mono ${
                            row.contribution > 0
                              ? "text-emerald-600"
                              : row.contribution < 0
                                ? "text-rose-600"
                                : "text-text-secondary"
                          }`}
                        >
                          {formatCurrency(row.contribution, currency)}
                        </td>
                        <td className="px-4 py-2 text-right font-mono text-text-secondary">
                          {formatPercent(row.contributionPct * 100)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ) : null}
        </>
      )}
    </div>
  );
}
