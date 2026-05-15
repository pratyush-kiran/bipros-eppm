"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import toast from "react-hot-toast";
import { Pencil, Plus, Search, UserCheck, UserX, X } from "lucide-react";

import { profileApi } from "@/lib/api/profileApi";
import { roleApi } from "@/lib/api/roleApi";
import { userApi } from "@/lib/api/userApi";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { MultiSelect } from "@/components/common/MultiSelect";
import { PageHeader } from "@/components/common/PageHeader";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type {
  Department,
  PresenceStatus,
  UserResponse,
} from "@/lib/types";
import { getErrorMessage } from "@/lib/utils/error";
import { CreateUserDialog } from "./CreateUserDialog";
import { EditUserDrawer } from "./EditUserDrawer";

const DEPARTMENTS: Department[] = [
  "CIVIL",
  "QUALITY",
  "SURVEY",
  "PLANT",
  "HSE",
  "STORES",
  "ADMIN",
  "FINANCE",
  "OTHER",
];

const PRESENCE_STATUSES: PresenceStatus[] = [
  "ON_SITE",
  "ON_LEAVE",
  "TRANSFERRED",
  "RELEASED",
];

const PRESENCE_VARIANT: Record<
  PresenceStatus,
  "success" | "warning" | "info" | "neutral"
> = {
  ON_SITE: "success",
  ON_LEAVE: "warning",
  TRANSFERRED: "info",
  RELEASED: "neutral",
};

function RoleChips({ roles }: { roles: string[] }) {
  if (!roles || roles.length === 0) {
    return <span className="text-xs italic text-slate">— None —</span>;
  }
  const visible = roles.slice(0, 3);
  const overflow = roles.length - visible.length;
  return (
    <div className="flex flex-wrap items-center gap-1" title={roles.join(", ")}>
      {visible.map((r) => (
        <Badge key={r} variant="gold" className="px-1.5 py-0.5 text-[10px]">
          {r.replace(/_/g, " ")}
        </Badge>
      ))}
      {overflow > 0 && (
        <Badge variant="neutral" className="px-1.5 py-0.5 text-[10px]">
          +{overflow}
        </Badge>
      )}
    </div>
  );
}

function PresencePill({
  status,
}: {
  status: PresenceStatus | null | undefined;
}) {
  if (!status) return <span className="text-xs text-slate">—</span>;
  return (
    <Badge variant={PRESENCE_VARIANT[status]} withDot>
      {status.replace("_", " ")}
    </Badge>
  );
}

