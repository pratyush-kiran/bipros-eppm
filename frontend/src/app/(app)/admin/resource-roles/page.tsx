"use client";

import { VirtualDataTable, type ColumnDef } from "@/components/common/VirtualDataTable";

import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search, Pencil, Trash2, Settings2 } from "lucide-react";
import Link from "next/link";
import {
  resourceRoleApi,
  type ResourceRole,
  type ResourceRoleRequest,
} from "@/lib/api/resourceRoleApi";
import { resourceTypeApi } from "@/lib/api/resourceTypeApi";
import { TabTip } from "@/components/common/TabTip";
import { Badge } from "@/components/ui/badge";
import { getErrorMessage } from "@/lib/utils/error";
import { RoleWithVariantsEditor } from "@/components/role/RoleWithVariantsEditor";

type TypeFilter = "ALL" | "MANPOWER" | "EQUIPMENT" | "MATERIAL";

interface RoleForm {
  code: string;
  name: string;
  description: string;
  resourceTypeId: string;
  sortOrder: string;
  active: boolean;
}

const initialRoleForm = (): RoleForm => ({
  code: "",
  name: "",
  description: "",
  resourceTypeId: "",
  sortOrder: "",
  active: true,
});

const formFromRole = (r: ResourceRole): RoleForm => ({
  code: r.code,
  name: r.name,
  description: r.description ?? "",
  resourceTypeId: r.resourceTypeId,
  sortOrder: r.sortOrder == null ? "" : String(r.sortOrder),
  active: r.active,
});

const toIntOrNull = (value: string): number | null => {
  if (value.trim() === "") return null;
  const parsed = parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : null;
};

function typeBadgeVariant(typeCode: string): import("@/components/ui/badge").BadgeVariant {
  switch (typeCode) {
    case "MANPOWER":
    case "LABOR":
      return "gold";
    case "MATERIAL":
      return "info";
    case "EQUIPMENT":
    case "NONLABOR":
      return "success";
    default:
      return "neutral";
  }
}

