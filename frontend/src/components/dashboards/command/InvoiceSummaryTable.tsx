import React from "react";
import { cn } from "@/lib/utils/cn";
import { StatusBadge } from "@/components/ui/status-badge";
import { formatCrore } from "@/components/common/dashboard/primitives";

export interface InvoiceRow {
  id: string;
  number: string;
  amount: number;
  date: string;
  status: string;
}

export interface InvoiceSummaryTableProps {
  rows: InvoiceRow[];
  className?: string;
}

export function InvoiceSummaryTable({ rows, className }: InvoiceSummaryTableProps) {
  if (rows.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-8 text-center text-sm text-text-secondary", className)}>
        No invoices on file.
      </div>
    );
  }
  return (
    <div className={cn("overflow-x-auto", className)}>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-secondary">
            <th className="px-3 py-3 text-left">Invoice</th>
            <th className="px-3 py-3 text-right">Amount</th>
            <th className="px-3 py-3 text-left">Date</th>
            <th className="px-3 py-3 text-left">Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="border-t border-hairline">
              <td className="px-3 py-3 font-semibold text-text-primary tabular-nums">{row.number}</td>
              <td className="px-3 py-3 text-right text-text-primary tabular-nums">
                {formatCrore(row.amount, 1)}
              </td>
              <td className="px-3 py-3 text-text-secondary">
                {new Date(row.date).toLocaleDateString("en-US", { month: "short", year: "2-digit" })}
              </td>
              <td className="px-3 py-3">
                <StatusBadge status={row.status} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
