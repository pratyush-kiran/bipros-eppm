"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  materialConsumptionApi,
  type MaterialConsumptionLogResponse,
  type CreateMaterialConsumptionLogRequest,
} from "@/lib/api/materialConsumptionApi";
import { projectApi } from "@/lib/api/projectApi";
import { activityApi } from "@/lib/api/activityApi";
import { userApi, type UserSummary } from "@/lib/api/userApi";
import { TabTip } from "@/components/common/TabTip";
import { SearchableSelect, type SelectOption } from "@/components/common/SearchableSelect";
import { useAuth } from "@/lib/auth/useAuth";
import { getErrorMessage } from "@/lib/utils/error";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";

// Phase A2: roles that can be the "entered by" of a material consumption log. Mirrors
// the backend enum on `MaterialConsumptionLog.enteredByRole`. We keep the set small —
// the DBS rollup only cares about SUPERVISOR vs STOREKEEPER; the others are catch-alls
// for users whose primary role is something else but who still enter logs.
const ENTERED_BY_ROLES = [
  "SUPERVISOR",
  "STOREKEEPER",
  "SITE_MANAGER",
  "ENGINEER",
  "PROJECT_MANAGER",
  "FOREMAN",
] as const;

// Roles eligible to appear as issuer/receiver in the User picker. Storekeeper +
// supervisor cover the canonical workflow; the rest are present so a project that
// hasn't fully aligned its directory roles still has a usable picker.
const ISSUER_RECEIVER_ROLES = [
  "STOREKEEPER",
  "SUPERVISOR",
  "FOREMAN",
  "SITE_ENGINEER",
  "SITE_MANAGER",
  "PROJECT_MANAGER",
];

const ROLE_CHIP_STYLES: Record<string, string> = {
  SUPERVISOR: "bg-amber-500/10 text-amber-600 border-amber-500/30",
  STOREKEEPER: "bg-blue-500/10 text-blue-600 border-blue-500/30",
  SITE_MANAGER: "bg-emerald-500/10 text-emerald-600 border-emerald-500/30",
  ENGINEER: "bg-purple-500/10 text-purple-600 border-purple-500/30",
  PROJECT_MANAGER: "bg-accent/10 text-accent border-accent/30",
  FOREMAN: "bg-rose-500/10 text-rose-600 border-rose-500/30",
};

