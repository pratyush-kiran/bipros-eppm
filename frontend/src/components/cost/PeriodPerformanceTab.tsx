"use client";

import { useState, useMemo } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { getErrorMessage } from "@/lib/utils/error";
import { Plus, Trash2, TrendingUp, DollarSign, BarChart3, Activity } from "lucide-react";
import {
  periodPerformanceApi,
  type StorePeriodPerformance,
  type FinancialPeriod,
  type CreateStorePeriodPerformanceRequest,
} from "@/lib/api/periodPerformanceApi";
import { activityApi, type ActivityResponse } from "@/lib/api/activityApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { KpiTile } from "@/components/common/KpiTile";
import { AiInsightsPanel } from "@/components/ai/AiInsightsPanel";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

function sumField(records: StorePeriodPerformance[], field: keyof StorePeriodPerformance): number {
  return records.reduce((acc, r) => acc + ((r[field] as number | null) ?? 0), 0);
}

const EMPTY_FORM: Omit<CreateStorePeriodPerformanceRequest, "projectId" | "financialPeriodId"> = {
  activityId: null,
  actualLaborCost: null,
  actualNonlaborCost: null,
  actualMaterialCost: null,
  actualExpenseCost: null,
  actualLaborUnits: null,
  actualNonlaborUnits: null,
  actualMaterialUnits: null,
  earnedValueCost: null,
  plannedValueCost: null,
};

