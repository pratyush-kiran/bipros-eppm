"use client";

import { useMemo } from "react";
import { CheckCircle, Clock, AlertCircle } from "lucide-react";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";
import { Progress } from "@/components/ui/progress";
import type { MonthlyProgressData, ActivitySummaryRow } from "@/lib/api/reportDataApi";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";

interface MonthlyProgressReportProps {
  data: MonthlyProgressData;
}

export function MonthlyProgressReport({ data }: MonthlyProgressReportProps) {
  const currency = useProjectCurrencyOptional();
  const moneyCompact = (v: number | null | undefined) =>
    currency ? currency.moneyCompact(v) : formatMoney(v, { code: "INR" }, { compact: true });

  const costVariance = useMemo(() => {
    const variance = data.budgetAmount - data.actualCost;
    const percentage = data.budgetAmount > 0 ? (variance / data.budgetAmount) * 100 : 0;
    return { amount: variance, percentage };
  }, [data.budgetAmount, data.actualCost]);

  const scheduleStatus = useMemo(() => {
    if (data.overallPercentComplete >= 100) return "completed";
    if (data.overallPercentComplete >= 50) return "progressing";
    return "at-risk";
  }, [data.overallPercentComplete]);

  const columns = useMemo<ColumnDef<ActivitySummaryRow>[]>(
    () => [
      {
        accessorKey: "code",
        header: "Code",
        cell: (info) => (
          <span className="font-mono text-text-primary">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "name",
        header: "Name",
        cell: (info) => (
          <span className="text-text-secondary">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: (info) => (
          <span className="px-2 py-1 bg-orange-500/10 text-orange-400 rounded text-xs font-semibold">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "delayDays",
        header: "Days Delayed",
        cell: (info) => (
          <span className="block text-right text-danger font-semibold">
            {Math.abs(Math.round(Number(info.getValue())))} days
          </span>
        ),
      },
      {
        accessorKey: "plannedFinish",
        header: "Planned Finish",
        cell: (info) => (
          <span className="text-text-secondary">
            {new Date(String(info.getValue())).toLocaleDateString()}
          </span>
        ),
      },
    ],
    []
  );

  return (
    <div className="space-y-6">
      {/* Header Info */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="flex justify-between items-start">
          <div>
            <h3 className="font-semibold text-lg text-text-primary">{data.projectName}</h3>
            <p className="text-sm text-text-secondary">Code: {data.projectCode} | Period: {data.period}</p>
          </div>
          <div className={`px-3 py-1 rounded-full text-sm font-semibold ${
            scheduleStatus === "completed" ? "bg-success/10 text-success" :
            scheduleStatus === "progressing" ? "bg-accent/10 text-accent" :
            "bg-danger/10 text-danger"
          }`}>
            {scheduleStatus === "completed" ? "Completed" :
             scheduleStatus === "progressing" ? "In Progress" :
             "At Risk"}
          </div>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-text-muted uppercase tracking-wider">Total Activities</p>
              <p className="text-2xl font-bold text-text-primary">{data.totalActivities}</p>
            </div>
            <Clock className="text-text-muted" size={24} />
          </div>
        </div>

        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-text-muted uppercase tracking-wider">Completed</p>
              <p className="text-2xl font-bold text-success">{data.completedActivities}</p>
            </div>
            <CheckCircle className="text-success" size={24} />
          </div>
        </div>

        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-text-muted uppercase tracking-wider">In Progress</p>
              <p className="text-2xl font-bold text-accent">{data.inProgressActivities}</p>
            </div>
            <Clock className="text-accent" size={24} />
          </div>
        </div>

        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-text-muted uppercase tracking-wider">% Complete</p>
              <p className="text-2xl font-bold text-indigo-600">{data.overallPercentComplete.toFixed(1)}%</p>
            </div>
            <CheckCircle className="text-indigo-400" size={24} />
          </div>
        </div>
      </div>

      {/* Progress Bar */}
      <div className="bg-surface/50 border border-border rounded-lg p-4">
        <h4 className="font-semibold text-text-primary mb-2">Overall Progress</h4>
        <Progress value={data.overallPercentComplete} className="h-3" />
        <div className="flex justify-between text-xs text-text-secondary mt-2">
          <span>0%</span>
          <span>{data.overallPercentComplete.toFixed(1)}%</span>
          <span>100%</span>
        </div>
      </div>

      {/* Budget vs Actual */}
      <div className="grid md:grid-cols-2 gap-4">
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <h4 className="font-semibold text-text-primary mb-4">Cost Status</h4>
          <div className="space-y-4">
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-text-secondary">Budget</span>
                <span className="font-semibold">{moneyCompact(data.budgetAmount)}</span>
              </div>
            </div>
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-text-secondary">Actual Cost</span>
                <span className="font-semibold">{moneyCompact(data.actualCost)}</span>
              </div>
            </div>
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-text-secondary">Forecast</span>
                <span className="font-semibold">{moneyCompact(data.forecastCost)}</span>
              </div>
            </div>
            <div className="border-t pt-4">
              <div className="flex justify-between text-sm">
                <span className={costVariance.percentage >= 0 ? "text-success" : "text-danger"}>
                  Variance
                </span>
                <span className={`font-semibold ${costVariance.percentage >= 0 ? "text-success" : "text-danger"}`}>
                  {moneyCompact(costVariance.amount)} ({costVariance.percentage.toFixed(1)}%)
                </span>
              </div>
            </div>
          </div>
        </div>

        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <h4 className="font-semibold text-text-primary mb-4">Milestone Status</h4>
          <div className="space-y-4">
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-text-secondary">Total Milestones</span>
                <span className="font-semibold">{data.totalMilestones}</span>
              </div>
            </div>
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-text-secondary">Achieved</span>
                <span className="font-semibold text-success">{data.achievedMilestones}</span>
              </div>
            </div>
            <div>
              <div className="flex justify-between text-sm mb-1">
                <span className="text-text-secondary">Pending</span>
                <span className="font-semibold text-orange-600">
                  {data.totalMilestones - data.achievedMilestones}
                </span>
              </div>
            </div>
            <div className="border-t pt-4">
              <div className="flex items-center justify-between">
                <span className="text-text-secondary">Open Risks</span>
                <div className="flex gap-2">
                  <span className="px-2 py-1 bg-danger/10 text-danger text-xs rounded-full font-semibold">
                    High: {data.highRisks}
                  </span>
                  <span className="px-2 py-1 bg-orange-500/10 text-orange-400 text-xs rounded-full font-semibold">
                    Total: {data.openRisks}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Top Delayed Activities */}
      {data.topDelayedActivities.length > 0 && (
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <h4 className="font-semibold text-text-primary mb-4 flex items-center gap-2">
            <AlertCircle className="text-orange-500" size={20} />
            Top Delayed Activities
          </h4>
          <SimpleTable
            columns={columns}
            data={data.topDelayedActivities}
            sortable={false}
          />
        </div>
      )}
    </div>
  );
}
