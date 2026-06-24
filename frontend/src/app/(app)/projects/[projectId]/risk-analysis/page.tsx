"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useMutation } from "@tanstack/react-query";
import { Play, AlertCircle, CheckCircle2, Clock } from "lucide-react";
import { monteCarloApi, type MonteCarloRunRequest, type DistributionType } from "@/lib/api/monteCarloApi";
import { PageHeader } from "@/components/common/PageHeader";
import { TabTip } from "@/components/common/TabTip";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/common/EmptyState";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from "recharts";
import { CHART_TOOLTIP_STYLE, CHART_TOOLTIP_LABEL_STYLE, CHART_TOOLTIP_ITEM_STYLE } from "@/components/common/dashboard/primitives";

const TABS = ["overview", "criticality", "tornado", "milestones", "cashflow", "drivers"] as const;
type Tab = typeof TABS[number];
const TAB_LABELS: Record<Tab, string> = {
  overview: "Overview",
  criticality: "Criticality",
  tornado: "Tornado",
  milestones: "Milestones",
  cashflow: "Cash Flow",
  drivers: "Risk Drivers",
};

function buildHistogram(values: number[], bins = 20): { range: string; count: number; mid: number }[] {
  if (!values.length) return [];
  const min = Math.min(...values);
  const max = Math.max(...values);
  if (min === max) return [{ range: `${min}`, count: values.length, mid: min }];
  const width = (max - min) / bins;
  const counts = new Array(bins).fill(0);
  for (const v of values) {
    const i = Math.min(bins - 1, Math.floor((v - min) / width));
    counts[i]++;
  }
  return counts.map((count, i) => {
    const lo = min + i * width;
    const hi = lo + width;
    return { range: `${Math.round(lo)}–${Math.round(hi)}`, count, mid: (lo + hi) / 2 };
  });
}