function BulkAssignRolesDialog({
  open,
  selectedCount,
  onClose,
  onConfirm,
  isPending,
}: {
  open: boolean;
  selectedCount: number;
  onClose: () => void;
  onConfirm: (roles: string[]) => void;
  isPending: boolean;
}) {
  const [roles, setRoles] = useState<string[]>([]);

  const { data } = useQuery({
    queryKey: ["roles"],
    queryFn: () => roleApi.list(),
    enabled: open,
  });
  const options = (data?.data ?? []).map((r) => ({
    value: r.name,
    label: r.name.replace(/_/g, " "),
  }));

  const close = () => {
    setRoles([]);
    onClose();
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && close()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>
            Assign roles to {selectedCount} user{selectedCount !== 1 ? "s" : ""}
          </DialogTitle>
        </DialogHeader>
        <DialogBody>
          <p className="mb-3 text-sm text-slate">
            The selected roles will <strong>replace</strong> the existing role
            set on every chosen user.
          </p>
          <MultiSelect
            options={options}
            value={roles}
            onChange={setRoles}
            placeholder="Pick one or more roles…"
          />
        </DialogBody>
        <DialogFooter>
          <button
            type="button"
            onClick={close}
            className="rounded-md border border-hairline bg-paper px-3 py-1.5 text-sm font-medium text-slate hover:bg-ivory"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={isPending || roles.length === 0}
            onClick={() => onConfirm(roles)}
            className="rounded-md bg-gold px-3 py-1.5 text-sm font-semibold text-paper hover:bg-gold-deep disabled:opacity-50"
          >
            {isPending ? "Assigning…" : "Assign"}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function UsersPage() {
  const queryClient = useQueryClient();

  // UI state
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<UserResponse | null>(null);
  const [confirmDeactivate, setConfirmDeactivate] = useState<{
    user: UserResponse;
  } | null>(null);
  const [confirmBulkDeactivate, setConfirmBulkDeactivate] = useState(false);
  const [bulkAssignOpen, setBulkAssignOpen] = useState(false);

  // Filter state — server filter for roles, client filter for the rest.
  const [roleFilter, setRoleFilter] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [departmentFilter, setDepartmentFilter] = useState<Department | "ALL">(
    "ALL",
  );
  const [presenceFilter, setPresenceFilter] = useState<PresenceStatus | "ALL">(
    "ALL",
  );

  // Row selection (external — VirtualDataTable's internal selection isn't
  // exposed and bulk actions need the IDs).
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const { data, isLoading, error } = useQuery({
    queryKey: ["users", { roles: roleFilter }],
    queryFn: () => userApi.listUsers(0, 200, roleFilter),
  });

  const { data: rolesResp } = useQuery({
    queryKey: ["roles"],
    queryFn: () => roleApi.list(),
  });
  const roleOptions = useMemo(
    () =>
      (rolesResp?.data ?? []).map((r) => ({
        value: r.name,
        label: r.name.replace(/_/g, " "),
      })),
    [rolesResp],
  );

  const { data: profilesResponse } = useQuery({
    queryKey: ["profiles"],
    queryFn: () => profileApi.listProfiles(),
  });
  const profilesById = useMemo(() => {
    const m = new Map<string, string>();
    for (const p of profilesResponse?.data ?? []) m.set(p.id, p.name);
    return m;
  }, [profilesResponse]);

  const allUsers = useMemo(() => data?.data?.content ?? [], [data]);

  const filteredUsers = useMemo(() => {
    let list = allUsers;
    if (search.trim()) {
      const q = search.toLowerCase();
      list = list.filter(
        (u) =>
          u.username.toLowerCase().includes(q) ||
          (u.email ?? "").toLowerCase().includes(q) ||
          (u.firstName ?? "").toLowerCase().includes(q) ||
          (u.lastName ?? "").toLowerCase().includes(q) ||
          (u.employeeCode ?? "").toLowerCase().includes(q),
      );
    }
    if (departmentFilter !== "ALL") {
      list = list.filter((u) => u.department === departmentFilter);
    }
    if (presenceFilter !== "ALL") {
      list = list.filter((u) => u.presenceStatus === presenceFilter);
    }
    return list;
  }, [allUsers, search, departmentFilter, presenceFilter]);

  const toggleMutation = useMutation({
    mutationFn: ({ userId, enabled }: { userId: string; enabled: boolean }) =>
      userApi.toggleUserEnabled(userId, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["users"] });
      toast.success("User status updated");
    },
    onError: (err: unknown) =>
      toast.error(getErrorMessage(err, "Failed to update user status")),
  });

  const bulkDeactivateMutation = useMutation({
    mutationFn: async (ids: string[]) => {
      await Promise.all(ids.map((id) => userApi.deactivate(id)));
    },
    onSuccess: (_, ids) => {
      queryClient.invalidateQueries({ queryKey: ["users"] });
      toast.success(
        `Deactivated ${ids.length} user${ids.length !== 1 ? "s" : ""}`,
      );
      setSelected(new Set());
      setConfirmBulkDeactivate(false);
    },
    onError: (err: unknown) =>
      toast.error(getErrorMessage(err, "Bulk deactivate failed")),
  });

  const bulkAssignMutation = useMutation({
    mutationFn: async ({
      ids,
      roles,
    }: {
      ids: string[];
      roles: string[];
    }) => {
      await Promise.all(ids.map((id) => userApi.assignRoles(id, roles)));
    },
    onSuccess: (_, vars) => {
      queryClient.invalidateQueries({ queryKey: ["users"] });
      toast.success(
        `Roles updated on ${vars.ids.length} user${vars.ids.length !== 1 ? "s" : ""}`,
      );
      setSelected(new Set());
      setBulkAssignOpen(false);
    },
    onError: (err: unknown) =>
      toast.error(getErrorMessage(err, "Bulk role assign failed")),
  });

  const allVisibleSelected =
    filteredUsers.length > 0 &&
    filteredUsers.every((u) => selected.has(u.id));

  const toggleSelectAll = () => {
    if (allVisibleSelected) {
      const next = new Set(selected);
      filteredUsers.forEach((u) => next.delete(u.id));
      setSelected(next);
    } else {
      const next = new Set(selected);
      filteredUsers.forEach((u) => next.add(u.id));
      setSelected(next);
    }
  };

  const toggleRow = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const clearAllFilters = () => {
    setRoleFilter([]);
    setSearch("");
    setDepartmentFilter("ALL");
    setPresenceFilter("ALL");
  };

  const filtersActive =
    roleFilter.length > 0 ||
    search.trim().length > 0 ||
    departmentFilter !== "ALL" ||
    presenceFilter !== "ALL";

  return (
    <div>
      <PageHeader
        title="User Management"
        description="Manage users, roles, and access permissions"
        actions={
          <button
            type="button"
            onClick={() => setCreateOpen(true)}
            className="inline-flex items-center gap-2 rounded-md bg-gold px-3 py-2 text-sm font-medium text-paper hover:bg-gold-deep"
          >
            <Plus size={16} /> New User
          </button>
        }
      />

      {/* Filters bar */}
      <div className="mb-4 rounded-xl border border-hairline bg-paper p-4 shadow-sm">
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-12">
          <div className="lg:col-span-5">
            <label className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate">
              Roles
            </label>
            <MultiSelect
              options={roleOptions}
              value={roleFilter}
              onChange={(v) => {
                setRoleFilter(v);
                setSelected(new Set()); // selection becomes ambiguous on filter change
              }}
              placeholder="Filter by role…"
            />
          </div>

          <div className="lg:col-span-4">
            <label className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate">
              Search
            </label>
            <div className="relative">
              <Search
                size={14}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-ash"
              />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="username, email, name, employee code…"
                className="w-full rounded-md border border-hairline bg-paper py-2 pl-8 pr-3 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/30"
              />
            </div>
          </div>

          <div className="lg:col-span-3">
            <label className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate">
              Department
            </label>
            <select
              value={departmentFilter}
              onChange={(e) =>
                setDepartmentFilter(e.target.value as Department | "ALL")
              }
              className="w-full rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/30"
            >
              <option value="ALL">All departments</option>
              {DEPARTMENTS.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="mt-3 flex items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-[11px] font-semibold uppercase tracking-wide text-slate">
              Presence:
            </span>
            <button
              type="button"
              onClick={() => setPresenceFilter("ALL")}
              className={`rounded-md px-2 py-0.5 text-xs font-medium transition-colors ${
                presenceFilter === "ALL"
                  ? "bg-gold text-paper"
                  : "border border-hairline text-slate hover:bg-ivory"
              }`}
            >
              All
            </button>
            {PRESENCE_STATUSES.map((p) => (
              <button
                key={p}
                type="button"
                onClick={() => setPresenceFilter(p)}
                className={`rounded-md px-2 py-0.5 text-xs font-medium transition-colors ${
                  presenceFilter === p
                    ? "bg-gold text-paper"
                    : "border border-hairline text-slate hover:bg-ivory"
                }`}
              >
                {p.replace("_", " ")}
              </button>
            ))}
          </div>

          <div className="flex items-center gap-3 text-xs text-slate">
            <span>
              {filteredUsers.length} of {allUsers.length}{" "}
              user{allUsers.length !== 1 ? "s" : ""}
            </span>
            {filtersActive && (
              <button
                type="button"
                onClick={clearAllFilters}
                className="inline-flex items-center gap-1 rounded-md border border-hairline px-2 py-0.5 text-xs text-slate hover:bg-ivory"
              >
                <X size={12} /> Clear filters
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Bulk actions bar */}
      {selected.size > 0 && (
        <div className="mb-3 flex items-center justify-between rounded-xl border border-gold/40 bg-gold-tint/40 px-4 py-2 text-sm">
          <span className="font-medium text-gold-ink">
            {selected.size} user{selected.size !== 1 ? "s" : ""} selected
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setBulkAssignOpen(true)}
              className="rounded-md border border-hairline bg-paper px-2.5 py-1 text-xs font-medium text-charcoal hover:bg-ivory"
            >
              Assign role…
            </button>
            <button
              type="button"
              onClick={() => setConfirmBulkDeactivate(true)}
              className="rounded-md bg-burgundy px-2.5 py-1 text-xs font-medium text-paper hover:bg-burgundy/90"
            >
              Deactivate selected
            </button>
            <button
              type="button"
              onClick={() => setSelected(new Set())}
              className="rounded-md px-2 py-1 text-xs text-slate hover:bg-ivory"
            >
              Clear
            </button>
          </div>
        </div>
      )}

      {error && (
        <div className="mb-4 rounded-md border border-burgundy/30 bg-burgundy/10 p-4 text-sm text-burgundy">
          {getErrorMessage(error, "Failed to load users")}
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-hairline bg-paper shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-slate">Loading users…</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-sm">
              <thead className="border-b border-hairline bg-ivory">
                <tr>
                  <th className="w-10 px-3 py-2.5 text-left">
                    <input
                      type="checkbox"
                      checked={allVisibleSelected}
                      onChange={toggleSelectAll}
                      aria-label="Select all visible"
                      className="h-4 w-4 cursor-pointer rounded border-hairline accent-gold"
                    />
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Username
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Email
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Emp. Code
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Designation
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Roles
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Profile
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Enabled
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Presence
                  </th>
                  <th className="px-3 py-2.5 text-right text-[11px] font-semibold uppercase tracking-wide text-slate">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {filteredUsers.length === 0 ? (
                  <tr>
                    <td
                      colSpan={10}
                      className="px-3 py-10 text-center text-sm text-slate"
                    >
                      {filtersActive
                        ? "No users match the current filters."
                        : "No users found."}
                    </td>
                  </tr>
                ) : (
                  filteredUsers.map((u) => (
                    <tr
                      key={u.id}
                      className="border-b border-hairline/50 hover:bg-ivory/50"
                    >
                      <td className="px-3 py-2">
                        <input
                          type="checkbox"
                          checked={selected.has(u.id)}
                          onChange={() => toggleRow(u.id)}
                          aria-label={`Select ${u.username}`}
                          className="h-4 w-4 cursor-pointer rounded border-hairline accent-gold"
                        />
                      </td>
                      <td className="px-3 py-2 font-medium text-charcoal">
                        {u.username}
                      </td>
                      <td className="px-3 py-2 text-slate">{u.email}</td>
                      <td className="px-3 py-2 font-mono text-xs text-slate">
                        {u.employeeCode ?? "—"}
                      </td>
                      <td className="px-3 py-2 text-slate">
                        {u.designation ?? "—"}
                      </td>
                      <td className="px-3 py-2">
                        <RoleChips roles={u.roles ?? []} />
                      </td>
                      <td className="px-3 py-2 text-slate">
                        {u.profileName ??
                          (u.profileId ? profilesById.get(u.profileId) : null) ??
                          <span className="text-xs italic">— None —</span>}
                      </td>
                      <td className="px-3 py-2">
                        <button
                          type="button"
                          role="switch"
                          aria-checked={u.enabled}
                          disabled={toggleMutation.isPending}
                          onClick={() =>
                            toggleMutation.mutate({
                              userId: u.id,
                              enabled: !u.enabled,
                            })
                          }
                          className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
                            u.enabled ? "bg-emerald" : "bg-ash"
                          } disabled:opacity-50`}
                        >
                          <span
                            className={`inline-block h-4 w-4 transform rounded-full bg-paper transition-transform ${
                              u.enabled ? "translate-x-4" : "translate-x-0.5"
                            }`}
                          />
                        </button>
                      </td>
                      <td className="px-3 py-2">
                        <PresencePill status={u.presenceStatus} />
                      </td>
                      <td className="px-3 py-2 text-right">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            type="button"
                            onClick={() => setEditing(u)}
                            className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-gold-deep"
                            aria-label="Edit"
                            title="Edit"
                          >
                            <Pencil size={14} />
                          </button>
                          {u.enabled ? (
                            <button
                              type="button"
                              onClick={() =>
                                setConfirmDeactivate({ user: u })
                              }
                              className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-burgundy"
                              aria-label="Deactivate"
                              title="Deactivate"
                            >
                              <UserX size={14} />
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() =>
                                toggleMutation.mutate({
                                  userId: u.id,
                                  enabled: true,
                                })
                              }
                              className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-emerald"
                              aria-label="Reactivate"
                              title="Reactivate"
                            >
                              <UserCheck size={14} />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <CreateUserDialog open={createOpen} onOpenChange={setCreateOpen} />
      <EditUserDrawer
        open={editing !== null}
        user={editing}
        onClose={() => setEditing(null)}
      />

      <ConfirmDialog
        open={confirmDeactivate !== null}
        title="Deactivate user?"
        message={`Disable login for ${confirmDeactivate?.user.username ?? ""}. They can be reactivated later from this page.`}
        confirmLabel="Deactivate"
        variant="danger"
        onCancel={() => setConfirmDeactivate(null)}
        onConfirm={() => {
          if (confirmDeactivate) {
            toggleMutation.mutate({
              userId: confirmDeactivate.user.id,
              enabled: false,
            });
            setConfirmDeactivate(null);
          }
        }}
      />

      <ConfirmDialog
        open={confirmBulkDeactivate}
        title={`Deactivate ${selected.size} user${selected.size !== 1 ? "s" : ""}?`}
        message="Each user's login will be disabled. They can be reactivated individually later."
        confirmLabel="Deactivate all"
        variant="danger"
        onCancel={() => setConfirmBulkDeactivate(false)}
        onConfirm={() => bulkDeactivateMutation.mutate(Array.from(selected))}
      />

      <BulkAssignRolesDialog
        open={bulkAssignOpen}
        selectedCount={selected.size}
        onClose={() => setBulkAssignOpen(false)}
        onConfirm={(roles) =>
          bulkAssignMutation.mutate({ ids: Array.from(selected), roles })
        }
        isPending={bulkAssignMutation.isPending}
      />
    </div>
  );
}
