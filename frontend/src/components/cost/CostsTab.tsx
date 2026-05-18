"use client";

import { useState, useCallback, useMemo } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { getErrorMessage } from "@/lib/utils/error";
import { Plus } from "lucide-react";
import Link from "next/link";
import {
  costApi,
  type ForecastMethod,
  type CashFlowForecastItem,
  type PeriodCostAggregation,
  type CreateExpenseRequest,
} from "@/lib/api/costApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { activityApi } from "@/lib/api/activityApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from "recharts";
import { useCurrency } from "@/lib/hooks/useCurrency";
import { SecretField } from "@/components/auth/SecretField";
import { AiInsightsPanel } from "@/components/ai/AiInsightsPanel";

const FINANCE_ROLES = ["ROLE_FINANCE", "ROLE_PMO", "ROLE_ADMIN"] as const;
const NO_FINANCE_PLACEHOLDER = (
  <div className="rounded-lg border border-dashed border-border bg-surface-hover/40 px-4 py-6 text-center text-sm text-text-muted">
    Cost figures are restricted to Finance / PMO roles.
  </div>
);

/**
 * Smart currency formatter — adapts to the project's budget currency and
 * picks the most readable scale:
 *   < 100 000          → "6,500 OMR"     (raw, e.g. small OMR projects)
 *   100 000 – 9 999 999 → "65k OMR"      (thousands)
 *   ≥ 10 000 000       → "₹4.85cr"       (INR crores) or "4.85M OMR"
 */
function makeFormatter(currency: string) {
  const code = (currency ?? "INR").toUpperCase();
  const isInr = code === "INR";
  const locale = isInr ? "en-IN" : "en-US";

  return function formatValue(value: number | null | undefined): string {
    const v = value ?? 0;
    const abs = Math.abs(v);

    if (abs < 100_000) {
      // Raw value with currency code
      return `${v.toLocaleString(locale, { maximumFractionDigits: 0 })} ${code}`;
    }
    if (abs < 10_000_000) {
      // Thousands
      const k = v / 1_000;
      return `${k.toLocaleString(locale, { maximumFractionDigits: 1 })}k ${code}`;
    }
    // Crore (INR) or Millions (others)
    if (isInr) {
      const cr = v / 10_000_000;
      return `₹${cr.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}cr`;
    }
    const m = v / 1_000_000;
    return `${m.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}M ${code}`;
  };
}

function formatAmount(amount: number, currency: string): string {
  return makeFormatter(currency)(amount);
}


interface SummaryCard {
  label: string;
  value: string;
  color: string;
}

interface ExpenseRow {
  id: string;
  description: string;
  actualCost: number;
  expenseCategory: string;
  actualStartDate: string | null;
  activityId: string | null;
}

const FORECAST_METHODS: { value: ForecastMethod; label: string }[] = [
  { value: "LINEAR", label: "Linear" },
  { value: "CPI_BASED", label: "CPI-Based" },
  { value: "SPI_CPI_COMPOSITE", label: "SPI×CPI Composite" },
];