export function PeriodPerformanceTab({ projectId }: { projectId: string }) {
  const { moneyCompact, symbol } = useProjectCurrency();
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [selectedPeriodId, setSelectedPeriodId] = useState<string>("");
  const [form, setForm] = useState(EMPTY_FORM);

  const { data: periodsData, isLoading: isLoadingPeriods } = useQuery({
    queryKey: ["financial-periods"],
    queryFn: () => periodPerformanceApi.getAllFinancialPeriods(),
  });

  const { data: sppData, isLoading: isLoadingSpp } = useQuery({
    queryKey: ["spp", projectId],
    queryFn: () => periodPerformanceApi.getProjectPeriodPerformance(projectId),
  });

  const { data: activitiesData } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 200),
  });

  const periods: FinancialPeriod[] = periodsData?.data ?? [];
  const records: StorePeriodPerformance[] = sppData?.data ?? [];
  const activities: ActivityResponse[] = activitiesData?.data?.content ?? [];

  const activityMap = useMemo(() => {
    const m = new Map<string, ActivityResponse>();
    for (const a of (activitiesData?.data?.content ?? [])) m.set(a.id, a);
    return m;
  }, [activitiesData]);

  const periodMap = useMemo(() => {
    const m = new Map<string, FinancialPeriod>();
    for (const p of (periodsData?.data ?? [])) m.set(p.id, p);
    return m;
  }, [periodsData]);

  const createMutation = useMutation({
    mutationFn: (data: CreateStorePeriodPerformanceRequest) =>
      periodPerformanceApi.createStorePeriodPerformance(projectId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["spp", projectId] });
      toast.success("Period performance recorded");
      setShowForm(false);
      setForm(EMPTY_FORM);
      setSelectedPeriodId("");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to save period performance"));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (sppId: string) =>
      periodPerformanceApi.deleteStorePeriodPerformance(projectId, sppId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["spp", projectId] });
      toast.success("Record deleted");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to delete record"));
    },
  });

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!selectedPeriodId) {
      toast.error("Please select a financial period");
      return;
    }
    createMutation.mutate({
      projectId,
      financialPeriodId: selectedPeriodId,
      ...form,
    });
  }

  function parseNum(val: string): number | null {
    const n = parseFloat(val);
    return isNaN(n) ? null : n;
  }

  const totalActualCost =
    sumField(records, "actualLaborCost") +
    sumField(records, "actualNonlaborCost") +
    sumField(records, "actualMaterialCost") +
    sumField(records, "actualExpenseCost");

  const totalEv = sumField(records, "earnedValueCost");
  const totalPv = sumField(records, "plannedValueCost");
  const totalLaborUnits = sumField(records, "actualLaborUnits");

  const columns = useMemo<ColumnDef<StorePeriodPerformance>[]>(
    () => [
      {
        accessorKey: "financialPeriodId",
        header: "Period",
        cell: (info) => {
          const r = info.row.original;
          const period = periodMap.get(r.financialPeriodId);
          return period ? (
            <span>
              <span className="font-medium">{period.name}</span>
              <span className="ml-1 text-xs text-text-muted">
                ({period.startDate} → {period.endDate})
              </span>
            </span>
          ) : (
            <span className="text-text-muted text-xs font-mono">
              {r.financialPeriodId.slice(0, 8)}…
            </span>
          );
        },
      },
      {
        accessorKey: "activityId",
        header: "Activity",
        cell: (info) => {
          const r = info.row.original;
          const activity = r.activityId
            ? activityMap.get(r.activityId)
            : null;
          return activity ? (
            <span>
              <span className="font-mono text-xs text-accent">
                {activity.code}
              </span>{" "}
              {activity.name}
            </span>
          ) : (
            <span className="italic text-text-muted">Project-level</span>
          );
        },
      },
      {
        accessorKey: "actualLaborCost",
        header: "Labor Cost",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span className="block text-right">
              {val != null ? moneyCompact(val) : "—"}
            </span>
          );
        },
      },
      {
        accessorKey: "actualNonlaborCost",
        header: "Non-Labor",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span className="block text-right text-text-secondary">
              {val != null ? moneyCompact(val) : "—"}
            </span>
          );
        },
      },
      {
        accessorKey: "actualMaterialCost",
        header: "Material",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span className="block text-right text-text-secondary">
              {val != null ? moneyCompact(val) : "—"}
            </span>
          );
        },
      },
      {
        accessorKey: "actualExpenseCost",
        header: "Expense",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span className="block text-right text-text-secondary">
              {val != null ? moneyCompact(val) : "—"}
            </span>
          );
        },
      },
      {
        accessorKey: "earnedValueCost",
        header: "EV Cost",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span className="block text-right text-success">
              {val != null ? moneyCompact(val) : "—"}
            </span>
          );
        },
      },
      {
        accessorKey: "plannedValueCost",
        header: "PV Cost",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span className="block text-right text-accent">
              {val != null ? moneyCompact(val) : "—"}
            </span>
          );
        },
      },
      {
        accessorKey: "actualLaborUnits",
        header: "Labor Units",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span className="block text-right text-text-secondary">
              {val != null
                ? val.toLocaleString("en-IN", { maximumFractionDigits: 2 })
                : "—"}
            </span>
          );
        },
      },
      {
        id: "actions",
        header: "",
        cell: (info) => {
          const r = info.row.original;
          return (
            <button
              onClick={() => {
                if (window.confirm("Delete this record?")) {
                  deleteMutation.mutate(r.id);
                }
              }}
              disabled={deleteMutation.isPending}
              className="text-danger hover:text-danger/80 disabled:opacity-50"
              title="Delete"
            >
              <Trash2 size={14} />
            </button>
          );
        },
      },
    ],
    [periodMap, activityMap, deleteMutation, moneyCompact]
  );

  return (
    <div className="space-y-6 px-6 pb-8">
      {/* <AiInsightsPanel projectId={projectId} endpoint={`/v1/projects/${projectId}/period-performance/ai/insights`} /> */}
      {/* Summary KPIs */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <KpiTile
          label="Total Actual Cost"
          value={moneyCompact(totalActualCost)}
          hint="Sum across all periods"
          tone="danger"
          icon={<DollarSign size={14} />}
        />
        <KpiTile
          label="Earned Value"
          value={moneyCompact(totalEv)}
          hint="Cumulative EV (SPP)"
          tone="success"
          icon={<TrendingUp size={14} />}
        />
        <KpiTile
          label="Planned Value"
          value={moneyCompact(totalPv)}
          hint="Cumulative PV (SPP)"
          tone="accent"
          icon={<BarChart3 size={14} />}
        />
        <KpiTile
          label="Labor Units"
          value={totalLaborUnits.toLocaleString("en-IN", { maximumFractionDigits: 1 })}
          hint="Actual labour units (all periods)"
          tone="default"
          icon={<Activity size={14} />}
        />
      </div>

      {/* Toolbar */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-text-secondary">
          Period Performance Records
        </h2>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-1.5 rounded-md bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent/90"
        >
          <Plus size={14} />
          Record Period
        </button>
      </div>

      {/* Entry Form */}
      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="rounded-xl border border-border bg-surface/60 p-5 shadow-sm space-y-4"
        >
          <h3 className="text-sm font-semibold text-text-primary">New Period Performance Entry</h3>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-text-secondary">
                Financial Period <span className="text-danger">*</span>
              </label>
              {isLoadingPeriods ? (
                <div className="h-9 animate-pulse rounded-md bg-surface-hover/50" />
              ) : (
                <select
                  value={selectedPeriodId}
                  onChange={(e) => setSelectedPeriodId(e.target.value)}
                  required
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
                >
                  <option value="">— Select period —</option>
                  {periods.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name} ({p.startDate} → {p.endDate}){p.isClosed ? " [Closed]" : ""}
                    </option>
                  ))}
                </select>
              )}
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium text-text-secondary">
                Activity (optional)
              </label>
              <select
                value={form.activityId ?? ""}
                onChange={(e) =>
                  setForm((f) => ({ ...f, activityId: e.target.value || null }))
                }
                className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
              >
                <option value="">— Project-level (no activity) —</option>
                {activities.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.code} — {a.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            {(
              [
                { field: "actualLaborCost", label: `Actual Labor Cost (${symbol})` },
                { field: "actualNonlaborCost", label: `Actual Non-Labor Cost (${symbol})` },
                { field: "actualMaterialCost", label: `Actual Material Cost (${symbol})` },
                { field: "actualExpenseCost", label: `Actual Expense Cost (${symbol})` },
              ] as const
            ).map(({ field, label }) => (
              <div key={field}>
                <label className="mb-1 block text-xs font-medium text-text-secondary">{label}</label>
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  value={form[field] ?? ""}
                  onChange={(e) =>
                    setForm((f) => ({ ...f, [field]: parseNum(e.target.value) }))
                  }
                  placeholder="0.00"
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-accent focus:outline-none"
                />
              </div>
            ))}
          </div>

          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            {(
              [
                { field: "actualLaborUnits", label: "Labor Units" },
                { field: "actualNonlaborUnits", label: "Non-Labor Units" },
                { field: "actualMaterialUnits", label: "Material Units" },
              ] as const
            ).map(({ field, label }) => (
              <div key={field}>
                <label className="mb-1 block text-xs font-medium text-text-secondary">{label}</label>
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  value={form[field] ?? ""}
                  onChange={(e) =>
                    setForm((f) => ({ ...f, [field]: parseNum(e.target.value) }))
                  }
                  placeholder="0.00"
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-accent focus:outline-none"
                />
              </div>
            ))}
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {(
              [
                { field: "earnedValueCost", label: `Earned Value Cost (${symbol})` },
                { field: "plannedValueCost", label: `Planned Value Cost (${symbol})` },
              ] as const
            ).map(({ field, label }) => (
              <div key={field}>
                <label className="mb-1 block text-xs font-medium text-text-secondary">{label}</label>
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  value={form[field] ?? ""}
                  onChange={(e) =>
                    setForm((f) => ({ ...f, [field]: parseNum(e.target.value) }))
                  }
                  placeholder="0.00"
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-accent focus:outline-none"
                />
              </div>
            ))}
          </div>

          <div className="flex gap-3 pt-1">
            <button
              type="submit"
              disabled={createMutation.isPending}
              className="rounded-md bg-accent px-4 py-1.5 text-xs font-semibold text-white hover:bg-accent/90 disabled:opacity-60"
            >
              {createMutation.isPending ? "Saving…" : "Save"}
            </button>
            <button
              type="button"
              onClick={() => {
                setShowForm(false);
                setForm(EMPTY_FORM);
                setSelectedPeriodId("");
              }}
              className="rounded-md border border-border px-4 py-1.5 text-xs font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
          </div>
        </form>
      )}

      {/* Records Table */}
      {isLoadingSpp ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-10 animate-pulse rounded-md bg-surface-hover/50" />
          ))}
        </div>
      ) : records.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border bg-surface-hover/20 py-12 text-center">
          <p className="text-sm text-text-muted">No period performance records yet.</p>
          <p className="mt-1 text-xs text-text-muted">
            Click &ldquo;Record Period&rdquo; to add the first entry.
          </p>
        </div>
      ) : (
        <VirtualDataTable
          columns={columns}
          data={records}
          sortable
          resizable
          searchable={false}
        />
      )}
    </div>
  );
}
