"use client";

import { useEffect, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, Users } from "lucide-react";

import { SearchableSelect, type SelectOption } from "@/components/common/SearchableSelect";
import { EmptyState } from "@/components/common/EmptyState";
import {
  dbsApi,
  type DbsEngineerDayResponse,
  type DbsPeriodType,
} from "@/lib/api/dbsApi";
import { projectTeamApi } from "@/lib/api/projectTeamApi";
import { userApi, type UserSummary } from "@/lib/api/userApi";
import { formatCurrency, formatPercent } from "@/lib/utils/format";

import { TotalsPanel } from "./TotalsPanel";

/**
 * Engineer / Site Manager tab — mirrors the PRE sheet in the client workbook.
 * The engineer roster comes from {@link projectTeamApi.list} filtered to the
 * ENGINEER project-role (see Phase A1 — this is the *project-scoped* hierarchy,
 * not the global RBAC role).
 *
 * The response carries `supervisorIds` — the supervisors whose daily rows roll
 * into this engineer-day. Each is rendered as a chip that, on click, switches
 * the page to the Supervisor tab pre-selected to that supervisor. URL state
 * (`?tab=supervisor&supervisor=…`) keeps the back button useful.
 */
export interface EngineerDbsTabProps {
  projectId: string;
  date: string;
  periodType: DbsPeriodType;
  engineerUserId: string;
  onEngineerChange: (value: string) => void;
  /** Called when a supervisor chip is clicked — parent switches tab + selection. */
  onNavigateToSupervisor: (supervisorUserId: string) => void;
  currency?: string | null;
}

export function EngineerDbsTab({
  projectId,
  date,
  periodType,
  engineerUserId,
  onEngineerChange,
  onNavigateToSupervisor,
  currency,
}: EngineerDbsTabProps) {
  // Engineer roster from project_team. We restrict to the ENGINEER role; the
  // backend resolves SITE_MANAGER separately if/when DBS gains a site-manager
  // rollup (out of v1 scope).
  const { data: teamData, isLoading: teamLoading } = useQuery({
    queryKey: ["project-team", projectId, "ENGINEER"],
    queryFn: () => projectTeamApi.list(projectId, "ENGINEER"),
    enabled: !!projectId,
  });
  const engineers = useMemo(() => teamData?.data ?? [], [teamData]);

  // User directory to resolve display names for engineer rows that didn't
  // come back with projected `firstName/lastName` from the backend, and for
  // the supervisor chip labels below.
  const { data: usersData } = useQuery({
    queryKey: ["users", "by-roles", ["ENGINEER", "SITE_ENGINEER", "SUPERVISOR", "FOREMAN"]],
    queryFn: () =>
      userApi.listByRoles(["ENGINEER", "SITE_ENGINEER", "SUPERVISOR", "FOREMAN"]),
  });
  const usersById = useMemo(() => {
    const m = new Map<string, UserSummary>();
    for (const u of usersData ?? []) m.set(u.id, u);
    return m;
  }, [usersData]);

  const engineerOptions: SelectOption[] = useMemo(
    () =>
      engineers.map((e) => {
        const fallback = usersById.get(e.userId);
        const name =
          [e.firstName, e.lastName].filter(Boolean).join(" ") ||
          e.username ||
          fallback?.name ||
          e.userId.slice(0, 8) + "…";
        return { value: e.userId, label: name };
      }),
    [engineers, usersById],
  );

  // Auto-pick the first engineer once the roster is in — same UX as the
  // supervisor tab.
  useEffect(() => {
    if (!engineerUserId && engineers.length > 0) {
      onEngineerChange(engineers[0].userId);
    }
  }, [engineers, engineerUserId, onEngineerChange]);

  const dayQuery = useQuery({
    queryKey: ["dbs-engineer-day", projectId, engineerUserId, date],
    queryFn: () => dbsApi.getEngineerDay(projectId, engineerUserId, date),
    enabled: !!projectId && !!engineerUserId && !!date && periodType === "DAY",
  });

  const periodQuery = useQuery({
    queryKey: ["dbs-engineer-period", projectId, engineerUserId, date, periodType],
    queryFn: () =>
      dbsApi.getEngineerPeriod(projectId, engineerUserId, date, periodType),
    enabled: !!projectId && !!engineerUserId && !!date && periodType !== "DAY",
  });

  const day: DbsEngineerDayResponse | null =
    periodType === "DAY"
      ? dayQuery.data?.data ?? null
      : periodQuery.data?.data?.totals ?? null;

  const dailyRows = periodQuery.data?.data?.dailyRows ?? [];

  const isLoading = periodType === "DAY" ? dayQuery.isLoading : periodQuery.isLoading;

  if (teamLoading) {
    return <div className="text-center text-text-muted">Loading engineers…</div>;
  }

  if (engineers.length === 0) {
    return (
      <EmptyState
        title="No engineers on the project team"
        description="Add Engineers to the Project Team (Project → Team) so their daily costs can be rolled up."
      />
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="w-full max-w-md">
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
            Engineer / Site Manager
          </label>
          <SearchableSelect
            options={engineerOptions}
            value={engineerUserId}
            onChange={onEngineerChange}
            placeholder="Pick an engineer…"
          />
        </div>
        <div className="flex items-center gap-2 text-xs text-text-muted">
          <Users size={14} />
          {engineers.length} engineer{engineers.length === 1 ? "" : "s"} on team
          {periodType !== "DAY" ? ` · ${periodType.toLowerCase()} view` : ""}
        </div>
      </div>

      {!engineerUserId ? (
        <EmptyState
          title="Choose an engineer"
          description="Pick an engineer from the dropdown to see their rolled-up daily balance sheet."
        />
      ) : isLoading ? (
        <div className="text-center text-text-muted">Loading DBS…</div>
      ) : !day ? (
        <EmptyState
          title="No data"
          description="No DBS data for this selection. Try the Recompute button from the PM tab or pick another date."
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

          <section className="rounded-lg border border-border bg-surface/50 p-4 shadow-sm">
            <h3 className="mb-3 text-sm font-semibold text-text-primary">
              Supervisors rolled up ({day.supervisorIds.length})
            </h3>
            {day.supervisorIds.length === 0 ? (
              <p className="text-xs text-text-muted">
                No supervisors had activity on this date for this engineer.
              </p>
            ) : (
              <div className="flex flex-wrap gap-2">
                {day.supervisorIds.map((sid) => {
                  const u = usersById.get(sid);
                  const label = u?.name ?? sid.slice(0, 8) + "…";
                  return (
                    <button
                      key={sid}
                      type="button"
                      onClick={() => onNavigateToSupervisor(sid)}
                      className="inline-flex items-center gap-1 rounded-full border border-amber-500/30 bg-amber-500/10 px-3 py-1 text-xs font-medium text-amber-700 transition-colors hover:bg-amber-500/20"
                      title="View this supervisor's DBS"
                    >
                      {label}
                      <ArrowRight size={12} />
                    </button>
                  );
                })}
              </div>
            )}
          </section>

          {/* Period-mode daily breakdown */}
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
