"use client";

import Link from "next/link";
import { ArrowUpRight, Receipt } from "lucide-react";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import {
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  formatINR,
} from "@/components/common/dashboard/primitives";
import type { RaBill, RaBillStatus } from "@/lib/api/raBillApi";

const STATUS_VARIANT: Record<RaBillStatus, BadgeVariant> = {
  DRAFT: "neutral",
  SUBMITTED: "warning",
  PMC_REVIEW_PENDING: "warning",
  HOLD_SATELLITE_DISPUTE: "danger",
  REJECTED: "danger",
  CERTIFIED: "info",
  APPROVED: "info",
  PAID: "success",
  PAID_PMC_OVERRIDE: "success",
};

const STATUS_LABEL: Record<RaBillStatus, string> = {
  DRAFT: "DRAFT",
  SUBMITTED: "PENDING",
  PMC_REVIEW_PENDING: "REVIEW",
  HOLD_SATELLITE_DISPUTE: "DISPUTE",
  REJECTED: "REJECTED",
  CERTIFIED: "CERTIFIED",
  APPROVED: "RAISED",
  PAID: "PAID",
  PAID_PMC_OVERRIDE: "PAID",
};

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("en-IN", { month: "short", year: "2-digit" });
}

interface Props {
  bills: RaBill[] | undefined;
  isLoading: boolean;
  projectId: string | null;
}

export function InvoiceSummaryTable({ bills, isLoading, projectId }: Props) {
  const rows = (bills ?? [])
    .slice()
    .sort((a, b) => (b.billPeriodFrom ?? "").localeCompare(a.billPeriodFrom ?? ""))
    .slice(0, 8);

  return (
    <SectionCard
      title="Invoice summary"
      subtitle="Most recent RA bills"
      icon={<Receipt size={16} strokeWidth={1.75} />}
      actions={
        projectId ? (
          <Link
            href={`/projects/${projectId}/ra-bills`}
            className="inline-flex items-center gap-1 text-[11px] font-semibold uppercase tracking-[0.1em] text-gold-deep hover:text-gold-ink"
          >
            View all
            <ArrowUpRight size={12} strokeWidth={2} />
          </Link>
        ) : null
      }
    >
      {isLoading ? (
        <LoadingBlock label="Loading invoices…" />
      ) : rows.length === 0 ? (
        <EmptyBlock label="No invoices for this project yet." />
      ) : (
        <div className="-mx-2 overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="border-b border-hairline text-[10px] font-semibold uppercase tracking-[0.12em] text-slate">
                <th className="px-2 py-2 text-left">Invoice</th>
                <th className="px-2 py-2 text-left">Date</th>
                <th className="px-2 py-2 text-right">Amount</th>
                <th className="px-2 py-2 text-right">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((b) => (
                <tr
                  key={b.id}
                  className="border-b border-hairline/60 last:border-0 hover:bg-ivory/40"
                >
                  <td className="px-2 py-2.5 font-medium text-charcoal">{b.billNumber}</td>
                  <td className="px-2 py-2.5 text-slate">{fmtDate(b.billPeriodFrom)}</td>
                  <td className="px-2 py-2.5 text-right font-display font-semibold text-charcoal">
                    {formatINR(b.grossAmount)}
                  </td>
                  <td className="px-2 py-2.5 text-right">
                    <Badge variant={STATUS_VARIANT[b.status]} withDot>
                      {STATUS_LABEL[b.status]}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </SectionCard>
  );
}
