"use client";

import { useMemo } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { stockApi } from "@/lib/api/materialCatalogueApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import type { MaterialStockRow, StockStatusTag } from "@/lib/types";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

const TAG_COLORS: Record<StockStatusTag, string> = {
  OK: "bg-success/20 text-success",
  LOW: "bg-amber-500/20 text-warning",
  CRITICAL: "bg-danger/20 text-danger",
};

export default function StockRegisterPage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const { money, symbol } = useProjectCurrency();

  const { data, isLoading } = useQuery({
    queryKey: ["stock-register", projectId],
    queryFn: () => stockApi.listByProject(projectId),
    enabled: !!projectId,
  });

  const rows = useMemo(() => data?.data ?? [], [data]);

  const columns = useMemo<ColumnDef<MaterialStockRow>[]>(() => [
    { accessorKey: "materialCode", header: "Code", enableSorting: true },
    { accessorKey: "materialName", header: "Material" },
    { accessorKey: "openingStock", header: "Opening" },
    { accessorKey: "receivedMonth", header: "Received (Month)" },
    { accessorKey: "issuedMonth", header: "Issued (Month)" },
    {
      accessorKey: "currentStock",
      header: "Current Stock",
      cell: (info) => {
        const row = info.row.original;
        const tag = row.stockStatusTag;
        const colour =
          tag === "CRITICAL"
            ? "text-danger"
            : tag === "LOW"
              ? "text-warning"
              : "text-text-primary";
        return <span className={`font-semibold ${colour}`}>{String(info.getValue() ?? 0)}</span>;
      },
    },
    { accessorKey: "minStockLevel", header: "Min Stock" },
    { accessorKey: "reorderQuantity", header: "Reorder Qty" },
    {
      accessorKey: "stockValue",
      header: `Stock Value (${symbol})`,
      cell: (info) => {
        const v = info.getValue();
        return v == null ? "—" : money(Number(v), { decimals: 0 });
      },
    },
    {
      accessorKey: "stockStatusTag",
      header: "Status",
      cell: (info) => {
        const s = info.getValue() as StockStatusTag | null;
        if (!s) return "—";
        return (
          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${TAG_COLORS[s]}`}>
            {s}
          </span>
        );
      },
    },
    {
      accessorKey: "wastagePercent",
      header: "Wastage %",
      cell: (info) => {
        const v = info.getValue();
        return v == null ? "—" : `${v}%`;
      },
    },
  ], [money, symbol]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Stock & Inventory Register"
        description="Real-time stock balances with automated OK / LOW / CRITICAL status tags."
        actions={
          <div className="flex gap-2">
            <Link
              href={`/projects/${projectId}/grns/new`}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90"
            >
              Log GRN
            </Link>
            <Link
              href={`/projects/${projectId}/issues/new`}
              className="rounded-md border border-border bg-surface-hover px-4 py-2 text-sm font-medium text-text-primary hover:bg-surface"
            >
              Log Issue
            </Link>
          </div>
        }
      />

      {isLoading ? (
        <div className="text-text-secondary">Loading…</div>
      ) : rows.length === 0 ? (
        <EmptyState
          title="No stock transactions yet"
          description="Log a GRN for any material in the catalogue to start tracking stock."
        />
      ) : (
        <VirtualDataTable columns={columns} data={rows} sortable resizable />
      )}
    </div>
  );
}
