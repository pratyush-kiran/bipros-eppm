"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Pencil, Plus, Trash2, X } from "lucide-react";
import { resourceRoleApi, type ResourceRole } from "@/lib/api/resourceRoleApi";
import {
  roleRateApi,
  type EquipmentRoleVariant,
  type ManpowerRoleRate,
  type MaterialRoleVariant,
} from "@/lib/api/roleRateApi";
import {
  roleAssignmentApi,
  type RoleAssignmentResponse,
} from "@/lib/api/roleAssignmentApi";

interface Props {
  projectId: string;
  activityId: string;
  onChanged?: () => void;
  // When true, every add/edit/delete control is disabled. Backend also rejects with ACTIVITY_LOCKED.
  locked?: boolean;
}

/**
 * Three-section role-based demand editor that mirrors the legacy mockup:
 * Manpower / Equipment / Material — each with its own cascade picker
 * (Role → variant → quantity) and a table of already-added demand rows.
 *
 * Backed by:
 *   GET  /v1/projects/{p}/activities/{a}/role-assignments
 *   POST /v1/projects/{p}/role-assignments
 *   DELETE /v1/role-assignments/{id}
 */
export function RoleDemandSections({
  projectId,
  activityId,
  onChanged,
  locked = false,
}: Props) {
  const qc = useQueryClient();

  const { data: rolesResp } = useQuery({
    queryKey: ["resource-roles", "all"],
    queryFn: () => resourceRoleApi.list(),
  });
  const roles = useMemo<ResourceRole[]>(
    () => (Array.isArray(rolesResp?.data) ? rolesResp.data : []),
    [rolesResp],
  );

  const { data: assignmentsResp, refetch } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId),
  });
  const assignments = useMemo<RoleAssignmentResponse[]>(
    () => (Array.isArray(assignmentsResp?.data) ? assignmentsResp.data : []),
    [assignmentsResp],
  );

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["role-assignments", projectId, activityId] });
    void refetch();
    onChanged?.();
  };

  const manpowerRoles = useMemo(
    () =>
      roles.filter((r) => {
        const c = r.resourceTypeCode?.toUpperCase();
        return c === "LABOR" || c === "MANPOWER";
      }),
    [roles],
  );
  const equipmentRoles = useMemo(
    () => roles.filter((r) => r.resourceTypeCode?.toUpperCase() === "EQUIPMENT"),
    [roles],
  );
  const materialRoles = useMemo(
    () => roles.filter((r) => r.resourceTypeCode?.toUpperCase() === "MATERIAL"),
    [roles],
  );

  const manpowerAssignments = assignments.filter(
    (a) => a.roleType === "LABOR" || a.roleType === "MANPOWER",
  );
  const equipmentAssignments = assignments.filter((a) => a.roleType === "EQUIPMENT");
  const materialAssignments = assignments.filter((a) => a.roleType === "MATERIAL");

  return (
    <div className="space-y-5">
      {locked && (
        <p className="rounded-md border border-border bg-surface-hover px-3 py-2 text-xs text-text-muted">
          Activity is locked — resource plan is frozen and can no longer be edited.
        </p>
      )}
      <ManpowerSection
        projectId={projectId}
        activityId={activityId}
        roles={manpowerRoles}
        rows={manpowerAssignments}
        onChanged={refresh}
        locked={locked}
      />
      <EquipmentSection
        projectId={projectId}
        activityId={activityId}
        roles={equipmentRoles}
        rows={equipmentAssignments}
        onChanged={refresh}
        locked={locked}
      />
      <MaterialSection
        projectId={projectId}
        activityId={activityId}
        roles={materialRoles}
        rows={materialAssignments}
        onChanged={refresh}
        locked={locked}
      />
    </div>
  );
}

// =============================================================================
// Manpower
// =============================================================================

interface ManpowerSectionProps {
  projectId: string;
  activityId: string;
  roles: ResourceRole[];
  rows: RoleAssignmentResponse[];
  onChanged: () => void;
  locked: boolean;
}

