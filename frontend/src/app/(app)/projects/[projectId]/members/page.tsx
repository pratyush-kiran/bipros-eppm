"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Pencil, Trash2, Search, UserPlus } from "lucide-react";
import toast from "react-hot-toast";

import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  PROJECT_MEMBER_ROLES,
  projectMemberApi,
  type ProjectMemberDto,
  type ProjectMemberRole,
} from "@/lib/api/projectMemberApi";
import { userApi } from "@/lib/api/userApi";
import { projectApi } from "@/lib/api/projectApi";
import { getErrorMessage } from "@/lib/utils/error";
import { useAuthStore } from "@/lib/state/store";
import type { UserResponse } from "@/lib/types";

/**
 * Project Members page — manage who is a member of this project and what
 * `ProjectMemberRole` they hold. Server enforces `PROJECT_MEMBER.MANAGE` for
 * mutating actions; Phase 5.5 will retrofit a Zustand `hasPermission()` helper
 * to also hide the buttons defensively. Until then we render unconditionally
 * and surface server-side 403s as toast errors.
 */

const ROLE_LABELS: Record<ProjectMemberRole, string> = {
  PROJECT_MANAGER: "Project Manager",
  SCHEDULER: "Scheduler",
  RESOURCE_MANAGER: "Resource Manager",
  TEAM_MEMBER: "Team Member",
  CLIENT: "Client",
};

const ROLE_CHIP_STYLES: Record<ProjectMemberRole, string> = {
  PROJECT_MANAGER: "bg-accent/10 text-accent border-accent/30",
  SCHEDULER: "bg-blue-500/10 text-blue-600 border-blue-500/30",
  RESOURCE_MANAGER: "bg-emerald-500/10 text-emerald-600 border-emerald-500/30",
  TEAM_MEMBER: "bg-surface-active/40 text-text-secondary border-border",
  CLIENT: "bg-amber-500/10 text-amber-600 border-amber-500/30",
};

