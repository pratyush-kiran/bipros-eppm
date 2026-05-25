"use client";

import { KpiTile } from "@/components/common/KpiTile";
import { formatCurrency, formatPercent } from "@/lib/utils/format";

/**
 * Strip of summary tiles shown above the section accordions on every DBS tab.
 *
 * Maps the five sections (Material / Manpower / Admin / Machinery / Fuel) + the
 * BOQ revenue/expense/contribution roll into KPI tiles so the user sees the
 * P&L at a glance without expanding every accordion.
 *
 * `contributionPct` is colour-coded green (>0) / red (<0) to mirror the
 * Profit/Loss chip on the PM tab.
 */
export interface TotalsPanelProps {
  materialAmount: number;
  manpowerAmount: number;
  adminAmount: number;
  machineryAmount: number;
  fuelAmount: number;
  subcontractAmount: number;
  /** Section G — daily-prorated overhead on the PM tab. Other tabs pass undefined. */
  generalExpenseAmount?: number | null;
  /** Section G — month total for the row's yearMonth; shown as the tile hint. */
  generalExpenseMonthlyTotal?: number | null;
  /**
   * Sub-contractor is a project-level entity and is not attributed under a
   * supervisor / engineer / CM — those tabs pass false (default) and the F. tile
   * is hidden. PM tab passes true to surface the SC total and includes it in
   * the Total Expense hint.
   */
  showSubContractor?: boolean;
  totalExpense: number;
  totalIncome: number;
  contribution: number;
  contributionPct: number;
  currency?: string | null;
}

export function TotalsPanel({
  materialAmount,
  manpowerAmount,
  adminAmount,
  machineryAmount,
  fuelAmount,
  subcontractAmount,
  generalExpenseAmount,
  generalExpenseMonthlyTotal,
  showSubContractor = false,
  totalExpense,
  totalIncome,
  contribution,
  contributionPct,
  currency,
}: TotalsPanelProps) {
  // Profit/loss tone — drives the colour on the contribution tile.
  const contribTone = contribution > 0 ? "success" : contribution < 0 ? "danger" : "default";

  return (
    <div className="space-y-4">
      {/* Top row — P&L totals. Most important numbers, biggest tiles. */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile
          label="Total Expense"
          value={formatCurrency(totalExpense, currency)}
          hint={
            showSubContractor
              ? "Material + Manpower + Admin + Machinery + Fuel + Sub-Contractor"
              : "Material + Manpower + Admin + Machinery + Fuel"
          }
          tone="warning"
        />
        <KpiTile
          label="Total Income"
          value={formatCurrency(totalIncome, currency)}
          hint="qty executed × boq_rate"
          tone="accent"
        />
        <KpiTile
          label="Contribution"
          value={formatCurrency(contribution, currency)}
          hint="Income − Expense"
          tone={contribTone}
        />
        <KpiTile
          label="Contribution %"
          value={formatPercent(contributionPct)}
          hint="Contribution ÷ Income"
          tone={contribTone}
        />
      </div>

      {/* Second row — per-section breakdown. Smaller default tiles. Column count
         adapts to which tiles are shown so the row doesn't leave a gap when F.
         (and/or G.) are hidden on supervisor/engineer/CM tabs. */}
      <div
        className={`grid grid-cols-2 gap-3 md:grid-cols-3 ${
          (showSubContractor ? 1 : 0) +
            (generalExpenseAmount !== undefined && generalExpenseAmount !== null
              ? 1
              : 0) +
            5 >=
          7
            ? "lg:grid-cols-7"
            : (showSubContractor ? 1 : 0) +
                (generalExpenseAmount !== undefined && generalExpenseAmount !== null
                  ? 1
                  : 0) +
                5 ===
              6
            ? "lg:grid-cols-6"
            : "lg:grid-cols-5"
        }`}
      >
        <KpiTile label="A. Manpower" value={formatCurrency(manpowerAmount, currency)} />
        <KpiTile label="B. Admin / Catering" value={formatCurrency(adminAmount, currency)} />
        <KpiTile label="C. Machinery" value={formatCurrency(machineryAmount, currency)} />
        <KpiTile label="D. Fuel" value={formatCurrency(fuelAmount, currency)} />
        <KpiTile label="E. Material" value={formatCurrency(materialAmount, currency)} />
        {showSubContractor && (
          <KpiTile
            label="F. Sub-Contractor"
            value={formatCurrency(subcontractAmount, currency)}
          />
        )}
        {generalExpenseAmount !== undefined && generalExpenseAmount !== null && (
          <KpiTile
            label="G. General Expenses"
            value={formatCurrency(generalExpenseAmount, currency)}
            hint={
              generalExpenseMonthlyTotal
                ? `Month total ${formatCurrency(generalExpenseMonthlyTotal, currency)}`
                : "Monthly overhead, prorated per day"
            }
          />
        )}
      </div>
    </div>
  );
}