function EnteredByChip({ role }: { role: string | null | undefined }) {
  if (!role) return <span className="text-text-muted">—</span>;
  const style = ROLE_CHIP_STYLES[role] ?? "bg-surface-active/40 text-text-secondary border-border";
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold tracking-wide ${style}`}>
      {role}
    </span>
  );
}

const UNITS = ["Cum", "MT", "Bag", "Rm", "Each"] as const;

interface MaterialConsumptionForm {
  logDate: string;
  materialName: string;
  unit: string;
  openingStock: number;
  received: number;
  consumed: number;
  wastagePercent: string; // optional — keep as string so empty stays empty
  /** User FK for the issuer (preferred). Empty when the user hasn't been picked. */
  issuedByUserId: string;
  /** Legacy free-text issuer — kept as a fallback when no directory user matches. */
  issuedBy: string;
  /** User FK for the receiver (preferred). */
  receivedByUserId: string;
  /** Legacy free-text receiver. */
  receivedBy: string;
  /** Phase A2: role to record on the log; defaults to the caller's primary role. */
  enteredByRole: string;
  activityId: string;
  remarks: string;
}

const today = () => new Date().toISOString().split("T")[0];

const initialFormState: MaterialConsumptionForm = {
  logDate: today(),
  materialName: "",
  unit: "Cum",
  openingStock: 0,
  received: 0,
  consumed: 0,
  wastagePercent: "",
  issuedByUserId: "",
  issuedBy: "",
  receivedByUserId: "",
  receivedBy: "",
  enteredByRole: "",
  activityId: "",
  remarks: "",
};

// Resolve the caller's most-relevant role from the JWT roles array. We prefer the
// ENTERED_BY_ROLES order so e.g. an ADMIN-also-SUPERVISOR records as SUPERVISOR,
// which is what the DBS rollup expects.
function pickDefaultEnteredByRole(roles: readonly string[]): string {
  for (const r of ENTERED_BY_ROLES) {
    if (roles.includes(r)) return r;
  }
  return "";
}

const fmtNum = (v: number | null | undefined) =>
  v === null || v === undefined ? "—" : String(v);

export default function MaterialConsumptionPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const { roles: currentUserRoles } = useAuth();

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data;

  // Activities used to tag a consumption log so the cost rolls into the activity AC. Logs
  // without an activity remain valid but won't contribute to per-activity actual cost.
  const { data: activitiesResponse } = useQuery({
    queryKey: ["activities-for-consumption", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 500),
    enabled: !!projectId,
  });
  const activities = useMemo(
    () => activitiesResponse?.data?.content ?? [],
    [activitiesResponse],
  );

  // Phase A2 — directory of storekeeper / supervisor users for the issuer/receiver pickers.
  // The endpoint returns UserSummary {id, name, username, employeeCode, email}.
  const { data: pickerUsers } = useQuery({
    queryKey: ["users", "by-roles", ISSUER_RECEIVER_ROLES],
    queryFn: () => userApi.listByRoles(ISSUER_RECEIVER_ROLES),
  });
  const usersById = useMemo(() => {
    const map = new Map<string, UserSummary>();
    for (const u of pickerUsers ?? []) map.set(u.id, u);
    return map;
  }, [pickerUsers]);
  const userOptions: SelectOption[] = useMemo(
    () =>
      (pickerUsers ?? [])
        .map((u) => {
          const code = u.employeeCode || u.username;
          return {
            value: u.id,
            label: code && code !== u.name ? `${code} — ${u.name}` : u.name,
          };
        })
        .sort((a, b) => a.label.localeCompare(b.label)),
    [pickerUsers],
  );

  const [from, setFrom] = useState<string>("");
  const [to, setTo] = useState<string>("");
  const [enteredByRoleFilter, setEnteredByRoleFilter] = useState<string>("");
  const [issuedByUserFilter, setIssuedByUserFilter] = useState<string>("");
  const [appliedFrom, setAppliedFrom] = useState<string>("");
  const [appliedTo, setAppliedTo] = useState<string>("");
  const [appliedEnteredByRole, setAppliedEnteredByRole] = useState<string>("");
  const [appliedIssuedByUser, setAppliedIssuedByUser] = useState<string>("");

  useEffect(() => {
    if (!project) return;
    if (appliedFrom === "" && project.plannedStartDate) {
      setFrom(project.plannedStartDate);
      setAppliedFrom(project.plannedStartDate);
    }
    if (appliedTo === "" && project.plannedFinishDate) {
      setTo(project.plannedFinishDate);
      setAppliedTo(project.plannedFinishDate);
    }
  }, [project, appliedFrom, appliedTo]);

  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<MaterialConsumptionForm>(initialFormState);
  const [error, setError] = useState<string | null>(null);

  // Default the enteredByRole field on the form to the caller's role on first render.
  // We don't watch `currentUserRoles` after mount — if the user happens to have
  // multiple eligible roles, the first match wins; they can change it in the dropdown.
  useEffect(() => {
    setFormData((prev) =>
      prev.enteredByRole
        ? prev
        : { ...prev, enteredByRole: pickDefaultEnteredByRole(currentUserRoles) },
    );
  }, [currentUserRoles]);

  const {
    data: listResponse,
    isLoading,
    isError,
    error: queryError,
  } = useQuery({
    queryKey: [
      "material-consumption",
      projectId,
      appliedFrom,
      appliedTo,
      appliedEnteredByRole,
      appliedIssuedByUser,
    ],
    queryFn: () =>
      materialConsumptionApi.list(projectId, {
        from: appliedFrom,
        to: appliedTo,
        enteredByRole: appliedEnteredByRole || undefined,
        issuedByUserId: appliedIssuedByUser || undefined,
      }),
    enabled: !!projectId && !!appliedFrom && !!appliedTo,
  });

  const logs: MaterialConsumptionLogResponse[] = Array.isArray(listResponse?.data)
    ? (listResponse?.data ?? [])
    : [];

  const handleApply = () => {
    setAppliedFrom(from);
    setAppliedTo(to);
    setAppliedEnteredByRole(enteredByRoleFilter);
    setAppliedIssuedByUser(issuedByUserFilter);
  };

  const invalidate = () => {
    queryClient.invalidateQueries({
      queryKey: [
        "material-consumption",
        projectId,
        appliedFrom,
        appliedTo,
        appliedEnteredByRole,
        appliedIssuedByUser,
      ],
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const payload: CreateMaterialConsumptionLogRequest = {
        logDate: formData.logDate,
        materialName: formData.materialName,
        unit: formData.unit,
        openingStock: formData.openingStock,
        received: formData.received,
        consumed: formData.consumed,
        wastagePercent:
          formData.wastagePercent === "" ? null : Number(formData.wastagePercent),
        // Prefer the User FK; keep the legacy free-text as a fallback for migration compatibility.
        issuedByUserId: formData.issuedByUserId || null,
        issuedBy: formData.issuedBy || null,
        receivedByUserId: formData.receivedByUserId || null,
        receivedBy: formData.receivedBy || null,
        enteredByRole: formData.enteredByRole || null,
        activityId: formData.activityId || null,
        remarks: formData.remarks || null,
      };
      await materialConsumptionApi.create(projectId, payload);
      setFormData({
        ...initialFormState,
        enteredByRole: pickDefaultEnteredByRole(currentUserRoles),
      });
      setShowForm(false);
      invalidate();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to create material consumption log"));
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm("Delete this material consumption entry?")) return;
    try {
      await materialConsumptionApi.delete(projectId, id);
      invalidate();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to delete entry"));
    }
  };

  const columns = useMemo<ColumnDef<MaterialConsumptionLogResponse>[]>(() => [
    { accessorKey: "logDate", header: "Date" },
    { accessorKey: "materialName", header: "Material" },
    { accessorKey: "unit", header: "Unit" },
    {
      accessorKey: "openingStock",
      header: "Opening",
      cell: ({ row }) => fmtNum(row.original.openingStock),
    },
    {
      accessorKey: "received",
      header: "Received",
      cell: ({ row }) => fmtNum(row.original.received),
    },
    {
      accessorKey: "consumed",
      header: "Consumed",
      cell: ({ row }) => fmtNum(row.original.consumed),
    },
    {
      accessorKey: "closingStock",
      header: "Closing",
      cell: ({ row }) => fmtNum(row.original.closingStock),
    },
    {
      accessorKey: "wastagePercent",
      header: "Wastage %",
      cell: ({ row }) =>
        row.original.wastagePercent === null || row.original.wastagePercent === undefined
          ? "—"
          : `${row.original.wastagePercent.toFixed(2)}%`,
    },
    {
      accessorKey: "issuedBy",
      header: "Issued By",
      cell: ({ row }) => {
        const r = row.original;
        // Prefer the backend-projected display name; fall back to the local user roster;
        // finally render the legacy free-text. If everything is null show the em dash.
        if (r.issuedByName) return r.issuedByName;
        if (r.issuedByUserId) {
          const u = usersById.get(r.issuedByUserId);
          if (u) return u.name;
        }
        return r.issuedBy ?? "—";
      },
    },
    {
      accessorKey: "receivedBy",
      header: "Received By",
      cell: ({ row }) => {
        const r = row.original;
        if (r.receivedByName) return r.receivedByName;
        if (r.receivedByUserId) {
          const u = usersById.get(r.receivedByUserId);
          if (u) return u.name;
        }
        return r.receivedBy ?? "—";
      },
    },
    {
      accessorKey: "enteredByRole",
      header: "Entered by",
      cell: ({ row }) => <EnteredByChip role={row.original.enteredByRole} />,
    },
    {
      accessorKey: "remarks",
      header: "Remarks",
      cell: ({ row }) => row.original.remarks ?? "—",
    },
    {
      id: "actions",
      header: "Actions",
      cell: ({ row }) => (
        <button
          onClick={() => handleDelete(row.original.id)}
          className="px-2 py-1 bg-danger/10 text-danger ring-1 ring-red-500/20 rounded text-sm hover:bg-danger/20"
        >
          Delete
        </button>
      ),
    },
    // handleDelete closes over stable values (projectId, queryClient, applied
    // filter dates); the only render-derived input is `usersById` which the
    // issuer/receiver columns read from. Re-memo when that map changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [usersById]);

  if (isLoading && logs.length === 0) {
    return <div className="p-6 text-text-muted">Loading material consumption...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Material Consumption"
        description="Daily store-keeper log — opening / received / consumed / closing stock with wastage."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Material Consumption</h1>

        {/* Filter bar — date window + Phase A2 server-side filters on entered-by role + issuer. */}
        <div className="flex flex-wrap items-end gap-3 mb-6">
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">From</label>
            <input
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">To</label>
            <input
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            />
          </div>
          <div>
            <label className="block text-sm font-medium mb-1 text-text-secondary">
              Entered by role
            </label>
            <select
              value={enteredByRoleFilter}
              onChange={(e) => setEnteredByRoleFilter(e.target.value)}
              className="px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
            >
              <option value="">All roles</option>
              {ENTERED_BY_ROLES.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </div>
          <div className="min-w-[220px]">
            <label className="block text-sm font-medium mb-1 text-text-secondary">
              Issued by user
            </label>
            <SearchableSelect
              options={[{ value: "", label: "All issuers" }, ...userOptions]}
              value={issuedByUserFilter}
              onChange={setIssuedByUserFilter}
              placeholder="All issuers"
            />
          </div>
          <button
            onClick={handleApply}
            className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
          >
            Apply
          </button>
        </div>

        <button
          onClick={() => setShowForm(!showForm)}
          className="mb-6 px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
        >
          {showForm ? "Cancel" : "Add Entry"}
        </button>

        {error && <div className="text-danger mb-4">{error}</div>}
        {isError && (
          <div className="text-danger mb-4">
            {getErrorMessage(queryError, "Failed to load material consumption")}
          </div>
        )}

        {showForm && (
          <form
            onSubmit={handleSubmit}
            className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl"
          >
            <div className="mb-4">
              <label className="block text-sm font-medium mb-1 text-text-secondary">
                Activity (optional)
              </label>
              <select
                value={formData.activityId}
                onChange={(e) => setFormData({ ...formData, activityId: e.target.value })}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              >
                <option value="">— No activity —</option>
                {activities.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.code} — {a.name}
                  </option>
                ))}
              </select>
              {formData.activityId === "" && (
                <p className="mt-2 rounded-md bg-yellow-500/10 border border-yellow-500/30 px-3 py-2 text-xs text-yellow-300">
                  Activity-linked entries are included in actual cost. Leave blank only when
                  the consumption isn&apos;t tied to a single activity.
                </p>
              )}
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Date</label>
                <input
                  type="date"
                  value={formData.logDate}
                  onChange={(e) => setFormData({ ...formData, logDate: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Material Name
                </label>
                <input
                  type="text"
                  value={formData.materialName}
                  onChange={(e) => setFormData({ ...formData, materialName: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">Unit</label>
                <select
                  value={formData.unit}
                  onChange={(e) => setFormData({ ...formData, unit: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                >
                  {UNITS.map((u) => (
                    <option key={u} value={u}>
                      {u}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Opening Stock
                </label>
                <input
                  type="number"
                  min={0}
                  step="0.01"
                  value={formData.openingStock}
                  onChange={(e) =>
                    setFormData({ ...formData, openingStock: parseFloat(e.target.value) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Received
                </label>
                <input
                  type="number"
                  min={0}
                  step="0.01"
                  value={formData.received}
                  onChange={(e) =>
                    setFormData({ ...formData, received: parseFloat(e.target.value) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Consumed
                </label>
                <input
                  type="number"
                  min={0}
                  step="0.01"
                  value={formData.consumed}
                  onChange={(e) =>
                    setFormData({ ...formData, consumed: parseFloat(e.target.value) || 0 })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Wastage %
                </label>
                <input
                  type="number"
                  min={0}
                  max={100}
                  step="0.01"
                  value={formData.wastagePercent}
                  onChange={(e) =>
                    setFormData({ ...formData, wastagePercent: e.target.value })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Issued By (user)
                </label>
                {userOptions.length > 0 ? (
                  <SearchableSelect
                    options={[{ value: "", label: "— Select user —" }, ...userOptions]}
                    value={formData.issuedByUserId}
                    onChange={(v) => setFormData({ ...formData, issuedByUserId: v })}
                    placeholder="Search storekeeper / supervisor…"
                  />
                ) : (
                  // Stop-gap when the directory call returns empty (e.g. RBAC blocks the
                  // /v1/users call) — accept a raw UUID so the user can still file the row.
                  <input
                    type="text"
                    value={formData.issuedByUserId}
                    onChange={(e) =>
                      setFormData({ ...formData, issuedByUserId: e.target.value })
                    }
                    placeholder="User UUID"
                    className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  />
                )}
                <input
                  type="text"
                  value={formData.issuedBy}
                  onChange={(e) => setFormData({ ...formData, issuedBy: e.target.value })}
                  placeholder="Or free-text name (legacy)"
                  className="mt-2 w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg text-xs"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Received By (user)
                </label>
                {userOptions.length > 0 ? (
                  <SearchableSelect
                    options={[{ value: "", label: "— Select user —" }, ...userOptions]}
                    value={formData.receivedByUserId}
                    onChange={(v) => setFormData({ ...formData, receivedByUserId: v })}
                    placeholder="Search supervisor / foreman…"
                  />
                ) : (
                  <input
                    type="text"
                    value={formData.receivedByUserId}
                    onChange={(e) =>
                      setFormData({ ...formData, receivedByUserId: e.target.value })
                    }
                    placeholder="User UUID"
                    className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  />
                )}
                <input
                  type="text"
                  value={formData.receivedBy}
                  onChange={(e) => setFormData({ ...formData, receivedBy: e.target.value })}
                  placeholder="Or free-text name (legacy)"
                  className="mt-2 w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg text-xs"
                />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Entered by role
                </label>
                <select
                  value={formData.enteredByRole}
                  onChange={(e) =>
                    setFormData({ ...formData, enteredByRole: e.target.value })
                  }
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                >
                  <option value="">— Auto / unset —</option>
                  {ENTERED_BY_ROLES.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
                <p className="mt-1 text-[11px] text-text-muted">
                  Defaults to your primary role; change only if filing on behalf of someone else.
                </p>
              </div>
              <div className="md:col-span-2">
                <label className="block text-sm font-medium mb-1 text-text-secondary">
                  Remarks
                </label>
                <textarea
                  value={formData.remarks}
                  onChange={(e) => setFormData({ ...formData, remarks: e.target.value })}
                  className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
                  rows={3}
                />
              </div>
            </div>
            <div className="flex gap-2 mt-4">
              <button
                type="submit"
                className="px-4 py-2 bg-green-600 text-text-primary rounded-lg hover:bg-green-600"
              >
                Save Entry
              </button>
              <button
                type="button"
                onClick={() => setShowForm(false)}
                className="px-4 py-2 bg-surface-active/50 text-text-secondary rounded-lg hover:bg-border"
              >
                Cancel
              </button>
            </div>
          </form>
        )}

        <VirtualDataTable
          columns={columns}
          data={logs}
          sortable
          resizable
          isLoading={isLoading}
          emptyMessage="No material consumption entries for this date range."
        />
      </div>
    </div>
  );
}
