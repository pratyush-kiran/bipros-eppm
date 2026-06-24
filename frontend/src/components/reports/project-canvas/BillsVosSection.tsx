"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { KpiTile } from "@/components/common/KpiTile";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";
import {
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  truncate,
} from "@/components/common/dashboard/primitives";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";

export function BillsVosSection({ projectId }: { projectId: string }) {
  // The project-canvas can render on the portfolio reports page (outside a
  // project route), so fall back to INR when no project currency is in context.
  const currency = useProjectCurrencyOptional();
  const moneyCompact = (v: number | null | undefined) =>
    currency ? currency.moneyCompact(v) : formatMoney(v, { code: "INR" }, { compact: true });

  const billsQuery = useQuery({
    queryKey: ["project-ra-bill-summary", projectId],
    queryFn: () => projectInsightsApi.getRaBillSummary(projectId),
    staleTime: 60_000,
  });
  const vosQuery = useQuery({
    queryKey: ["project-variation-orders", projectId],
    queryFn: () => projectInsightsApi.getVariationOrders(projectId),
    staleTime: 60_000,
  });

  const billColumns = useMemo<
    ColumnDef<NonNullable<typeof billsQuery.data>["bills"][number]>[]
  >(
    () => [
      {
        accessorKey: "billNumber",
        header: "Bill #",
        cell: (info) => (
          <span className="font-mono text-text-primary">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "billPeriodFrom",
        header: "Period",
        cell: (info) => {
          const row = info.row.original;
          return (
            <span className="text-text-secondary">
              {row.billPeriodFrom ?? "—"} → {row.billPeriodTo ?? "—"}
            </span>
          );
        },
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: (info) => (
          <span className="rounded-full bg-surface-hover px-2 py-0.5 text-[10px] font-semibold text-text-secondary">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "grossAmount",
        header: "Gross",
        cell: (info) => (
          <span className="block text-right font-mono">
            {moneyCompact(info.getValue() as number)}
          </span>
        ),
      },
      {
        accessorKey: "netAmount",
        header: "Net",
        cell: (info) => (
          <span className="block text-right font-mono">
            {moneyCompact(info.getValue() as number)}
          </span>
        ),
      },
      {
        accessorKey: "submittedDate",
        header: "Submitted",
        cell: (info) => <span>{(info.getValue() as string) ?? "—"}</span>,
      },
      {
        accessorKey: "paidDate",
        header: "Paid",
        cell: (info) => <span>{(info.getValue() as string) ?? "—"}</span>,
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [currency]
  );

  const voColumns = useMemo<ColumnDef<NonNullable<typeof vosQuery.data>[number]>[]>(
    () => [
      {
        accessorKey: "voNumber",
        header: "VO #",
        cell: (info) => (
          <span className="font-mono text-text-primary">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "description",
        header: "Description",
        cell: (info) => (
          <span className="text-text-primary">
            {truncate(info.getValue() as string, 90)}
          </span>
        ),
      },
      {
        accessorKey: "costImpactCrores",
        header: "Cost impact",
        cell: (info) => {
          const v = info.getValue() as number;
          return (
            <span
              className={`block text-right font-mono ${
                v < 0 ? "text-success" : "text-danger"
              }`}
            >
              {moneyCompact(v * 1e7)}
            </span>
          );
        },
      },
      {
        accessorKey: "timeImpactDays",
        header: "Time (d)",
        cell: (info) => (
          <span className="block text-right font-mono">
            {(info.getValue() as number | null) ?? "—"}
          </span>
        ),
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: (info) => (
          <span className="rounded-full bg-surface-hover px-2 py-0.5 text-[10px] font-semibold text-text-secondary">
            {info.getValue() as string}
          </span>
        ),
      },
      {
        accessorKey: "approvedDate",
        header: "Approved",
        cell: (info) => <span>{(info.getValue() as string) ?? "—"}</span>,
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [currency]
  );

  if (billsQuery.isLoading || vosQuery.isLoading)
    return (
      <SectionCard title="Bills & Variation Orders">
        <LoadingBlock />
      </SectionCard>
    );

  const bills = billsQuery.data;
  const vos = vosQuery.data ?? [];

  const hasBills =
    bills &&
    (bills.totalSubmittedCrores > 0 ||
      bills.pendingApprovalCrores > 0 ||
      bills.approvedCrores > 0 ||
      bills.paidCrores > 0 ||
      (bills.bills?.length ?? 0) > 0);
  const hasVos = vos.length > 0;

  if (!hasBills && !hasVos) {
    return (
      <SectionCard title="Bills & Variation Orders">
        <EmptyBlock label="No bills or variation orders recorded" />
      </SectionCard>
    );
  }

  return (
    <SectionCard
      title="Bills & Variation Orders"
      subtitle="RA-bill lifecycle and VO register"
    >
      {hasBills && bills && (
        <>
          <div className="mb-3 grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-6">
            <KpiTile label="Submitted" value={moneyCompact(bills.totalSubmittedCrores * 1e7)} />
            <KpiTile label="Pending approval" value={moneyCompact(bills.pendingApprovalCrores * 1e7)} tone="warning" />
            <KpiTile label="Approved" value={moneyCompact(bills.approvedCrores * 1e7)} tone="accent" />
            <KpiTile label="Paid" value={moneyCompact(bills.paidCrores * 1e7)} tone="success" />
            <KpiTile label="Rejected" value={moneyCompact(bills.rejectedCrores * 1e7)} tone="danger" />
            <KpiTile label="Retention" value={moneyCompact(bills.retentionHeldCrores * 1e7)} />
          </div>

          {bills.bills && bills.bills.length > 0 && (
            <div className="mb-6">
              <h3 className="mb-2 text-sm font-medium text-text-secondary">Recent bills</h3>
              <SimpleTable
                columns={billColumns}
                data={bills.bills.slice(0, 20)}
                sortable={false}
              />
            </div>
          )}
        </>
      )}

      <h3 className="mb-2 text-sm font-medium text-text-secondary">Variation orders</h3>
      {!hasVos ? (
        <EmptyBlock label="No variation orders" />
      ) : (
        <SimpleTable columns={voColumns} data={vos} sortable={false} />
      )}
    </SectionCard>
  );
}