export default function ResourceRolesPage() {
  const queryClient = useQueryClient();

  const [typeFilter, setTypeFilter] = useState<TypeFilter>("ALL");
  const [searchQuery, setSearchQuery] = useState("");

  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<RoleForm>(initialRoleForm());
  const [error, setError] = useState<string | null>(null);

  const { data: typesData } = useQuery({
    queryKey: ["resource-types"],
    queryFn: () => resourceTypeApi.list(),
  });
  const types = useMemo(() => typesData?.data ?? [], [typesData]);

  // Build a quick lookup of code → id for the tab filter
  const typeIdByCode = useMemo(() => {
    const m = new Map<string, string>();
    for (const t of types) m.set(t.code, t.id);
    return m;
  }, [types]);

  const {
    data: rolesData,
    isLoading: rolesLoading,
    isError: rolesError,
    error: rolesQueryError,
    refetch: refetchRoles,
    isFetching: rolesFetching,
  } = useQuery({
    queryKey: ["resource-roles"],
    queryFn: () => resourceRoleApi.list(),
  });

  const roles = useMemo(() => rolesData?.data ?? [], [rolesData]);

  const filteredRoles = useMemo(() => {
    let list = roles;
    if (typeFilter !== "ALL") {
      // The "Manpower" tab uses code "MANPOWER" but the seeded type code is "LABOR".
      // Accept both so the filter actually matches.
      list = list.filter((r) => {
        if (typeFilter === "MANPOWER") {
          return r.resourceTypeCode === "MANPOWER" || r.resourceTypeCode === "LABOR";
        }
        return r.resourceTypeCode === typeFilter;
      });
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      list = list.filter(
        (r) =>
          r.code.toLowerCase().includes(q) ||
          r.name.toLowerCase().includes(q) ||
          (r.resourceTypeName ?? "").toLowerCase().includes(q),
      );
    }
    return list;
  }, [roles, typeFilter, typeIdByCode, searchQuery]);

  const defaultTypeId = useMemo(() => {
    if (types.length === 0) return "";
    return (types.find((t) => t.code === "MANPOWER") ?? types[0]).id;
  }, [types]);

  const openCreate = () => {
    setEditingId(null);
    const init = initialRoleForm();
    init.resourceTypeId = defaultTypeId;
    setForm(init);
    setError(null);
    setShowForm(true);
  };

  const openEdit = (role: ResourceRole) => {
    setEditingId(role.id);
    setForm(formFromRole(role));
    setError(null);
    setShowForm(true);
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingId(null);
    setForm(initialRoleForm());
    setError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!form.resourceTypeId) {
      setError("Pick a Resource Type");
      return;
    }
    try {
      const payload: ResourceRoleRequest = {
        code: form.code.trim(),
        name: form.name.trim(),
        description: form.description.trim() || null,
        resourceTypeId: form.resourceTypeId,
        sortOrder: toIntOrNull(form.sortOrder),
        active: form.active,
      };
      if (editingId) {
        await resourceRoleApi.update(editingId, payload);
      } else {
        await resourceRoleApi.create(payload);
      }
      closeForm();
      queryClient.invalidateQueries({ queryKey: ["resource-roles"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to save role"));
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this role? Resources assigned to it must be reassigned first.")) return;
    try {
      await resourceRoleApi.delete(id);
      if (editingId === id) closeForm();
      queryClient.invalidateQueries({ queryKey: ["resource-roles"] });
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete role"));
    }
  };

  const tabs: { key: TypeFilter; label: string }[] = [
    { key: "ALL", label: "All" },
    { key: "MANPOWER", label: "Manpower" },
    { key: "EQUIPMENT", label: "Equipment" },
    { key: "MATERIAL", label: "Material" },
  ];

  const columns = useMemo<ColumnDef<ResourceRole>[]>(() => [
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
        <div>
          <Link
            href={`/admin/resource-roles/${row.original.id}`}
            className="font-semibold text-charcoal hover:text-gold-deep hover:underline"
            title="Open role and configure rates"
          >
            {row.original.name}
          </Link>
          {row.original.description && (
            <div className="text-xs text-slate mt-0.5">{row.original.description}</div>
          )}
        </div>
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
          <Link
            href={`/admin/resource-roles/${row.original.id}`}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-gold-deep"
            aria-label="Configure rates"
            title="Configure rates"
          >
            <Settings2 size={14} strokeWidth={1.5} />
          </Link>
          <button
            onClick={() => openEdit(row.original)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-gold-deep"
            aria-label="Edit"
            title="Edit"
          >
            <Pencil size={14} strokeWidth={1.5} />
          </button>
          <button
            onClick={() => handleDelete(row.original.id)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-burgundy"
            aria-label="Delete"
            title="Delete"
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
        title="Resource Roles"
        description="Roles within each Resource Type. Used as the unit of demand on activities (e.g. Carpenter, Excavator-Op, Cement)."
      />

      {/* Page header */}
      <div className="mb-8 flex items-start justify-between gap-6">
        <div>
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-1.5">
            {roles.length} role{roles.length !== 1 ? "s" : ""}
          </div>
          <h1
            className="font-display text-[38px] font-semibold leading-[1.08] tracking-tight text-charcoal"
            style={{ fontVariationSettings: "'opsz' 144" }}
          >
            Resource Roles
          </h1>
          <p className="mt-2 max-w-[560px] text-sm text-slate leading-relaxed">
            Define manpower, equipment and material roles. Pure metadata — rates live on the
            individual Resource (Default Rate) because they vary by experience, skill, and project.
          </p>
        </div>
        <button
          onClick={() => (showForm ? closeForm() : openCreate())}
          className="inline-flex h-10 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep hover:shadow-[0_4px_14px_rgba(212,175,55,0.3)] hover:-translate-y-px"
        >
          <Plus size={14} strokeWidth={2.5} />
          {showForm ? "Cancel" : "Add Role"}
        </button>
      </div>

      {/* Tabs */}
      <div className="mb-5 flex flex-wrap items-center gap-2">
        {tabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setTypeFilter(t.key)}
            className={`rounded-[10px] px-3.5 py-1.5 text-sm font-medium transition-colors ${
              typeFilter === t.key
                ? "bg-gold text-paper shadow-[0_4px_14px_rgba(212,175,55,0.3)]"
                : "border border-hairline bg-paper text-charcoal hover:bg-ivory"
            }`}
          >
            {t.label}
          </button>
        ))}
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

      {showForm && (
        <>
          {/* Backdrop */}
          <div
            className="fixed inset-0 z-30 bg-black/50"
            onClick={closeForm}
            aria-hidden="true"
          />
          {/* Right-side drawer — matches ActivityDetailDrawer width */}
          <aside
            className="fixed right-0 top-0 z-40 flex h-screen w-full flex-col border-l border-border bg-paper shadow-xl md:w-[720px] lg:w-[880px]"
            role="dialog"
            aria-modal="true"
            aria-label={editingId ? "Edit role" : "New role"}
          >
            <header className="flex items-center justify-between border-b border-hairline px-5 py-3">
              <h2 className="text-base font-semibold">
                {editingId ? "Edit Role" : "New Role"}
              </h2>
              <button
                type="button"
                onClick={closeForm}
                aria-label="Close"
                className="rounded-md p-1 text-slate hover:bg-ivory hover:text-charcoal"
              >
                ✕
              </button>
            </header>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              <RoleWithVariantsEditor
                editingRoleId={editingId}
                onSaved={() => {
                  closeForm();
                  queryClient.invalidateQueries({ queryKey: ["resource-roles"] });
                }}
                onCancel={closeForm}
              />
            </div>
          </aside>
        </>
      )}

      {rolesError && (() => {
        const msg = getErrorMessage(rolesQueryError, "Failed to load roles");
        const isNetwork = msg === "Network Error";
        return (
          <div className="mb-4 rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm">
            <div className="font-medium text-burgundy">
              {isNetwork ? "Couldn't reach the API" : "Failed to load roles"}
            </div>
            <div className="text-slate mt-1">
              {isNetwork
                ? "The browser couldn't reach the backend. Click Retry, or refresh the page."
                : msg}
            </div>
            <button
              type="button"
              onClick={() => refetchRoles()}
              disabled={rolesFetching}
              className="mt-3 inline-flex h-8 items-center gap-1.5 rounded-[10px] bg-gold px-3 text-xs font-semibold text-paper hover:bg-gold-deep disabled:opacity-50"
            >
              {rolesFetching ? "Retrying…" : "Retry"}
            </button>
          </div>
        );
      })()}

      {rolesLoading && (
        <div className="space-y-3">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="h-14 animate-pulse rounded-lg bg-parchment" />
          ))}
        </div>
      )}

      {!rolesLoading && filteredRoles.length === 0 && (
        <div className="rounded-xl border border-dashed border-hairline bg-paper py-12 text-center">
          <p className="text-sm text-slate">
            {roles.length === 0
              ? "No roles yet. Add your first role to get started."
              : "No roles match your filters."}
          </p>
        </div>
      )}

      {!rolesLoading && filteredRoles.length > 0 && (
        <VirtualDataTable columns={columns} data={filteredRoles} sortable resizable searchable={false} />
      )}
    </div>
  );
}

