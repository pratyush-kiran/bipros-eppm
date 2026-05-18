"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2, UserPlus, Users } from "lucide-react";
import toast from "react-hot-toast";

import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState } from "@/components/common/EmptyState";
import { TabTip } from "@/components/common/TabTip";
import { SearchableSelect, type SelectOption } from "@/components/common/SearchableSelect";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  PROJECT_TEAM_ROLES,
  PROJECT_TEAM_ROLE_LABELS,
  projectTeamApi,
  type CreateProjectTeamMemberRequest,
  type ProjectRole,
  type ProjectTeamMember,
} from "@/lib/api/projectTeamApi";
import { projectApi } from "@/lib/api/projectApi";
import { userApi, type UserSummary } from "@/lib/api/userApi";
import { getErrorMessage } from "@/lib/utils/error";
import { useAuthStore } from "@/lib/state/store";

/**
 * Phase A1 Project Team admin page — manages the project-scoped reporting hierarchy
 * (PM → Engineer / Site Manager → Supervisor / QS / Safety). This is the data DBS
 * uses to roll daily costs up the chain. The page is intentionally simple: a table
 * per role + an Add dialog + delete. Editing the `reportsTo` edge requires deleting
 * and re-adding for now (the backend update endpoint is wired up but a row-level
 * edit affordance can come later — Phase B doesn't depend on it).
 */

// Order is significant — drives the section order on the page.
const ROLE_ORDER: ProjectRole[] = [
  "PM",
  "SITE_MANAGER",
  "ENGINEER",
  "SUPERVISOR",
  "QS",
  "SAFETY",
];

const ROLE_CHIP: Record<ProjectRole, string> = {
  PM: "bg-accent/10 text-accent border-accent/30",
  SITE_MANAGER: "bg-blue-500/10 text-blue-600 border-blue-500/30",
  ENGINEER: "bg-emerald-500/10 text-emerald-600 border-emerald-500/30",
  SUPERVISOR: "bg-amber-500/10 text-amber-600 border-amber-500/30",
  QS: "bg-purple-500/10 text-purple-600 border-purple-500/30",
  SAFETY: "bg-rose-500/10 text-rose-600 border-rose-500/30",
};

