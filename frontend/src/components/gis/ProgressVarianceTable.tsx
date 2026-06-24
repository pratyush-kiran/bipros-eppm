"use client";

import { useMemo } from "react";
import type { ColumnDef } from "@tanstack/react-table";
import { SimpleTable } from "@/components/common/SimpleTable";
import { ProgressVariance } from "@/lib/api/gisApi";

interface ProgressVarianceTableProps {
  projectId: string;
  variance: ProgressVariance[];
}

function getStatusColor(status: string): string {
  switch (status) {
    case "ON_TRACK":
      return "bg-success/10 text-success";
    case "BEHIND":
      return "bg-danger/10 text-danger";
    case "AHEAD":
      return "bg-info/10 text-info";
    default:
      return "bg-surface-hover text-text-secondary";
  }
}

function getVarianceColor(variance?: number): string {
  if (!variance) return "text-text-secondary";
  if (variance > 10) return "text-danger font-bold";
  if (variance < -5) return "text-success font-bold";
  return "text-text-primary";
}

export function ProgressVarianceTable({
  projectId,
  variance,
}: ProgressVarianceTableProps) {
  const columns = useMemo<ColumnDef<ProgressVariance>[]>(
    () => [
      {
        header: "WBS Code",
        accessorKey: "wbsCode",
        cell: ({ getValue }) => (
          <span className="font-mono text-text-primary">{String(getValue())}</span>
        ),
      },
      {
        header: "WBS Name",
        accessorKey: "wbsName",
        cell: ({ getValue }) => (
          <span className="text-text-secondary">{String(getValue())}</span>
        ),
      },
      {
        header: "Derived %",
        accessorKey: "derivedPercent",
        meta: { align: "center" },
        cell: ({ row }) => {
          const v = row.original.derivedPercent;
          return (
            <span className="text-text-primary">
              {v !== null && v !== undefined ? v.toFixed(1) + "%" : "-"}
            </span>
          );
        },
      },
      {
        header: "Claimed %",
        accessorKey: "claimedPercent",
        meta: { align: "center" },
        cell: ({ row }) => {
          const v = row.original.claimedPercent;
          return (
            <span className="text-text-primary">
              {v !== null && v !== undefined ? v.toFixed(1) + "%" : "-"}
            </span>
          );
        },
      },
      {
        header: "Variance %",
        accessorKey: "variancePercent",
        meta: { align: "center" },
        cell: ({ row }) => {
          const v = row.original.variancePercent;
          return (
            <span className={getVarianceColor(v ?? undefined)}>
              {v !== null && v !== undefined
                ? (v > 0 ? "+" : "") + v.toFixed(1) + "%"
                : "-"}
            </span>
          );
        },
      },
      {
        header: "Status",
        accessorKey: "varianceStatus",
        meta: { align: "center" },
        cell: ({ getValue }) => (
          <span
            className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusColor(String(getValue()))}`}
          >
            {String(getValue()).replace(/_/g, " ")}
          </span>
        ),
      },
    ],
    []
  );

  return (
    <div className="space-y-4">
      <div className="flex justify-between items-center">
        <h3 className="text-lg font-semibold text-text-primary">
          Progress Variance Analysis
        </h3>
        <p className="text-sm text-text-secondary">
          Comparing derived vs claimed progress
        </p>
      </div>

      {variance.length === 0 ? (
        <div className="bg-surface/50 rounded-lg border border-border p-8 text-center">
          <p className="text-text-secondary">No progress data available</p>
        </div>
      ) : (
        <SimpleTable
          data={variance}
          columns={columns}
          sortable={true}
          className="bg-surface/50 rounded-lg border border-border overflow-hidden"
        />
      )}

      {/* Legend */}
      <div className="bg-info/10 border border-info/20 rounded-lg p-4 text-sm text-text-secondary">
        <p className="font-medium mb-2">Understanding Variance:</p>
        <ul className="space-y-1 text-xs">
          <li>
            <span className="font-medium">Derived %:</span> Progress calculated
            from satellite imagery
          </li>
          <li>
            <span className="font-medium">Claimed %:</span> Progress reported by
            contractor
          </li>
          <li>
            <span className="font-medium">Variance:</span> Derived minus Claimed
          </li>
          <li>
            <span className="font-medium text-success">AHEAD:</span> Variance
            &lt; -5% (ahead of contractor claims)
          </li>
          <li>
            <span className="font-medium text-warning">ON_TRACK:</span>{" "}
            Variance between -5% and +10%
          </li>
          <li>
            <span className="font-medium text-danger">BEHIND:</span> Variance
            &gt; +10% (behind contractor claims)
          </li>
        </ul>
      </div>
    </div>
  );
}
