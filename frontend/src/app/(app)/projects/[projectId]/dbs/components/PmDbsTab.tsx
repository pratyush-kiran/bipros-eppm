"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  RefreshCw,
  Calendar,
  AlertCircle,
  ArrowRight,
  FileSpreadsheet,
  FileText,
  Loader2,
} from "lucide-react";
import Link from "next/link";
import toast from "react-hot-toast";

import { EmptyState } from "@/components/common/EmptyState";
import { KpiTile } from "@/components/common/KpiTile";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  dbsApi,
  type DbsAlertCode,
  type DbsPeriodType,
  type DbsProjectDayResponse,
} from "@/lib/api/dbsApi";
import { userApi, type UserSummary } from "@/lib/api/userApi";
import { useAuthStore } from "@/lib/state/store";
import { getErrorMessage } from "@/lib/utils/error";
import { formatCurrency, formatPercent } from "@/lib/utils/format";

import { DailyBreakdownTable } from "./DailyBreakdownTable";
import { EquipmentRegisterPanel } from "./EquipmentRegisterPanel";
import { ManpowerRegisterPanel } from "./ManpowerRegisterPanel";
import { SubContractorSection } from "./SubContractorSection";
import { TotalsPanel } from "./TotalsPanel";

/**
 * PM tab — mirrors the "Summary-Financial (NEW)" sheet in the client workbook.
 * No picker; the response is the project rollup. Layout:
 *
 *   1. TotalsPanel (P&L + per-section).
 *   2. Cumulative tiles (Cumulative Expense / Income / Contribution) when present.
 *   3. Per-engineer breakdown table — loops {@link DbsProjectDayResponse.engineerIds}
 *      and fans out {@link dbsApi.getEngineerDay} via `useQueries`. Columns mirror
 *      the workbook: Plan Amount, Achieved (Income), Cost (Expense), Cost%,
 *      Contribution, Contribution%, plus a Profit/Loss chip.
 *   4. Admin actions — Recompute (this date) and Recompute range. Both require
 *      the `DBS.RECOMPUTE` permission; we degrade gracefully when the user lacks
 *      it (falls back to a hidden affordance — server is the authority).
 */
/**
 * Visual mapping for {@link DbsAlertCode}s rendered in the alerts banner.
 * Red = blocking (NEGATIVE_CONTRIBUTION); amber = warning (LOW_CONTRIBUTION_PCT,
 * RUNAWAY_FUEL); blue = informational (MISSING_RATE_DATA).
 */
const ALERT_META: Record<DbsAlertCode, { label: string; description: string; classes: string }> = {
  NEGATIVE_CONTRIBUTION: {
    label: "Negative contribution",
    description: "Expenses exceeded income for this date — investigate cost overruns.",
    classes:
      "border-rose-500/40 bg-rose-500/10 text-rose-700 dark:text-rose-300",
  },
  LOW_CONTRIBUTION_PCT: {
    label: "Low contribution %",
    description: "Contribution margin is below 5% of income.",
    classes:
      "border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300",
  },
  RUNAWAY_FUEL: {
    label: "Runaway fuel cost",
    description: "Fuel exceeds 50% of total expense — check equipment utilisation.",
    classes:
      "border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300",
  },
  MISSING_RATE_DATA: {
    label: "Missing rate data",
    description:
      "Manpower & machinery amounts are zero despite work happening — check Rate Master coverage.",
    classes:
      "border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300",
  },
};

export interface PmDbsTabProps {
  projectId: string;
  date: string;
  periodType: DbsPeriodType;
  currency?: string | null;
  /**
   * Phase 8 — callback fired when the user clicks "drill in" on a row in the
   * "Group by CM" table. Parent switches the active tab to `cm` and seeds the
   * `?cm=<userId>` query param.
   */
  onNavigateToCm?: (cmUserId: string) => void;
}

