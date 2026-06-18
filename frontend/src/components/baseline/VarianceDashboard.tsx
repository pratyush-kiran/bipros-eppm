"use client";

import type { BaselineVarianceRow } from "@/lib/api/baselineApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

interface VarianceDashboardProps {
  data: BaselineVarianceRow[];
}

function getVarianceColor(value: number): string {
  if (value > 0) return "text-danger";
  if (value < 0) return "text-success";
  return "text-text-secondary";
}

function getVarianceBg(value: number): string {
  if (value > 0) return "bg-danger/10 border-danger/20";
  if (value < 0) return "bg-success/10 border-success/20";
  return "bg-surface-hover/50 border-border/50";
}

export function VarianceDashboard({ data }: VarianceDashboardProps) {
  const { money } = useProjectCurrency();

  if (data.length === 0) {
    return (
      <div className="rounded-lg border border-border bg-surface/50 p-8 text-center text-text-secondary">
        No variance data available
      </div>
    );
  }

  // Calculate summary metrics
  const totalActivities = data.length;
  const delayedStart = data.filter((r) => r.startVarianceDays > 0).length;
  const delayedFinish = data.filter((r) => r.finishVarianceDays > 0).length;
  const onTrack = data.filter(
    (r) => r.startVarianceDays === 0 && r.finishVarianceDays === 0
  ).length;
  const ahead = data.filter(
    (r) => r.startVarianceDays < 0 || r.finishVarianceDays < 0
  ).length;

  const avgStartVariance =
    data.reduce((sum, r) => sum + r.startVarianceDays, 0) / totalActivities;
  const avgFinishVariance =
    data.reduce((sum, r) => sum + r.finishVarianceDays, 0) / totalActivities;
  const totalCostVariance = data.reduce((sum, r) => sum + r.costVariance, 0);

  const onTrackPct = Math.round((onTrack / totalActivities) * 100);
  const delayedPct = Math.round((delayedFinish / totalActivities) * 100);
  const aheadPct = Math.round((ahead / totalActivities) * 100);

  // Variance distribution buckets
  const buckets = [
    { label: "> 10d early", count: data.filter((r) => r.finishVarianceDays < -10).length },
    { label: "1-10d early", count: data.filter((r) => r.finishVarianceDays >= -10 && r.finishVarianceDays < 0).length },
    { label: "On time", count: data.filter((r) => r.finishVarianceDays === 0).length },
    { label: "1-10d late", count: data.filter((r) => r.finishVarianceDays > 0 && r.finishVarianceDays <= 10).length },
    { label: "11-30d late", count: data.filter((r) => r.finishVarianceDays > 10 && r.finishVarianceDays <= 30).length },
    { label: "> 30d late", count: data.filter((r) => r.finishVarianceDays > 30).length },
  ];
  const maxBucket = Math.max(...buckets.map((b) => b.count), 1);

  const bucketColors = [
    "bg-success",
    "bg-emerald-400",
    "bg-surface-active",
    "bg-amber-400",
    "bg-red-400",
    "bg-red-500",
  ];

  return (
    <div className="space-y-6">
      {/* Summary Cards */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <div className={`rounded-lg border p-4 ${getVarianceBg(avgStartVariance)}`}>
          <p className="text-xs font-medium uppercase tracking-wider text-text-secondary">
            Avg Start Variance
          </p>
          <p className={`mt-1 text-2xl font-bold ${getVarianceColor(avgStartVariance)}`}>
            {avgStartVariance > 0 ? "+" : ""}
            {avgStartVariance.toFixed(1)}d
          </p>
        </div>
        <div className={`rounded-lg border p-4 ${getVarianceBg(avgFinishVariance)}`}>
          <p className="text-xs font-medium uppercase tracking-wider text-text-secondary">
            Avg Finish Variance
          </p>
          <p className={`mt-1 text-2xl font-bold ${getVarianceColor(avgFinishVariance)}`}>
            {avgFinishVariance > 0 ? "+" : ""}
            {avgFinishVariance.toFixed(1)}d
          </p>
        </div>
        <div className={`rounded-lg border p-4 ${getVarianceBg(totalCostVariance)}`}>
          <p className="text-xs font-medium uppercase tracking-wider text-text-secondary">
            Total Cost Variance
          </p>
          <p className={`mt-1 text-2xl font-bold ${getVarianceColor(totalCostVariance)}`}>
            {totalCostVariance > 0 ? "+" : ""}{money(Math.abs(totalCostVariance))}
          </p>
        </div>
        <div className="rounded-lg border border-border/50 bg-surface-hover/50 p-4">
          <p className="text-xs font-medium uppercase tracking-wider text-text-secondary">
            Activities On Track
          </p>
          <p className="mt-1 text-2xl font-bold text-text-primary">
            {onTrackPct}%
          </p>
          <p className="text-xs text-text-secondary">
            {onTrack} of {totalActivities}
          </p>
        </div>
      </div>

      {/* Status Breakdown */}
      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-lg border border-success/20 bg-success/10 p-4 text-center">
          <p className="text-3xl font-bold text-success">{ahead}</p>
          <p className="text-sm text-success">Ahead ({aheadPct}%)</p>
        </div>
        <div className="rounded-lg border border-border/50 bg-surface-hover/50 p-4 text-center">
          <p className="text-3xl font-bold text-text-primary">{onTrack}</p>
          <p className="text-sm text-text-secondary">On Track ({onTrackPct}%)</p>
        </div>
        <div className="rounded-lg border border-danger/20 bg-danger/10 p-4 text-center">
          <p className="text-3xl font-bold text-danger">{delayedFinish}</p>
          <p className="text-sm text-danger">Delayed ({delayedPct}%)</p>
        </div>
      </div>

      {/* Variance Distribution Chart */}
      <div className="rounded-lg border border-border bg-surface/50 p-6">
        <h4 className="mb-4 text-sm font-semibold text-text-primary">
          Finish Variance Distribution
        </h4>
        <div className="space-y-2">
          {buckets.map((bucket, i) => (
            <div key={bucket.label} className="flex items-center gap-3">
              <span className="w-24 text-right text-xs text-text-secondary">
                {bucket.label}
              </span>
              <div className="flex-1">
                <div
                  className={`h-6 rounded ${bucketColors[i]} transition-all`}
                  style={{
                    width: `${(bucket.count / maxBucket) * 100}%`,
                    minWidth: bucket.count > 0 ? "16px" : "0",
                  }}
                />
              </div>
              <span className="w-8 text-right text-xs font-medium text-text-secondary">
                {bucket.count}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
