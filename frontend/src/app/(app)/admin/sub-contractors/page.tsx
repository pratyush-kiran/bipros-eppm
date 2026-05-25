"use client";

import { VirtualDataTable, type ColumnDef } from "@/components/common/VirtualDataTable";

import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search, Pencil, Trash2 } from "lucide-react";
import {
  subContractorMasterApi,
  type SubContractorMaster,
} from "@/lib/api/subContractorMasterApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import SubContractorWithMappingsEditor from "@/components/sub-contractor/SubContractorWithMappingsEditor";

export default function SubContractorsPage() {
  const queryClient = useQueryClient();
  const [searchQuery, setSearchQuery] = useState("");
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading, isError, error: queryError, refetch, isFetching } = useQuery({
    queryKey: ["sub-contractors"],
    queryFn: () => subContractorMasterApi.list(),
  });
  const rows = useMemo(() => data?.data ?? [], [data]);

  const filtered = useMemo(() => {
    if (!searchQuery.trim()) return rows;
    const q = searchQuery.toLowerCase();
    return rows.filter(
      (r) =>
        r.code.toLowerCase().includes(q) ||
        r.name.toLowerCase().includes(q) ||
        (r.location ?? "").toLowerCase().includes(q),
    );
  }, [rows, searchQuery]);

  const openCreate = () => {
    setEditingId(null);
    setError(null);
    setShowForm(true);
  };

  const openEdit = (row: SubContractorMaster) => {
    setEditingId(row.id);
    setError(null);
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingId(null);
    setError(null);
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this sub-contractor?")) return;
    try {
      await subContractorMasterApi.delete(id);
      if (editingId === id) closeForm();
      queryClient.invalidateQueries({ queryKey: ["sub-contractors"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete sub-contractor"));
    }
  };

  const columns: ColumnDef<SubContractorMaster>[] = [
    {
      accessorKey: "code",
      header: "Code",
      cell: ({ row }) => (
        <span className="font-mono text-[12px] font-medium text-gold-deep">
          {row.original.code}
        </span>
      ),
    },
    {
      accessorKey: "name",
      header: "Name",
      cell: ({ row }) => (
        <span className="font-semibold text-charcoal">{row.original.name}</span>
      ),
    },
    {
      accessorKey: "location",
      header: "Location",
      cell: ({ row }) => (
        <span className="text-slate">{row.original.location ?? "—"}</span>
      ),
    },
    {
      accessorKey: "primaryContactName",
      header: "Contact Name",
      cell: ({ row }) => (
        <span className="text-slate">{row.original.primaryContactName ?? "—"}</span>
      ),
    },
    {
      accessorKey: "primaryContactNumber",
      header: "Contact Number",
      cell: ({ row }) => (
        <span className="text-slate">{row.original.primaryContactNumber ?? "—"}</span>
      ),
    },
    {
      id: "mappings",
      header: "Activities",
      cell: ({ row }) => (
        <span className="text-xs text-slate">
          {row.original.workActivityMappings?.length ?? 0} mapped
        </span>
      ),
    },
    {
      accessorKey: "active",
      header: "Active",
      cell: ({ row }) =>
        row.original.active ? (
          <span className="text-emerald font-medium text-xs">Active</span>
        ) : (
          <span className="text-slate text-xs">Inactive</span>
        ),
    },
    {
      id: "actions",
      header: "",
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1">
          <button
            onClick={() => openEdit(row.original)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-gold-deep"
            aria-label="Edit"
          >
            <Pencil size={14} strokeWidth={1.5} />
          </button>
          <button
            onClick={() => handleDelete(row.original.id)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-burgundy"
            aria-label="Delete"
          >
            <Trash2 size={14} strokeWidth={1.5} />
          </button>
        </div>
      ),
    },
  ];

  return (
    <div>
      <TabTip
        title="Sub-Contractors"
        description="Master list of sub-contractors with work-activity rate mappings. Used when planning activity resource demand and DPR reporting."
      />

      <div className="mb-8 flex items-start justify-between gap-6">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            {rows.length} sub-contractor{rows.length !== 1 ? "s" : ""}
          </div>
          <h1
            className="font-display text-[38px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Sub-Contractors
          </h1>
          <p className="mt-2 max-w-[560px] text-sm text-slate leading-relaxed">
            Admin-managed directory of sub-contractor organisations with activity rate
            mappings. Referenced by the Activity Resource Demand panel and DPR forms.
          </p>
        </div>
        <button
          onClick={() => (showForm ? closeForm() : openCreate())}
          className="inline-flex h-10 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(212,175,55,0.3)] hover:-translate-y-px"
        >
          <Plus size={14} strokeWidth={2.5} />
          {showForm ? "Cancel" : "Add Sub-Contractor"}
        </button>
      </div>

      <div className="mb-5 flex items-center">
        <div className="ml-auto flex h-10 max-w-[340px] flex-1 items-center gap-2 rounded-[10px] border border-hairline bg-paper px-3">
          <Search size={15} className="text-ash" strokeWidth={1.5} />
          <input
            type="text"
            placeholder="Search by code or name…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="flex-1 border-none bg-transparent text-sm text-charcoal placeholder:text-ash outline-none"
          />
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm text-burgundy">
          {error}
        </div>
      )}

      {/* Drawer */}
      {showForm && (
        <>
          <div
            className="fixed inset-0 z-30 bg-black/50"
            onClick={closeForm}
            aria-hidden="true"
          />
          <aside
            className="fixed right-0 top-0 z-40 flex h-screen w-full flex-col border-l border-hairline bg-paper shadow-xl md:w-[760px] lg:w-[920px]"
            role="dialog"
            aria-modal="true"
          >
            <header className="flex items-center justify-between border-b border-hairline px-5 py-3">
              <h2 className="text-base font-semibold text-charcoal">
                {editingId ? "Edit Sub-Contractor" : "New Sub-Contractor"}
              </h2>
              <button
                onClick={closeForm}
                className="rounded-md p-1 text-slate hover:bg-ivory"
                aria-label="Close"
              >
                ✕
              </button>
            </header>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              <SubContractorWithMappingsEditor
                editingId={editingId}
                onSaved={() => {
                  closeForm();
                  queryClient.invalidateQueries({ queryKey: ["sub-contractors"] });
                }}
                onCancel={closeForm}
              />
            </div>
          </aside>
        </>
      )}

      {isError && (
        <div className="mb-4 rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm">
          <div className="font-medium text-burgundy">Failed to load sub-contractors</div>
          <div className="text-slate mt-1">{getErrorMessage(queryError, "Unknown error")}</div>
          <button
            type="button"
            onClick={() => refetch()}
            disabled={isFetching}
            className="mt-3 inline-flex h-8 items-center gap-1.5 rounded-[10px] bg-gold px-3 text-xs font-semibold text-paper hover:bg-gold-deep disabled:opacity-50"
          >
            {isFetching ? "Retrying…" : "Retry"}
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

      {!isLoading && filtered.length === 0 && (
        <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
          <p className="text-sm text-slate">
            {rows.length === 0
              ? "No sub-contractors yet. Add your first one to get started."
              : "No sub-contractors match your search."}
          </p>
        </div>
      )}

      {!isLoading && filtered.length > 0 && (
        <VirtualDataTable columns={columns} data={filtered} sortable resizable searchable={false} />
      )}
    </div>
  );
}
