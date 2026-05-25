"use client";

import { useMemo } from "react";
import { Package } from "lucide-react";
import type { ColumnDef } from "@tanstack/react-table";
import { format } from "date-fns";
import {
  EmptyBlock,
  SectionCard,
} from "@/components/common/dashboard/primitives";
import { SimpleTable } from "@/components/common/SimpleTable";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { ActivityStatusRow } from "@/lib/api/projectInsightsApi";
import { mapWorkPackageStatus } from "./dashboardDerivations";

interface WorkPackageRow {
  id: string;
  code: string;
  name: string;
  contractor: string;
  pctComplete: number;
  status: string;
  plannedFinish: string | null;
}

function toRow(r: ActivityStatusRow): WorkPackageRow {
  return {
    id: r.activityId,
    code: r.wbsCode || r.code,
    name: r.name,
    contractor: "—",
    pctComplete: r.pctComplete,
    status: mapWorkPackageStatus(r),
    plannedFinish: r.plannedFinish,
  };
}

function formatDate(s: string | null): string {
  if (!s) return "—";
  try {
    return format(new Date(s), "d MMM");
  } catch {
    return s;
  }
}

interface WorkPackageTableProps {
  activities: ActivityStatusRow[];
  maxRows?: number;
}

export function WorkPackageTable({
  activities,
  maxRows = 10,
}: WorkPackageTableProps) {
  const rows = useMemo<WorkPackageRow[]>(() => {
    const sorted = [...activities].sort((a, b) => {
      if (a.plannedFinish && b.plannedFinish) {
        return a.plannedFinish.localeCompare(b.plannedFinish);
      }
      if (a.plannedFinish) return -1;
      if (b.plannedFinish) return 1;
      return a.code.localeCompare(b.code);
    });
    return sorted.slice(0, maxRows).map(toRow);
  }, [activities, maxRows]);

  const columns = useMemo<ColumnDef<WorkPackageRow, unknown>[]>(
    () => [
      {
        id: "code",
        header: "#",
        accessorKey: "code",
        cell: ({ row }) => (
          <span className="font-mono text-[12px] font-semibold tracking-tight text-slate">
            {row.original.code}
          </span>
        ),
      },
      {
        id: "name",
        header: "Package",
        accessorKey: "name",
        cell: ({ row }) => (
          <span className="font-medium text-charcoal">{row.original.name}</span>
        ),
      },
      {
        id: "contractor",
        header: "Contractor",
        accessorKey: "contractor",
        cell: ({ row }) => (
          <span className="text-slate">{row.original.contractor}</span>
        ),
      },
      {
        id: "pctComplete",
        header: "Prog %",
        accessorKey: "pctComplete",
        cell: ({ row }) => {
          const pct = Math.max(0, Math.min(100, row.original.pctComplete));
          return (
            <div className="flex w-[120px] items-center gap-2">
              <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-ivory">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-gold to-gold-deep"
                  style={{ width: `${pct}%` }}
                />
              </div>
              <span className="w-9 text-right text-[11px] font-semibold tabular-nums text-charcoal">
                {pct.toFixed(0)}%
              </span>
            </div>
          );
        },
      },
      {
        id: "status",
        header: "Status",
        accessorKey: "status",
        cell: ({ row }) => (
          <StatusBadge status={row.original.status} variant="compact" />
        ),
      },
      {
        id: "plannedFinish",
        header: "Due",
        accessorKey: "plannedFinish",
        cell: ({ row }) => (
          <span className="text-slate">
            {formatDate(row.original.plannedFinish)}
          </span>
        ),
      },
    ],
    [],
  );

  return (
    <SectionCard
      title="Work Package Status"
      subtitle={`${rows.length} of ${activities.length} packages`}
      icon={<Package size={16} />}
      accent
    >
      {rows.length === 0 ? (
        <EmptyBlock label="No work packages scheduled yet" />
      ) : (
        <SimpleTable data={rows} columns={columns} sortable={false} />
      )}
    </SectionCard>
  );
}
