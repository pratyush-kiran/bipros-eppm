"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";

import { PageHeader } from "@/components/common/PageHeader";
import { TabTip } from "@/components/common/TabTip";
import { budgetApi } from "@/lib/api/budgetApi";
import type { DbsPeriodType } from "@/lib/api/dbsApi";

import { CmDbsTab } from "./components/CmDbsTab";
import { EngineerDbsTab } from "./components/EngineerDbsTab";
import { PmDbsTab } from "./components/PmDbsTab";
import { SupervisorDbsTab } from "./components/SupervisorDbsTab";

/**
 * Phase C — Daily Balance Sheet (DBS) dashboard.
 *
 * Three tabs that mirror the client workbook (`docs/ActualData/3. Supervisor-
 * Engineer-CM-PM DBS (2).xlsx`):
 *
 *   - Supervisor → Anbazhagan-TS sheet (one supervisor's day with 7 section
 *     accordions + period rollups)
 *   - Engineer   → PRE sheet (engineer-day with chips for the supervisors that
 *     rolled into it; clicking a chip drops you on the Supervisor tab pre-picked)
 *   - PM         → Summary-Financial (NEW) (project rollup with per-engineer
 *     breakdown columns, cumulative tiles, and admin Recompute buttons)
 *
 * State lives in the URL so back/forward + share-link work cleanly:
 *   ?tab=supervisor|engineer|pm  ?date=YYYY-MM-DD  ?period=DAY|WEEK|MONTH
 *   ?supervisor=<userId>  ?engineer=<userId>
 *
 * Period rollups (WEEK / MONTH) are computed server-side by SUM over the daily
 * tables — see {@link dbsApi.getSupervisorPeriod}. Cumulative columns are
 * computed on read so late edits stay consistent without cascade.
 */

const TABS = ["supervisor", "engineer", "cm", "pm"] as const;
type DbsTab = (typeof TABS)[number];
const TAB_LABELS: Record<DbsTab, string> = {
  supervisor: "Supervisor",
  engineer: "Engineer / Site Manager",
  cm: "Construction Manager",
  pm: "Project Manager",
};

const PERIODS: DbsPeriodType[] = ["DAY", "WEEK", "MONTH"];
const PERIOD_LABELS: Record<DbsPeriodType, string> = {
  DAY: "Day",
  WEEK: "Week",
  MONTH: "Month",
};

