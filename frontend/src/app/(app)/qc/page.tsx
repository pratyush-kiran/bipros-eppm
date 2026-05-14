"use client";

import { useState, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import Link from "next/link";
import { projectApi } from "@/lib/api/projectApi";
import { formatDate, getPriorityInfo } from "@/lib/utils/format";
import { Badge } from "@/components/ui/badge";
import { VirtualDataTable, type ColumnDef } from "@/components/common/VirtualDataTable";

const STATUS_OPTIONS = ["All", "PLANNED", "ACTIVE", "INACTIVE", "COMPLETED"] as const;
const PRIORITY_OPTIONS = ["All", "CRITICAL", "HIGH", "MEDIUM", "LOW"] as const;

function statusVariant(status: string) {
  switch (status) {
    case "ACTIVE": return "success" as const;
    case "PLANNED": return "gold" as const;
    case "COMPLETED": return "info" as const;
    case "INACTIVE": return "warning" as const;
    default: return "neutral" as const;
  }
}

export default function QcProjectPicker() {
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("All");
  const [priorityFilter, setPriorityFilter] = useState<string>("All");

  const { data, isLoading, error } = useQuery({
    queryKey: ["projects-qc-picker"],
    queryFn: () => projectApi.listProjects(0, 200),
  });

  const allProjects = data?.data?.content ?? [];

  const projects = useMemo(() => {
    let out = allProjects;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      out = out.filter(
        (p) => p.code.toLowerCase().includes(q) || p.name.toLowerCase().includes(q)
      );
    }
    if (statusFilter !== "All") out = out.filter((p) => p.status === statusFilter);
    if (priorityFilter !== "All") {
      out = out.filter(
        (p) => (p.priority ?? "").toString().toUpperCase() === priorityFilter
      );
    }
    return out;
  }, [allProjects, searchQuery, statusFilter, priorityFilter]);

  const counts = useMemo(() => ({
    active: allProjects.filter((p) => p.status === "ACTIVE").length,
    planned: allProjects.filter((p) => p.status === "PLANNED").length,
    completed: allProjects.filter((p) => p.status === "COMPLETED").length,
  }), [allProjects]);

  const columns = useMemo<ColumnDef<(typeof allProjects)[number]>[]>(() => [
    {
      accessorKey: "code",
      header: "Code",
      cell: ({ row }) => (
        <Link
          href={`/qc/${row.original.id}`}
          className="font-mono text-[12px] font-medium text-gold-deep hover:text-gold-ink"
        >
          {row.original.code}
        </Link>
      ),
    },
    {
      accessorKey: "name",
      header: "Name",
      cell: ({ row }) => (
        <Link
          href={`/qc/${row.original.id}`}
          className="font-semibold text-charcoal hover:text-gold-ink hover:underline underline-offset-2"
        >
          {row.original.name}
        </Link>
      ),
    },
    {
      accessorKey: "status",
      header: "Status",
      cell: ({ row }) => (
        <Badge variant={statusVariant(row.original.status)} withDot>
          {row.original.status}
        </Badge>
      ),
    },
    {
      accessorKey: "plannedStartDate",
      header: "Start",
      cell: ({ row }) => (
        <span className="text-slate">{formatDate(row.original.plannedStartDate)}</span>
      ),
    },
    {
      accessorKey: "plannedFinishDate",
      header: "Finish",
      cell: ({ row }) => (
        <span className="text-slate">{formatDate(row.original.plannedFinishDate)}</span>
      ),
    },
    {
      accessorKey: "priority",
      header: "Priority",
      cell: ({ row }) => {
        const priority = getPriorityInfo(row.original.priority);
        return <span className={`font-semibold ${priority.color}`}>{priority.label}</span>;
      },
    },
  ], []);

  return (
    <div>
      <div className="mb-8 flex items-start justify-between gap-6">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            {counts.active} active · {counts.planned} planned · {counts.completed} completed
          </div>
          <h1
            className="font-display text-[38px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Quality Control
          </h1>
          <p className="mt-2 max-w-[560px] text-sm text-slate leading-relaxed">
            Select a project to view test records, test types, and the QC dashboard.
          </p>
        </div>
      </div>

      {/* Toolbar */}
      <div className="mb-5 flex flex-wrap items-center gap-2.5">
        <div className="flex h-10 flex-1 max-w-[340px] items-center gap-2 rounded-[10px] border border-hairline bg-paper px-3">
          <Search size={15} className="text-ash" strokeWidth={1.5} />
          <input
            type="text"
            placeholder="Search by code or name…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="flex-1 border-none bg-transparent text-sm text-charcoal placeholder:text-ash outline-none"
          />
        </div>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className="h-10 rounded-[10px] border border-hairline bg-paper pl-3.5 pr-8 text-sm font-medium text-charcoal focus:border-gold focus:outline-none focus:shadow-[0_0_0_3px_rgba(212,175,55,0.18)]"
        >
          {STATUS_OPTIONS.map((s) => (
            <option key={s} value={s}>{s === "All" ? "All statuses" : s}</option>
          ))}
        </select>
        <select
          value={priorityFilter}
          onChange={(e) => setPriorityFilter(e.target.value)}
          className="h-10 rounded-[10px] border border-hairline bg-paper pl-3.5 pr-8 text-sm font-medium text-charcoal focus:border-gold focus:outline-none focus:shadow-[0_0_0_3px_rgba(212,175,55,0.18)]"
        >
          {PRIORITY_OPTIONS.map((p) => (
            <option key={p} value={p}>
              {p === "All" ? "All priorities" : p.charAt(0) + p.slice(1).toLowerCase()}
            </option>
          ))}
        </select>
      </div>

      {/* Active filter chips */}
      {(statusFilter !== "All" || priorityFilter !== "All") && (
        <div className="mb-3 flex items-center gap-1.5">
          {statusFilter !== "All" && (
            <button
              onClick={() => setStatusFilter("All")}
              className="inline-flex items-center gap-1.5 rounded-md border border-gold/40 bg-gold-tint px-2.5 py-1 text-[11px] font-semibold text-gold-ink hover:bg-gold/20"
            >
              Status: {statusFilter} <span aria-hidden>✕</span>
            </button>
          )}
          {priorityFilter !== "All" && (
            <button
              onClick={() => setPriorityFilter("All")}
              className="inline-flex items-center gap-1.5 rounded-md border border-gold/40 bg-gold-tint px-2.5 py-1 text-[11px] font-semibold text-gold-ink hover:bg-gold/20"
            >
              Priority: {priorityFilter} <span aria-hidden>✕</span>
            </button>
          )}
          <button
            onClick={() => { setStatusFilter("All"); setPriorityFilter("All"); }}
            className="ml-1 text-[11px] font-semibold text-gold-deep hover:text-gold-ink"
          >
            Clear all
          </button>
        </div>
      )}

      {isLoading && (
        <div className="space-y-3">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-14 animate-pulse rounded-lg bg-parchment" />
          ))}
        </div>
      )}

      {error && (
        <div className="rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm text-burgundy">
          Failed to load projects. Is the backend running?
        </div>
      )}

      {!isLoading && allProjects.length === 0 && (
        <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
          <p className="text-sm text-slate">No projects found.</p>
        </div>
      )}

      {!isLoading && allProjects.length > 0 && projects.length === 0 && (
        <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
          <p className="text-sm text-slate">No projects match your filters.</p>
        </div>
      )}

      {projects.length > 0 && (
        <>
          <VirtualDataTable columns={columns} data={projects} sortable resizable searchable={false} />
          <div className="pt-3 text-center text-xs text-slate">
            Showing <span className="font-semibold text-charcoal">{projects.length} of {allProjects.length}</span>
          </div>
        </>
      )}
    </div>
  );
}
