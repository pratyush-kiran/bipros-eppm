"use client";

import { useMemo } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { grnApi, materialCatalogueApi } from "@/lib/api/materialCatalogueApi";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import type { GoodsReceiptResponse } from "@/lib/types";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

export default function GrnsPage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const { money } = useProjectCurrency();

  const { data, isLoading } = useQuery({
    queryKey: ["grns", projectId],
    queryFn: () => grnApi.listByProject(projectId),
    enabled: !!projectId,
  });
  const { data: materials } = useQuery({
    queryKey: ["materials", projectId, "ALL"],
    queryFn: () => materialCatalogueApi.listByProject(projectId),
    enabled: !!projectId,
  });

  const matName = (id: string) =>
    materials?.data?.find((m) => m.id === id)?.name ?? id.slice(0, 8);

  const rows = useMemo(() => data?.data ?? [], [data]);

  const columns = useMemo<ColumnDef<GoodsReceiptResponse>[]>(() => [
    { accessorKey: "grnNumber", header: "GRN #", enableSorting: true },
    { accessorKey: "receivedDate", header: "Date" },
    {
      accessorKey: "materialId",
      header: "Material",
      cell: (info) => matName(info.getValue() as string),
    },
    { accessorKey: "quantity", header: "Qty" },
    {
      accessorKey: "unitRate",
      header: "Unit Rate",
      cell: (info) => {
        const v = info.getValue();
        return v == null ? "—" : money(Number(v), { decimals: 0 });
      },
    },
    {
      accessorKey: "amount",
      header: "Amount",
      cell: (info) => {
        const v = info.getValue();
        return v == null ? "—" : money(Number(v), { decimals: 0 });
      },
    },
    { accessorKey: "poNumber", header: "PO #" },
    { accessorKey: "vehicleNumber", header: "Vehicle" },
  ], [materials, money]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Goods Receipt Notes (GRN)"
        description="Inward receipt entries for material stock."
        actions={
          <Link
            href={`/projects/${projectId}/grns/new`}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent/90"
          >
            New GRN
          </Link>
        }
      />

      {isLoading ? (
        <div className="text-text-secondary">Loading…</div>
      ) : rows.length === 0 ? (
        <EmptyState title="No GRNs logged yet" description="Record a GRN when material arrives on site." />
      ) : (
        <VirtualDataTable columns={columns} data={rows} sortable resizable />
      )}
    </div>
  );
}
