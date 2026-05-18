"use client";

import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";

import type { DbsSectionLine } from "@/lib/api/dbsApi";
import { formatCurrency } from "@/lib/utils/format";

/**
 * Collapsible accordion card for one DBS section (Material / Manpower / Admin /
 * Machinery / Fuel / Sub-Contractor / BOQ). Header always shows the section total;
 * expanding reveals the underlying lines as a tight, P&L-style table.
 *
 * `comingSoon` is used by the Sub-Contractor card in v1 — backend always returns
 * `subcontractAmount: 0` (the SubcontractorRateMaster work is deferred to v2), so
 * the card renders a "Coming soon" badge instead of an empty table.
 */
export interface SectionCardProps {
  title: string;
  lines: DbsSectionLine[];
  total: number;
  currency?: string | null;
  comingSoon?: boolean;
  /** When true, default-collapsed; when omitted, default-expanded for non-empty cards. */
  defaultCollapsed?: boolean;
}

export function SectionCard({
  title,
  lines,
  total,
  currency,
  comingSoon = false,
  defaultCollapsed,
}: SectionCardProps) {
  const startCollapsed = defaultCollapsed ?? (lines.length === 0 || comingSoon);
  const [collapsed, setCollapsed] = useState(startCollapsed);

  const Caret = collapsed ? ChevronRight : ChevronDown;

  return (
    <section className="rounded-lg border border-border bg-surface/50 shadow-sm">
      <button
        type="button"
        onClick={() => setCollapsed((c) => !c)}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-hover"
        aria-expanded={!collapsed}
      >
        <div className="flex items-center gap-2">
          <Caret size={16} className="text-text-secondary" />
          <h3 className="text-sm font-semibold text-text-primary">{title}</h3>
          {comingSoon ? (
            <span className="rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-600">
              Coming soon (v2)
            </span>
          ) : (
            <span className="text-xs text-text-muted">
              {lines.length} {lines.length === 1 ? "line" : "lines"}
            </span>
          )}
        </div>
        <div className="font-mono text-sm font-semibold text-text-primary">
          {formatCurrency(total, currency)}
        </div>
      </button>

      {!collapsed && !comingSoon ? (
        <div className="border-t border-border">
          {lines.length === 0 ? (
            <div className="px-4 py-6 text-center text-xs text-text-muted">
              No lines recorded for this section on this date.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
                  <tr>
                    <th className="px-4 py-2">Description</th>
                    <th className="px-4 py-2">Unit</th>
                    <th className="px-4 py-2 text-right">Rate</th>
                    <th className="px-4 py-2 text-right">Qty</th>
                    <th className="px-4 py-2 text-right">Total</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {lines.map((line, idx) => (
                    <tr key={`${title}-${idx}-${line.description}`}>
                      <td className="px-4 py-2 text-text-primary">{line.description}</td>
                      <td className="px-4 py-2 text-text-secondary">{line.unit ?? "—"}</td>
                      <td className="px-4 py-2 text-right font-mono text-text-secondary">
                        {line.rate != null ? formatCurrency(line.rate, currency) : "—"}
                      </td>
                      <td className="px-4 py-2 text-right font-mono text-text-secondary">
                        {line.quantity != null ? line.quantity.toLocaleString() : "—"}
                      </td>
                      <td className="px-4 py-2 text-right font-mono font-medium text-text-primary">
                        {formatCurrency(line.totalAmount, currency)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : null}
    </section>
  );
}
