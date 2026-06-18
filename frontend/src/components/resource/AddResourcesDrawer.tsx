"use client";

import { useEffect, useMemo, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  X,
  Search,
  ChevronDown,
  ChevronRight,
  Pencil,
  Check,
  Plus,
} from "lucide-react";
import {
  projectResourceApi,
  type PoolEntryInput,
} from "@/lib/api/projectResourceApi";
import { type ResourceResponse } from "@/lib/api/resourceApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { displayResourceTypeName } from "@/lib/utils/resourceTypeLabel";
import toast from "react-hot-toast";
import { getErrorMessage } from "@/lib/utils/error";

interface Props {
  open: boolean;
  onClose: () => void;
  projectId: string;
}


interface SelectedEntry {
  resource: ResourceResponse;
  rateOverride?: string;
  availabilityOverride?: string;
  notes?: string;
}

export function AddResourcesDrawer({ open, onClose, projectId }: Props) {
  const queryClient = useQueryClient();
  const { money } = useProjectCurrency();
  const [search, setSearch] = useState("");
  const [activeType, setActiveType] = useState<string | null>(null); // resourceTypeName
  const [activeRole, setActiveRole] = useState<string | null>(null); // roleName under activeType
  const [expandedTypes, setExpandedTypes] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<Map<string, SelectedEntry>>(new Map());
  const [editingId, setEditingId] = useState<string | null>(null);

  const resetAndClose = () => {
    setSearch("");
    setActiveType(null);
    setActiveRole(null);
    setSelected(new Map());
    setEditingId(null);
    setExpandedTypes(new Set());
    onClose();
  };

  // Close on Escape (matches AssignSupervisorDrawer pattern).
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") resetAndClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const { data: availableData, isLoading } = useQuery({
    queryKey: ["resource-pool-available-drawer", projectId],
    queryFn: () => projectResourceApi.listAvailable(projectId),
    enabled: open,
  });

  const available = useMemo<ResourceResponse[]>(() => {
    const raw = availableData?.data as unknown;
    return Array.isArray(raw) ? (raw as ResourceResponse[]) : [];
  }, [availableData]);

  // Build the category tree from the available list: type -> role -> resources.
  const tree = useMemo(() => {
    const byType = new Map<string, Map<string, ResourceResponse[]>>();
    for (const r of available) {
      const typeName = r.resourceTypeName ?? "Other";
      const roleName = r.roleName ?? "Other";
      let typeBucket = byType.get(typeName);
      if (!typeBucket) {
        typeBucket = new Map();
        byType.set(typeName, typeBucket);
      }
      const roleBucket = typeBucket.get(roleName) ?? [];
      roleBucket.push(r);
      typeBucket.set(roleName, roleBucket);
    }
    return Array.from(byType.entries())
      .map(([typeName, roles]) => ({
        typeName,
        count: Array.from(roles.values()).reduce((s, arr) => s + arr.length, 0),
        roles: Array.from(roles.entries())
          .map(([roleName, items]) => ({ roleName, count: items.length, items }))
          .sort((a, b) => a.roleName.localeCompare(b.roleName)),
      }))
      .sort((a, b) => a.typeName.localeCompare(b.typeName));
  }, [available]);

  // Apply tree filter + search to the middle pane.
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return available.filter((r) => {
      if (activeType && r.resourceTypeName !== activeType) return false;
      if (activeRole && r.roleName !== activeRole) return false;
      if (!q) return true;
      return (
        r.code.toLowerCase().includes(q) ||
        r.name.toLowerCase().includes(q) ||
        (r.roleName ?? "").toLowerCase().includes(q)
      );
    });
  }, [available, activeType, activeRole, search]);

  const toggleType = (typeName: string) => {
    setExpandedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(typeName)) next.delete(typeName);
      else next.add(typeName);
      return next;
    });
  };

  const toggleResource = (r: ResourceResponse) => {
    setSelected((prev) => {
      const next = new Map(prev);
      if (next.has(r.id)) next.delete(r.id);
      else next.set(r.id, { resource: r });
      return next;
    });
  };

  const addAllOfRole = (items: ResourceResponse[]) => {
    setSelected((prev) => {
      const next = new Map(prev);
      for (const r of items) {
        if (!next.has(r.id)) next.set(r.id, { resource: r });
      }
      return next;
    });
  };

  const clearAll = () => {
    setSelected(new Map());
    setEditingId(null);
  };

  const updateEntry = (id: string, patch: Partial<SelectedEntry>) => {
    setSelected((prev) => {
      const next = new Map(prev);
      const existing = next.get(id);
      if (!existing) return prev;
      next.set(id, { ...existing, ...patch });
      return next;
    });
  };

  const addMutation = useMutation({
    mutationFn: (entries: PoolEntryInput[]) =>
      projectResourceApi.addToPool(projectId, entries),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["resource-pool", projectId] });
      queryClient.invalidateQueries({
        queryKey: ["resource-pool-available-drawer", projectId],
      });
      toast.success(`Added ${selected.size} resource${selected.size !== 1 ? "s" : ""} to team`);
      resetAndClose();
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to add resources to team"));
    },
  });

  const handleSubmit = () => {
    const entries: PoolEntryInput[] = Array.from(selected.values()).map((e) => ({
      resourceId: e.resource.id,
      rateOverride: e.rateOverride ? parseFloat(e.rateOverride) : undefined,
      availabilityOverride: e.availabilityOverride
        ? parseFloat(e.availabilityOverride)
        : undefined,
      notes: e.notes || undefined,
    }));
    addMutation.mutate(entries);
  };

  if (!open) return null;

  return (
    <>
      <div
        className="fixed inset-0 z-30 bg-charcoal/30"
        onClick={resetAndClose}
        aria-hidden
      />
      <aside
        className="fixed right-0 top-0 z-40 flex h-screen w-full flex-col border-l border-border bg-surface shadow-xl md:w-[920px] lg:w-[1100px]"
        role="dialog"
        aria-modal="true"
        aria-label="Add resources to team"
      >
        <header className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
          <div>
            <h2 className="text-lg font-semibold text-text-primary">Add Resources to Team</h2>
            <p className="text-xs text-text-secondary">
              Browse by category, pick what you need, set per-project overrides before committing.
            </p>
          </div>
          <button
            type="button"
            onClick={resetAndClose}
            className="rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-text-primary"
            aria-label="Close drawer"
          >
            <X size={18} />
          </button>
        </header>

        <div className="flex flex-1 min-h-0">
          {/* Left pane: Categories tree */}
          <div className="w-56 flex-shrink-0 overflow-y-auto border-r border-border bg-surface/40">
            <div className="p-3">
              <button
                onClick={() => {
                  setActiveType(null);
                  setActiveRole(null);
                }}
                className={`flex w-full items-center justify-between rounded px-2 py-1.5 text-sm transition-colors ${
                  activeType === null
                    ? "bg-accent/15 font-medium text-text-primary"
                    : "text-text-secondary hover:bg-surface-hover hover:text-text-primary"
                }`}
              >
                <span>All</span>
                <span className="text-xs text-text-muted">{available.length}</span>
              </button>
            </div>
            {tree.map((typeNode) => {
              const isExpanded = expandedTypes.has(typeNode.typeName);
              const isActive = activeType === typeNode.typeName && activeRole === null;
              return (
                <div key={typeNode.typeName} className="border-t border-border/40 px-3 py-1">
                  <div className="flex items-center">
                    <button
                      onClick={() => toggleType(typeNode.typeName)}
                      className="rounded p-0.5 text-text-muted hover:text-text-primary"
                      aria-label={isExpanded ? "Collapse" : "Expand"}
                    >
                      {isExpanded ? (
                        <ChevronDown size={14} />
                      ) : (
                        <ChevronRight size={14} />
                      )}
                    </button>
                    <button
                      onClick={() => {
                        setActiveType(typeNode.typeName);
                        setActiveRole(null);
                        setExpandedTypes((p) => new Set(p).add(typeNode.typeName));
                      }}
                      className={`ml-1 flex flex-1 items-center justify-between rounded px-2 py-1 text-sm transition-colors ${
                        isActive
                          ? "bg-accent/15 font-medium text-text-primary"
                          : "text-text-secondary hover:bg-surface-hover hover:text-text-primary"
                      }`}
                    >
                      <span className="truncate">{displayResourceTypeName(typeNode.typeName)}</span>
                      <span className="ml-2 text-xs text-text-muted">{typeNode.count}</span>
                    </button>
                  </div>
                  {isExpanded && (
                    <div className="mt-1 space-y-0.5 pl-6">
                      {typeNode.roles.map((roleNode) => {
                        const isRoleActive =
                          activeType === typeNode.typeName &&
                          activeRole === roleNode.roleName;
                        return (
                          <div key={roleNode.roleName} className="group flex items-center">
                            <button
                              onClick={() => {
                                setActiveType(typeNode.typeName);
                                setActiveRole(roleNode.roleName);
                              }}
                              className={`flex flex-1 items-center justify-between rounded px-2 py-1 text-xs transition-colors ${
                                isRoleActive
                                  ? "bg-accent/15 font-medium text-text-primary"
                                  : "text-text-secondary hover:bg-surface-hover hover:text-text-primary"
                              }`}
                            >
                              <span className="truncate">{roleNode.roleName}</span>
                              <span className="ml-2 text-xs text-text-muted">
                                {roleNode.count}
                              </span>
                            </button>
                            <button
                              onClick={() => addAllOfRole(roleNode.items)}
                              className="ml-1 rounded p-0.5 text-text-muted opacity-0 transition-opacity hover:text-accent group-hover:opacity-100"
                              title={`Add all ${roleNode.count} ${roleNode.roleName} resources`}
                            >
                              <Plus size={12} />
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {/* Middle pane: Available list */}
          <div className="flex flex-1 flex-col overflow-hidden">
            <div className="border-b border-border px-4 py-3">
              <div className="relative">
                <Search
                  size={14}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
                />
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search by code, name, or role..."
                  className="w-full rounded-md border border-border bg-surface/50 py-2 pl-9 pr-3 text-sm text-text-primary placeholder-text-muted focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <p className="mt-2 text-xs text-text-secondary">
                {isLoading
                  ? "Loading..."
                  : `${filtered.length} of ${available.length} available`}
              </p>
            </div>
            <div className="flex-1 overflow-y-auto">
              {isLoading ? (
                <div className="space-y-2 p-4">
                  {[1, 2, 3, 4, 5].map((i) => (
                    <div key={i} className="h-12 animate-pulse rounded-md bg-surface-hover/50" />
                  ))}
                </div>
              ) : filtered.length === 0 ? (
                <div className="px-4 py-12 text-center text-sm text-text-secondary">
                  {available.length === 0
                    ? "All active resources are already in this team."
                    : "No resources match this filter."}
                </div>
              ) : (
                <ul className="divide-y divide-border/50">
                  {filtered.map((r) => {
                    const isSelected = selected.has(r.id);
                    return (
                      <li key={r.id}>
                        <label className="flex cursor-pointer items-start gap-3 px-4 py-2.5 hover:bg-surface-hover/60">
                          <input
                            type="checkbox"
                            checked={isSelected}
                            onChange={() => toggleResource(r)}
                            className="mt-1 h-4 w-4 rounded border-border text-accent focus:ring-accent"
                          />
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center justify-between gap-3">
                              <div className="min-w-0">
                                <p className="truncate text-sm font-medium text-text-primary">
                                  {r.code} · {r.name}
                                </p>
                                <p className="mt-0.5 truncate text-xs text-text-secondary">
                                  {displayResourceTypeName(r.resourceTypeName)}
                                  {r.roleName ? ` · ${r.roleName}` : ""}
                                </p>
                              </div>
                              {r.costPerUnit != null && (
                                <span className="flex-shrink-0 text-sm text-text-secondary">
                                  {money(r.costPerUnit)}
                                  {r.unit ? `/${r.unit}` : ""}
                                </span>
                              )}
                            </div>
                          </div>
                        </label>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </div>

          {/* Right pane: Selected cart */}
          <div className="flex w-80 flex-shrink-0 flex-col border-l border-border bg-surface/40">
            <div className="border-b border-border px-4 py-3">
              <div className="flex items-center justify-between">
                <p className="text-sm font-semibold text-text-primary">
                  Selected ({selected.size})
                </p>
                {selected.size > 0 && (
                  <button
                    onClick={clearAll}
                    className="text-xs text-text-secondary hover:text-text-primary hover:underline"
                  >
                    Clear all
                  </button>
                )}
              </div>
            </div>
            <div className="flex-1 overflow-y-auto">
              {selected.size === 0 ? (
                <div className="px-4 py-8 text-center text-xs text-text-secondary">
                  Pick resources from the list. You can set per-project rate, availability, or
                  notes here before adding to the team.
                </div>
              ) : (
                <ul className="divide-y divide-border/50">
                  {Array.from(selected.values()).map((entry) => {
                    const r = entry.resource;
                    const isEditing = editingId === r.id;
                    return (
                      <li key={r.id} className="px-3 py-2.5">
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0 flex-1">
                            <p className="truncate text-xs font-medium text-text-primary">
                              {r.code}
                            </p>
                            <p className="truncate text-xs text-text-secondary">{r.name}</p>
                            <p className="mt-0.5 text-xs text-text-muted">
                              Master:{" "}
                              {r.costPerUnit != null
                                ? money(r.costPerUnit)
                                : "—"}
                              {entry.rateOverride && (
                                <span className="ml-2 text-accent">
                                  → {money(parseFloat(entry.rateOverride))}
                                </span>
                              )}
                            </p>
                          </div>
                          <div className="flex flex-shrink-0 items-center gap-1">
                            <button
                              onClick={() => setEditingId(isEditing ? null : r.id)}
                              className="rounded p-1 text-text-muted hover:bg-surface-hover hover:text-accent"
                              title="Set overrides"
                            >
                              {isEditing ? <Check size={12} /> : <Pencil size={12} />}
                            </button>
                            <button
                              onClick={() => toggleResource(r)}
                              className="rounded p-1 text-text-muted hover:bg-surface-hover hover:text-danger"
                              title="Remove"
                            >
                              <X size={12} />
                            </button>
                          </div>
                        </div>
                        {isEditing && (
                          <div className="mt-2 space-y-1.5 rounded-md border border-border/60 bg-surface p-2">
                            <div>
                              <label className="text-xs text-text-muted">Override Rate</label>
                              <input
                                type="number"
                                value={entry.rateOverride ?? ""}
                                onChange={(e) =>
                                  updateEntry(r.id, { rateOverride: e.target.value })
                                }
                                placeholder={
                                  r.costPerUnit != null ? String(r.costPerUnit) : "—"
                                }
                                step="0.01"
                                className="mt-0.5 w-full rounded border border-border bg-surface/50 px-2 py-1 text-xs text-text-primary focus:border-accent focus:outline-none"
                              />
                            </div>
                            <div>
                              <label className="text-xs text-text-muted">
                                Override Availability (%)
                              </label>
                              <input
                                type="number"
                                value={entry.availabilityOverride ?? ""}
                                onChange={(e) =>
                                  updateEntry(r.id, { availabilityOverride: e.target.value })
                                }
                                placeholder={
                                  r.availability != null ? String(r.availability) : "—"
                                }
                                step="0.01"
                                className="mt-0.5 w-full rounded border border-border bg-surface/50 px-2 py-1 text-xs text-text-primary focus:border-accent focus:outline-none"
                              />
                            </div>
                            <div>
                              <label className="text-xs text-text-muted">Notes</label>
                              <input
                                type="text"
                                value={entry.notes ?? ""}
                                onChange={(e) =>
                                  updateEntry(r.id, { notes: e.target.value })
                                }
                                placeholder="Optional note"
                                className="mt-0.5 w-full rounded border border-border bg-surface/50 px-2 py-1 text-xs text-text-primary focus:border-accent focus:outline-none"
                              />
                            </div>
                          </div>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </div>
        </div>

        <footer className="flex items-center justify-between gap-3 border-t border-border bg-surface px-5 py-3">
          <p className="text-sm text-text-secondary">
            {selected.size === 0
              ? "No resources selected"
              : `${selected.size} resource${selected.size !== 1 ? "s" : ""} ready to add`}
          </p>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={resetAndClose}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={selected.size === 0 || addMutation.isPending}
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-50"
            >
              {addMutation.isPending ? "Adding..." : `Add to team (${selected.size})`}
            </button>
          </div>
        </footer>
      </aside>
    </>
  );
}