function RoleChip({ role }: { role: ProjectMemberRole }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium ${ROLE_CHIP_STYLES[role]}`}
    >
      {ROLE_LABELS[role]}
    </span>
  );
}

function formatDate(value: string | null | undefined) {
  if (!value) return "—";
  try {
    return new Date(value).toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
    });
  } catch {
    return "—";
  }
}

function userDisplayName(user: UserResponse | undefined): string {
  if (!user) return "Unknown user";
  const full = [user.firstName, user.lastName].filter(Boolean).join(" ").trim();
  return full || user.username;
}

function userInitials(user: UserResponse | undefined): string {
  if (!user) return "?";
  const first = user.firstName?.[0] ?? user.username[0] ?? "?";
  const last = user.lastName?.[0] ?? "";
  return (first + last).toUpperCase();
}

/** Build a minimal user-like object from the project-member row's embedded
 *  user fields (populated by the backend so PMs without ADMIN_USER.READ can
 *  still render names). Falls back to the {@code usersById} lookup when the
 *  embedded data isn't present (older clients / cached responses). */
function memberAsUser(
  member: ProjectMemberDto,
  usersById: Map<string, UserResponse>
): UserResponse | undefined {
  if (member.username) {
    return {
      id: member.userId,
      username: member.username,
      email: member.email ?? "",
      firstName: member.firstName ?? null,
      lastName: member.lastName ?? null,
    } as UserResponse;
  }
  return usersById.get(member.userId);
}

export default function ProjectMembersPage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const qc = useQueryClient();

  const hasPermission = useAuthStore((s) => s.hasPermission);
  // Server enforces PROJECT_MEMBER.MANAGE; hide the write affordances when the
  // caller can't action them so the page is just a read-only roster for
  // SUPERVISOR / TEAM_MEMBER tier users.
  const canManageMembers = hasPermission("PROJECT_MEMBER.MANAGE");

  const [search, setSearch] = useState("");
  const [addOpen, setAddOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ProjectMemberDto | null>(null);
  const [removeTarget, setRemoveTarget] = useState<ProjectMemberDto | null>(null);

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data ?? null;

  const {
    data: membersData,
    isLoading: isLoadingMembers,
    error: membersError,
  } = useQuery({
    queryKey: ["project-members", projectId],
    queryFn: () => projectMemberApi.list(projectId),
    enabled: !!projectId,
  });

  // The /v1/users list is needed by the Add-member dialog (typeahead). The
  // /members endpoint already embeds user display fields so the table itself
  // doesn't need it. Skip the call for non-managers — they can't open the
  // dialog anyway, and the endpoint requires ADMIN_USER.READ which would 403.
  const { data: usersData } = useQuery({
    queryKey: ["users", "all", 500],
    queryFn: () => userApi.listUsers(0, 500),
    enabled: canManageMembers,
  });

  const usersById = useMemo(() => {
    const map = new Map<string, UserResponse>();
    for (const u of usersData?.data?.content ?? []) {
      map.set(u.id, u);
    }
    return map;
  }, [usersData]);

  const members = membersData?.data ?? [];

  const filteredMembers = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return members;
    return members.filter((m) => {
      const u = memberAsUser(m, usersById);
      if (!u) return m.userId.toLowerCase().includes(q);
      return (
        u.username.toLowerCase().includes(q) ||
        (u.email ?? "").toLowerCase().includes(q) ||
        userDisplayName(u).toLowerCase().includes(q)
      );
    });
  }, [members, search, usersById]);

  const assignMutation = useMutation({
    mutationFn: (body: { userId: string; role: ProjectMemberRole }) =>
      projectMemberApi.assign(projectId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["project-members", projectId] });
      setAddOpen(false);
      toast.success("Member added");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to add member"));
    },
  });

  const updateRoleMutation = useMutation({
    mutationFn: (vars: { member: ProjectMemberDto; nextRole: ProjectMemberRole }) =>
      projectMemberApi.updateRole(
        projectId,
        vars.member.id,
        vars.member.userId,
        vars.nextRole
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["project-members", projectId] });
      setEditTarget(null);
      toast.success("Role updated");
    },
    onError: (err: unknown) => {
      // Best-effort recovery: if the delete succeeded but the re-assign
      // failed, the user is left without a row. Re-fetch so the UI matches
      // server state and surface the error.
      qc.invalidateQueries({ queryKey: ["project-members", projectId] });
      toast.error(getErrorMessage(err, "Failed to update role"));
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (memberId: string) => projectMemberApi.revoke(projectId, memberId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["project-members", projectId] });
      setRemoveTarget(null);
      toast.success("Member removed");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to remove member"));
    },
  });

  const description =
    project?.name
      ? `Manage who can act on ${project.name} and the project role they hold. Each role grants the permissions defined for it in the global profile.`
      : "Manage who can act on this project and the project role they hold.";

  return (
    <div className="space-y-6">
      <PageHeader
        title="Project Members"
        description={description}
        actions={
          canManageMembers ? (
            <button
              type="button"
              onClick={() => setAddOpen(true)}
              className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
            >
              <UserPlus size={16} />
              Add member
            </button>
          ) : null
        }
      />

      {/* Search toolbar */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search
            size={14}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
          />
          <input
            type="text"
            placeholder="Search by username, name, or email"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full rounded-md border border-border bg-surface px-9 py-2 text-sm text-text-primary placeholder:text-text-muted focus:outline-none focus:ring-2 focus:ring-accent/40"
          />
        </div>
        <div className="text-xs text-text-muted">
          {filteredMembers.length} of {members.length} member
          {members.length === 1 ? "" : "s"}
        </div>
      </div>

      {membersError ? (
        <div className="rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
          {getErrorMessage(membersError, "Failed to load project members")}
        </div>
      ) : isLoadingMembers ? (
        <div className="text-center text-text-muted">Loading members…</div>
      ) : members.length === 0 ? (
        <EmptyState
          title="No members yet"
          description="Add a project manager, scheduler, or team member to get started."
        />
      ) : filteredMembers.length === 0 ? (
        <div className="rounded-lg border border-border bg-surface/80 p-8 text-center text-text-muted">
          No members match &ldquo;{search}&rdquo;.
        </div>
      ) : (
        <div className="rounded-lg border border-border bg-surface/50 shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="border-b border-border bg-surface/80">
                <tr>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">
                    User
                  </th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">
                    Email
                  </th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">
                    Project Role
                  </th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">
                    Added by
                  </th>
                  <th className="px-4 py-3 text-left text-sm font-semibold text-text-secondary">
                    Added on
                  </th>
                  <th className="px-4 py-3 text-right text-sm font-semibold text-text-secondary">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/50">
                {filteredMembers.map((m) => {
                  const user = memberAsUser(m, usersById);
                  const grantedByUser = m.grantedBy
                    ? usersById.get(m.grantedBy)
                    : undefined;
                  // Prefer the backend's pre-resolved granter display when present
                  // (PM doesn't have ADMIN_USER.READ to populate usersById for the
                  // granter, but the controller looked them up server-side).
                  const grantedByDisplay = m.grantedByName
                    ?? (grantedByUser ? userDisplayName(grantedByUser) : null);
                  return (
                    <tr key={m.id} className="hover:bg-surface/80">
                      <td className="px-4 py-3 text-sm">
                        <div className="flex items-center gap-3">
                          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-accent/20 text-xs font-semibold text-accent">
                            {userInitials(user)}
                          </div>
                          <div className="min-w-0">
                            <div className="font-medium text-text-primary">
                              {user ? userDisplayName(user) : "Unknown user"}
                            </div>
                            <div className="text-xs text-text-muted">
                              @{user?.username ?? m.userId.slice(0, 8)}
                            </div>
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-sm text-text-secondary">
                        {user?.email || "—"}
                      </td>
                      <td className="px-4 py-3 text-sm">
                        <RoleChip role={m.role} />
                      </td>
                      <td className="px-4 py-3 text-sm text-text-secondary">
                        {grantedByDisplay
                          ?? (m.grantedBy ? "—" : "System")}
                      </td>
                      <td className="px-4 py-3 text-sm text-text-secondary">
                        {formatDate(m.createdAt)}
                      </td>
                      <td className="px-4 py-3 text-sm">
                        {canManageMembers ? (
                          <div className="flex items-center justify-end gap-2">
                            <button
                              type="button"
                              onClick={() => setEditTarget(m)}
                              className="inline-flex items-center gap-1 rounded-md border border-border bg-surface px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-surface-hover"
                            >
                              <Pencil size={12} />
                              Edit
                            </button>
                            <button
                              type="button"
                              onClick={() => setRemoveTarget(m)}
                              className="inline-flex items-center gap-1 rounded-md border border-danger/40 bg-danger/5 px-2.5 py-1 text-xs font-medium text-danger hover:bg-danger/10"
                            >
                              <Trash2 size={12} />
                              Remove
                            </button>
                          </div>
                        ) : (
                          <span className="text-text-muted text-xs">—</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <AddMemberDialog
        open={addOpen}
        onClose={() => setAddOpen(false)}
        users={usersData?.data?.content ?? []}
        existingMemberRoleKeys={
          new Set(members.map((m) => `${m.userId}:${m.role}`))
        }
        onSubmit={(body) => assignMutation.mutate(body)}
        submitting={assignMutation.isPending}
      />

      <EditRoleDialog
        member={editTarget}
        userLabel={
          editTarget
            ? userDisplayName(usersById.get(editTarget.userId))
            : ""
        }
        existingRoles={
          editTarget
            ? new Set(
                members
                  .filter((m) => m.userId === editTarget.userId)
                  .map((m) => m.role)
              )
            : new Set()
        }
        onClose={() => setEditTarget(null)}
        onSubmit={(nextRole) =>
          editTarget && updateRoleMutation.mutate({ member: editTarget, nextRole })
        }
        submitting={updateRoleMutation.isPending}
      />

      <Dialog
        open={!!removeTarget}
        onOpenChange={(next) => {
          if (!next) setRemoveTarget(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove member?</DialogTitle>
          </DialogHeader>
          <DialogBody>
            {removeTarget && (
              <p>
                Remove{" "}
                <span className="font-semibold text-charcoal">
                  {userDisplayName(usersById.get(removeTarget.userId))}
                </span>{" "}
                from the <RoleChip role={removeTarget.role} /> role on this
                project? They will lose any access this assignment grants.
              </p>
            )}
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setRemoveTarget(null)}
              className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() =>
                removeTarget && revokeMutation.mutate(removeTarget.id)
              }
              disabled={revokeMutation.isPending}
              className="rounded-md bg-danger px-4 py-2 text-sm font-medium text-text-primary hover:bg-danger/80 disabled:opacity-50"
            >
              {revokeMutation.isPending ? "Removing…" : "Remove"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

// ───────────────────────────────────────────────────────────────────────────
// Add member dialog
// ───────────────────────────────────────────────────────────────────────────

interface AddMemberDialogProps {
  open: boolean;
  onClose: () => void;
  users: UserResponse[];
  /**
   * Set of `${userId}:${role}` pairs already present — used to disable
   * duplicate combinations (the backend rejects them with HTTP 409).
   */
  existingMemberRoleKeys: Set<string>;
  onSubmit: (body: { userId: string; role: ProjectMemberRole }) => void;
  submitting: boolean;
}

function AddMemberDialog({
  open,
  onClose,
  users,
  existingMemberRoleKeys,
  onSubmit,
  submitting,
}: AddMemberDialogProps) {
  const [query, setQuery] = useState("");
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [role, setRole] = useState<ProjectMemberRole>("TEAM_MEMBER");

  // Reset state when the dialog closes — avoids carrying a stale selection
  // into the next "Add member" click.
  const handleClose = () => {
    setQuery("");
    setSelectedUserId(null);
    setRole("TEAM_MEMBER");
    onClose();
  };

  // Backend has no typeahead endpoint — filter the already-fetched first 500
  // users by username/email/name prefix and cap the dropdown at 50 rows.
  const filteredUsers = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return users.slice(0, 50);
    return users
      .filter((u) => {
        const haystack = [
          u.username,
          u.email,
          u.firstName,
          u.lastName,
          [u.firstName, u.lastName].filter(Boolean).join(" "),
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase();
        return haystack.includes(q);
      })
      .slice(0, 50);
  }, [users, query]);

  const selectedUser = useMemo(
    () => users.find((u) => u.id === selectedUserId) ?? null,
    [users, selectedUserId]
  );

  const duplicate =
    selectedUserId && existingMemberRoleKeys.has(`${selectedUserId}:${role}`);
  const canSubmit = !!selectedUserId && !duplicate && !submitting;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) handleClose();
      }}
    >
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Add project member</DialogTitle>
        </DialogHeader>
        <DialogBody className="space-y-4">
          <div>
            <label className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary">
              User
            </label>
            <div className="relative">
              <Search
                size={14}
                className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
              />
              <input
                type="text"
                autoFocus
                value={query}
                onChange={(e) => {
                  setQuery(e.target.value);
                  setSelectedUserId(null);
                }}
                placeholder="Search by username, name, or email"
                className="w-full rounded-md border border-border bg-surface px-9 py-2 text-sm text-text-primary placeholder:text-text-muted focus:outline-none focus:ring-2 focus:ring-accent/40"
              />
            </div>

            {selectedUser ? (
              <div className="mt-2 flex items-center justify-between rounded-md border border-accent/30 bg-accent/5 px-3 py-2">
                <div className="flex items-center gap-3">
                  <div className="flex h-8 w-8 items-center justify-center rounded-full bg-accent/20 text-xs font-semibold text-accent">
                    {userInitials(selectedUser)}
                  </div>
                  <div>
                    <div className="text-sm font-medium text-text-primary">
                      {userDisplayName(selectedUser)}
                    </div>
                    <div className="text-xs text-text-muted">
                      @{selectedUser.username} · {selectedUser.email}
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setSelectedUserId(null);
                    setQuery("");
                  }}
                  className="text-xs text-text-secondary hover:text-text-primary"
                >
                  Change
                </button>
              </div>
            ) : (
              <div className="mt-2 max-h-56 overflow-y-auto rounded-md border border-border bg-surface">
                {filteredUsers.length === 0 ? (
                  <div className="px-3 py-4 text-center text-xs text-text-muted">
                    No users match.
                  </div>
                ) : (
                  filteredUsers.map((u) => (
                    <button
                      key={u.id}
                      type="button"
                      onClick={() => setSelectedUserId(u.id)}
                      className="flex w-full items-center gap-3 border-b border-border/40 px-3 py-2 text-left text-sm last:border-0 hover:bg-surface-hover/60"
                    >
                      <div className="flex h-7 w-7 items-center justify-center rounded-full bg-surface-active/60 text-[10px] font-semibold text-text-secondary">
                        {userInitials(u)}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="truncate font-medium text-text-primary">
                          {userDisplayName(u)}
                        </div>
                        <div className="truncate text-xs text-text-muted">
                          @{u.username} · {u.email}
                        </div>
                      </div>
                    </button>
                  ))
                )}
              </div>
            )}
          </div>

          <div>
            <label
              htmlFor="member-role-select"
              className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary"
            >
              Project role
            </label>
            <select
              id="member-role-select"
              value={role}
              onChange={(e) => setRole(e.target.value as ProjectMemberRole)}
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-accent/40"
            >
              {PROJECT_MEMBER_ROLES.map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABELS[r]}
                </option>
              ))}
            </select>
            {duplicate && (
              <p className="mt-1 text-xs text-warning">
                This user already holds {ROLE_LABELS[role]} on this project.
              </p>
            )}
          </div>
        </DialogBody>
        <DialogFooter>
          <button
            type="button"
            onClick={handleClose}
            className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!canSubmit}
            onClick={() => {
              if (!selectedUserId) return;
              onSubmit({ userId: selectedUserId, role });
            }}
            className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
          >
            <Plus size={14} />
            {submitting ? "Adding…" : "Add"}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ───────────────────────────────────────────────────────────────────────────
// Edit role dialog
// ───────────────────────────────────────────────────────────────────────────

interface EditRoleDialogProps {
  member: ProjectMemberDto | null;
  userLabel: string;
  /** Roles this user already holds on the project — disabled in the picker. */
  existingRoles: Set<ProjectMemberRole>;
  onClose: () => void;
  onSubmit: (nextRole: ProjectMemberRole) => void;
  submitting: boolean;
}

function EditRoleDialog({
  member,
  userLabel,
  existingRoles,
  onClose,
  onSubmit,
  submitting,
}: EditRoleDialogProps) {
  const [nextRole, setNextRole] = useState<ProjectMemberRole>(
    member?.role ?? "TEAM_MEMBER"
  );

  // Sync local state when the target member changes — without this, opening
  // the edit dialog on a second member would still show the first member's
  // role until the user manually picked from the dropdown.
  useEffect(() => {
    if (member) setNextRole(member.role);
  }, [member]);

  const isSameRole = member?.role === nextRole;
  const isDuplicate =
    !isSameRole && existingRoles.has(nextRole);

  return (
    <Dialog
      open={!!member}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Change project role</DialogTitle>
        </DialogHeader>
        <DialogBody className="space-y-4">
          <p className="text-sm">
            Change the project role for{" "}
            <span className="font-semibold text-charcoal">{userLabel}</span>.
            The previous role is revoked and the new one is granted in its
            place.
          </p>
          <div>
            <label
              htmlFor="edit-member-role-select"
              className="mb-1 block text-xs font-medium uppercase tracking-wider text-text-secondary"
            >
              Project role
            </label>
            <select
              id="edit-member-role-select"
              value={nextRole}
              onChange={(e) =>
                setNextRole(e.target.value as ProjectMemberRole)
              }
              className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-accent/40"
            >
              {PROJECT_MEMBER_ROLES.map((r) => {
                const isCurrent = member?.role === r;
                const taken = !isCurrent && existingRoles.has(r);
                return (
                  <option key={r} value={r} disabled={taken}>
                    {ROLE_LABELS[r]}
                    {isCurrent ? " (current)" : ""}
                    {taken ? " — already assigned" : ""}
                  </option>
                );
              })}
            </select>
            {isDuplicate && (
              <p className="mt-1 text-xs text-warning">
                {userLabel} already holds {ROLE_LABELS[nextRole]} on this
                project.
              </p>
            )}
          </div>
        </DialogBody>
        <DialogFooter>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-border px-4 py-2 text-sm font-medium text-text-secondary hover:bg-surface-hover/50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={isSameRole || isDuplicate || submitting}
            onClick={() => onSubmit(nextRole)}
            className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
          >
            {submitting ? "Saving…" : "Save"}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
