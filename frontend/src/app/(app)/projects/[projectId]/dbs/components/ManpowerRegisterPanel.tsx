"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";

import { EmptyState } from "@/components/common/EmptyState";
import { dbsApi, type CmShiftCount } from "@/lib/api/dbsApi";

/**
 * Phase 8 — Manpower Deployment Register panel.
 *
 * Mirror of {@link EquipmentRegisterPanel} but rows are keyed by trade
 * (Mason / Carpenter / Helper / …) instead of equipment type. Same Today /
 * Cumulative toggle; cumulative collapses to a two-column table because the
 * cumulative endpoint does not carry CM / shift columns.
 */
export interface ManpowerRegisterPanelProps {
  projectId: string;
  date: string;
}

type Mode = "today" | "cumulative";

export function ManpowerRegisterPanel({ projectId, date }: ManpowerRegisterPanelProps) {
  const [mode, setMode] = useState<Mode>("today");

  const todayQuery = useQuery({
    queryKey: ["dbs-manpower-register", projectId, date],
    queryFn: () => dbsApi.getManpowerRegister(projectId, date),
    enabled: !!projectId && !!date && mode === "today",
  });

  const cumulativeQuery = useQuery({
    queryKey: ["dbs-cumulative-days", projectId, date],
    queryFn: () => dbsApi.getCumulative(projectId, date),
    enabled: !!projectId && !!date && mode === "cumulative",
  });

  const manpowerRows = useMemo(
    () => todayQuery.data?.data?.manpower ?? [],
    [todayQuery.data],
  );
  const cumulativeRows = useMemo(
    () => cumulativeQuery.data?.data?.manpower ?? [],
    [cumulativeQuery.data],
  );

  // Track raw cmName so we can distinguish a named CM from an unattributed bucket.
  // A null cmName means the supervisors who logged this row don't report through
  // a Construction Manager in the project team — we surface that as "Unassigned"
  // and show a banner pointing the PM at Project → Team.
  const cmColumns = useMemo(() => {
    const map = new Map<string, string | null>();
    for (const row of manpowerRows) {
      for (const cm of row.byCm) {
        if (!map.has(cm.cmUserId)) map.set(cm.cmUserId, cm.cmName ?? null);
      }
    }
    return Array.from(map.entries()).map(([cmUserId, rawName]) => ({
      cmUserId,
      displayName: rawName ?? "Unassigned",
      isUnassigned: !rawName,
    }));
  }, [manpowerRows]);

  const hasUnassigned = useMemo(
    () => cmColumns.some((c) => c.isUnassigned),
    [cmColumns],
  );

  const lookupCm = (row: { byCm: CmShiftCount[] }, cmUserId: string) =>
    row.byCm.find((c) => c.cmUserId === cmUserId);

  return (
    <section className="rounded-lg border border-border bg-surface/50 shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
        <div>
          <h3 className="text-sm font-semibold text-text-primary">
            Manpower Deployment Register
          </h3>
          <p className="mt-0.5 text-xs text-text-muted">
            Per trade, sliced by Construction Manager and shift. Toggle
            Cumulative to see total man-days since project start.
          </p>
        </div>
        <div className="inline-flex overflow-hidden rounded-md border border-border bg-surface">
          <button
            type="button"
            onClick={() => setMode("today")}
            className={`px-3 py-1.5 text-xs font-semibold transition-colors ${
              mode === "today"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover"
            }`}
          >
            Today
          </button>
          <button
            type="button"
            onClick={() => setMode("cumulative")}
            className={`px-3 py-1.5 text-xs font-semibold transition-colors ${
              mode === "cumulative"
                ? "bg-accent text-accent-foreground"
                : "text-text-secondary hover:bg-surface-hover"
            }`}
          >
            Cumulative
          </button>
        </div>
      </header>

      {mode === "today" ? (
        todayQuery.isLoading ? (
          <div className="px-4 py-6 text-center text-xs text-text-muted">Loading register…</div>
        ) : manpowerRows.length === 0 ? (
          <EmptyState
            title="No manpower deployed on this date"
            description="No DPR manpower rows submitted for this date — nothing to roll up."
          />
        ) : (
          <>
            {hasUnassigned ? (
              <div className="border-b border-amber-500/40 bg-amber-500/10 px-4 py-2 text-xs text-amber-800 dark:text-amber-300">
                Some man-days are not attributed to a Construction Manager. Assign CMs to
                your supervisors in{" "}
                <a
                  href={`/projects/${projectId}/team`}
                  className="font-semibold underline hover:no-underline"
                >
                  Project → Team
                </a>{" "}
                to see per-CM slicing.
              </div>
            ) : null}
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid="manpower-register-today">
                <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
                  <tr>
                    <th className="px-4 py-2">Trade</th>
                    <th className="px-4 py-2 text-right">Total</th>
                    {cmColumns.map((cm) => (
                      <th
                        key={cm.cmUserId}
                        colSpan={2}
                        className={`border-l border-border px-4 py-2 text-center ${
                          cm.isUnassigned ? "italic text-text-muted" : ""
                        }`}
                        title={cm.displayName}
                      >
                        {cm.displayName}
                      </th>
                    ))}
                  </tr>
                <tr className="text-[10px] uppercase tracking-wide text-text-muted">
                  <th className="px-4 pb-2"></th>
                  <th className="px-4 pb-2 text-right">Day+Night</th>
                  {cmColumns.flatMap((cm) => [
                    <th
                      key={`${cm.cmUserId}-day`}
                      className="border-l border-border px-2 pb-2 text-right"
                    >
                      Day
                    </th>,
                    <th key={`${cm.cmUserId}-night`} className="px-2 pb-2 text-right">
                      Night
                    </th>,
                  ])}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {manpowerRows.map((row) => (
                  <tr key={row.trade}>
                    <td className="px-4 py-2 font-medium text-text-primary">{row.trade}</td>
                    <td className="px-4 py-2 text-right font-mono font-semibold text-text-primary">
                      {row.total}
                    </td>
                    {cmColumns.flatMap((cm) => {
                      const hit = lookupCm(row, cm.cmUserId);
                      return [
                        <td
                          key={`${row.trade}-${cm.cmUserId}-day`}
                          className="border-l border-border px-2 py-2 text-right font-mono text-text-secondary"
                        >
                          {hit?.day ?? 0}
                        </td>,
                        <td
                          key={`${row.trade}-${cm.cmUserId}-night`}
                          className="px-2 py-2 text-right font-mono text-text-secondary"
                        >
                          {hit?.night ?? 0}
                        </td>,
                      ];
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          </>
        )
      ) : cumulativeQuery.isLoading ? (
        <div className="px-4 py-6 text-center text-xs text-text-muted">
          Loading cumulative man-days…
        </div>
      ) : cumulativeRows.length === 0 ? (
        <EmptyState
          title="No cumulative man-days yet"
          description="No manpower has been deployed on this project as of the chosen date."
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm" data-testid="manpower-register-cumulative">
            <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
              <tr>
                <th className="px-4 py-2">Trade</th>
                <th className="px-4 py-2 text-right">Cumulative Man-Days</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {cumulativeRows.map((row) => (
                <tr key={row.trade}>
                  <td className="px-4 py-2 font-medium text-text-primary">{row.trade}</td>
                  <td className="px-4 py-2 text-right font-mono font-semibold text-text-primary">
                    {row.days}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