function todayIso(): string {
  // YYYY-MM-DD in local time. Matches the date-input's expected format and the
  // backend's @RequestParam LocalDate parser.
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function DbsPageInner() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const router = useRouter();
  const searchParams = useSearchParams();

  // --- URL state — single source of truth, mirrored into local state via effects
  // when we need to push back to the URL. Reading is direct from searchParams.
  const tabParam = searchParams.get("tab") as DbsTab | null;
  const activeTab: DbsTab = TABS.includes(tabParam as DbsTab)
    ? (tabParam as DbsTab)
    : "supervisor";

  const dateParam = searchParams.get("date");
  const date = dateParam || todayIso();

  const periodParam = searchParams.get("period") as DbsPeriodType | null;
  const periodType: DbsPeriodType = PERIODS.includes(periodParam as DbsPeriodType)
    ? (periodParam as DbsPeriodType)
    : "DAY";

  const supervisorParam = searchParams.get("supervisor") ?? "";
  const engineerParam = searchParams.get("engineer") ?? "";
  const cmParam = searchParams.get("cm") ?? "";

  // Helper to update one query param without dropping the others. `null` removes.
  const updateParams = useCallback(
    (updates: Record<string, string | null>) => {
      const next = new URLSearchParams(searchParams.toString());
      for (const [k, v] of Object.entries(updates)) {
        if (v === null || v === "") next.delete(k);
        else next.set(k, v);
      }
      router.replace(`?${next.toString()}`, { scroll: false });
    },
    [router, searchParams],
  );

  // Ensure the URL always carries the resolved tab/date/period so refresh+share
  // are reproducible. Runs once on mount + whenever the *resolved* values
  // diverge from the URL.
  useEffect(() => {
    const missing: Record<string, string | null> = {};
    if (!tabParam) missing.tab = activeTab;
    if (!dateParam) missing.date = date;
    if (!periodParam) missing.period = periodType;
    if (Object.keys(missing).length > 0) updateParams(missing);
    // We intentionally only run this when the URL is missing values, not on
    // every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Local controlled picker state — initialised from URL, written back to URL.
  const [supervisorUserId, setSupervisorUserId] = useState(supervisorParam);
  const [engineerUserId, setEngineerUserId] = useState(engineerParam);
  const [cmUserId, setCmUserId] = useState(cmParam);

  // Sync local state ← URL when the user navigates externally (e.g. via the
  // chip-click on the Engineer tab).
  useEffect(() => {
    setSupervisorUserId(supervisorParam);
  }, [supervisorParam]);
  useEffect(() => {
    setEngineerUserId(engineerParam);
  }, [engineerParam]);
  useEffect(() => {
    setCmUserId(cmParam);
  }, [cmParam]);

  const handleSupervisorChange = useCallback(
    (value: string) => {
      setSupervisorUserId(value);
      updateParams({ supervisor: value || null });
    },
    [updateParams],
  );
  const handleEngineerChange = useCallback(
    (value: string) => {
      setEngineerUserId(value);
      updateParams({ engineer: value || null });
    },
    [updateParams],
  );
  const handleCmChange = useCallback(
    (value: string) => {
      setCmUserId(value);
      updateParams({ cm: value || null });
    },
    [updateParams],
  );

  // Engineer → Supervisor cross-tab jump. URL updates first so a refresh from
  // the new tab restores the right supervisor selection.
  const handleNavigateToSupervisor = useCallback(
    (sid: string) => {
      updateParams({ tab: "supervisor", supervisor: sid });
    },
    [updateParams],
  );

  // Project currency for money formatting. Falls back to INR when not set on
  // the budget summary.
  const { data: budgetData } = useQuery({
    queryKey: ["budget-summary", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
    enabled: !!projectId,
  });
  const currency = budgetData?.data?.budgetCurrency ?? "INR";

  const headerDescription = useMemo(
    () =>
      `Daily Balance Sheet — supervisor → engineer → project rollup of cost vs revenue. Source data: DPR + Daily Resource Deployment + Material Consumption.`,
    [],
  );

  return (
    <div className="space-y-6 p-6">
      <TabTip
        title="Daily Balance Sheet (DBS)"
        description="Three-level cost rollup mirroring the client costing workbook (Supervisor, Engineer, PM). Aggregates refresh event-driven when DPRs / deployments / material consumption are saved. Use the PM tab's Recompute to force a rebuild after backfills."
      />

      <PageHeader
        title="Daily Balance Sheet"
        description={headerDescription}
        actions={
          <div className="flex flex-wrap items-end gap-3">
            <div>
              <label className="mb-1 block text-[10px] font-semibold uppercase tracking-[0.12em] text-text-secondary">
                Date
              </label>
              <input
                type="date"
                value={date}
                onChange={(e) => updateParams({ date: e.target.value || null })}
                className="rounded-md border border-border bg-surface-hover px-3 py-1.5 text-sm text-text-primary"
              />
            </div>
            <div>
              <label className="mb-1 block text-[10px] font-semibold uppercase tracking-[0.12em] text-text-secondary">
                Period
              </label>
              <div className="inline-flex overflow-hidden rounded-md border border-border bg-surface">
                {PERIODS.map((p) => (
                  <button
                    key={p}
                    type="button"
                    onClick={() => updateParams({ period: p })}
                    className={`px-3 py-1.5 text-xs font-semibold transition-colors ${
                      periodType === p
                        ? "bg-accent text-accent-foreground"
                        : "text-text-secondary hover:bg-surface-hover"
                    }`}
                  >
                    {PERIOD_LABELS[p]}
                  </button>
                ))}
              </div>
            </div>
          </div>
        }
      />

      {/* Tabs — button strip pattern reused from risk-analysis page. No shadcn
          Tabs component exists in this repo; the manual strip keeps the bundle
          tight and the keyboard semantics simple. */}
      <div className="flex gap-2 border-b border-border">
        {TABS.map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => updateParams({ tab: t })}
            className={`px-4 py-2 text-sm font-medium ${
              activeTab === t
                ? "border-b-2 border-accent text-accent"
                : "text-text-secondary hover:text-text-primary"
            }`}
          >
            {TAB_LABELS[t]}
          </button>
        ))}
      </div>

      {activeTab === "supervisor" ? (
        <SupervisorDbsTab
          projectId={projectId}
          date={date}
          periodType={periodType}
          supervisorUserId={supervisorUserId}
          onSupervisorChange={handleSupervisorChange}
          currency={currency}
        />
      ) : null}

      {activeTab === "engineer" ? (
        <EngineerDbsTab
          projectId={projectId}
          date={date}
          periodType={periodType}
          engineerUserId={engineerUserId}
          onEngineerChange={handleEngineerChange}
          onNavigateToSupervisor={handleNavigateToSupervisor}
          currency={currency}
        />
      ) : null}

      {activeTab === "cm" ? (
        <CmDbsTab
          projectId={projectId}
          date={date}
          periodType={periodType}
          cmUserId={cmUserId}
          onCmChange={handleCmChange}
          currency={currency}
        />
      ) : null}

      {activeTab === "pm" ? (
        <PmDbsTab
          projectId={projectId}
          date={date}
          periodType={periodType}
          currency={currency}
          onNavigateToCm={(cid: string) => {
            updateParams({ tab: "cm", cm: cid });
          }}
        />
      ) : null}
    </div>
  );
}

export default function DbsPage() {
  // useSearchParams suspends in Next 16 App Router — wrap so the page can be
  // pre-rendered while the params resolve client-side.
  return (
    <Suspense fallback={<div className="p-6 text-text-muted">Loading DBS…</div>}>
      <DbsPageInner />
    </Suspense>
  );
}
