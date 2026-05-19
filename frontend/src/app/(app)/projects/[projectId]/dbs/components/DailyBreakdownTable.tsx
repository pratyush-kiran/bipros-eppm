"use client";

import { useMemo } from "react";

import { formatCurrency, formatPercent } from "@/lib/utils/format";

/**
 * One-row-per-day breakdown of a DBS period response, used by every tab that
 * supports WEEK / MONTH mode. Adds three "balance-sheet-style" affordances on top
 * of the raw daily flow:
 *
 * <ul>
 *   <li>Running cumulative columns (Cum Expense / Income / Contribution) so the
 *       table reads as a running stock, not just isolated daily deltas. The cum
 *       is within-period only — project-to-date cumulative requires extra
 *       backend data and isn't included here.</li>
 *   <li>Empty days (no expense and no income) are styled muted so the eye skips
 *       past them to the days that actually had activity.</li>
 *   <li>Period-total row in the table footer with summed Expense / Income /
 *       Contribution / Contribution % (recomputed from period totals, not
 *       averaged across daily %).</li>
 * </ul>
 */
export interface DailyBreakdownRow {
  reportDate: string;
  totalExpense: number;
  totalIncome: number;
  contribution: number;
  contributionPct: number;
  /** Present on PM-tier rows only; the other tiers omit it. */
  dprCount?: number;
}

export interface DailyBreakdownTableProps {
  rows: DailyBreakdownRow[];
  currency?: string | null;
  /** "week" / "month" — drives the section header copy. */
  periodLabel: string;
  /** Show the DPRs column (PM tier only). */
  showDprCount?: boolean;
}

interface EnrichedRow extends DailyBreakdownRow {
  cumExpense: number;
  cumIncome: number;
  cumContribution: number;
  isEmpty: boolean;
}