function RoleChip({ role }: { role: ProjectRole }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium ${ROLE_CHIP[role]}`}
    >
      {PROJECT_TEAM_ROLE_LABELS[role]}
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

function memberDisplayName(
  member: ProjectTeamMember,
  usersById: Map<string, UserSummary>,
): string {
  // Prefer the backend-projected name; fall back to the user-roster lookup; last
  // resort, show the userId so we never render an empty cell.
  if (member.firstName || member.lastName) {
    return [member.firstName, member.lastName].filter(Boolean).join(" ");
  }
  if (member.username) return member.username;
  const u = usersById.get(member.userId);
  if (u) return u.name;
  return member.userId.slice(0, 8) + "…";
}

function memberSubtitle(
  member: ProjectTeamMember,
  usersById: Map<string, UserSummary>,
): string | null {
  const u = usersById.get(member.userId);
  const email = member.email ?? u?.email ?? null;
  return email;
}

// Roles eligible to be picked when adding a new team member. We don't try to
// filter by global RBAC role — the team-role is a *project-local* assignment
// and the backend is the authority. Anyone in the directory can be assigned.
const PICKER_ROLES = [
  "ADMIN",
  "PROJECT_MANAGER",
  "SITE_MANAGER",
  "SUPERVISOR",
  "FOREMAN",
  "SITE_ENGINEER",
  "QUANTITY_SURVEYOR",
  "SAFETY_OFFICER",
  "ENGINEER",
  "PLANNER",
];

export default function ProjectTeamPage() {
  const params = useParams<{ projectId: string }>();
  const projectId = params.projectId;
  const qc = useQueryClient();

  // The backend doesn't yet have a dedicated PROJECT_TEAM.MANAGE permission — fall
  // back to PROJECT_MEMBER.MANAGE which gates the older roster page. Server is the
  // authority either way; this just hides the write affordances defensively.
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canManage = hasPermission("PROJECT_MEMBER.MANAGE");

  const [addOpen, setAddOpen] = useState(false);
  const [removeTarget, setRemoveTarget] = useState<ProjectTeamMember | null>(null);

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data ?? null;

  const {
    data: teamData,
    isLoading: isLoadingTeam,
    error: teamError,
  } = useQuery({
    queryKey: ["project-team", projectId],
    queryFn: () => projectTeamApi.list(projectId),
    enabled: !!projectId,
  });
  // Wrap in useMemo so downstream `useMemo`s (groupings, picker options) don't churn
  // every render — the `??` fallback would otherwise create a fresh array each time.
  const members: ProjectTeamMember[] = useMemo(
    () => teamData?.data ?? [],
    [teamData],
  );

  // User directory for the Add dialog typeahead (and for resolving display names
  // on existing rows when the backend didn't embed them).
  const { data: usersData } = useQuery({
    queryKey: ["users", "by-roles", PICKER_ROLES],
    queryFn: () => userApi.listByRoles(PICKER_ROLES),
    // Loaded unconditionally so existing-row display names resolve even for
    // read-only viewers. The endpoint returns only summary data.
  });
  const usersById = useMemo(() => {
    const map = new Map<string, UserSummary>();
    for (const u of usersData ?? []) map.set(u.id, u);
    return map;
  }, [usersData]);

  // Group members by role for the per-role tables.
  const byRole = useMemo(() => {
    const groups = new Map<ProjectRole, ProjectTeamMember[]>();
    for (const r of ROLE_ORDER) groups.set(r, []);
    for (const m of members) {
      const bucket = groups.get(m.role);
      if (bucket) bucket.push(m);
    }
    return groups;
  }, [members]);

  // "Reports to" picker — anyone already on the team (excluding the user being added).
  const reportsToCandidates = useMemo(
    () =>
      members.map((m) => ({
        userId: m.userId,
        label: `${memberDisplayName(m, usersById)} — ${PROJECT_TEAM_ROLE_LABELS[m.role]}`,
      })),
    [members, usersById],
  );

  const createMutation = useMutation({
    mutationFn: (body: CreateProjectTeamMemberRequest) =>
      projectTeamApi.create(projectId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["project-team", projectId] });
      setAddOpen(false);
      toast.success("Team member added");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to add team member"));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (memberId: string) => projectTeamApi.delete(projectId, memberId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["project-team", projectId] });
      setRemoveTarget(null);
      toast.success("Team member removed");
    },
    onError: (err: unknown) => {
      toast.error(getErrorMessage(err, "Failed to remove team member"));
    },
  });

  const description = project?.name
    ? `Project-scoped reporting hierarchy for ${project.name}. Drives DBS rollup: Supervisor → Engineer → Project Manager.`
    : "Project-scoped reporting hierarchy. Drives DBS rollup: Supervisor → Engineer → Project Manager.";

  return (
    <div className="space-y-6 p-6">
      <TabTip
        title="Project Team"
        description="Org chart for this project. The roles and reporting edges set here drive DBS aggregation, AI tool grounding, and per-supervisor reports."
      />

      <PageHeader
        title="Project Team"
        description={description}
        actions={
          canManage ? (
            <button
              type="button"
              onClick={() => setAddOpen(true)}
              className="inline-flex items-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover"
            >
              <UserPlus size={16} />
              Add team member
            </button>
          ) : null
        }
      />

      {teamError ? (
        <div className="rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
          {getErrorMessage(teamError, "Failed to load project team")}
        </div>
      ) : isLoadingTeam ? (
        <div className="text-center text-text-muted">Loading team…</div>
      ) : members.length === 0 ? (
        <EmptyState
          title="No team yet"
          description="Add a Project Manager first, then attach Engineers and Supervisors with reporting edges."
        />
      ) : (
        <div className="space-y-6">
          {ROLE_ORDER.map((role) => {
            const rows = byRole.get(role) ?? [];
            if (rows.length === 0) return null;
            return (
              <section
                key={role}
                className="rounded-lg border border-border bg-surface/50 shadow-sm"
              >
                <header className="flex items-center justify-between border-b border-border px-4 py-3">
                  <div className="flex items-center gap-3">
                    <Users size={16} className="text-text-secondary" />
                    <h2 className="text-sm font-semibold text-text-primary">
                      {PROJECT_TEAM_ROLE_LABELS[role]}
                    </h2>
                    <span className="text-xs text-text-muted">
                      {rows.length} {rows.length === 1 ? "member" : "members"}
                    </span>
                  </div>
                  <RoleChip role={role} />
                </header>
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead className="bg-surface/40 text-left text-xs uppercase tracking-wide text-text-muted">
                      <tr>
                        <th className="px-4 py-2">Name</th>
                        <th className="px-4 py-2">Reports to</th>
                        <th className="px-4 py-2">Active from</th>
                        <th className="px-4 py-2">Active to</th>
                        {canManage ? <th className="px-4 py-2 text-right">Actions</th> : null}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {rows.map((m) => {
                        const name = memberDisplayName(m, usersById);
                        const sub = memberSubtitle(m, usersById);
                        const reportsTo = m.reportsToUserId
                          ? members.find((x) => x.userId === m.reportsToUserId)
                          : null;
                        const reportsToLabel = reportsTo
                          ? `${memberDisplayName(reportsTo, usersById)} (${PROJECT_TEAM_ROLE_LABELS[reportsTo.role]})`
                          : m.reportsToName || m.reportsToUsername || "—";
                        return (
                          <tr key={m.id} className="text-sm text-text-primary">
                            <td className="px-4 py-3">
                              <div className="font-medium">{name}</div>
                              {sub ? (
                                <div className="text-xs text-text-muted">{sub}</div>
                              ) : null}
                            </td>
                            <td className="px-4 py-3 text-text-secondary">{reportsToLabel}</td>
                            <td className="px-4 py-3 text-text-secondary">
                              {formatDate(m.activeFrom)}
                            </td>
                            <td className="px-4 py-3 text-text-secondary">
                              {formatDate(m.activeTo)}
                            </td>
                            {canManage ? (
                              <td className="px-4 py-3 text-right">
                                <button
                                  type="button"
                                  onClick={() => setRemoveTarget(m)}
                                  className="inline-flex items-center gap-1 rounded-md border border-danger/30 px-2 py-1 text-xs text-danger hover:bg-danger/10"
                                  aria-label={`Remove ${name}`}
                                >
                                  <Trash2 size={12} />
                                  Remove
                                </button>
                              </td>
                            ) : null}
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </section>
            );
          })}
        </div>
      )}

      {/* Add dialog — gate at the parent so the inner component fully unmounts on close.
          That avoids a `setState-in-useEffect` reset of the controlled inputs (which the
          React Compiler lint rule flags) and gives a clean form on every re-open. */}
      {addOpen ? (
        <AddTeamMemberDialog
          open={addOpen}
          onClose={() => setAddOpen(false)}
          users={usersData ?? []}
          reportsToCandidates={reportsToCandidates}
          existingUserIds={new Set(members.map((m) => m.userId))}
          onSubmit={(body) => createMutation.mutate(body)}
          isSubmitting={createMutation.isPending}
        />
      ) : null}

      {/* Confirm-remove dialog */}
      <Dialog open={!!removeTarget} onOpenChange={(o) => !o && setRemoveTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove team member?</DialogTitle>
          </DialogHeader>
          <DialogBody>
            {removeTarget ? (
              <>
                Remove <strong>{memberDisplayName(removeTarget, usersById)}</strong> from
                this project&apos;s {PROJECT_TEAM_ROLE_LABELS[removeTarget.role]} team?
                Any members reporting to them will be left without a parent until you
                re-assign their reporting edge.
              </>
            ) : null}
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setRemoveTarget(null)}
              className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-surface-hover"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => removeTarget && deleteMutation.mutate(removeTarget.id)}
              disabled={deleteMutation.isPending}
              className="rounded-md bg-danger px-3 py-1.5 text-sm text-white hover:bg-danger/90 disabled:opacity-60"
            >
              {deleteMutation.isPending ? "Removing…" : "Remove"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Add dialog
// ---------------------------------------------------------------------------

interface AddTeamMemberDialogProps {
  open: boolean;
  onClose: () => void;
  users: UserSummary[];
  reportsToCandidates: Array<{ userId: string; label: string }>;
  existingUserIds: Set<string>;
  onSubmit: (body: CreateProjectTeamMemberRequest) => void;
  isSubmitting: boolean;
}

function AddTeamMemberDialog({
  open,
  onClose,
  users,
  reportsToCandidates,
  existingUserIds,
  onSubmit,
  isSubmitting,
}: AddTeamMemberDialogProps) {
  // Component is mounted only when `open` is true (see parent), so initial state is
  // always a fresh form. No effect-based reset needed.
  const [userId, setUserId] = useState("");
  const [role, setRole] = useState<ProjectRole>("SUPERVISOR");
  const [reportsToUserId, setReportsToUserId] = useState("");
  const [activeFrom, setActiveFrom] = useState("");
  const [activeTo, setActiveTo] = useState("");

  const userOptions: SelectOption[] = useMemo(
    () =>
      users
        .filter((u) => !existingUserIds.has(u.id))
        .map((u) => {
          const code = u.employeeCode || u.username;
          return {
            value: u.id,
            label: code && code !== u.name ? `${code} — ${u.name}` : u.name,
          };
        })
        .sort((a, b) => a.label.localeCompare(b.label)),
    [users, existingUserIds],
  );

  const reportsToOptions: SelectOption[] = useMemo(
    () =>
      reportsToCandidates
        .filter((c) => c.userId !== userId)
        .map((c) => ({ value: c.userId, label: c.label })),
    [reportsToCandidates, userId],
  );

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!userId) {
      toast.error("Pick a user");
      return;
    }
    onSubmit({
      userId,
      role,
      reportsToUserId: reportsToUserId || null,
      activeFrom: activeFrom || null,
      activeTo: activeTo || null,
    });
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Add team member</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit}>
          <DialogBody className="space-y-4">
            <div data-testid="add-team-member-user-field">
              <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
                User
              </label>
              {userOptions.length > 0 ? (
                <SearchableSelect
                  options={userOptions}
                  value={userId}
                  onChange={setUserId}
                  placeholder="Search users by name or code…"
                />
              ) : (
                // Stop-gap when the directory call fails / returns empty so the form is still usable.
                <input
                  type="text"
                  value={userId}
                  onChange={(e) => setUserId(e.target.value)}
                  placeholder="User UUID"
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary"
                />
              )}
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
                Role on project
              </label>
              <select
                data-testid="add-team-member-role-select"
                value={role}
                onChange={(e) => setRole(e.target.value as ProjectRole)}
                className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary"
              >
                {PROJECT_TEAM_ROLES.map((r) => (
                  <option key={r} value={r}>
                    {PROJECT_TEAM_ROLE_LABELS[r]}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
                Reports to (optional)
              </label>
              {reportsToOptions.length > 0 ? (
                <SearchableSelect
                  options={[{ value: "", label: "— No reporting edge —" }, ...reportsToOptions]}
                  value={reportsToUserId}
                  onChange={setReportsToUserId}
                  placeholder="Choose a manager already on the team…"
                />
              ) : (
                <div className="rounded-md border border-dashed border-border bg-surface-hover px-3 py-2 text-xs text-text-muted">
                  No other team members yet — add the PM first, then re-open this dialog
                  to set reporting edges.
                </div>
              )}
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
                  Active from
                </label>
                <input
                  type="date"
                  value={activeFrom}
                  onChange={(e) => setActiveFrom(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-text-secondary">
                  Active to
                </label>
                <input
                  type="date"
                  value={activeTo}
                  onChange={(e) => setActiveTo(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary"
                />
              </div>
            </div>
          </DialogBody>
          <DialogFooter>
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-surface-hover"
            >
              Cancel
            </button>
            <button
              type="submit"
              data-testid="add-team-member-submit"
              disabled={isSubmitting}
              className="rounded-md bg-accent px-3 py-1.5 text-sm text-accent-foreground hover:bg-accent-hover disabled:opacity-60"
            >
              {isSubmitting ? "Adding…" : "Add member"}
            </button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
