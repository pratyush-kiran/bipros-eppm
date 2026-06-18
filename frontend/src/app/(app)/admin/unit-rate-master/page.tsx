"use client";

import { VirtualDataTable, type ColumnDef } from "@/components/common/VirtualDataTable";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  unitRateMasterApi,
  type UnitRateMasterRow,
  type UnitRateCategoryFilter,
} from "@/lib/api/unitRateMasterApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";

interface CategoryOption {
  label: string;
  value: UnitRateCategoryFilter | undefined;
}

const CATEGORY_OPTIONS: CategoryOption[] = [
  { label: "All", value: undefined },
  { label: "Equipment", value: "EQUIPMENT" },
  { label: "Manpower", value: "MANPOWER" },
  { label: "Material", value: "MATERIAL" },
  { label: "Sub-Contract", value: "SUB_CONTRACT" },
];

function formatCurrency(value: number | null): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString("en-IN");
}

function formatPercent(value: number | null): string {
  if (value === null || value === undefined) return "—";
  return (value * 100).toFixed(2) + "%";
}

function varianceClass(value: number | null): string {
  if (value === null || value === undefined || value === 0) return "";
  return value > 0 ? "text-danger" : "text-success";
}

function categoryBadgeClass(category: string): string {
  switch (category) {
    case "Equipment":
      return "bg-accent/10 text-accent ring-1 ring-accent/20";
    case "Manpower":
      return "bg-success/10 text-success ring-1 ring-success/20";
    case "Material":
      return "bg-warning/10 text-warning ring-1 ring-amber-500/20";
    case "Sub-Contract":
      return "bg-danger/10 text-danger ring-1 ring-red-500/20";
    default:
      return "bg-surface-active/50 text-text-secondary ring-1 ring-border";
  }
}

export default function UnitRateMasterPage() {
  const [category, setCategory] = useState<UnitRateCategoryFilter | undefined>(undefined);

  const { data, isLoading, error } = useQuery({
    queryKey: ["unit-rate-master", category],
    queryFn: () => unitRateMasterApi.list(category),
  });

  const rows: UnitRateMasterRow[] = data?.data ?? [];

  const counts = {
    Equipment: 0,
    Manpower: 0,
    Material: 0,
    "Sub-Contract": 0,
  } as Record<string, number>;
  for (const row of rows) {
    if (row.category in counts) {
      counts[row.category] += 1;
    }
  }

  const columns = useMemo<ColumnDef<UnitRateMasterRow>[]>(() => [
    {
      accessorKey: "category",
      header: "Category",
      cell: ({ row }) => (
        <span className={`px-2 py-1 rounded text-sm ${categoryBadgeClass(row.original.category)}`}>
          {row.original.category}
        </span>
      ),
    },
    {
      accessorKey: "description",
      header: "Description",
      cell: ({ row }) => <span>{row.original.description}</span>,
    },
    {
      accessorKey: "unit",
      header: "Unit",
      cell: ({ row }) => <span>{row.original.unit ?? "—"}</span>,
    },
    {
      accessorKey: "budgetedRate",
      header: "Budgeted Rate",
      cell: ({ row }) => <span className="text-right block">{formatCurrency(row.original.budgetedRate)}</span>,
    },
    {
      accessorKey: "actualRate",
      header: "Actual Rate",
      cell: ({ row }) => <span className="text-right block">{formatCurrency(row.original.actualRate)}</span>,
    },
    {
      accessorKey: "variance",
      header: "Variance",
      cell: ({ row }) => (
        <span className={`text-right block ${varianceClass(row.original.variance)}`}>
          {formatCurrency(row.original.variance)}
        </span>
      ),
    },
    {
      accessorKey: "variancePercent",
      header: "Variance %",
      cell: ({ row }) => (
        <span className={`text-right block ${varianceClass(row.original.variance)}`}>
          {formatPercent(row.original.variancePercent)}
        </span>
      ),
    },
    {
      accessorKey: "remarks",
      header: "Remarks",
      cell: ({ row }) => <span>{row.original.remarks ?? "—"}</span>,
    },
  ], []);

  if (isLoading && rows.length === 0) {
    return <div className="p-6 text-text-muted">Loading unit rate master...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Unit Rate Master"
        description="Authoritative budgeted vs actual unit rates across all resource categories — Section A of the Daily Cost Report."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Unit Rate Master</h1>

        {/* Category filter pills */}
        <div className="flex flex-wrap gap-2 mb-6">
          {CATEGORY_OPTIONS.map((opt) => {
            const isActive = category === opt.value;
            return (
              <button
                key={opt.label}
                onClick={() => setCategory(opt.value)}
                className={`px-4 py-2 rounded-full text-sm transition-colors ${
                  isActive
                    ? "bg-accent text-accent-foreground hover:bg-accent-hover"
                    : "bg-surface-active/50 text-text-secondary hover:bg-border"
                }`}
              >
                {opt.label}
              </button>
            );
          })}
        </div>

        {/* Summary strip */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          <div className="bg-accent/10 p-4 rounded-lg border border-accent/20">
            <p className="text-sm text-text-secondary mb-1">Equipment</p>
            <p className="text-2xl font-bold text-accent">{counts.Equipment}</p>
          </div>
          <div className="bg-success/10 p-4 rounded-lg border border-success/20">
            <p className="text-sm text-text-secondary mb-1">Manpower</p>
            <p className="text-2xl font-bold text-success">{counts.Manpower}</p>
          </div>
          <div className="bg-warning/10 p-4 rounded-lg border border-warning/20">
            <p className="text-sm text-text-secondary mb-1">Material</p>
            <p className="text-2xl font-bold text-warning">{counts.Material}</p>
          </div>
          <div className="bg-danger/10 p-4 rounded-lg border border-danger/20">
            <p className="text-sm text-text-secondary mb-1">Sub-Contract</p>
            <p className="text-2xl font-bold text-danger">{counts["Sub-Contract"]}</p>
          </div>
        </div>

        {error && (
          <div className="text-danger mb-4">
            {getErrorMessage(error, "Failed to load unit rate master")}
          </div>
        )}


        <VirtualDataTable
          columns={columns}
          data={rows}
          sortable
          resizable
          searchable={false}
          emptyMessage={isLoading ? "Loading…" : "No unit rate entries found."}
        />
      </div>
    </div>
  );
}