function ManpowerSection({
  projectId,
  activityId,
  roles,
  rows,
  onChanged,
  locked,
}: ManpowerSectionProps) {
  const [roleId, setRoleId] = useState("");
  const [variantId, setVariantId] = useState("");
  const [headcount, setHeadcount] = useState<number>(1);
  const [error, setError] = useState<string | null>(null);

  const { data: variantsResp } = useQuery({
    queryKey: ["manpower-rates-for-role", roleId],
    queryFn: () => roleRateApi.listManpowerForRole(roleId),
    enabled: !!roleId,
  });
  const variants = useMemo<ManpowerRoleRate[]>(
    () => (Array.isArray(variantsResp?.data) ? variantsResp.data : []),
    [variantsResp],
  );
  useEffect(() => setVariantId(""), [roleId]);

  const selected = variants.find((v) => v.id === variantId);
  const effectiveDuration = 1;
  const plannedUnits = headcount * effectiveDuration;
  const plannedCost = selected ? plannedUnits * selected.rate : 0;

  const create = useMutation({
    mutationFn: () =>
      roleAssignmentApi.create(projectId, {
        activityId,
        roleId,
        manpowerRoleRateId: variantId,
        headcount,
      }),
    onSuccess: () => {
      setRoleId("");
      setVariantId("");
      setHeadcount(1);
      setError(null);
      onChanged();
    },
    onError: (e: unknown) =>
      setError(e instanceof Error ? e.message : "Failed to add manpower"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => roleAssignmentApi.delete(id),
    onSuccess: () => onChanged(),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Failed to remove row"),
  });

  const update = useMutation({
    mutationFn: ({
      row,
      value,
    }: {
      row: RoleAssignmentResponse;
      value: number;
    }) =>
      roleAssignmentApi.update(row.id, {
        activityId,
        roleId: row.roleId!,
        manpowerRoleRateId: row.variantId ?? undefined,
        headcount: value,
      }),
    onSuccess: () => onChanged(),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Failed to update row"),
  });

  return (
    <section className="rounded-md border border-border bg-surface p-3">
      <h4 className="mb-2 text-sm font-semibold">Manpower Requirements</h4>
      {error && <div className="mb-2 text-xs text-danger">{error}</div>}
      <div className="grid grid-cols-[1.4fr_1.4fr_0.7fr_auto] gap-2 items-end">
        <label className="text-xs">
          <span className="text-text-muted">Role</span>
          <select
            value={roleId}
            onChange={(e) => setRoleId(e.target.value)}
            disabled={locked}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          >
            <option value="">— pick role —</option>
            {roles.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs">
          <span className="text-text-muted">Category / Grade</span>
          <select
            value={variantId}
            onChange={(e) => setVariantId(e.target.value)}
            disabled={locked || !roleId}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          >
            <option value="">— pick variant —</option>
            {variants.map((v) => (
              <option key={v.id} value={v.id}>
                {v.categoryName} / {v.gradeName} — ₹{v.rate}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs">
          <span className="text-text-muted">Nos</span>
          <input
            type="number"
            min={1}
            value={headcount}
            onChange={(e) => setHeadcount(parseInt(e.target.value) || 1)}
            disabled={locked}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          />
        </label>
        <button
          disabled={locked || !roleId || !variantId || !headcount || create.isPending}
          onClick={() => create.mutate()}
          className="inline-flex items-center gap-1 rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
        >
          <Plus className="h-3.5 w-3.5" /> Add
        </button>
      </div>

      {selected && (
        <div className="mt-2 text-xs text-text-muted">
          Planned: <b>{plannedUnits}</b> · Cost: <b>₹{plannedCost.toFixed(2)}</b>
        </div>
      )}

      <DemandTable
        columns={["Role", "Category / Grade", "Nos", "Planned Cost"]}
        rows={rows}
        cells={(a) => [
          a.roleName ?? "—",
          a.variantLabel ?? "—",
          a.headcount ?? a.plannedUnits ?? "—",
          a.plannedCost != null ? `₹${a.plannedCost.toFixed(2)}` : "—",
        ]}
        editCellIndex={2}
        editValueOf={(a) => a.headcount ?? a.plannedUnits ?? 0}
        onEditSave={(row, value) => update.mutate({ row, value })}
        onDelete={(id) => remove.mutate(id)}
        locked={locked}
      />
    </section>
  );
}

// =============================================================================
// Equipment
// =============================================================================

interface EquipmentSectionProps {
  projectId: string;
  activityId: string;
  roles: ResourceRole[];
  rows: RoleAssignmentResponse[];
  onChanged: () => void;
  locked: boolean;
}

function EquipmentSection({
  projectId,
  activityId,
  roles,
  rows,
  onChanged,
  locked,
}: EquipmentSectionProps) {
  const [roleId, setRoleId] = useState("");
  const [variantId, setVariantId] = useState("");
  const [headcount, setHeadcount] = useState<number>(1);
  const [error, setError] = useState<string | null>(null);

  const { data: variantsResp } = useQuery({
    queryKey: ["equipment-variants-for-role", roleId],
    queryFn: () => roleRateApi.listEquipmentForRole(roleId),
    enabled: !!roleId,
  });
  const variants = useMemo<EquipmentRoleVariant[]>(
    () => (Array.isArray(variantsResp?.data) ? variantsResp.data : []),
    [variantsResp],
  );
  useEffect(() => setVariantId(""), [roleId]);

  const selected = variants.find((v) => v.id === variantId);
  const effectiveDuration = 1;
  const plannedUnits = headcount * effectiveDuration;
  const plannedCost = selected ? plannedUnits * selected.rate : 0;

  const create = useMutation({
    mutationFn: () =>
      roleAssignmentApi.create(projectId, {
        activityId,
        roleId,
        equipmentRoleVariantId: variantId,
        headcount,
      }),
    onSuccess: () => {
      setRoleId("");
      setVariantId("");
      setHeadcount(1);
      setError(null);
      onChanged();
    },
    onError: (e: unknown) =>
      setError(e instanceof Error ? e.message : "Failed to add equipment"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => roleAssignmentApi.delete(id),
    onSuccess: () => onChanged(),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Failed to remove row"),
  });

  const update = useMutation({
    mutationFn: ({
      row,
      value,
    }: {
      row: RoleAssignmentResponse;
      value: number;
    }) =>
      roleAssignmentApi.update(row.id, {
        activityId,
        roleId: row.roleId!,
        equipmentRoleVariantId: row.variantId ?? undefined,
        headcount: value,
      }),
    onSuccess: () => onChanged(),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Failed to update row"),
  });

  return (
    <section className="rounded-md border border-border bg-surface p-3">
      <h4 className="mb-2 text-sm font-semibold">Equipment Requirements</h4>
      {error && <div className="mb-2 text-xs text-danger">{error}</div>}
      <div className="grid grid-cols-[1.4fr_1.4fr_0.7fr_auto] gap-2 items-end">
        <label className="text-xs">
          <span className="text-text-muted">Equipment</span>
          <select
            value={roleId}
            onChange={(e) => setRoleId(e.target.value)}
            disabled={locked}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          >
            <option value="">— pick equipment —</option>
            {roles.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs">
          <span className="text-text-muted">Variant (Make / Model)</span>
          <select
            value={variantId}
            onChange={(e) => setVariantId(e.target.value)}
            disabled={locked || !roleId}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          >
            <option value="">— pick variant —</option>
            {variants.map((v) => (
              <option key={v.id} value={v.id}>
                {v.make} / {v.model} — ₹{v.rate}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs">
          <span className="text-text-muted">Nos</span>
          <input
            type="number"
            min={1}
            value={headcount}
            onChange={(e) => setHeadcount(parseInt(e.target.value) || 1)}
            disabled={locked}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          />
        </label>
        <button
          disabled={locked || !roleId || !variantId || !headcount || create.isPending}
          onClick={() => create.mutate()}
          className="inline-flex items-center gap-1 rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
        >
          <Plus className="h-3.5 w-3.5" /> Add
        </button>
      </div>

      {selected && (
        <div className="mt-2 text-xs text-text-muted">
          Planned: <b>{plannedUnits}</b> · Cost: <b>₹{plannedCost.toFixed(2)}</b>
        </div>
      )}

      <DemandTable
        columns={["Equipment", "Variant", "Nos", "Planned Cost"]}
        rows={rows}
        cells={(a) => [
          a.roleName ?? "—",
          a.variantLabel ?? "—",
          a.headcount ?? a.plannedUnits ?? "—",
          a.plannedCost != null ? `₹${a.plannedCost.toFixed(2)}` : "—",
        ]}
        editCellIndex={2}
        editValueOf={(a) => a.headcount ?? a.plannedUnits ?? 0}
        onEditSave={(row, value) => update.mutate({ row, value })}
        onDelete={(id) => remove.mutate(id)}
        locked={locked}
      />
    </section>
  );
}

// =============================================================================
// Material
// =============================================================================

interface MaterialSectionProps {
  projectId: string;
  activityId: string;
  roles: ResourceRole[];
  rows: RoleAssignmentResponse[];
  onChanged: () => void;
  locked: boolean;
}

function MaterialSection({
  projectId,
  activityId,
  roles,
  rows,
  onChanged,
  locked,
}: MaterialSectionProps) {
  const [roleId, setRoleId] = useState("");
  const [variantId, setVariantId] = useState("");
  const [quantity, setQuantity] = useState<number>(0);
  const [error, setError] = useState<string | null>(null);

  const { data: variantsResp } = useQuery({
    queryKey: ["material-variants-for-role", roleId],
    queryFn: () => roleRateApi.listMaterialForRole(roleId),
    enabled: !!roleId,
  });
  const variants = useMemo<MaterialRoleVariant[]>(
    () => (Array.isArray(variantsResp?.data) ? variantsResp.data : []),
    [variantsResp],
  );
  useEffect(() => setVariantId(""), [roleId]);

  const selected = variants.find((v) => v.id === variantId);
  const plannedCost = selected ? quantity * selected.rate : 0;

  const create = useMutation({
    mutationFn: () =>
      roleAssignmentApi.create(projectId, {
        activityId,
        roleId,
        materialRoleVariantId: variantId,
        quantity,
      }),
    onSuccess: () => {
      setRoleId("");
      setVariantId("");
      setQuantity(0);
      setError(null);
      onChanged();
    },
    onError: (e: unknown) =>
      setError(e instanceof Error ? e.message : "Failed to add material"),
  });

  const remove = useMutation({
    mutationFn: (id: string) => roleAssignmentApi.delete(id),
    onSuccess: () => onChanged(),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Failed to remove row"),
  });

  const update = useMutation({
    mutationFn: ({ row, value }: { row: RoleAssignmentResponse; value: number }) =>
      roleAssignmentApi.update(row.id, {
        activityId,
        roleId: row.roleId!,
        materialRoleVariantId: row.variantId ?? undefined,
        quantity: value,
      }),
    onSuccess: () => onChanged(),
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Failed to update row"),
  });

  return (
    <section className="rounded-md border border-border bg-surface p-3">
      <h4 className="mb-2 text-sm font-semibold">Material Requirements</h4>
      {error && <div className="mb-2 text-xs text-danger">{error}</div>}
      <div className="grid grid-cols-[1.4fr_1.4fr_1fr_auto] gap-2 items-end">
        <label className="text-xs">
          <span className="text-text-muted">Material</span>
          <select
            value={roleId}
            onChange={(e) => setRoleId(e.target.value)}
            disabled={locked}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          >
            <option value="">— pick material —</option>
            {roles.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs">
          <span className="text-text-muted">Spec / Grade</span>
          <select
            value={variantId}
            onChange={(e) => setVariantId(e.target.value)}
            disabled={locked || !roleId}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          >
            <option value="">— pick variant —</option>
            {variants.map((v) => (
              <option key={v.id} value={v.id}>
                {v.specGrade} — {v.unit} @ ₹{v.rate}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs">
          <span className="text-text-muted">
            Quantity {selected ? `(${selected.unit})` : ""}
          </span>
          <input
            type="number"
            step="0.01"
            min={0}
            value={quantity}
            onChange={(e) => setQuantity(parseFloat(e.target.value) || 0)}
            disabled={locked}
            className="mt-1 w-full rounded-md border border-border bg-surface-hover px-2 py-1.5 text-xs disabled:opacity-50"
          />
        </label>
        <button
          disabled={locked || !roleId || !variantId || !quantity || create.isPending}
          onClick={() => create.mutate()}
          className="inline-flex items-center gap-1 rounded-md bg-accent px-3 py-1.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:opacity-50"
        >
          <Plus className="h-3.5 w-3.5" /> Add
        </button>
      </div>

      {selected && (
        <div className="mt-2 text-xs text-text-muted">
          Planned: <b>{quantity}</b> {selected.unit} · Cost: <b>₹{plannedCost.toFixed(2)}</b>
        </div>
      )}

      <DemandTable
        columns={["Material", "Spec / Grade", "Quantity", "Planned Cost"]}
        rows={rows}
        cells={(a) => [
          a.roleName ?? "—",
          a.variantLabel ?? "—",
          a.quantity ?? "—",
          a.plannedCost != null ? `₹${a.plannedCost.toFixed(2)}` : "—",
        ]}
        editCellIndex={2}
        editValueOf={(a) => Number(a.quantity ?? 0)}
        onEditSave={(row, value) => update.mutate({ row, value })}
        onDelete={(id) => remove.mutate(id)}
        locked={locked}
      />
    </section>
  );
}

// =============================================================================
// Shared table
// =============================================================================

interface DemandTableProps {
  columns: string[];
  rows: RoleAssignmentResponse[];
  cells: (row: RoleAssignmentResponse) => (string | number)[];
  onDelete: (id: string) => void;
  // Inline edit: when all three are set, the cell at editCellIndex flips to a number input
  // when the pencil is clicked. Save calls onEditSave(row, newValue, secondaryValue?); Cancel restores.
  editCellIndex?: number;
  editValueOf?: (row: RoleAssignmentResponse) => number;
  // Optional second editable cell (e.g. Duration column on Manpower/Equipment rows).
  // When provided, that cell also flips to a number input during edit mode and its
  // value is passed as the third arg to onEditSave.
  secondaryEditCellIndex?: number;
  secondaryEditValueOf?: (row: RoleAssignmentResponse) => number;
  onEditSave?: (
    row: RoleAssignmentResponse,
    value: number,
    secondaryValue?: number,
  ) => void;
  locked?: boolean;
}

function DemandTable({
  columns,
  rows,
  cells,
  onDelete,
  editCellIndex,
  editValueOf,
  secondaryEditCellIndex,
  secondaryEditValueOf,
  onEditSave,
  locked = false,
}: DemandTableProps) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<number>(0);
  const [secondaryDraft, setSecondaryDraft] = useState<number>(0);

  const editable = editCellIndex != null && editValueOf != null && onEditSave != null;
  const secondaryEditable =
    editable && secondaryEditCellIndex != null && secondaryEditValueOf != null;

  if (rows.length === 0) {
    return (
      <p className="mt-3 text-xs text-text-muted">No rows added yet.</p>
    );
  }
  return (
    <div className="mt-3 overflow-x-auto">
      <table className="w-full text-xs">
        <thead className="border-b border-border text-text-muted">
          <tr>
            {columns.map((c) => (
              <th key={c} className="py-1.5 pr-2 text-left font-medium">
                {c}
              </th>
            ))}
            <th className="py-1.5 text-right" />
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const isEditing = editable && editingId === r.id;
            const cellValues = cells(r);
            return (
              <tr key={r.id} className="border-b border-border/40">
                {cellValues.map((c, i) => (
                  <td key={i} className="py-1.5 pr-2">
                    {isEditing && i === editCellIndex ? (
                      <input
                        type="number"
                        step="0.01"
                        min={0}
                        value={draft}
                        onChange={(e) => setDraft(parseFloat(e.target.value) || 0)}
                        className="w-20 rounded-md border border-border bg-surface-hover px-2 py-0.5 text-xs"
                        autoFocus
                      />
                    ) : isEditing && secondaryEditable && i === secondaryEditCellIndex ? (
                      <input
                        type="number"
                        step="0.01"
                        min={0}
                        value={secondaryDraft}
                        onChange={(e) => setSecondaryDraft(parseFloat(e.target.value) || 0)}
                        className="w-20 rounded-md border border-border bg-surface-hover px-2 py-0.5 text-xs"
                      />
                    ) : (
                      c
                    )}
                  </td>
                ))}
                <td className="py-1.5 text-right">
                  {isEditing ? (
                    <span className="inline-flex items-center gap-2">
                      <button
                        onClick={() => {
                          onEditSave!(
                            r,
                            draft,
                            secondaryEditable ? secondaryDraft : undefined,
                          );
                          setEditingId(null);
                        }}
                        className="text-accent hover:opacity-80"
                        title="Save"
                      >
                        <Check className="h-3.5 w-3.5 inline" />
                      </button>
                      <button
                        onClick={() => setEditingId(null)}
                        className="text-text-muted hover:opacity-80"
                        title="Cancel"
                      >
                        <X className="h-3.5 w-3.5 inline" />
                      </button>
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-2">
                      {editable && (
                        <button
                          onClick={() => {
                            setEditingId(r.id);
                            setDraft(editValueOf!(r));
                            if (secondaryEditable) {
                              setSecondaryDraft(secondaryEditValueOf!(r));
                            }
                          }}
                          disabled={locked}
                          className="text-text-secondary hover:text-text-primary disabled:opacity-40"
                          title={locked ? "Activity is locked" : "Edit"}
                        >
                          <Pencil className="h-3.5 w-3.5 inline" />
                        </button>
                      )}
                      <button
                        onClick={() => onDelete(r.id)}
                        disabled={locked}
                        className="text-danger hover:opacity-80 disabled:opacity-40"
                        title={locked ? "Activity is locked" : "Remove"}
                      >
                        <Trash2 className="h-3.5 w-3.5 inline" />
                      </button>
                    </span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