export default function RiskAnalysisPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const [tab, setTab] = useState<Tab>("overview");
  const [showRunDialog, setShowRunDialog] = useState(false);
  const [runRequest, setRunRequest] = useState<MonteCarloRunRequest>({
    iterations: 10000,
    defaultDistribution: "TRIANGULAR",
    fallbackVariancePct: 0.2,
    enableRisks: false,
  });

  const { data: latestSim, isLoading, refetch } = useQuery({
    queryKey: ["monte-carlo", projectId],
    queryFn: () => monteCarloApi.getLatestSimulation(projectId),
    retry: false,
  });

  const sim = latestSim?.data;

  const { data: activityStats } = useQuery({
    queryKey: ["monte-carlo-activity-stats", projectId, sim?.id],
    queryFn: () => (sim?.id ? monteCarloApi.getActivityStats(projectId, sim.id) : Promise.resolve(null)),
    enabled: !!sim?.id,
    retry: false,
  });

  const { data: tornado } = useQuery({
    queryKey: ["monte-carlo-tornado", projectId, sim?.id, "duration"],
    queryFn: () => (sim?.id ? monteCarloApi.getTornado(projectId, sim.id, "duration") : Promise.resolve(null)),
    enabled: !!sim?.id && tab === "tornado",
    retry: false,
  });

  const { data: milestones } = useQuery({
    queryKey: ["monte-carlo-milestones", projectId, sim?.id],
    queryFn: () => (sim?.id ? monteCarloApi.getMilestoneStats(projectId, sim.id) : Promise.resolve(null)),
    enabled: !!sim?.id && tab === "milestones",
    retry: false,
  });

  const { data: cashflow } = useQuery({
    queryKey: ["monte-carlo-cashflow", projectId, sim?.id],
    queryFn: () => (sim?.id ? monteCarloApi.getCashflow(projectId, sim.id) : Promise.resolve(null)),
    enabled: !!sim?.id && tab === "cashflow",
    retry: false,
  });

  const { data: drivers } = useQuery({
    queryKey: ["monte-carlo-drivers", projectId, sim?.id],
    queryFn: () => (sim?.id ? monteCarloApi.getRiskContributions(projectId, sim.id) : Promise.resolve(null)),
    enabled: !!sim?.id && tab === "drivers",
    retry: false,
  });

  const runMutation = useMutation({
    mutationFn: (req: MonteCarloRunRequest) => monteCarloApi.runSimulation(projectId, req),
    onSuccess: () => {
      setShowRunDialog(false);
      refetch();
    },
  });

  const durationHistogram = useMemo(
    () => buildHistogram((sim?.results ?? []).map((r) => r.projectDuration)),
    [sim?.results]
  );
  const costHistogram = useMemo(
    () => buildHistogram((sim?.results ?? []).map((r) => parseFloat(r.projectCost))),
    [sim?.results]
  );

  const statusConfig: Record<string, { icon: typeof Clock; color: string; bg: string }> = {
    PENDING: { icon: Clock, color: "text-warning", bg: "bg-warning/10" },
    RUNNING: { icon: Clock, color: "text-accent", bg: "bg-accent/10" },
    COMPLETED: { icon: CheckCircle2, color: "text-success", bg: "bg-success/10" },
    FAILED: { icon: AlertCircle, color: "text-danger", bg: "bg-danger/10" },
  };
  const status = sim?.status ?? "COMPLETED";
  const StatusIcon = statusConfig[status]?.icon ?? Clock;
  const statusColor = statusConfig[status]?.color ?? "text-text-secondary";
  const statusBg = statusConfig[status]?.bg ?? "bg-surface/80";

  const pctDelta = (v: number | null | undefined, baseline: number | undefined | null) => {
    if (v == null || !baseline || baseline === 0) return "";
    const pct = ((v - baseline) / baseline) * 100;
    return `${pct >= 0 ? "+" : ""}${pct.toFixed(1)}% vs baseline`;
  };

  return (
    <div className="space-y-8">
      <PageHeader
        title="Risk Analysis — Monte Carlo Simulation"
        description="Probabilistic schedule & cost simulation via real CPM + PERT sampling"
      />

      <TabTip
        title="Monte Carlo Risk Simulation"
        description="Every iteration samples a duration per activity from its configured distribution, runs CPM, and records project duration + cost. P50/P80 answer 'likely-case' and 'conservative' finish dates."
      />

      {/* Run controls */}
      <div className="bg-surface/50 rounded-lg border border-border p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-text-primary">Monte Carlo Simulation</h2>
          <Button
            onClick={() => setShowRunDialog(!showRunDialog)}
            disabled={runMutation.isPending}
            className="flex items-center gap-2"
          >
            <Play className="w-4 h-4" />
            {runMutation.isPending ? "Running…" : "Run Simulation"}
          </Button>
        </div>

        {showRunDialog && (
          <div className="mb-2 p-4 bg-accent/10 border border-accent/30 rounded-lg grid grid-cols-1 md:grid-cols-2 gap-4">
            <label className="block text-sm">
              <span className="text-text-primary">Iterations</span>
              <input
                type="number"
                value={runRequest.iterations}
                onChange={(e) => setRunRequest({ ...runRequest, iterations: parseInt(e.target.value) || 10000 })}
                min={100} max={100000} step={1000}
                className="mt-1 w-full px-3 py-2 border border-border rounded-md text-sm bg-surface text-text-primary"
              />
            </label>
            <label className="block text-sm">
              <span className="text-text-primary">Default distribution</span>
              <select
                value={runRequest.defaultDistribution}
                onChange={(e) => setRunRequest({ ...runRequest, defaultDistribution: e.target.value as DistributionType })}
                className="mt-1 w-full px-3 py-2 border border-border rounded-md text-sm bg-surface text-text-primary"
              >
                <option value="TRIANGULAR">Triangular</option>
                <option value="BETA_PERT">Beta-PERT</option>
                <option value="UNIFORM">Uniform</option>
                <option value="NORMAL">Normal</option>
                <option value="LOGNORMAL">Lognormal</option>
                <option value="TRIGEN">Trigen</option>
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-text-primary">Fallback variance (±%)</span>
              <input
                type="number"
                value={(runRequest.fallbackVariancePct ?? 0.2) * 100}
                onChange={(e) =>
                  setRunRequest({ ...runRequest, fallbackVariancePct: (parseFloat(e.target.value) || 20) / 100 })
                }
                min={0} max={90}
                className="mt-1 w-full px-3 py-2 border border-border rounded-md text-sm bg-surface text-text-primary"
              />
              <p className="text-xs text-text-secondary mt-1">
                Used when an activity has no PERT estimate.
              </p>
            </label>
            <label className="block text-sm">
              <span className="text-text-primary">Random seed (optional)</span>
              <input
                type="number"
                value={runRequest.randomSeed ?? ""}
                onChange={(e) =>
                  setRunRequest({ ...runRequest, randomSeed: e.target.value ? parseInt(e.target.value) : null })
                }
                className="mt-1 w-full px-3 py-2 border border-border rounded-md text-sm bg-surface text-text-primary"
                placeholder="Leave blank for non-reproducible"
              />
            </label>
            <label className="md:col-span-2 flex items-center gap-2 text-sm text-text-primary cursor-pointer">
              <input
                type="checkbox"
                checked={!!runRequest.enableRisks}
                onChange={(e) => setRunRequest({ ...runRequest, enableRisks: e.target.checked })}
              />
              <span>
                Enable risk register drivers — each open risk with probability + affected activities + impact
                fires Bernoulli per iteration and adds its schedule &amp; cost impact.
              </span>
            </label>
            <div className="md:col-span-2 flex gap-2">
              <Button
                onClick={() => runMutation.mutate(runRequest)}
                disabled={runMutation.isPending}
              >
                {runMutation.isPending ? "Running…" : "Start Simulation"}
              </Button>
              <Button onClick={() => setShowRunDialog(false)} variant="ghost">Cancel</Button>
            </div>
            {runMutation.isError && (
              <div className="md:col-span-2 text-sm text-danger">
                {(runMutation.error as Error)?.message ?? "Run failed"}
              </div>
            )}
          </div>
        )}

        {!sim && !isLoading && (
          <EmptyState
            icon={AlertCircle}
            title="No Simulation Data"
            description="Run a Monte Carlo simulation to analyze project duration and cost distributions. An active project baseline is required."
          />
        )}
      </div>

      {sim && (
        <>
          {/* Tabs */}
          <div className="flex gap-2 border-b border-border">
            {TABS.map((t) => (
              <button
                key={t}
                onClick={() => setTab(t)}
                className={`px-4 py-2 font-medium text-sm ${
                  tab === t
                    ? "border-b-2 border-blue-600 text-accent"
                    : "text-text-secondary hover:text-text-primary"
                }`}
              >
                {TAB_LABELS[t]}
              </button>
            ))}
          </div>

          {tab === "overview" && (
            <>
              {/* Status + baseline strip */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
                <div className={`${statusBg} rounded-lg border border-border p-4`}>
                  <div className="flex items-center gap-2 mb-2">
                    <StatusIcon className={`w-5 h-5 ${statusColor}`} />
                    <span className="text-sm font-medium text-text-secondary">Status</span>
                  </div>
                  <p className={`text-2xl font-bold ${statusColor}`}>{status}</p>
                </div>

                <div className="bg-accent/10 rounded-lg border border-border p-4">
                  <p className="text-sm font-medium text-text-secondary mb-2">Baseline Duration</p>
                  <p className="text-2xl font-bold text-accent">{Math.round(sim.baselineDuration)} days</p>
                  {sim.baselineId && (
                    <p className="text-xs text-text-muted mt-1">baseline {sim.baselineId.slice(0, 8)}</p>
                  )}
                </div>

                <div className="bg-purple-500/10 rounded-lg border border-border p-4">
                  <p className="text-sm font-medium text-text-secondary mb-2">Baseline Cost</p>
                  <p className="text-2xl font-bold text-purple-400">
                    {parseFloat(sim.baselineCost).toLocaleString(undefined, { maximumFractionDigits: 0 })}
                  </p>
                </div>

                <div className="bg-surface-hover/50 rounded-lg border border-border p-4">
                  <p className="text-sm font-medium text-text-secondary mb-2">Iterations</p>
                  <p className="text-2xl font-bold text-text-primary">{sim.iterations.toLocaleString()}</p>
                  {sim.dataDate && <p className="text-xs text-text-muted mt-1">data date {sim.dataDate}</p>}
                </div>
              </div>

              {/* Duration percentiles table */}
              <div className="bg-surface/50 rounded-lg border border-border p-6">
                <h3 className="text-lg font-semibold text-text-primary mb-4">Duration distribution (days)</h3>
                <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-8 gap-3 text-sm">
                  {[
                    { label: "P10", v: sim.p10Duration },
                    { label: "P25", v: sim.p25Duration },
                    { label: "P50", v: sim.confidenceP50Duration },
                    { label: "P75", v: sim.p75Duration },
                    { label: "P80", v: sim.confidenceP80Duration },
                    { label: "P90", v: sim.p90Duration },
                    { label: "P95", v: sim.p95Duration },
                    { label: "P99", v: sim.p99Duration },
                  ].map(({ label, v }) => (
                    <div key={label} className="bg-surface-hover/40 rounded border border-border p-3">
                      <p className="text-xs uppercase text-text-secondary">{label}</p>
                      <p className="text-lg font-semibold text-text-primary">
                        {v != null ? Math.round(v) : "—"}
                      </p>
                      <p className="text-xs text-text-muted">{pctDelta(v, sim.baselineDuration)}</p>
                    </div>
                  ))}
                </div>
                <div className="mt-3 text-xs text-text-secondary">
                  Mean {sim.meanDuration != null ? sim.meanDuration.toFixed(1) : "—"} days
                  , σ {sim.stddevDuration != null ? sim.stddevDuration.toFixed(1) : "—"} days
                </div>

                {durationHistogram.length > 0 && (
                  <div className="mt-6">
                    <ResponsiveContainer width="100%" height={320}>
                      <BarChart data={durationHistogram}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey="range" angle={-45} textAnchor="end" height={80} tick={{ fontSize: 11 }} />
                        <YAxis />
                        <Tooltip
                          formatter={(v) => `${v} iterations`}
                          contentStyle={CHART_TOOLTIP_STYLE}
                          labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                          itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                        />
                        <ReferenceLine
                          x={durationHistogram.reduce((best, b) =>
                            Math.abs(b.mid - sim.baselineDuration) < Math.abs(best.mid - sim.baselineDuration) ? b : best
                          ).range}
                          stroke="#ef4444"
                          label={{ value: "Baseline", position: "top", fill: "#ef4444" }}
                        />
                        <Bar dataKey="count" fill="#3b82f6" />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                )}
              </div>

              {/* Cost percentiles table */}
              <div className="bg-surface/50 rounded-lg border border-border p-6">
                <h3 className="text-lg font-semibold text-text-primary mb-4">Cost distribution</h3>
                <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-8 gap-3 text-sm">
                  {[
                    { label: "P10", v: sim.p10Cost },
                    { label: "P25", v: sim.p25Cost },
                    { label: "P50", v: sim.confidenceP50Cost },
                    { label: "P75", v: sim.p75Cost },
                    { label: "P80", v: sim.confidenceP80Cost },
                    { label: "P90", v: sim.p90Cost },
                    { label: "P95", v: sim.p95Cost },
                    { label: "P99", v: sim.p99Cost },
                  ].map(({ label, v }) => (
                    <div key={label} className="bg-surface-hover/40 rounded border border-border p-3">
                      <p className="text-xs uppercase text-text-secondary">{label}</p>
                      <p className="text-lg font-semibold text-text-primary">
                        {v != null ? Math.round(parseFloat(v)).toLocaleString() : "—"}
                      </p>
                    </div>
                  ))}
                </div>
                <div className="mt-3 text-xs text-text-secondary">
                  Mean {sim.meanCost != null ? Math.round(parseFloat(sim.meanCost)).toLocaleString() : "—"}
                  , σ {sim.stddevCost != null ? Math.round(parseFloat(sim.stddevCost)).toLocaleString() : "—"}
                </div>

                {costHistogram.length > 0 && (
                  <div className="mt-6">
                    <ResponsiveContainer width="100%" height={320}>
                      <BarChart data={costHistogram}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey="range" angle={-45} textAnchor="end" height={80} tick={{ fontSize: 11 }} />
                        <YAxis />
                        <Tooltip
                          formatter={(v) => `${v} iterations`}
                          contentStyle={CHART_TOOLTIP_STYLE}
                          labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                          itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                        />
                        <Bar dataKey="count" fill="#a855f7" />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                )}
              </div>
            </>
          )}

          {tab === "criticality" && (
            <div className="bg-surface/50 rounded-lg border border-border p-6">
              <h3 className="text-lg font-semibold text-text-primary mb-4">Criticality index by activity</h3>
              <p className="text-sm text-text-secondary mb-4">
                Fraction of iterations each activity appeared on the critical path. 1.0 = always on critical path.
                Duration sensitivity is the Pearson correlation between the activity&apos;s sampled duration and project duration.
              </p>
              {!activityStats?.data?.length && (
                <p className="text-sm text-text-muted">No activity stats available.</p>
              )}
              {activityStats?.data?.length ? (
                <SimpleTable
                  columns={[
                    {
                      accessorKey: "activityCode",
                      header: "Activity",
                      cell: ({ row }) => (
                        <span>
                          <span className="text-text-primary">{row.original.activityCode ?? row.original.activityId.slice(0, 8)}</span>
                          <span className="text-text-muted ml-2">{row.original.activityName}</span>
                        </span>
                      ),
                    },
                    {
                      accessorKey: "criticalityIndex",
                      header: "Criticality",
                      cell: ({ row }) => <span className="text-right">{(row.original.criticalityIndex * 100).toFixed(1)}%</span>,
                    },
                    {
                      accessorKey: "durationSensitivity",
                      header: "Sensitivity",
                      cell: ({ row }) => <span className="text-right">{row.original.durationSensitivity != null ? row.original.durationSensitivity.toFixed(2) : "—"}</span>,
                    },
                    {
                      accessorKey: "cruciality",
                      header: "Cruciality",
                      cell: ({ row }) => <span className="text-right">{row.original.cruciality != null ? row.original.cruciality.toFixed(2) : "—"}</span>,
                    },
                    {
                      accessorKey: "durationMean",
                      header: "Mean dur.",
                      cell: ({ row }) => <span className="text-right">{row.original.durationMean != null ? row.original.durationMean.toFixed(1) : "—"}</span>,
                    },
                    {
                      accessorKey: "durationStddev",
                      header: "σ",
                      cell: ({ row }) => <span className="text-right">{row.original.durationStddev != null ? row.original.durationStddev.toFixed(2) : "—"}</span>,
                    },
                  ]}
                  data={activityStats.data}
                />
              ) : null}
            </div>
          )}

          {tab === "tornado" && (
            <div className="bg-surface/50 rounded-lg border border-border p-6">
              <h3 className="text-lg font-semibold text-text-primary mb-2">Duration sensitivity (Tornado)</h3>
              <p className="text-sm text-text-secondary mb-4">
                Activities ranked by |Pearson correlation| of their sampled duration against project duration.
                Longer bars = more influence on how long the project runs.
              </p>
              {!tornado?.data?.length && <p className="text-sm text-text-muted">No tornado data available.</p>}
              {tornado?.data?.length ? (() => {
                const rows = tornado.data.slice(0, 20);
                const scale = Math.max(
                  ...rows.map((r) => Math.abs(r.durationSensitivity ?? 0)),
                  0.01
                );
                return (
                  <div className="space-y-2">
                    {rows.map((r) => {
                      const s = r.durationSensitivity ?? 0;
                      const pct = (Math.abs(s) / scale) * 50; // 0..50% of row width
                      const positive = s >= 0;
                      return (
                        <div key={r.id} className="flex items-center gap-3 text-xs">
                          <div className="w-48 text-text-secondary truncate">
                            <span className="text-text-primary">{r.activityCode ?? r.activityId.slice(0, 8)}</span>
                            <span className="text-text-muted ml-2">{r.activityName}</span>
                          </div>
                          <div className="relative flex-1 h-5 bg-surface-hover rounded">
                            <div className="absolute left-1/2 top-0 bottom-0 w-px bg-border" />
                            {!positive && (
                              <div
                                className="absolute top-0 bottom-0 bg-red-500/70 rounded-l"
                                style={{ right: "50%", width: `${pct}%` }}
                              />
                            )}
                            {positive && (
                              <div
                                className="absolute top-0 bottom-0 bg-success/70 rounded-r"
                                style={{ left: "50%", width: `${pct}%` }}
                              />
                            )}
                          </div>
                          <div className="w-16 text-right text-text-primary">{s.toFixed(2)}</div>
                        </div>
                      );
                    })}
                  </div>
                );
              })() : null}
            </div>
          )}

          {tab === "milestones" && (
            <div className="bg-surface/50 rounded-lg border border-border p-6">
              <h3 className="text-lg font-semibold text-text-primary mb-2">Milestone finish-date probabilities</h3>
              <p className="text-sm text-text-secondary mb-4">
                Per-milestone percentile finish dates across all iterations. A milestone is any activity with
                type START_MILESTONE or FINISH_MILESTONE. Planned column is the baseline finish date.
              </p>
              {!milestones?.data?.length && (
                <p className="text-sm text-text-muted">No milestones in the project&apos;s activity list.</p>
              )}
              {milestones?.data?.length ? (
                <SimpleTable
                  columns={[
                    {
                      accessorKey: "activityCode",
                      header: "Milestone",
                      cell: ({ row }) => (
                        <span>
                          <span className="text-text-primary">{row.original.activityCode ?? row.original.activityId.slice(0, 8)}</span>
                          <span className="text-text-muted ml-2">{row.original.activityName}</span>
                        </span>
                      ),
                    },
                    { accessorKey: "plannedFinishDate", header: "Planned", cell: ({ row }) => row.original.plannedFinishDate ?? "—" },
                    { accessorKey: "p50FinishDate", header: "P50 finish", cell: ({ row }) => row.original.p50FinishDate ?? "—" },
                    { accessorKey: "p80FinishDate", header: "P80 finish", cell: ({ row }) => row.original.p80FinishDate ?? "—" },
                    { accessorKey: "p90FinishDate", header: "P90 finish", cell: ({ row }) => row.original.p90FinishDate ?? "—" },
                  ]}
                  data={milestones.data}
                />
              ) : null}
            </div>
          )}

          {tab === "cashflow" && (
            <div className="bg-surface/50 rounded-lg border border-border p-6">
              <h3 className="text-lg font-semibold text-text-primary mb-2">Probabilistic cash flow (S-curve)</h3>
              <p className="text-sm text-text-secondary mb-4">
                Cumulative spend by month-end. P10/P50/P80/P90 bands show the full stochastic envelope; spread
                between bands at each period tells you how much schedule/cost uncertainty drives that period.
              </p>
              {!cashflow?.data?.length && <p className="text-sm text-text-muted">No cash-flow data available.</p>}
              {cashflow?.data?.length ? (
                <SimpleTable
                  columns={[
                    { accessorKey: "periodEndDate", header: "Period end" },
                    {
                      accessorKey: "p10Cumulative",
                      header: "P10 cumulative",
                      cell: ({ row }) => <span className="text-right">{row.original.p10Cumulative != null ? Math.round(parseFloat(row.original.p10Cumulative)).toLocaleString() : "—"}</span>,
                    },
                    {
                      accessorKey: "p50Cumulative",
                      header: "P50 cumulative",
                      cell: ({ row }) => <span className="text-right">{row.original.p50Cumulative != null ? Math.round(parseFloat(row.original.p50Cumulative)).toLocaleString() : "—"}</span>,
                    },
                    {
                      accessorKey: "p80Cumulative",
                      header: "P80 cumulative",
                      cell: ({ row }) => <span className="text-right">{row.original.p80Cumulative != null ? Math.round(parseFloat(row.original.p80Cumulative)).toLocaleString() : "—"}</span>,
                    },
                    {
                      accessorKey: "p90Cumulative",
                      header: "P90 cumulative",
                      cell: ({ row }) => <span className="text-right">{row.original.p90Cumulative != null ? Math.round(parseFloat(row.original.p90Cumulative)).toLocaleString() : "—"}</span>,
                    },
                  ]}
                  data={cashflow.data}
                />
              ) : null}
            </div>
          )}

          {tab === "drivers" && (
            <div className="bg-surface/50 rounded-lg border border-border p-6">
              <h3 className="text-lg font-semibold text-text-primary mb-2">Risk-register contributions</h3>
              <p className="text-sm text-text-secondary mb-4">
                Per risk: how often the Bernoulli draw fired across iterations (<em>Rate</em>), mean schedule and
                cost impact when it did, and the activities it was wired to. Risks only contribute when the run
                had &quot;Enable risk drivers&quot; on and the risk has a non-zero probability + affected activities.
              </p>
              {!drivers?.data?.length && (
                <p className="text-sm text-text-muted">
                  No drivers recorded. Enable risk drivers on the Run dialog and ensure risks have probability,
                  affected activities, and a non-zero schedule/cost impact.
                </p>
              )}
              {drivers?.data?.length ? (
                <SimpleTable
                  columns={[
                    {
                      accessorKey: "riskCode",
                      header: "Risk",
                      cell: ({ row }) => (
                        <span>
                          <span className="text-text-primary">{row.original.riskCode ?? row.original.riskId.slice(0, 8)}</span>
                          <span className="text-text-muted ml-2">{row.original.riskTitle}</span>
                        </span>
                      ),
                    },
                    {
                      accessorKey: "occurrenceRate",
                      header: "Rate",
                      cell: ({ row }) => <span className="text-right">{row.original.occurrenceRate != null ? `${(row.original.occurrenceRate * 100).toFixed(1)}%` : "—"}</span>,
                    },
                    {
                      accessorKey: "occurrences",
                      header: "Hits",
                      cell: ({ row }) => <span className="text-right">{row.original.occurrences ?? 0}</span>,
                    },
                    {
                      accessorKey: "meanDurationImpact",
                      header: "Mean Δ days",
                      cell: ({ row }) => <span className="text-right">{row.original.meanDurationImpact != null ? row.original.meanDurationImpact.toFixed(1) : "—"}</span>,
                    },
                    {
                      accessorKey: "meanCostImpact",
                      header: "Mean Δ cost",
                      cell: ({ row }) => <span className="text-right">{row.original.meanCostImpact != null ? Math.round(parseFloat(row.original.meanCostImpact)).toLocaleString() : "—"}</span>,
                    },
                    {
                      accessorKey: "affectedActivityIds",
                      header: "Activities",
                      cell: ({ row }) => <span className="text-xs text-text-secondary truncate max-w-xs">{row.original.affectedActivityIds ?? ""}</span>,
                    },
                  ]}
                  data={drivers.data}
                />
              ) : null}
            </div>
          )}
        </>
      )}
    </div>
  );
}
