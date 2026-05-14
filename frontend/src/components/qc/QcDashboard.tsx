"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle, Filter, RotateCcw, TrendingUp, X } from "lucide-react";
import { qcApi } from "@/lib/api/qcApi";
import type { QcOutcome } from "@/lib/types/qc";
import type { SelectOption } from "@/components/common/SearchableSelect";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { KpiTile } from "@/components/common/KpiTile";
import { EmptyState } from "@/components/common/EmptyState";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils/cn";

interface Props {
  projectId: string;
  activityOptions: SelectOption[];
}

const OUTCOMES: QcOutcome[] = ["PASS", "FAIL", "REPEAT"];

const OUTCOME_STYLE: Record<QcOutcome, { active: string; inactive: string }> = {
  PASS: {
    active: "bg-emerald text-white border-emerald",
    inactive: "border-emerald/40 text-emerald hover:bg-emerald/10",
  },
  FAIL: {
    active: "bg-burgundy text-white border-burgundy",
    inactive: "border-burgundy/40 text-burgundy hover:bg-burgundy/10",
  },
  REPEAT: {
    active: "bg-bronze-warn text-white border-bronze-warn",
    inactive: "border-bronze-warn/40 text-bronze-warn hover:bg-bronze-warn/10",
  },
};

const inputCls =
  "rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40";

