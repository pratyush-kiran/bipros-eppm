"use client";

import { useState, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  costApi,
  type ForecastMethod,
  type CashFlowForecastItem,
  type PeriodCostAggregation,
} from "@/lib/api/costApi";
import { budgetApi } from "@/lib/api/budgetApi";
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

interface SummaryCard {
  label: string;
  value: string;
  color: string;
}

const FORECAST_METHODS: { value: ForecastMethod; label: string }[] = [
  { value: "LINEAR", label: "Linear" },
  { value: "CPI_BASED", label: "CPI-Based" },
  { value: "SPI_CPI_COMPOSITE", label: "SPI×CPI Composite" },
];

export function CostsTab({ projectId }: { projectId: string }) {
  const { baseCurrency } = useCurrency();
  const [forecastMethod, setForecastMethod] = useState<ForecastMethod>("LINEAR");

  // P6-style project budget — fetched early so we have budgetCurrency before column defs.
  const { data: projectBudgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
  });

  // Derive project currency early (needed in column definitions below).
  const projectCurrencyEarly = projectBudgetData?.data?.budgetCurrency ?? baseCurrency.code;

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

  const { data: forecastData } = useQuery({
    queryKey: ["cost-forecast", projectId, forecastMethod],
    queryFn: () => costApi.generateForecast(projectId, forecastMethod),
  });

  const { data: periodData } = useQuery({
    queryKey: ["cost-periods", projectId],
    queryFn: () => costApi.getCostPeriods(projectId),
  });

  const summary = summaryData?.data;
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
      label: "Budget at Completion (BAC)",
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
            {[...summaryCards, ...evmCards].map((card) => (
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
          </div>{/* CV/CPI tiles flow into the same 4-col grid → wraps 4+3 */}

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
            <p className="text-text-secondary">No forecast data available. Create financial periods first.</p>
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

    </div>
  );
}