export function PmDbsTab({
  projectId,
  date,
  periodType,
  currency,
  onNavigateToCm,
}: PmDbsTabProps) {
  const qc = useQueryClient();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  // Permission code is best-effort — backend enforces the real ABAC. We mirror
  // the convention used by other admin affordances (e.g. PROJECT_MEMBER.MANAGE).
  const canRecompute =
    hasPermission("DBS.RECOMPUTE") || hasPermission("PROJECT_MEMBER.MANAGE");

  const [confirmRecompute, setConfirmRecompute] = useState(false);
  const [confirmCumulativeRecompute, setConfirmCumulativeRecompute] = useState(false);
  const [rangeOpen, setRangeOpen] = useState(false);
  const [rangeFrom, setRangeFrom] = useState("");
  const [rangeTo, setRangeTo] = useState("");
  const [exporting, setExporting] = useState<"xlsx" | "pdf" | null>(null);
  // Phase 8 — Group-by-CM toggle. When on, the engineer breakdown table is
  // replaced with a per-CM table (no per-engineer fan-out queries; uses the
  // `/cms` summary endpoint directly).
  const [groupByCm, setGroupByCm] = useState(false);
  const [activeJobId, setActiveJobId] = useState<string | null>(null);

  const handleExport = async (kind: "xlsx" | "pdf") => {
    if (!projectId || !date) return;
    setExporting(kind);
    try {
      if (kind === "xlsx") {
        await dbsApi.downloadExcel(projectId, date, "PM");
      } else {
        await dbsApi.downloadPdf(projectId, date, "PM");
      }
    } catch (err) {
      toast.error(getErrorMessage(err, `Export ${kind.toUpperCase()} failed`));
    } finally {
      setExporting(null);
    }
  };

  const dayQuery = useQuery({
    queryKey: ["dbs-project-day", projectId, date],
    queryFn: () => dbsApi.getProjectDay(projectId, date),
    enabled: !!projectId && !!date && periodType === "DAY",
  });

  // Alerts banner — fetched separately so the banner can render even if the
  // project rollup payload changes shape. Empty array is normal (no alerts to show).
  const alertsQuery = useQuery({
    queryKey: ["dbs-project-alerts", projectId, date],
    queryFn: () => dbsApi.getAlerts(projectId, date),
    enabled: !!projectId && !!date && periodType === "DAY",
  });
  const alerts: DbsAlertCode[] = alertsQuery.data?.data ?? [];

  const periodQuery = useQuery({
    queryKey: ["dbs-project-period", projectId, date, periodType],
    queryFn: () => dbsApi.getProjectPeriod(projectId, date, periodType),
    enabled: !!projectId && !!date && periodType !== "DAY",
  });

  const boqSummaryQuery = useQuery({
    queryKey: ["dbs-boq-summary", projectId, date, periodType],
    queryFn: () => dbsApi.getBoqExecutedSummary(projectId, date, periodType),
    enabled: !!projectId && !!date,
  });

  const day: DbsProjectDayResponse | null =
    periodType === "DAY"
      ? dayQuery.data?.data ?? null
      : periodQuery.data?.data?.totals ?? null;

  const dailyRows = periodQuery.data?.data?.dailyRows ?? [];

  const isLoading = periodType === "DAY" ? dayQuery.isLoading : periodQuery.isLoading;

  // Per-engineer breakdown — fan out one query per engineerId. Only enabled in
  // DAY mode for v1; period rollups would need per-engineer period queries
  // which is a small follow-up (not in spec).
  const engineerIds = day?.engineerIds ?? [];
  const engineerQueries = useQueries({
    queries: engineerIds.map((eid) => ({
      queryKey: ["dbs-engineer-day", projectId, eid, date],
      queryFn: () => dbsApi.getEngineerDay(projectId, eid, date),
      enabled: !!projectId && !!eid && !!date && periodType === "DAY",
    })),
  });

  const { data: usersData } = useQuery({
    queryKey: ["users", "by-roles", ["ENGINEER", "SITE_ENGINEER", "CONSTRUCTION_MANAGER"]],
    queryFn: () =>
      userApi.listByRoles(["ENGINEER", "SITE_ENGINEER", "CONSTRUCTION_MANAGER"]),
  });
  const usersById = useMemo(() => {
    const m = new Map<string, UserSummary>();
    for (const u of usersData ?? []) m.set(u.id, u);
    return m;
  }, [usersData]);

  // Phase 8 — CM roster for the Group-by-CM table. Only fetched when the toggle
  // is on, in DAY mode (period rollups aren't supported per-CM in v1).
  const cmsQuery = useQuery({
    queryKey: ["dbs-cms-roster", projectId, date],
    queryFn: () => dbsApi.listCms(projectId, date),
    enabled: !!projectId && !!date && periodType === "DAY" && groupByCm,
  });
  const cms = cmsQuery.data?.data ?? [];

  const recomputeMutation = useMutation({
    mutationFn: () => dbsApi.recompute(projectId, date),
    onSuccess: () => {
      toast.success("Recompute triggered. Aggregates will refresh shortly.");
      setConfirmRecompute(false);
      qc.invalidateQueries({ queryKey: ["dbs-project-day", projectId, date] });
      qc.invalidateQueries({ queryKey: ["dbs-project-period", projectId, date] });
      qc.invalidateQueries({ queryKey: ["dbs-project-alerts", projectId, date] });
      qc.invalidateQueries({ queryKey: ["dbs-supervisor-day", projectId] });
      qc.invalidateQueries({ queryKey: ["dbs-engineer-day", projectId] });
      qc.invalidateQueries({ queryKey: ["dbs-supervisor-roster", projectId, date] });
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Recompute failed"));
    },
  });

  // Invalidate every DBS aggregate this tab (and its children) render, so the
  // numbers refresh once a recompute finishes. Shared by the completion effect
  // and the instant-finish path below.
  const invalidateDbsAggregates = useCallback(() => {
    for (const key of [
      "dbs-project-day",
      "dbs-project-period",
      "dbs-project-alerts",
      "dbs-supervisor-day",
      "dbs-engineer-day",
      "dbs-boq-summary",
      "dbs-cms-roster",
    ]) {
      qc.invalidateQueries({ queryKey: [key, projectId] });
    }
  }, [qc, projectId]);

  const recomputeRangeMutation = useMutation({
    mutationFn: () => dbsApi.recomputeRange(projectId, rangeFrom, rangeTo),
    onSuccess: (data) => {
      const job = data.data;
      const label = `${rangeFrom} → ${rangeTo}`;
      setRangeOpen(false);
      setRangeFrom("");
      setRangeTo("");
      if (!job) return;
      if (job.status === "QUEUED" || job.status === "RUNNING") {
        setActiveJobId(job.jobId);
        toast.success(`Recompute queued — running in the background (${label}).`);
      } else if (job.status === "SUCCEEDED") {
        // Instantly done (no days to process) — no background phase.
        invalidateDbsAggregates();
        toast.success("DBS recompute completed (nothing to process).");
      } else {
        toast.error(job.errorMessage ?? "Range recompute failed");
      }
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Range recompute failed"));
    },
  });

  const recomputeCumulativeMutation = useMutation({
    mutationFn: () => dbsApi.recomputeCumulative(projectId),
    onSuccess: (data) => {
      const job = data.data;
      setConfirmCumulativeRecompute(false);
      if (!job) return;
      if (job.status === "QUEUED" || job.status === "RUNNING") {
        setActiveJobId(job.jobId);
        toast.success("Recompute queued — running in the background.");
      } else if (job.status === "SUCCEEDED") {
        // Instantly done (no days to process) — no background phase.
        invalidateDbsAggregates();
        toast.success("DBS recompute completed (nothing to process).");
      } else {
        toast.error(job.errorMessage ?? "Cumulative recompute failed");
      }
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Cumulative recompute failed"));
    },
  });

  // Polling query for the active background recompute job.
  const jobQuery = useQuery({
    queryKey: ["dbs-recompute-job", projectId, activeJobId],
    queryFn: () => dbsApi.getRecomputeJob(projectId, activeJobId!),
    enabled: !!activeJobId,
    refetchInterval: (q) => {
      const s = q.state.data?.data?.status;
      return s === "QUEUED" || s === "RUNNING" ? 1500 : false;
    },
  });

  // Re-attach on mount: if the server still has an active job (navigated away
  // and back while the server was still running it), resume the progress chip.
  const latestJobQuery = useQuery({
    queryKey: ["dbs-latest-recompute-job", projectId],
    queryFn: () => dbsApi.getLatestRecomputeJob(projectId),
    enabled: !!projectId,
  });

  // Seed at most once per mount. The ref is load-bearing for two reasons:
  // (1) after the job completes and the completion effect clears activeJobId,
  // re-running this effect would otherwise re-seed from the stale, never-
  // refetched latestJobQuery snapshot and loop the success toast; (2) a
  // window-focus refetch of latestJobQuery would re-trigger the same re-seed.
  const reattachedRef = useRef(false);
  useEffect(() => {
    if (reattachedRef.current) return;
    const job = latestJobQuery.data?.data;
    if (job && (job.status === "QUEUED" || job.status === "RUNNING")) {
      reattachedRef.current = true;
      if (!activeJobId) setActiveJobId(job.jobId);
    }
  }, [latestJobQuery.data, activeJobId]);

  // Side-effects on job completion: invalidate DBS queries and notify the user.
  // Guard: only fires when activeJobId is set, preventing double-fire after clear.
  useEffect(() => {
    if (!activeJobId) return;
    const status = jobQuery.data?.data?.status;
    if (status === "SUCCEEDED") {
      invalidateDbsAggregates();
      toast.success("DBS recompute completed. Refreshing data…");
      setActiveJobId(null);
    } else if (status === "FAILED") {
      toast.error(jobQuery.data?.data?.errorMessage ?? "Recompute failed");
      setActiveJobId(null);
    } else if (jobQuery.isSuccess && jobQuery.data?.data == null) {
      // Job no longer known to the server (e.g. it restarted and lost the
      // in-memory job). Stop tracking so the chip clears and buttons re-enable.
      setActiveJobId(null);
    }
  }, [jobQuery.data, jobQuery.isSuccess, activeJobId, invalidateDbsAggregates]);

  const activeJob = activeJobId ? (jobQuery.data?.data ?? null) : null;
  const isJobActive =
    !!activeJobId && (activeJob?.status === "QUEUED" || activeJob?.status === "RUNNING");

  return (
    <div className="space-y-6">
      {/* Admin actions + exports */}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <button
          type="button"
          onClick={() => handleExport("xlsx")}
          disabled={exporting !== null}
          className="inline-flex items-center gap-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm text-text-primary hover:bg-surface-hover disabled:opacity-60"
        >
          <FileSpreadsheet size={14} />
          {exporting === "xlsx" ? "Exporting…" : "Export Excel"}
        </button>
        <button
          type="button"
          onClick={() => handleExport("pdf")}
          disabled={exporting !== null}
          className="inline-flex items-center gap-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm text-text-primary hover:bg-surface-hover disabled:opacity-60"
        >
          <FileText size={14} />
          {exporting === "pdf" ? "Exporting…" : "Export PDF"}
        </button>
        {canRecompute ? (
          <>
            <button
              type="button"
              onClick={() => setConfirmRecompute(true)}
              disabled={recomputeMutation.isPending}
              className="inline-flex items-center gap-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm text-text-primary hover:bg-surface-hover disabled:opacity-60"
            >
              <RefreshCw size={14} />
              Recompute (this date)
            </button>
            <button
              type="button"
              onClick={() => setRangeOpen(true)}
              disabled={isJobActive}
              className="inline-flex items-center gap-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm text-text-primary hover:bg-surface-hover disabled:opacity-60"
            >
              <Calendar size={14} />
              Recompute range
            </button>
            <button
              type="button"
              onClick={() => setConfirmCumulativeRecompute(true)}
              disabled={recomputeCumulativeMutation.isPending || isJobActive}
              className="inline-flex items-center gap-2 rounded-md border border-border bg-surface px-3 py-1.5 text-sm text-text-primary hover:bg-surface-hover disabled:opacity-60"
            >
              <RefreshCw size={14} />
              {recomputeCumulativeMutation.isPending ? "Recomputing…" : "Cumulative recompute"}
            </button>
            {isJobActive ? (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-sky-500/30 bg-sky-500/10 px-3 py-1.5 text-xs text-sky-700 dark:text-sky-300">
                <Loader2 size={12} className="animate-spin" />
                Recomputing… {activeJob?.processedDays ?? 0}/{activeJob?.totalDays ?? 0} days
              </span>
            ) : null}
          </>
        ) : null}
      </div>

      {isLoading ? (
        <div className="text-center text-text-muted">Loading DBS…</div>
      ) : !day ? (
        <EmptyState
          title="No project rollup yet"
          description="The backend has not computed a DBS for this date. Run Recompute (above) or wait for the next event-driven recalculation."
        />
      ) : (
        <>
          {/* Headline counts strip */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <KpiTile label="Engineers" value={day.engineerIds.length} />
            <KpiTile label="Supervisors" value={day.supervisorCount} />
            <KpiTile label="DPRs" value={day.dprCount} />
            <KpiTile
              label="Period"
              value={periodType === "DAY" ? "Day" : periodType === "WEEK" ? "Week" : "Month"}
              hint={
                periodType !== "DAY" && periodQuery.data?.data
                  ? `${periodQuery.data.data.from} → ${periodQuery.data.data.to}`
                  : date
              }
            />
          </div>

          {/* Alerts banner — only rendered when the backend returns one or more
              codes. Colour mapping mirrors the spec: red for NEGATIVE_CONTRIBUTION,
              amber for LOW_CONTRIBUTION_PCT / RUNAWAY_FUEL, blue for MISSING_RATE_DATA. */}
          {alerts.length > 0 ? (
            <section
              role="alert"
              aria-label="DBS alerts"
              className="space-y-2 rounded-lg border border-border bg-surface/30 p-3"
            >
              {alerts.map((code) => {
                const meta = ALERT_META[code];
                if (!meta) return null;
                return (
                  <div
                    key={code}
                    className={`flex items-start gap-2 rounded-md border px-3 py-2 text-sm ${meta.classes}`}
                  >
                    <AlertCircle size={14} className="mt-0.5 shrink-0" />
                    <div>
                      <span className="font-semibold">{meta.label}</span>
                      <span className="ml-1 opacity-80">{meta.description}</span>
                    </div>
                  </div>
                );
              })}
            </section>
          ) : null}

          {/* Phase 8 — Prelim-aware KPI tiles. Sit above the standard P&L panel so
              they're the first numbers a PM sees on opening the tab. Falls back
              to 0 / "—" when the backend hasn't populated the columns yet. */}
          <div
            className="grid grid-cols-2 gap-3 sm:grid-cols-4"
            data-testid="pm-prelim-kpis"
          >
            <KpiTile
              label="Direct BOQ Value"
              value={formatCurrency(day.directCost ?? 0, currency)}
              hint="Today's BOQ income · excl. preliminaries"
              tone="warning"
            />
            <KpiTile
              label="Prelim BOQ Value"
              value={formatCurrency(day.prelimCost ?? 0, currency)}
              hint="Today's BOQ income · prelim items only"
            />
            <KpiTile
              label="Total BOQ Value"
              value={formatCurrency(day.totalCostInclPrelims ?? 0, currency)}
              hint="Direct + Prelim BOQ value today"
              tone="accent"
            />
            <KpiTile
              label="% Achieved"
              value={
                day.pctAchieved != null
                  ? formatPercent(day.pctAchieved)
                  : "—"
              }
              hint="qty executed-to-date ÷ planned"
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
            generalExpenseAmount={day.generalExpenseAmount ?? 0}
            generalExpenseMonthlyTotal={day.generalExpenseMonthlyTotal ?? null}
            showSubContractor
            totalExpense={day.totalExpense}
            totalIncome={day.totalIncome}
            contribution={day.contribution}
            contributionPct={day.contributionPct * 100}
            currency={currency}
            boqItemsExecuted={boqSummaryQuery.data?.data?.boqItemsExecuted}
            boqQtyExecuted={boqSummaryQuery.data?.data?.boqQtyExecuted}
          />

          {/* F. Sub-Contractor — project-level breakdown. Hidden on other tabs
              because SC is a separate domain entity (not attributed under a
              supervisor / engineer / CM). Grouped by sub-contractor master with
              per-work-type sub-rows mirroring the Excel Anbazhagan-TS layout. */}
          <SubContractorSection
            lines={day.subcontractLines ?? []}
            totalExpense={day.subcontractAmount}
            currency={currency}
          />

          {/* Quick link to the Section G entry page — the totals panel surfaces
              the daily-prorated value, but editing the plan / monthly actual
              lives on a dedicated page. */}
          <div className="flex items-center justify-end text-xs text-text-secondary">
            <Link
              href={`/projects/${projectId}/general-expenses`}
              className="rounded border border-hairline px-2 py-1 hover:bg-surface-hover/40"
            >
              Edit General Expenses (Section G) →
            </Link>
          </div>

          {/* Cumulative tiles — only render when backend included them (DAY mode
              typically; period mode rolls cumulative differently). */}
          {day.cumulativeExpense != null ||
          day.cumulativeIncome != null ||
          day.cumulativeContribution != null ? (
            <section className="rounded-lg border border-border bg-surface/30 p-4 shadow-sm">
              <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-text-secondary">
                Cumulative to date
              </h3>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <KpiTile
                  label="Cumulative Expense"
                  value={formatCurrency(day.cumulativeExpense ?? 0, currency)}
                  tone="warning"
                />
                <KpiTile
                  label="Cumulative Income"
                  value={formatCurrency(day.cumulativeIncome ?? 0, currency)}
                  hint="qty executed-to-date × boq_rate"
                  tone="accent"
                />
                <KpiTile
                  label="Cumulative Contribution"
                  value={formatCurrency(day.cumulativeContribution ?? 0, currency)}
                  tone={
                    (day.cumulativeContribution ?? 0) > 0
                      ? "success"
                      : (day.cumulativeContribution ?? 0) < 0
                        ? "danger"
                        : "default"
                  }
                />
              </div>
            </section>
          ) : null}

          {/* Phase 8 — Group-by-CM toggle. Default shows per-engineer (existing
              behaviour); on, shows one row per CM with summary fields, click-
              through into the CM tab. */}
          {periodType === "DAY" ? (
            <div className="flex items-center justify-end gap-2">
              <span className="text-xs text-text-muted">Group rows by</span>
              <div className="inline-flex overflow-hidden rounded-md border border-border bg-surface">
                <button
                  type="button"
                  onClick={() => setGroupByCm(false)}
                  className={`px-3 py-1.5 text-xs font-semibold transition-colors ${
                    !groupByCm
                      ? "bg-accent text-accent-foreground"
                      : "text-text-secondary hover:bg-surface-hover"
                  }`}
                >
                  Engineer
                </button>
                <button
                  type="button"
                  onClick={() => setGroupByCm(true)}
                  className={`px-3 py-1.5 text-xs font-semibold transition-colors ${
                    groupByCm
                      ? "bg-accent text-accent-foreground"
                      : "text-text-secondary hover:bg-surface-hover"
                  }`}
                >
                  CM
                </button>
              </div>
            </div>
          ) : null}

          {/* Per-CM breakdown — Phase 8. Fetched on-demand by `cmsQuery`. */}
          {periodType === "DAY" && groupByCm ? (
            <section className="rounded-lg border border-border bg-surface/50 shadow-sm">
              <header className="border-b border-border px-4 py-3">
                <h3 className="text-sm font-semibold text-text-primary">
                  Per-CM breakdown
                </h3>
                <p className="mt-0.5 text-xs text-text-muted">
                  One row per Construction Manager. Click {`"Drill in"`} to see
                  the CM tab pre-filtered.
                </p>
              </header>
              {cmsQuery.isLoading ? (
                <div className="px-4 py-6 text-center text-xs text-text-muted">
                  Loading CM rollup…
                </div>
              ) : cms.length === 0 ? (
                <div className="px-4 py-6 text-center text-xs text-text-muted">
                  No CMs with downline activity on this date.
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm" data-testid="pm-cm-breakdown">
                    <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
                      <tr>
                        <th className="px-4 py-2">Construction Manager</th>
                        <th className="px-4 py-2 text-right">Supervisors</th>
                        <th className="px-4 py-2 text-right">Direct Cost</th>
                        <th className="px-4 py-2 text-right">Prelim Cost</th>
                        <th className="px-4 py-2 text-right">Cost incl Prelims</th>
                        <th className="px-4 py-2 text-right">Contribution %</th>
                        <th className="px-4 py-2 text-right">% Achieved</th>
                        <th className="px-4 py-2 text-right"></th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {cms.map((cm) => {
                        const u = usersById.get(cm.cmUserId);
                        const name = cm.cmName ?? u?.name ?? cm.cmUserId.slice(0, 8) + "…";
                        return (
                          <tr key={cm.cmUserId}>
                            <td className="px-4 py-2 text-text-primary">{name}</td>
                            <td className="px-4 py-2 text-right font-mono text-text-secondary">
                              {cm.supervisorCount ?? 0}
                            </td>
                            <td className="px-4 py-2 text-right font-mono text-text-secondary">
                              {formatCurrency(cm.directCost ?? 0, currency)}
                            </td>
                            <td className="px-4 py-2 text-right font-mono text-text-secondary">
                              {formatCurrency(cm.prelimCost ?? 0, currency)}
                            </td>
                            <td className="px-4 py-2 text-right font-mono text-text-secondary">
                              {formatCurrency(cm.totalCostInclPrelims ?? 0, currency)}
                            </td>
                            <td className="px-4 py-2 text-right font-mono text-text-secondary">
                              {cm.contributionPct != null
                                ? formatPercent(cm.contributionPct)
                                : "—"}
                            </td>
                            <td className="px-4 py-2 text-right font-mono text-text-secondary">
                              {cm.pctAchieved != null
                                ? formatPercent(cm.pctAchieved)
                                : "—"}
                            </td>
                            <td className="px-4 py-2 text-right">
                              {onNavigateToCm ? (
                                <button
                                  type="button"
                                  onClick={() => onNavigateToCm(cm.cmUserId)}
                                  className="inline-flex items-center gap-1 rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 text-[11px] font-medium text-amber-700 hover:bg-amber-500/20"
                                >
                                  Drill in
                                  <ArrowRight size={11} />
                                </button>
                              ) : null}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          ) : null}

          {/* Per-engineer breakdown — mirrors Summary-Financial (NEW) columns */}
          {periodType === "DAY" && !groupByCm && engineerIds.length > 0 ? (
            <section className="rounded-lg border border-border bg-surface/50 shadow-sm">
              <header className="border-b border-border px-4 py-3">
                <h3 className="text-sm font-semibold text-text-primary">
                  Per-engineer breakdown
                </h3>
                <p className="mt-0.5 text-xs text-text-muted">
                  Plan / Achieved (Income) / Cost / Contribution, with profit/loss chip.
                </p>
              </header>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
                    <tr>
                      <th className="px-4 py-2">Engineer</th>
                      <th className="px-4 py-2 text-right">Plan Amount</th>
                      <th className="px-4 py-2 text-right">Achieved (Income)</th>
                      <th className="px-4 py-2 text-right">Cost (Expense)</th>
                      <th className="px-4 py-2 text-right">Cost %</th>
                      <th className="px-4 py-2 text-right">Contribution</th>
                      <th className="px-4 py-2 text-right">Contribution %</th>
                      <th className="px-4 py-2 text-right">P/L</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {engineerIds.map((eid, idx) => {
                      const q = engineerQueries[idx];
                      const row = q?.data?.data ?? null;
                      const user = usersById.get(eid);
                      const name = user?.name ?? eid.slice(0, 8) + "…";
                      if (q?.isLoading) {
                        return (
                          <tr key={eid}>
                            <td className="px-4 py-2 text-text-primary">{name}</td>
                            <td
                              colSpan={7}
                              className="px-4 py-2 text-center text-xs text-text-muted"
                            >
                              Loading…
                            </td>
                          </tr>
                        );
                      }
                      if (!row) {
                        return (
                          <tr key={eid}>
                            <td className="px-4 py-2 text-text-primary">{name}</td>
                            <td
                              colSpan={7}
                              className="px-4 py-2 text-center text-xs text-text-muted"
                            >
                              <span className="inline-flex items-center gap-1">
                                <AlertCircle size={12} />
                                No data
                              </span>
                            </td>
                          </tr>
                        );
                      }
                      const costPct =
                        row.totalIncome > 0
                          ? (row.totalExpense / row.totalIncome) * 100
                          : null;
                      const profit = row.contribution;
                      return (
                        <tr key={eid}>
                          <td className="px-4 py-2 text-text-primary">{name}</td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {formatCurrency(row.boqPlannedAmount, currency)}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {formatCurrency(row.totalIncome, currency)}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {formatCurrency(row.totalExpense, currency)}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {costPct != null ? formatPercent(costPct) : "—"}
                          </td>
                          <td
                            className={`px-4 py-2 text-right font-mono ${
                              profit > 0
                                ? "text-emerald-600"
                                : profit < 0
                                  ? "text-rose-600"
                                  : "text-text-secondary"
                            }`}
                          >
                            {formatCurrency(profit, currency)}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {formatPercent(row.contributionPct * 100)}
                          </td>
                          <td className="px-4 py-2 text-right">
                            <span
                              className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-semibold ${
                                profit > 0
                                  ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-700"
                                  : profit < 0
                                    ? "border-rose-500/30 bg-rose-500/10 text-rose-700"
                                    : "border-border bg-surface text-text-muted"
                              }`}
                            >
                              {profit > 0 ? "Profit" : profit < 0 ? "Loss" : "Flat"}
                            </span>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </section>
          ) : null}

          {/* Phase 8 — Equipment & Manpower deployment registers. Mounted at the
              bottom of the PM tab because they're cross-cutting and reference
              the same date as the totals above. */}
          {periodType === "DAY" ? (
            <>
              <EquipmentRegisterPanel projectId={projectId} date={date} />
              <ManpowerRegisterPanel projectId={projectId} date={date} />
            </>
          ) : null}

          {/* Period mode — daily project rollup with cumulative columns + period totals. */}
          {periodType !== "DAY" && dailyRows.length > 0 ? (
            <DailyBreakdownTable
              rows={dailyRows}
              currency={currency}
              periodLabel={periodType.toLowerCase()}
              showDprCount
            />
          ) : null}
        </>
      )}

      <ConfirmDialog
        open={confirmRecompute}
        title="Recompute DBS for this date?"
        message={`Re-aggregate every supervisor / engineer / project row for ${date}. Existing aggregates will be overwritten with values computed from current DPR + Resource Deployment + Material Consumption data.`}
        confirmLabel={recomputeMutation.isPending ? "Recomputing…" : "Recompute"}
        cancelLabel="Cancel"
        variant="warning"
        onConfirm={() => recomputeMutation.mutate()}
        onCancel={() => setConfirmRecompute(false)}
      />

      <ConfirmDialog
        open={confirmCumulativeRecompute}
        title="Cumulative recompute?"
        message="Recompute the DBS cumulatively from the first DPR through today? This may take a while on long projects."
        confirmLabel={recomputeCumulativeMutation.isPending ? "Recomputing…" : "Recompute"}
        cancelLabel="Cancel"
        variant="warning"
        onConfirm={() => recomputeCumulativeMutation.mutate()}
        onCancel={() => setConfirmCumulativeRecompute(false)}
      />

      <Dialog open={rangeOpen} onOpenChange={(o) => !o && setRangeOpen(false)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Recompute DBS range</DialogTitle>
          </DialogHeader>
          <DialogBody className="space-y-3">
            <p className="text-sm text-text-secondary">
              Pick a date range to re-aggregate. Useful after backfilling DPRs or
              changing rate-master values that affect historical costs.
            </p>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
                  From
                </label>
                <input
                  type="date"
                  value={rangeFrom}
                  onChange={(e) => setRangeFrom(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
                  To
                </label>
                <input
                  type="date"
                  value={rangeTo}
                  onChange={(e) => setRangeTo(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary"
                />
              </div>
            </div>
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setRangeOpen(false)}
              className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-surface-hover"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => {
                if (!rangeFrom || !rangeTo) {
                  toast.error("Pick both dates");
                  return;
                }
                if (rangeFrom > rangeTo) {
                  toast.error("From must be on or before To");
                  return;
                }
                recomputeRangeMutation.mutate();
              }}
              disabled={recomputeRangeMutation.isPending || isJobActive}
              className="rounded-md bg-accent px-3 py-1.5 text-sm text-accent-foreground hover:bg-accent-hover disabled:opacity-60"
            >
              {recomputeRangeMutation.isPending ? "Recomputing…" : "Recompute"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
