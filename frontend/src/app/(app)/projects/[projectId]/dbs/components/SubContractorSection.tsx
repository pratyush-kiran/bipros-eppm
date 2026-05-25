"use client";

import { Fragment, useMemo, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";

import type { DbsSubContractLine } from "@/lib/api/dbsApi";
import { formatCurrency } from "@/lib/utils/format";

export interface SubContractorSectionProps {
  /** PM tab's F. Sub-Contractor accordion data. */
  lines: DbsSubContractLine[];
  /** Header subtotal — sum of {@link DbsSubContractLine.scExpense} across all rows. */
  totalExpense: number;
  currency?: string | null;
}

/**
 * PM tab's F. Sub-Contractor accordion. Lines are grouped by sub-contractor
 * master, with one sub-row per (work-type) tuple within each group. The header
 * shows the day's total SC expense; expanding the card reveals per-SC subtotals
 * with margin (BOQ imputed income − SC expense).
 *
 * Mirrors the {@code Anbazhagan-TS} sheet's F. SubContractor block, plus the
 * imputed-income column the user asked to surface so margin is visible.
 */
export function SubContractorSection({
  lines,
  totalExpense,
  currency,
}: SubContractorSectionProps) {
  const [open, setOpen] = useState(false);

  // Group lines by sub-contractor master code+name. One group per SC; sub-rows
  // are the (work-type) lines within that group with subtotals on the group
  // header row.
  const groups = useMemo(() => {
    const byKey = new Map<
      string,
      {
        code?: string | null;
        name?: string | null;
        rows: DbsSubContractLine[];
        subTotalExpense: number;
        subTotalIncome: number;
      }
    >();
    for (const line of lines ?? []) {
      const key = (line.subContractorCode ?? "") + "|" + (line.subContractorName ?? "");
      const existing = byKey.get(key);
      if (existing) {
        existing.rows.push(line);
        existing.subTotalExpense += line.scExpense ?? 0;
        existing.subTotalIncome += line.scImputedIncome ?? 0;
      } else {
        byKey.set(key, {
          code: line.subContractorCode,
          name: line.subContractorName,
          rows: [line],
          subTotalExpense: line.scExpense ?? 0,
          subTotalIncome: line.scImputedIncome ?? 0,
        });
      }
    }
    return Array.from(byKey.values());
  }, [lines]);

  const lineCount = lines?.length ?? 0;

  return (
    <section className="rounded-lg border border-border bg-surface/50 shadow-sm">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left"
      >
        <div className="flex items-center gap-2">
          {open ? (
            <ChevronDown size={14} className="text-text-muted" />
          ) : (
            <ChevronRight size={14} className="text-text-muted" />
          )}
          <h3 className="text-sm font-semibold text-text-primary">F. Sub-Contractor</h3>
          <span className="text-xs text-text-muted">
            {lineCount} {lineCount === 1 ? "line" : "lines"}
          </span>
        </div>
        <span className="font-mono text-sm text-text-primary">
          {formatCurrency(totalExpense, currency)}
        </span>
      </button>

      {open ? (
        lineCount === 0 ? (
          <div className="border-t border-border px-4 py-6 text-center text-xs text-text-muted">
            No sub-contractor entries on this date.
          </div>
        ) : (
          <div className="overflow-x-auto border-t border-border">
            <table className="w-full text-sm" data-testid="pm-sub-contractor-section">
              <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
                <tr>
                  <th className="px-4 py-2">Description</th>
                  <th className="px-4 py-2">Unit</th>
                  <th className="px-4 py-2 text-right">Qty</th>
                  <th className="px-4 py-2 text-right">SC Rate</th>
                  <th className="px-4 py-2 text-right">Expense</th>
                  <th className="px-4 py-2 text-right">BOQ Rate</th>
                  <th className="px-4 py-2 text-right">Imputed Income</th>
                  <th className="px-4 py-2 text-right">Margin</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {groups.map((g, gi) => {
                  const margin = g.subTotalIncome - g.subTotalExpense;
                  return (
                    <Fragment key={`group-${gi}`}>
                      <tr className="bg-surface/30">
                        <td className="px-4 py-2 font-semibold text-text-primary" colSpan={4}>
                          {g.name ?? "—"}
                          {g.code ? (
                            <span className="ml-2 text-xs font-normal text-text-muted">
                              ({g.code})
                            </span>
                          ) : null}
                        </td>
                        <td className="px-4 py-2 text-right font-mono text-sm font-semibold text-text-primary">
                          {formatCurrency(g.subTotalExpense, currency)}
                        </td>
                        <td className="px-4 py-2"></td>
                        <td className="px-4 py-2 text-right font-mono text-sm font-semibold text-text-primary">
                          {formatCurrency(g.subTotalIncome, currency)}
                        </td>
                        <td
                          className={`px-4 py-2 text-right font-mono text-sm font-semibold ${
                            margin > 0
                              ? "text-emerald-600"
                              : margin < 0
                                ? "text-rose-600"
                                : "text-text-secondary"
                          }`}
                        >
                          {formatCurrency(margin, currency)}
                        </td>
                      </tr>
                      {g.rows.map((row, ri) => (
                        <tr key={`row-${gi}-${ri}`}>
                          <td className="px-4 py-2 pl-10 text-text-secondary">
                            {row.workTypeName ?? "—"}
                          </td>
                          <td className="px-4 py-2 text-text-secondary">{row.unit ?? "—"}</td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {row.qty}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {row.scRate}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {formatCurrency(row.scExpense, currency)}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {row.boqRate}
                          </td>
                          <td className="px-4 py-2 text-right font-mono text-text-secondary">
                            {formatCurrency(row.scImputedIncome, currency)}
                          </td>
                          <td
                            className={`px-4 py-2 text-right font-mono ${
                              row.scMargin > 0
                                ? "text-emerald-600"
                                : row.scMargin < 0
                                  ? "text-rose-600"
                                  : "text-text-secondary"
                            }`}
                          >
                            {formatCurrency(row.scMargin, currency)}
                          </td>
                        </tr>
                      ))}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )
      ) : null}
    </section>
  );
}