export function QcDashboard({ projectId, activityOptions }: Props) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [activityId, setActivityId] = useState("");
  const [outcome, setOutcome] = useState<QcOutcome | "">("");

  const hasFilter = !!(from || to || activityId || outcome);

  const clearFilters = () => {
    setFrom("");
    setTo("");
    setActivityId("");
    setOutcome("");
  };

  const toggleOutcome = (o: QcOutcome) =>
    setOutcome((prev) => (prev === o ? "" : o));

  const { data, isLoading } = useQuery({
    queryKey: ["qc-dashboard", projectId, from, to, activityId, outcome],
    queryFn: () =>
      qcApi.getDashboard(projectId, {
        from: from || undefined,
        to: to || undefined,
        activityId: activityId || undefined,
        outcome: outcome || undefined,
      }),
  });

  const dash = data?.data;

  const todayLabel = from || to ? "In Range" : "Today";

  return (
    <div className="space-y-5">

      {/* ── Filter bar ── */}
      <div className="rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <div className="mb-3 flex items-center gap-2">
          <Filter className="h-3.5 w-3.5 text-gold-deep" />
          <span className="text-xs font-semibold uppercase tracking-widest text-slate">Filters</span>
          {hasFilter && (
            <button
              onClick={clearFilters}
              className="ml-auto inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-slate hover:bg-ivory hover:text-charcoal transition"
            >
              <X className="h-3 w-3" /> Clear
            </button>
          )}
        </div>

        <div className="flex flex-wrap items-end gap-3">
          {/* Date range */}
          <div>
            <label className="mb-1 block text-xs font-medium text-slate">From</label>
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className={cn(inputCls, from && "border-gold ring-1 ring-gold/30")}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-slate">To</label>
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className={cn(inputCls, to && "border-gold ring-1 ring-gold/30")}
            />
          </div>

          {/* Activity */}
          <div className="min-w-[220px]">
            <label className="mb-1 block text-xs font-medium text-slate">Activity</label>
            <SearchableSelect
              options={activityOptions}
              value={activityId}
              onChange={setActivityId}
              placeholder="All activities"
            />
          </div>

          {/* Outcome chips */}
          <div>
            <label className="mb-1 block text-xs font-medium text-slate">Outcome</label>
            <div className="flex gap-1.5">
              {OUTCOMES.map((o) => (
                <button
                  key={o}
                  type="button"
                  onClick={() => toggleOutcome(o)}
                  className={cn(
                    "rounded-full border px-3 py-1.5 text-xs font-semibold transition-all",
                    outcome === o ? OUTCOME_STYLE[o].active : OUTCOME_STYLE[o].inactive
                  )}
                >
                  {o}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Active filter pills */}
        {hasFilter && (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {from && (
              <span className="inline-flex items-center gap-1 rounded-full bg-gold/10 px-2 py-0.5 text-xs font-medium text-gold-deep">
                From: {from}
                <button onClick={() => setFrom("")}><X className="h-2.5 w-2.5" /></button>
              </span>
            )}
            {to && (
              <span className="inline-flex items-center gap-1 rounded-full bg-gold/10 px-2 py-0.5 text-xs font-medium text-gold-deep">
                To: {to}
                <button onClick={() => setTo("")}><X className="h-2.5 w-2.5" /></button>
              </span>
            )}
            {activityId && (
              <span className="inline-flex items-center gap-1 rounded-full bg-gold/10 px-2 py-0.5 text-xs font-medium text-gold-deep">
                {activityOptions.find((o) => o.value === activityId)?.label ?? activityId}
                <button onClick={() => setActivityId("")}><X className="h-2.5 w-2.5" /></button>
              </span>
            )}
            {outcome && (
              <span className="inline-flex items-center gap-1 rounded-full bg-gold/10 px-2 py-0.5 text-xs font-medium text-gold-deep">
                {outcome}
                <button onClick={() => setOutcome("")}><X className="h-2.5 w-2.5" /></button>
              </span>
            )}
          </div>
        )}
      </div>

      {/* ── KPI tiles ── */}
      {isLoading ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-24 animate-pulse rounded-xl bg-surface-hover" />
          ))}
        </div>
      ) : !dash ? (
        <EmptyState title="Dashboard unavailable" description="Could not load QC dashboard data." />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <KpiTile label="Total Tests" value={dash.totalTests} icon={<TrendingUp className="h-4 w-4" />} />
            <KpiTile label="Pass" value={dash.passCount} icon={<CheckCircle className="h-4 w-4 text-success" />} tone="success" />
            <KpiTile label="Fail" value={dash.failCount} icon={<AlertTriangle className="h-4 w-4 text-burgundy" />} tone="danger" />
            <KpiTile label="Repeat" value={dash.repeatCount} icon={<RotateCcw className="h-4 w-4 text-bronze-warn" />} tone="warning" />
            <KpiTile
              label="Pass Rate %"
              value={`${dash.passRate.toFixed(1)}%`}
              icon={<TrendingUp className="h-4 w-4" />}
            />
            <KpiTile
              label={todayLabel}
              value={dash.todayTests}
              icon={<TrendingUp className="h-4 w-4" />}
            />
          </div>

          {/* Recent fails */}
          {dash.recentFails.length > 0 && (
            <div>
              <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate">Recent Fails</h3>
              <div className="space-y-2">
                {dash.recentFails.map((r) => (
                  <div
                    key={r.id}
                    className="flex flex-wrap items-center gap-3 rounded-md border border-burgundy/20 bg-burgundy/5 px-3 py-2 text-sm"
                  >
                    <Badge variant="danger" withDot>FAIL</Badge>
                    <span className="font-medium text-charcoal">{r.testTypeName}</span>
                    {r.sampleRefNo && <span className="text-slate">· Ref {r.sampleRefNo}</span>}
                    {r.labInspector && <span className="text-slate">· {r.labInspector}</span>}
                    {r.testResult != null && (
                      <span className="tabular-nums text-slate">Result: {r.testResult}</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* By activity */}
          {dash.byActivity.length > 0 && (
            <div>
              <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate">By Activity</h3>
              <div className="overflow-x-auto rounded-lg border border-hairline">
                <table className="w-full text-sm">
                  <thead className="border-b border-hairline bg-ivory/60">
                    <tr>
                      <th className="px-4 py-2.5 text-left font-semibold text-slate">Activity</th>
                      <th className="px-4 py-2.5 text-right font-semibold text-emerald">Pass</th>
                      <th className="px-4 py-2.5 text-right font-semibold text-burgundy">Fail</th>
                      <th className="px-4 py-2.5 text-right font-semibold text-bronze-warn">Repeat</th>
                      <th className="px-4 py-2.5 text-right font-semibold text-slate">Total</th>
                      <th className="px-4 py-2.5 text-right font-semibold text-slate">Pass Rate</th>
                    </tr>
                  </thead>
                  <tbody>
                    {dash.byActivity.map((a) => {
                      const total = a.pass + a.fail + a.repeat;
                      const rate = total > 0 ? ((a.pass / total) * 100).toFixed(1) : "—";
                      return (
                        <tr key={a.activityId} className="border-t border-hairline hover:bg-ivory/40 transition-colors">
                          <td className="px-4 py-2.5 font-medium text-charcoal">{a.activityName}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums text-emerald">{a.pass}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums text-burgundy">{a.fail}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums text-bronze-warn">{a.repeat}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums font-semibold text-charcoal">{total}</td>
                          <td className="px-4 py-2.5 text-right tabular-nums">
                            <span className={cn(
                              "font-semibold",
                              typeof rate === "string" && rate !== "—"
                                ? parseFloat(rate) >= 80 ? "text-emerald" : parseFloat(rate) >= 50 ? "text-bronze-warn" : "text-burgundy"
                                : "text-slate"
                            )}>
                              {rate}{rate !== "—" ? "%" : ""}
                            </span>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {dash.totalTests === 0 && (
            <EmptyState
              title="No tests match filters"
              description="Try adjusting the date range, activity, or outcome filter."
            />
          )}
        </>
      )}
    </div>
  );
}
