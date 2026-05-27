"use client";

import { VirtualDataTable, type ColumnDef } from "@/components/common/VirtualDataTable";

import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search, Pencil, Trash2 } from "lucide-react";
import {
  manpowerCategoryMasterApi,
  type ManpowerCategoryMaster,
  type ManpowerCategoryMasterRequest,
} from "@/lib/api/manpowerCategoryMasterApi";
import { TabTip } from "@/components/common/TabTip";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { getErrorMessage } from "@/lib/utils/error";

interface RowForm {
  code: string;
  name: string;
  description: string;
  parentId: string;
  sortOrder: string;
  active: boolean;
}

const initialForm = (): RowForm => ({
  code: "",
  name: "",
  description: "",
  parentId: "",
  sortOrder: "",
  active: true,
});

const formFromRow = (r: ManpowerCategoryMaster): RowForm => ({
  code: r.code,
  name: r.name,
  description: r.description ?? "",
  parentId: r.parentId ?? "",
  sortOrder: r.sortOrder == null ? "" : String(r.sortOrder),
  active: r.active,
});

const toIntOrNull = (value: string): number | null => {
  if (value.trim() === "") return null;
  const parsed = parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : null;
};

export default function ManpowerCategoriesPage() {
  const queryClient = useQueryClient();
  const [searchQuery, setSearchQuery] = useState("");
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<RowForm>(initialForm());
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading, isError, error: queryError, refetch, isFetching } = useQuery({
    queryKey: ["manpower-categories"],
    queryFn: () => manpowerCategoryMasterApi.list(),
  });
  const rows = useMemo(() => data?.data ?? [], [data]);

  // Top-level categories — used as the "Parent Category" picker options.
  const topLevel = useMemo(
    () => rows.filter((r) => r.parentId == null),
    [rows],
  );

  const filtered = useMemo(() => {
    if (!searchQuery.trim()) return rows;
    const q = searchQuery.toLowerCase();
    return rows.filter(
      (r) =>
        r.code.toLowerCase().includes(q) ||
        r.name.toLowerCase().includes(q) ||
        (r.parentName ?? "").toLowerCase().includes(q),
    );
  }, [rows, searchQuery]);

  const openCreate = () => {
    setEditingId(null);
    setForm(initialForm());
    setError(null);
    setShowForm(true);
  };
  const openEdit = (row: ManpowerCategoryMaster) => {
    setEditingId(row.id);
    setForm(formFromRow(row));
    setError(null);
    setShowForm(true);
  };
  const closeForm = () => {
    setShowForm(false);
    setEditingId(null);
    setForm(initialForm());
    setError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const payload: ManpowerCategoryMasterRequest = {
        code: form.code.trim(),
        name: form.name.trim(),
        description: form.description.trim() || null,
        parentId: form.parentId || null,
        sortOrder: toIntOrNull(form.sortOrder),
        active: form.active,
      };
      if (editingId) await manpowerCategoryMasterApi.update(editingId, payload);
      else await manpowerCategoryMasterApi.create(payload);
      closeForm();
      queryClient.invalidateQueries({ queryKey: ["manpower-categories"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to save category"));
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this category? Sub-categories and resources using it must be reassigned first.")) return;
    try {
      await manpowerCategoryMasterApi.delete(id);
      if (editingId === id) closeForm();
      queryClient.invalidateQueries({ queryKey: ["manpower-categories"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete category"));
    }
  };

  const parentOptions = useMemo(() => {
    // When editing, exclude self (can't be its own parent) and any direct child as parent —
    // simple guard; the backend has a stricter check.
    return topLevel
      .filter((r) => r.id !== editingId)
      .map((r) => ({ value: r.id, label: r.name }));
  }, [topLevel, editingId]);

  const columns = useMemo<ColumnDef<ManpowerCategoryMaster>[]>(() => [
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
        <span className="font-semibold text-charcoal">
          {row.original.parentName && <span className="mr-1 text-slate">—</span>}
          {row.original.name}
        </span>
      ),
    },
    {
      accessorKey: "parentName",
      header: "Parent",
      cell: ({ row }) => (
        <span className="text-slate">{row.original.parentName ?? "—"}</span>
      ),
    },
    {
      accessorKey: "description",
      header: "Description",
      cell: ({ row }) => (
        <span className="text-slate">{row.original.description ?? "—"}</span>
      ),
    },
    {
      accessorKey: "sortOrder",
      header: "Sort",
      cell: ({ row }) => (
        <span className="text-right text-slate block">{row.original.sortOrder ?? "—"}</span>
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
  ], [openEdit, handleDelete]);

  return (
    <div>
      <TabTip
        title="Manpower Categories"
        description="Top-level categories (Skilled, Unskilled, Staff) and their sub-categories (Mason, Helper, Site Engineer, etc.). Sub-Category dropdown on the Manpower form filters by selected Category."
      />

      <div className="mb-8 flex items-start justify-between gap-6">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            {rows.length} categor{rows.length !== 1 ? "ies" : "y"}
          </div>
          <h1
            className="font-display text-[38px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Manpower Categories
          </h1>
          <p className="mt-2 max-w-[640px] text-sm text-slate leading-relaxed">
            Leave Parent Category blank to create a top-level Category. Pick a parent to create a
            Sub-Category under it. The Manpower form filters Sub-Category options by the
            selected Category.
          </p>
        </div>
        <button
          onClick={() => (showForm ? closeForm() : openCreate())}
          className="inline-flex h-10 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(0,88,202,0.3)] hover:-translate-y-px"
        >
          <Plus size={14} strokeWidth={2.5} />
          {showForm ? "Cancel" : "Add Category"}
        </button>
      </div>

      <div className="mb-5 flex items-center">
        <div className="ml-auto flex h-10 max-w-[340px] flex-1 items-center gap-2 rounded-[10px] border border-hairline bg-paper px-3">
          <Search size={15} className="text-ash" strokeWidth={1.5} />
          <input
            type="text"
            placeholder="Search by code, name or parent…"
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

      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="mb-6 rounded-xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]"
        >
          <h2 className="text-lg font-semibold text-charcoal mb-4">
            {editingId ? "Edit Category" : "New Category"}
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <FormField label="Code *">
              <input
                type="text"
                value={form.code}
                onChange={(e) => setForm({ ...form, code: e.target.value })}
                className={inputCls}
                required
              />
            </FormField>
            <FormField label="Name *">
              <input
                type="text"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                className={inputCls}
                required
              />
            </FormField>
            <div className="md:col-span-2">
              <FormField label="Description">
                <input
                  type="text"
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                  className={inputCls}
                />
              </FormField>
            </div>
            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Parent Category
              </label>
              <SearchableSelect
                options={parentOptions}
                value={form.parentId}
                onChange={(v) => setForm({ ...form, parentId: v })}
                placeholder="— top-level (leave blank) —"
              />
              <p className="mt-1 text-xs text-text-muted">
                Leave blank for a top-level Category. Pick a parent to create a Sub-Category.
              </p>
            </div>
            <FormField label="Sort Order">
              <input
                type="number"
                value={form.sortOrder}
                onChange={(e) => setForm({ ...form, sortOrder: e.target.value })}
                className={inputCls}
              />
            </FormField>
            <div className="flex items-end">
              <label className="flex items-center gap-2 text-sm text-text-secondary">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={(e) => setForm({ ...form, active: e.target.checked })}
                />
                Active
              </label>
            </div>
          </div>
          <div className="flex gap-2 mt-4">
            <button
              type="submit"
              className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep"
            >
              {editingId ? "Save Changes" : "Create"}
            </button>
            <button
              type="button"
              onClick={closeForm}
              className="inline-flex h-9 items-center gap-1.5 rounded-[10px] border border-hairline bg-paper px-4 text-sm font-semibold text-slate hover:border-gold hover:text-gold-deep"
            >
              Cancel
            </button>
          </div>
        </form>
      )}

      {isError && (
        <div className="mb-4 rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm">
          <div className="font-medium text-burgundy">Failed to load categories</div>
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
              ? "No categories yet. Add your first one to get started."
              : "No categories match your search."}
          </p>
        </div>
      )}

      {!isLoading && filtered.length > 0 && (
        <VirtualDataTable columns={columns} data={filtered} sortable resizable searchable={false} />
      )}
    </div>
  );
}

const inputCls =
  "w-full rounded-[10px] border border-hairline bg-paper px-3 py-2 text-sm text-charcoal placeholder:text-ash focus:border-gold focus:outline-none focus:shadow-[0_0_0_3px_rgba(0,88,202,0.18)]";

function FormField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-sm font-medium mb-1 text-text-secondary">{label}</label>
      {children}
    </div>
  );
}
