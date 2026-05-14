"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2, Plus } from "lucide-react";
import {
  roleRateApi,
  type EquipmentRoleVariant,
  type EquipmentRoleVariantRequest,
} from "@/lib/api/roleRateApi";

interface Props {
  roleId: string;
}

const UNITS = ["Day", "Hour"] as const;

export function EquipmentRoleVariantTable({ roleId }: Props) {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<EquipmentRoleVariantRequest>({
    make: "",
    model: "",
    unit: "Day",
    rate: 0,
    active: true,
  });
  const [error, setError] = useState<string | null>(null);

  const { data: variantsResp } = useQuery({
    queryKey: ["equipment-role-variants", roleId],
    queryFn: () => roleRateApi.listEquipmentForRole(roleId),
  });
  const variants = useMemo<EquipmentRoleVariant[]>(
    () => (Array.isArray(variantsResp?.data) ? variantsResp.data : []),
    [variantsResp],
  );

  const create = useMutation({
    mutationFn: (req: EquipmentRoleVariantRequest) =>
      roleRateApi.createEquipmentVariant(roleId, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["equipment-role-variants", roleId] });
      setShowForm(false);
      setForm({ make: "", model: "", unit: "Day", rate: 0, active: true });
      setError(null);
    },
    onError: (e: unknown) =>
      setError(e instanceof Error ? e.message : "Failed to create variant"),
  });
  const remove = useMutation({
    mutationFn: (id: string) => roleRateApi.deleteEquipmentVariant(id),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: ["equipment-role-variants", roleId] }),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">Equipment Variants</h3>
        <button
          onClick={() => setShowForm(true)}
          className="flex items-center gap-1 rounded-md bg-amber-500 px-3 py-1.5 text-sm text-black hover:bg-amber-400"
        >
          <Plus className="h-4 w-4" /> Add Variant
        </button>
      </div>

      {showForm && (
        <div className="rounded-md border border-neutral-700 p-4">
          {error && <div className="mb-2 text-sm text-red-400">{error}</div>}
          <div className="grid grid-cols-2 gap-3">
            <label className="block text-sm">
              <span className="text-neutral-400">Make *</span>
              <input
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.make}
                onChange={(e) => setForm({ ...form, make: e.target.value })}
                placeholder="e.g. Caterpillar"
              />
            </label>
            <label className="block text-sm">
              <span className="text-neutral-400">Model *</span>
              <input
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.model}
                onChange={(e) => setForm({ ...form, model: e.target.value })}
                placeholder="e.g. 320D"
              />
            </label>
            <label className="block text-sm">
              <span className="text-neutral-400">Unit *</span>
              <select
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.unit}
                onChange={(e) => setForm({ ...form, unit: e.target.value })}
              >
                {UNITS.map((u) => (
                  <option key={u} value={u}>
                    {u}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-neutral-400">Rate *</span>
              <input
                type="number"
                step="0.01"
                className="mt-1 w-full rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
                value={form.rate}
                onChange={(e) =>
                  setForm({ ...form, rate: parseFloat(e.target.value) || 0 })
                }
              />
            </label>
          </div>
          <div className="mt-3 flex gap-2">
            <button
              disabled={!form.make || !form.model || !form.rate || create.isPending}
              onClick={() => create.mutate(form)}
              className="rounded-md bg-green-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
            >
              Create
            </button>
            <button
              onClick={() => setShowForm(false)}
              className="rounded-md border border-neutral-700 px-3 py-1.5 text-sm"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      <table className="w-full text-sm">
        <thead className="border-b border-neutral-700 text-neutral-400">
          <tr>
            <th className="py-2 text-left">Make</th>
            <th className="py-2 text-left">Model</th>
            <th className="py-2 text-left">Unit</th>
            <th className="py-2 text-right">Rate</th>
            <th className="py-2 text-center">Active</th>
            <th className="py-2 text-right">Actions</th>
          </tr>
        </thead>
        <tbody>
          {variants.length === 0 && (
            <tr>
              <td colSpan={6} className="py-6 text-center text-neutral-500">
                No variants defined yet
              </td>
            </tr>
          )}
          {variants.map((v: EquipmentRoleVariant) => (
            <tr key={v.id} className="border-b border-neutral-800">
              <td className="py-2">{v.make}</td>
              <td className="py-2">{v.model}</td>
              <td className="py-2">{v.unit}</td>
              <td className="py-2 text-right">{v.rate}</td>
              <td className="py-2 text-center">{v.active ? "✓" : "—"}</td>
              <td className="py-2 text-right">
                <button
                  onClick={() => remove.mutate(v.id)}
                  className="text-red-400 hover:text-red-300"
                >
                  <Trash2 className="inline h-4 w-4" />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