export function DailyBreakdownTable({
  rows,
  currency,
  periodLabel,
  showDprCount = false,
}: DailyBreakdownTableProps) {
  // Date-sort defensively — backends should already emit chronological rows but
  // running cumulative breaks badly if they don't.
  const sortedRows = useMemo(
    () => [...rows].sort((a, b) => a.reportDate.localeCompare(b.reportDate)),
    [rows],
  );

  // Reduce over sortedRows building an immutable enriched array. Each step reads
  // the previous element's running totals from the accumulator — no `let`
  // reassignment, which keeps React Compiler's immutability rule happy.
  const enriched = useMemo(
    () =>
      sortedRows.reduce<EnrichedRow[]>((acc, row) => {
        const prev = acc[acc.length - 1];
        const expense = row.totalExpense ?? 0;
        const income = row.totalIncome ?? 0;
        const cumExpense = (prev?.cumExpense ?? 0) + expense;
        const cumIncome = (prev?.cumIncome ?? 0) + income;
        // "Empty" = no money moved in either direction. Lets us mute the row even
        // when dprCount is missing (Supervisor / Engineer responses don't carry it).
        const isEmpty = expense === 0 && income === 0;
        return [
          ...acc,
          {
            ...row,
            cumExpense,
            cumIncome,
            cumContribution: cumIncome - cumExpense,
            isEmpty,
          },
        ];
      }, []),
    [sortedRows],
  );

  const totals = useMemo(
    () =>
      enriched.reduce(
        (acc, row) => ({
          expense: acc.expense + (row.totalExpense ?? 0),
          income: acc.income + (row.totalIncome ?? 0),
          contribution: acc.contribution + (row.contribution ?? 0),
          dprs: acc.dprs + (row.dprCount ?? 0),
          activeDays: acc.activeDays + (row.isEmpty ? 0 : 1),
        }),
        { expense: 0, income: 0, contribution: 0, dprs: 0, activeDays: 0 },
      ),
    [enriched],
  );

  // Recompute the period contribution % from period totals so a few high-margin
  // days don't get over-weighted vs many low-margin days (which would happen if
  // we averaged the daily %).
  const periodContributionPct =
    totals.income > 0 ? (totals.contribution / totals.income) * 100 : 0;

  const dayWordPlural = totals.activeDays === 1 ? "day" : "days";

  return (
    <section className="rounded-lg border border-border bg-surface/50 shadow-sm">
      <header className="flex items-center justify-between border-b border-border px-4 py-3">
        <h3 className="text-sm font-semibold text-text-primary">
          Daily breakdown ({periodLabel} view)
        </h3>
        <span className="text-xs text-text-muted">
          {totals.activeDays} active {dayWordPlural} of {enriched.length}
        </span>
      </header>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
            <tr>
              <th className="px-4 py-2">Date</th>
              <th className="px-4 py-2 text-right">Expense</th>
              <th className="px-4 py-2 text-right">Income</th>
              <th className="px-4 py-2 text-right">Contribution</th>
              <th className="px-4 py-2 text-right">Contribution %</th>
              <th className="border-l border-border px-4 py-2 text-right">
                Cum Expense
              </th>
              <th className="px-4 py-2 text-right">Cum Income</th>
              <th className="px-4 py-2 text-right">Cum Contribution</th>
              {showDprCount ? (
                <th className="px-4 py-2 text-right">DPRs</th>
              ) : null}
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {enriched.map((row) => (
              <tr key={row.reportDate} className={row.isEmpty ? "opacity-40" : ""}>
                <td className="px-4 py-2 text-text-primary">{row.reportDate}</td>
                <td className="px-4 py-2 text-right font-mono text-text-secondary">
                  {row.isEmpty ? "—" : formatCurrency(row.totalExpense, currency)}
                </td>
                <td className="px-4 py-2 text-right font-mono text-text-secondary">
                  {row.isEmpty ? "—" : formatCurrency(row.totalIncome, currency)}
                </td>
                <td
                  className={`px-4 py-2 text-right font-mono ${
                    row.isEmpty
                      ? "text-text-muted"
                      : row.contribution > 0
                        ? "text-emerald-600"
                        : row.contribution < 0
                          ? "text-rose-600"
                          : "text-text-secondary"
                  }`}
                >
                  {row.isEmpty ? "—" : formatCurrency(row.contribution, currency)}
                </td>
                <td className="px-4 py-2 text-right font-mono text-text-secondary">
                  {row.isEmpty ? "—" : formatPercent(row.contributionPct * 100)}
                </td>
                <td className="border-l border-border px-4 py-2 text-right font-mono text-text-secondary">
                  {formatCurrency(row.cumExpense, currency)}
                </td>
                <td className="px-4 py-2 text-right font-mono text-text-secondary">
                  {formatCurrency(row.cumIncome, currency)}
                </td>
                <td
                  className={`px-4 py-2 text-right font-mono ${
                    row.cumContribution > 0
                      ? "text-emerald-600"
                      : row.cumContribution < 0
                        ? "text-rose-600"
                        : "text-text-secondary"
                  }`}
                >
                  {formatCurrency(row.cumContribution, currency)}
                </td>
                {showDprCount ? (
                  <td className="px-4 py-2 text-right font-mono text-text-secondary">
                    {row.dprCount ?? 0}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t-2 border-border bg-surface/40">
            <tr className="font-semibold">
              <td className="px-4 py-2 text-xs uppercase tracking-wide text-text-primary">
                Period total
              </td>
              <td className="px-4 py-2 text-right font-mono text-text-primary">
                {formatCurrency(totals.expense, currency)}
              </td>
              <td className="px-4 py-2 text-right font-mono text-text-primary">
                {formatCurrency(totals.income, currency)}
              </td>
              <td
                className={`px-4 py-2 text-right font-mono ${
                  totals.contribution > 0
                    ? "text-emerald-600"
                    : totals.contribution < 0
                      ? "text-rose-600"
                      : "text-text-primary"
                }`}
              >
                {formatCurrency(totals.contribution, currency)}
              </td>
              <td className="px-4 py-2 text-right font-mono text-text-primary">
                {formatPercent(periodContributionPct)}
              </td>
              <td
                colSpan={3}
                className="border-l border-border px-4 py-2 text-right text-xs italic text-text-muted"
              >
                running totals →
              </td>
              {showDprCount ? (
                <td className="px-4 py-2 text-right font-mono text-text-primary">
                  {totals.dprs}
                </td>
              ) : null}
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}
