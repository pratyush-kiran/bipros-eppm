"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { resourceRoleApi } from "@/lib/api/resourceRoleApi";
import { roleRateApi } from "@/lib/api/roleRateApi";
import { roleAssignmentApi, type RoleAssignmentRequest } from "@/lib/api/roleAssignmentApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

type RoleType = "MANPOWER" | "EQUIPMENT" | "MATERIAL";

interface Props {
  projectId: string;
  activityId: string;
  onSaved?: () => void;
  onCancel?: () => void;
}

export function RoleDemandForm({ projectId, activityId, onSaved, onCancel }: Props) {
  const qc = useQueryClient();
  const { money } = useProjectCurrency();
  const [type, setType] = useState<RoleType>("MANPOWER");
  const [roleId, setRoleId] = useState<string>("");
  const [variantId, setVariantId] = useState<string>("");
  const [headcount, setHeadcount] = useState<number>(1);
  const [duration, setDuration] = useState<number>(1);
  const [quantity, setQuantity] = useState<number>(0);
  const [error, setError] = useState<string | null>(null);

  const { data: rolesResp } = useQuery({
    queryKey: ["resource-roles", "all"],
    queryFn: () => resourceRoleApi.list(),
  });
  const roles = useMemo(() => {
    const list = rolesResp?.data ?? [];
    return list.filter((r) => {
      const code = r.resourceTypeCode?.toUpperCase();
      if (type === "MANPOWER") return code === "LABOR" || code === "MANPOWER";
      return code === type;
    });
  }, [rolesResp, type]);

  const variantsKey: [string, string, string] = [type, roleId, "variants"];
  const { data: variants = [] } = useQuery({
    queryKey: variantsKey,
    queryFn: async () => {
      if (!roleId) return [];
      if (type === "MANPOWER")
        return (await roleRateApi.listManpowerForRole(roleId)).data ?? [];
      if (type === "EQUIPMENT")
        return (await roleRateApi.listEquipmentForRole(roleId)).data ?? [];
      return (await roleRateApi.listMaterialForRole(roleId)).data ?? [];
    },
    enabled: !!roleId,
  });

  const selectedVariant = (variants as Array<{ id: string; rate: number; unit: string }>).find(
    (v) => v.id === variantId,
  );

  const plannedUnits =
    type === "MATERIAL" ? quantity : headcount * duration;
  const plannedCost = selectedVariant ? plannedUnits * selectedVariant.rate : 0;

  // Reset variant when role changes
  useEffect(() => setVariantId(""), [roleId]);

  const create = useMutation({
    mutationFn: (req: RoleAssignmentRequest) =>
      roleAssignmentApi.create(projectId, req),
    onSuccess: () => {
      qc.invalidateQueries({
        queryKey: ["role-assignments", projectId, activityId],
      });
      onSaved?.();
    },
    onError: (e: unknown) =>
      setError(e instanceof Error ? e.message : "Failed to create assignment"),
  });

  const submit = () => {
    if (!roleId || !variantId) {
      setError("Role and variant required");
      return;
    }
    const req: RoleAssignmentRequest = {
      activityId,
      roleId,
      manpowerRoleRateId: type === "MANPOWER" ? variantId : undefined,
      equipmentRoleVariantId: type === "EQUIPMENT" ? variantId : undefined,
      materialRoleVariantId: type === "MATERIAL" ? variantId : undefined,
      headcount: type === "MATERIAL" ? undefined : headcount,
      duration: type === "MATERIAL" ? undefined : duration,
      quantity: type === "MATERIAL" ? quantity : undefined,
    };
    create.mutate(req);
  };

  return (
    <div className="space-y-4 rounded-md border border-neutral-700 p-4">
      <h3 className="text-lg font-semibold">Add Role Demand</h3>
      {error && <div className="text-sm text-red-400">{error}</div>}

      <div className="flex gap-2">
        {(["MANPOWER", "EQUIPMENT", "MATERIAL"] as RoleType[]).map((t) => (
          <button
            key={t}
            onClick={() => {
              setType(t);
              setRoleId("");
              setVariantId("");
            }}
            className={`rounded-md px-3 py-1.5 text-sm ${
              type === t ? "bg-amber-500 text-black" : "border border-neutral-700"
            }`}
          >
            {t}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <label className="block text-sm">
          <span className="text-neutral-400">Role *</span>
          <select
            className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
            value={roleId}
            onChange={(e) => setRoleId(e.target.value)}
          >
            <option value="">— pick a role —</option>
            {roles.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>

        <label className="block text-sm">
          <span className="text-neutral-400">Variant *</span>
          <select
            className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
            value={variantId}
            onChange={(e) => setVariantId(e.target.value)}
            disabled={!roleId}
          >
            <option value="">— pick a variant —</option>
            {variants.map((v) => {
              const variant = v as unknown as Record<string, unknown> & {
                id: string;
                rate: number;
                unit: string;
              };
              const label =
                type === "MANPOWER"
                  ? `${variant.categoryName} / ${variant.gradeName} — ${variant.unit} @ ${money(variant.rate)}`
                  : type === "EQUIPMENT"
                  ? `${variant.make} / ${variant.model} — ${variant.unit} @ ${money(variant.rate)}`
                  : `${variant.specGrade} — ${variant.unit} @ ${money(variant.rate)}`;
              return (
                <option key={variant.id} value={variant.id}>
                  {label}
                </option>
              );
            })}
          </select>
        </label>

        {type !== "MATERIAL" ? (
          <>
            <label className="block text-sm">
              <span className="text-neutral-400">Headcount *</span>
              <input
                type="number"
                min={1}
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={headcount}
                onChange={(e) => setHeadcount(parseInt(e.target.value) || 1)}
              />
            </label>
            <label className="block text-sm">
              <span className="text-neutral-400">
                Duration ({selectedVariant?.unit ?? "unit"}) *
              </span>
              <input
                type="number"
                step="0.5"
                min={0}
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={duration}
                onChange={(e) => setDuration(parseFloat(e.target.value) || 0)}
              />
            </label>
          </>
        ) : (
          <label className="block text-sm col-span-2">
            <span className="text-neutral-400">
              Quantity ({selectedVariant?.unit ?? "unit"}) *
            </span>
            <input
              type="number"
              step="0.01"
              min={0}
              className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
              value={quantity}
              onChange={(e) => setQuantity(parseFloat(e.target.value) || 0)}
            />
          </label>
        )}
      </div>

      {selectedVariant && (
        <div className="rounded-md bg-neutral-800 p-3 text-sm">
          <div>
            Planned Units = <b>{plannedUnits}</b> {selectedVariant.unit}
          </div>
          <div>
            Planned Cost = <b>{money(plannedCost)}</b> (units × {money(selectedVariant.rate)})
          </div>
        </div>
      )}

      <div className="flex gap-2">
        <button
          onClick={submit}
          disabled={!roleId || !variantId || create.isPending}
          className="rounded-md bg-green-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
        >
          Save
        </button>
        {onCancel && (
          <button
            onClick={onCancel}
            className="rounded-md border border-neutral-700 px-3 py-1.5 text-sm"
          >
            Cancel
          </button>
        )}
      </div>
    </div>
  );
}