export function CostsTab({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const { baseCurrency } = useCurrency();
  const [forecastMethod, setForecastMethod] = useState<ForecastMethod>("LINEAR");
  const [showExpenseForm, setShowExpenseForm] = useState(false);
  const [expenseForm, setExpenseForm] = useState<CreateExpenseRequest>({
    description: "",
    actualCost: 0,
    currency: "INR",
    actualStartDate: new Date().toISOString().split("T")[0],
    expenseCategory: "LABOR",
  });

  const createExpenseMutation = useMutation({
    mutationFn: (data: CreateExpenseRequest) => costApi.createExpense(projectId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["expenses", projectId] });
      queryClient.invalidateQueries({ queryKey: ["cost-summary", projectId] });
      setShowExpenseForm(false);
      setExpenseForm({ description: "", actualCost: 0, currency: baseCurrency.code, actualStartDate: new Date().toISOString().split("T")[0], expenseCategory: "LABOR" });
      toast.success("Expense recorded successfully");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to create expense"));
    },
  });

  const [editingExpenseId, setEditingExpenseId] = useState<string | null>(null);

  const updateExpenseMutation = useMutation({
    mutationFn: (data: CreateExpenseRequest) => costApi.updateExpense(projectId, editingExpenseId!, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["expenses", projectId] });
      queryClient.invalidateQueries({ queryKey: ["cost-summary", projectId] });
      setShowExpenseForm(false);
      setEditingExpenseId(null);
      setExpenseForm({ description: "", actualCost: 0, currency: baseCurrency.code, actualStartDate: new Date().toISOString().split("T")[0], expenseCategory: "LABOR" });
      toast.success("Expense updated successfully");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to update expense"));
    },
  });

  const deleteExpenseMutation = useMutation({
    mutationFn: (expenseId: string) => costApi.deleteExpense(projectId, expenseId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["expenses", projectId] });
      queryClient.invalidateQueries({ queryKey: ["cost-summary", projectId] });
      toast.success("Expense deleted successfully");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to delete expense"));
    },
  });

  const { data: activitiesData } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 200),
  });

  // P6-style project budget — fetched early so we have budgetCurrency before column defs.
  const { data: projectBudgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
  });

  // Derive project currency early (needed in column definitions below).
  const projectCurrencyEarly = projectBudgetData?.data?.budgetCurrency ?? baseCurrency.code;

  const activities = useMemo(() => activitiesData?.data?.content ?? [], [activitiesData]);

  const handleEdit = useCallback((expense: ExpenseRow) => {
    setEditingExpenseId(expense.id);
    setExpenseForm({
      description: expense.description,
      actualCost: expense.actualCost,
      currency: baseCurrency.code,
      actualStartDate: expense.actualStartDate,
      expenseCategory: expense.expenseCategory,
      activityId: expense.activityId ?? undefined,
    });
    setShowExpenseForm(true);
  }, [baseCurrency.code]);

  const expenseColumns = useMemo<ColumnDef<ExpenseRow>[]>(() => [
    { accessorKey: "description", header: "Description", enableSorting: true },
    {
      accessorKey: "activityId",
      header: "Activity",
      cell: (info) => {
        const row = info.row.original;
        if (!row.activityId) {
          return <span className="text-text-muted">—</span>;
        }
        const activity = activities.find((a) => a.id === row.activityId);
        const label = activity ? `${activity.code} - ${activity.name}` : row.activityId;
        return (
          <Link
            href={`/projects/${projectId}/activities/${row.activityId}`}
            className="text-accent hover:underline"
          >
            {label}
          </Link>
        );
      },
    },
    { accessorKey: "expenseCategory", header: "Category", enableSorting: true },
    {
      accessorKey: "actualCost",
      header: `Amount (${projectCurrencyEarly})`,
      enableSorting: true,
      cell: (info) => formatAmount(Number(info.getValue()), projectCurrencyEarly),
    },
    { accessorKey: "actualStartDate", header: "Date", enableSorting: true },
    {
      id: "actions",
      header: "Actions",
      cell: (info) => {
        const row = info.row.original;
        return (
          <div className="flex gap-2">
            <button
              onClick={() => handleEdit(row)}
              className="rounded-md border border-border px-2 py-1 text-xs text-text-secondary hover:bg-surface-hover"
            >
              Edit
            </button>
            <button
              onClick={() => {
                if (window.confirm("Delete this expense?")) {
                  deleteExpenseMutation.mutate(row.id);
                }
              }}
              disabled={deleteExpenseMutation.isPending}
              className="rounded-md border border-border px-2 py-1 text-xs text-danger hover:bg-surface-hover disabled:opacity-50"
            >
              Delete
            </button>
          </div>
        );
      },
    },
  ], [handleEdit, deleteExpenseMutation, activities, projectId, projectCurrencyEarly]);

  const periodColumns = useMemo<ColumnDef<PeriodCostAggregation>[]>(
    () => {
      const cu = projectCurrencyEarly;
      const fmtPeriod = makeFormatter(cu);
      return [
        { accessorKey: "periodName", header: "Period" },
        {
          accessorKey: "budget",
          header: `Budget (${cu})`,
          cell: (info) => (
            <span className="block text-right text-accent">
              {fmtPeriod(Number(info.getValue()))}
            </span>
          ),
        },
        {
          accessorKey: "actual",
          header: `Actual (${cu})`,
          cell: (info) => (
            <span className="block text-right text-success">
              {fmtPeriod(Number(info.getValue()))}
            </span>
          ),
        },
        {
          accessorKey: "variance",
          header: `Variance (${cu})`,
          cell: (info) => {
            const v = Number(info.getValue());
            return (
              <span
                className={`block text-right ${
                  v >= 0 ? "text-success" : "text-danger"
                }`}
              >
                {fmtPeriod(v)}
              </span>
            );
          },
        },
        {
          accessorKey: "earnedValue",
          header: `Earned Value (${cu})`,
          cell: (info) => (
            <span className="block text-right text-warning">
              {fmtPeriod(Number(info.getValue()))}
            </span>
          ),
        },
        {
          accessorKey: "plannedValue",
          header: `Planned Value (${cu})`,
          cell: (info) => (
            <span className="block text-right text-info">
              {fmtPeriod(Number(info.getValue()))}
            </span>
          ),
        },
      ];
    },
    [projectCurrencyEarly]
  );

  const { data: summaryData, isLoading: isLoadingSummary } = useQuery({
    queryKey: ["cost-summary", projectId],
    queryFn: () => costApi.getCostSummary(projectId),
  });

  const { data: expensesData, isLoading: isLoadingExpenses } = useQuery({
    queryKey: ["expenses", projectId],
    queryFn: () => costApi.getExpensesByProject(projectId, 0, 100),
  });

  const { data: forecastData } = useQuery({
    queryKey: ["cost-forecast", projectId, forecastMethod],
    queryFn: () => costApi.generateForecast(projectId, forecastMethod),
  });

  const { data: periodData } = useQuery({
    queryKey: ["cost-periods", projectId],
    queryFn: () => costApi.getCostPeriods(projectId),
  });

  const summary = summaryData?.data;
  const expenses = expensesData?.data?.content ?? [];
  const forecastItems: CashFlowForecastItem[] = forecastData?.data ?? [];
  const periodAggregations: PeriodCostAggregation[] = periodData?.data ?? [];

  // projectCurrencyEarly is already derived above (before column definitions).
  // Use it directly here for consistency.
  const projectCurrency = projectCurrencyEarly;
  const fmt = makeFormatter(projectCurrency);

  // cost-summary is the source of truth for budget and actual (absolute amounts in project currency).
  const totalBudget = summary?.totalBudget ?? 0;
  const totalActual = summary?.totalActual ?? 0;
  const totalRemaining = Math.max(totalBudget - totalActual, 0);
  const atCompletion = Math.max(totalBudget, totalActual);

  const chartData = forecastItems.map((item) => ({
    period: item.period,
    planned: item.plannedAmount || 0,
    actual: item.actualAmount || 0,
    forecast: item.forecastAmount || 0,
    cumulativePlanned: item.cumulativePlanned || 0,
    cumulativeActual: item.cumulativeActual || 0,
    cumulativeForecast: item.cumulativeForecast || 0,
  }));

  const summaryCards: SummaryCard[] = [
    {
      label: "Project Budget (P6)",
      value: projectBudgetData?.data?.currentBudget != null
        ? fmt(projectBudgetData.data.currentBudget)
        : "Not set",
      color: "indigo",
    },
    {
      label: "Total Budget (Expenses)",
      value: summary?.totalBudget != null && summary.totalBudget > 0
        ? fmt(totalBudget)
        : "—",
      color: "blue",
    },
    {
      label: "Total Actual",
      value: summary?.totalActual != null && summary.totalActual > 0
        ? fmt(totalActual)
        : "—",
      color: "green",
    },
    {
      label: "Total Remaining",
      value: fmt(totalRemaining),
      color: "yellow",
    },
    {
      label: "At Completion",
      value: fmt(atCompletion),
      color: "purple",
    },
  ];

  const evmCards: SummaryCard[] = summary
    ? [
        {
          label: "Cost Variance (CV)",
          value: fmt(summary.costVariance),
          color: summary.costVariance >= 0 ? "green" : "red",
        },
        {
          label: "CPI",
          value: summary.costPerformanceIndex != null
            ? summary.costPerformanceIndex.toFixed(4)
            : "—",
          color: summary.costPerformanceIndex != null
            ? summary.costPerformanceIndex >= 1
              ? "green"
              : "red"
            : "slate",
        },
        {
          label: "Expenses",
          value: String(summary.expenseCount),
          color: "slate",
        },
      ]
    : [];

  // PMS MasterData procurement roll-up — shown only when the project has material activity.
  const procurementCards: SummaryCard[] = summary && (summary.materialProcurementCost ?? 0) > 0
    ? [
        {
          label: "Material Procured",
          value: fmt(summary.materialProcurementCost ?? 0),
          color: "blue",
        },
        {
          label: "Open Stock Value",
          value: fmt(summary.openStockValue ?? 0),
          color: "yellow",
        },
        {
          label: "Material Issued",
          value: fmt(summary.materialIssuedCost ?? 0),
          color: "green",
        },
      ]
    : [];

  const accentMap: Record<string, string> = {
    blue: "border-l-4 border-l-accent",
    green: "border-l-4 border-l-success",
    yellow: "border-l-4 border-l-warning",
    purple: "border-l-4 border-l-info",
    red: "border-l-4 border-l-danger",
    indigo: "border-l-4 border-l-indigo-500",
    slate: "",
  };

  const textColorMap: Record<string, string> = {
    blue: "text-accent",
    green: "text-success",
    yellow: "text-warning",
    purple: "text-info",
    red: "text-danger",
    indigo: "text-indigo-600 dark:text-indigo-400",
    slate: "text-text-primary",
  };

  return (
    <div className="space-y-6">
      {/* <AiInsightsPanel projectId={projectId} endpoint={`/v1/projects/${projectId}/cost/ai/insights`} /> */}
      {isLoadingSummary ? (
        <div className="text-center text-text-secondary">Loading cost summary...</div>
      ) : (
        // The whole financial roll-up (budget/actual/remaining/EVM/procurement) is FINANCE/PMO-only.
        // Backend already strips the underlying fields via @JsonView; this just gives the UI a clean
        // placeholder instead of empty cards full of zeros for users who aren't entitled to see them.
        <SecretField visibleTo={FINANCE_ROLES} masked={NO_FINANCE_PLACEHOLDER}>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            {summaryCards.map((card) => (
              <div
                key={card.label}
                className={`rounded-lg border border-border bg-surface-hover/40 p-4 ${accentMap[card.color]}`}
              >
                <h3 className="text-xs font-medium uppercase tracking-wide text-text-secondary">{card.label}</h3>
                <p className={`mt-2 text-2xl font-bold ${textColorMap[card.color]}`}>
                  {card.value}
                </p>
              </div>
            ))}
          </div>

          {evmCards.length > 0 && (
            <div className="grid grid-cols-3 gap-4">
              {evmCards.map((card) => (
                <div
                  key={card.label}
                  className={`rounded-lg border border-border bg-surface-hover/40 p-4 ${accentMap[card.color]}`}
                >
                  <h3 className="text-xs font-medium uppercase tracking-wide text-text-secondary">{card.label}</h3>
                  <p className={`mt-2 text-xl font-bold ${textColorMap[card.color]}`}>
                    {card.value}
                  </p>
                </div>
              ))}
            </div>
          )}

          {procurementCards.length > 0 && (
            <div>
              <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-text-secondary">
                Material Procurement
              </h3>
              <div className="grid grid-cols-3 gap-4">
                {procurementCards.map((card) => (
                  <div
                    key={card.label}
                    className={`rounded-lg border border-border bg-surface-hover/40 p-4 ${accentMap[card.color]}`}
                  >
                    <h4 className="text-xs font-medium uppercase tracking-wide text-text-secondary">{card.label}</h4>
                    <p className={`mt-2 text-xl font-bold ${textColorMap[card.color]}`}>
                      {card.value}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          )}
        </SecretField>
      )}

      {/* Cash Flow S-Curve with Forecast Method Selector */}
      <SecretField visibleTo={FINANCE_ROLES} masked={null}>
      <div className="rounded-lg border border-border bg-surface/50 p-6">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-semibold text-text-primary">Cash Flow S-Curve</h3>
          <div className="flex items-center gap-2">
            <label className="text-sm text-text-secondary">Forecast Method:</label>
            <select
              value={forecastMethod}
              onChange={(e) => setForecastMethod(e.target.value as ForecastMethod)}
              className="rounded-md border border-border bg-surface-hover px-3 py-1.5 text-sm text-text-primary focus:border-accent focus:outline-none"
            >
              {FORECAST_METHODS.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </div>
        </div>
        {chartData.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border py-12 text-center">
            <p className="text-text-secondary">No forecast data available. Create financial periods and expenses first.</p>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={400}>
            <LineChart data={chartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--grid-color)" />
              <XAxis
                dataKey="period"
                stroke="var(--text-muted)"
                style={{ fontSize: "12px" }}
              />
              <YAxis
                stroke="var(--text-muted)"
                style={{ fontSize: "12px" }}
                label={{ value: `Amount (${projectCurrency})`, angle: -90, position: "insideLeft" }}
                tickFormatter={(v) => typeof v === "number" ? fmt(v) : String(v)}
                width={90}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: "var(--surface)",
                  border: "1px solid var(--border)",
                  borderRadius: "0.5rem",
                  color: "var(--text-primary)",
                }}
                formatter={(value) =>
                  typeof value === "number" ? fmt(value) : String(value ?? "")
                }
              />
              <Legend />
              <Line
                type="monotone"
                dataKey="cumulativePlanned"
                name="Planned"
                stroke="var(--accent)"
                strokeWidth={2}
                dot={false}
              />
              <Line
                type="monotone"
                dataKey="cumulativeActual"
                name="Actual"
                stroke="var(--success)"
                strokeWidth={2}
                dot={false}
              />
              <Line
                type="monotone"
                dataKey="cumulativeForecast"
                name="Forecast"
                stroke="var(--warning)"
                strokeWidth={2}
                strokeDasharray="5 5"
                dot={false}
              />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
      </SecretField>

      {/* Period-by-Period Cost Table */}
      {periodAggregations.length > 0 && (
        <SecretField visibleTo={FINANCE_ROLES} masked={null}>
        <div className="rounded-lg border border-border bg-surface/50 p-6">
          <h3 className="mb-4 text-lg font-semibold text-text-primary">
            Period Cost Breakdown
          </h3>
          <SimpleTable
            columns={periodColumns}
            data={periodAggregations}
            sortable={false}
          />
        </div>
        </SecretField>
      )}

      <SecretField visibleTo={FINANCE_ROLES} masked={null}>
      <div>
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-semibold text-text-primary">Expenses</h3>
          <button
            onClick={() => {
              if (showExpenseForm) {
                setEditingExpenseId(null);
                    setExpenseForm({ description: "", actualCost: 0, currency: baseCurrency.code, actualStartDate: new Date().toISOString().split("T")[0], expenseCategory: "LABOR" });
              }
              setShowExpenseForm(!showExpenseForm);
            }}
            className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
          >
            <Plus size={16} />
            Add Expense
          </button>
        </div>

        {showExpenseForm && (
          <div className="mb-4 rounded-lg border border-border bg-surface-hover/50 p-4">
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (!expenseForm.description || !expenseForm.actualCost) return;
                if (editingExpenseId) {
                  updateExpenseMutation.mutate(expenseForm);
                } else {
                  createExpenseMutation.mutate(expenseForm);
                }
              }}
              className="grid grid-cols-2 gap-4 lg:grid-cols-4"
            >
              <div>
                <label className="block text-xs font-medium text-text-secondary">Description *</label>
                <input
                  type="text"
                  value={expenseForm.description}
                  onChange={(e) => setExpenseForm((prev) => ({ ...prev, description: e.target.value }))}
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  placeholder="e.g., Concrete delivery"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-text-secondary">Amount ({baseCurrency.symbol}) *</label>
                <input
                  type="number"
                  value={expenseForm.actualCost || ""}
                  onChange={(e) => setExpenseForm((prev) => ({ ...prev, actualCost: parseFloat(e.target.value) || 0 }))}
                  min="0"
                  step="0.01"
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                  placeholder="e.g., 5000"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-text-secondary">Category</label>
                <select
                  value={expenseForm.expenseCategory}
                  onChange={(e) => setExpenseForm((prev) => ({ ...prev, expenseCategory: e.target.value }))}
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  <option value="LABOR">Labor</option>
                  <option value="MATERIAL">Material</option>
                  <option value="EQUIPMENT">Equipment</option>
                  <option value="SUBCONTRACT">Subcontract</option>
                  <option value="OVERHEAD">Overhead</option>
                  <option value="OTHER">Other</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-text-secondary">Date</label>
                <input
                  type="date"
                  value={expenseForm.actualStartDate ?? ""}
                  onChange={(e) => setExpenseForm((prev) => ({ ...prev, actualStartDate: e.target.value || null }))}
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-text-secondary">Activity (optional)</label>
                <select
                  value={expenseForm.activityId ?? ""}
                  onChange={(e) =>
                    setExpenseForm((prev) => ({
                      ...prev,
                      activityId: e.target.value || undefined,
                    }))
                  }
                  className="mt-1 block w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                >
                  <option value="">(Unassigned)</option>
                  {activities.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.code} - {a.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="col-span-full flex gap-2">
                <button
                  type="submit"
                  disabled={createExpenseMutation.isPending || updateExpenseMutation.isPending}
                  className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-border"
                >
                  {editingExpenseId
                    ? (updateExpenseMutation.isPending ? "Updating..." : "Update Expense")
                    : (createExpenseMutation.isPending ? "Saving..." : "Save Expense")}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setShowExpenseForm(false);
                    setEditingExpenseId(null);
                setExpenseForm({ description: "", actualCost: 0, currency: baseCurrency.code, actualStartDate: new Date().toISOString().split("T")[0], expenseCategory: "LABOR" });
                  }}
                  className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        )}

        {isLoadingExpenses ? (
          <div className="text-center text-text-muted">Loading expenses...</div>
        ) : expenses.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border py-12 text-center">
            <h3 className="text-lg font-medium text-text-primary">No Expenses</h3>
            <p className="mt-2 text-text-muted">No expenses recorded yet.</p>
          </div>
        ) : (
          <VirtualDataTable columns={expenseColumns} data={expenses} sortable resizable />
        )}
      </div>
      </SecretField>
    </div>
  );
}
