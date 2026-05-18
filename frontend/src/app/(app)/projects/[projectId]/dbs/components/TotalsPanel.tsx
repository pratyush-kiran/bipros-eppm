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
          hint="Material + Manpower + Admin + Machinery + Fuel"
          tone="warning"
        />
        <KpiTile
          label="Total Income"
          value={formatCurrency(totalIncome, currency)}
          hint="BOQ executed today × rate"
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

      {/* Second row — per-section breakdown. Smaller default tiles. */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
        <KpiTile label="Material" value={formatCurrency(materialAmount, currency)} />
        <KpiTile label="Manpower" value={formatCurrency(manpowerAmount, currency)} />
        <KpiTile label="Admin / Catering" value={formatCurrency(adminAmount, currency)} />
        <KpiTile label="Machinery" value={formatCurrency(machineryAmount, currency)} />
        <KpiTile label="Fuel" value={formatCurrency(fuelAmount, currency)} />
        <KpiTile
          label="Sub-Contractor"
          value={formatCurrency(subcontractAmount, currency)}
          hint="v2"
        />
      </div>
    </div>
  );
}
