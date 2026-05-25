"use client";

import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { resourceTypeApi } from "@/lib/api/resourceTypeApi";
import { manpowerCategoryMasterApi } from "@/lib/api/manpowerCategoryMasterApi";
import { gradeMasterApi } from "@/lib/api/gradeMasterApi";
import {
  roleRateApi,
  type ManpowerVariantInput,
  type EquipmentVariantInput,
  type MaterialVariantInput,
  type RoleWithVariantsRequest,
} from "@/lib/api/roleRateApi";
import { getErrorMessage } from "@/lib/utils/error";
import { rateUnitOptionsWithFallback } from "@/lib/constants/resourceUnits";

const UNITS_MP = ["Day", "Hour"];
const UNITS_EQ = ["Day", "Hour"];
const UNITS_MAT = ["MT", "Bag", "Cum", "Litre", "Nos", "Kg"];

/** Canonical 4 manpower categories used by the rate book. Anything else in the
 *  manpower_category_master table (e.g. role-like entries) is filtered out. */
const ALLOWED_MANPOWER_CATEGORIES = new Set([
  "skilled",
  "semi-skilled",
  "unskilled",
  "staff",
]);

interface ManpowerRow extends ManpowerVariantInput {
  _rowKey: string;
}
interface EquipmentRow extends EquipmentVariantInput {
  _rowKey: string;
}
interface MaterialRow extends MaterialVariantInput {
  _rowKey: string;
}

interface Props {
  editingRoleId?: string | null;
  onSaved: () => void;
  onCancel: () => void;
}

export function RoleWithVariantsEditor({ editingRoleId, onSaved, onCancel }: Props) {
  // Role fields
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [resourceTypeId, setResourceTypeId] = useState("");
  const [sortOrder, setSortOrder] = useState("");
  const [active, setActive] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Variant rows
  const [manpower, setManpower] = useState<ManpowerRow[]>([]);
  const [equipment, setEquipment] = useState<EquipmentRow[]>([]);
  const [material, setMaterial] = useState<MaterialRow[]>([]);

  // Reference data
  const { data: typesData } = useQuery({
    queryKey: ["resource-types"],
    queryFn: () => resourceTypeApi.list(),
  });
  const types = useMemo(
    () => (Array.isArray(typesData?.data) ? typesData.data : []),
    [typesData],
  );

  const { data: catData } = useQuery({
    queryKey: ["manpower-categories"],
    queryFn: () => manpowerCategoryMasterApi.list(),
  });
  const categories = useMemo(() => {
    const raw = Array.isArray(catData?.data) ? catData.data : [];
    return raw.filter((c) => ALLOWED_MANPOWER_CATEGORIES.has(c.name.trim().toLowerCase()));
  }, [catData]);

  const { data: gradeData } = useQuery({
    queryKey: ["grades"],
    queryFn: () => gradeMasterApi.list(),
  });
  const grades = useMemo(
    () => (Array.isArray(gradeData?.data) ? gradeData.data : []),
    [gradeData],
  );

  // Default resource type on create
  useEffect(() => {
    if (!resourceTypeId && types.length > 0) {
      const def = types.find((t) => t.code === "LABOR" || t.code === "MANPOWER") ?? types[0];
      setResourceTypeId(def.id);
    }
  }, [types, resourceTypeId]);

  // Load existing role + variants when editing
  const { data: existingResp } = useQuery({
    queryKey: ["role-with-variants", editingRoleId],
    queryFn: () => roleRateApi.getWithVariants(editingRoleId!),
    enabled: !!editingRoleId,
  });
  useEffect(() => {
    if (!existingResp?.data) return;
    const r = existingResp.data.role;
    setCode(r.code);
    setName(r.name);
    setDescription(r.description ?? "");
    setResourceTypeId(r.resourceTypeId);
    setSortOrder(r.sortOrder == null ? "" : String(r.sortOrder));
    setActive(r.active);
    setManpower(
      existingResp.data.manpowerVariants.map((v) => ({
        _rowKey: v.id,
        id: v.id,
        categoryId: v.categoryId,
        gradeId: v.gradeId,
        unit: v.unit,
        rate: v.rate,
        active: v.active,
      })),
    );
    setEquipment(
      existingResp.data.equipmentVariants.map((v) => ({
        _rowKey: v.id,
        id: v.id,
        make: v.make,
        model: v.model,
        unit: v.unit,
        rate: v.rate,
        active: v.active,
      })),
    );
    setMaterial(
      existingResp.data.materialVariants.map((v) => ({
        _rowKey: v.id,
        id: v.id,
        specGrade: v.specGrade,
        unit: v.unit,
        rate: v.rate,
        active: v.active,
      })),
    );
  }, [existingResp]);

  const selectedType = types.find((t) => t.id === resourceTypeId);
  const typeCode = selectedType?.code?.toUpperCase() ?? "";
  const isManpower = typeCode === "LABOR" || typeCode === "MANPOWER";
  const isEquipment = typeCode === "EQUIPMENT";
  const isMaterial = typeCode === "MATERIAL";

  const newKey = () => `new-${Date.now()}-${Math.random().toString(36).slice(2)}`;

  const addManpower = () =>
    setManpower((rows) => [
      ...rows,
      { _rowKey: newKey(), categoryId: "", gradeId: "", unit: "Day", rate: 0, active: true },
    ]);
  const addEquipment = () =>
    setEquipment((rows) => [
      ...rows,
      { _rowKey: newKey(), make: "", model: "", unit: "Day", rate: 0, active: true },
    ]);
  const addMaterial = () =>
    setMaterial((rows) => [
      ...rows,
      { _rowKey: newKey(), specGrade: "", unit: "MT", rate: 0, active: true },
    ]);

  const updateMP = (key: string, patch: Partial<ManpowerRow>) =>
    setManpower((rows) => rows.map((r) => (r._rowKey === key ? { ...r, ...patch } : r)));
  const removeMP = (key: string) => setManpower((rows) => rows.filter((r) => r._rowKey !== key));

  const updateEQ = (key: string, patch: Partial<EquipmentRow>) =>
    setEquipment((rows) => rows.map((r) => (r._rowKey === key ? { ...r, ...patch } : r)));
  const removeEQ = (key: string) => setEquipment((rows) => rows.filter((r) => r._rowKey !== key));

  const updateMT = (key: string, patch: Partial<MaterialRow>) =>
    setMaterial((rows) => rows.map((r) => (r._rowKey === key ? { ...r, ...patch } : r)));
  const removeMT = (key: string) => setMaterial((rows) => rows.filter((r) => r._rowKey !== key));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!code.trim() || !name.trim()) {
      setError("Code and Name are required.");
      return;
    }
    if (!resourceTypeId) {
      setError("Pick a Resource Type.");
      return;
    }
    // Validate variant rows
    if (isManpower) {
      for (const r of manpower) {
        if (!r.categoryId || !r.gradeId || !r.unit || !r.rate || r.rate <= 0) {
          setError("Every manpower variant needs category, grade, unit, and rate.");
          return;
        }
      }
    } else if (isEquipment) {
      for (const r of equipment) {
        if (!r.make?.trim() || !r.model?.trim() || !r.unit || !r.rate || r.rate <= 0) {
          setError("Every equipment variant needs make, model, unit, and rate.");
          return;
        }
      }
    } else if (isMaterial) {
      for (const r of material) {
        if (!r.specGrade?.trim() || !r.unit || !r.rate || r.rate <= 0) {
          setError("Every material variant needs spec/grade, unit, and rate.");
          return;
        }
      }
    }

    const payload: RoleWithVariantsRequest = {
      code: code.trim(),
      name: name.trim(),
      description: description.trim() || null,
      resourceTypeId,
      sortOrder: sortOrder.trim() === "" ? null : parseInt(sortOrder, 10),
      active,
      manpowerVariants: isManpower
        ? manpower.map(({ _rowKey: _, ...rest }) => rest)
        : [],
      equipmentVariants: isEquipment
        ? equipment.map(({ _rowKey: _, ...rest }) => rest)
        : [],
      materialVariants: isMaterial
        ? material.map(({ _rowKey: _, ...rest }) => rest)
        : [],
    };

    setSaving(true);
    try {
      if (editingRoleId) {
        await roleRateApi.updateWithVariants(editingRoleId, payload);
      } else {
        await roleRateApi.createWithVariants(payload);
      }
      onSaved();
    } catch (err: unknown) {
      setError(getErrorMessage(err, "Failed to save role"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-5">
      {error && (
        <div className="rounded-md border border-burgundy/40 bg-burgundy/10 px-3 py-2 text-sm text-burgundy">
          {error}
        </div>
      )}

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <label className="block text-sm">
          <span className="text-slate">Code *</span>
          <input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            className="mt-1 w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1.5"
          />
        </label>
        <label className="block text-sm">
          <span className="text-slate">Name *</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="mt-1 w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1.5"
          />
        </label>
        <label className="block text-sm md:col-span-2">
          <span className="text-slate">Description</span>
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="mt-1 w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1.5"
          />
        </label>
        <label className="block text-sm">
          <span className="text-slate">Resource Type *</span>
          <select
            value={resourceTypeId}
            onChange={(e) => setResourceTypeId(e.target.value)}
            disabled={!!editingRoleId}
            className="mt-1 w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1.5 disabled:opacity-60"
          >
            {types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name} ({t.code})
              </option>
            ))}
          </select>
        </label>
        <label className="block text-sm">
          <span className="text-slate">Sort Order</span>
          <input
            value={sortOrder}
            onChange={(e) => setSortOrder(e.target.value)}
            className="mt-1 w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1.5"
          />
        </label>
        <label className="block text-sm">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          <span className="ml-2">Active</span>
        </label>
      </div>

      {isManpower && (
        <section className="rounded-md border border-hairline p-3">
          <div className="mb-2 flex items-center justify-between">
            <h4 className="text-sm font-semibold">Configure Role Rates</h4>
            <button
              type="button"
              onClick={addManpower}
              className="inline-flex items-center gap-1 rounded-md bg-gold-deep px-2.5 py-1 text-xs font-medium text-black hover:bg-gold"
            >
              <Plus className="h-3.5 w-3.5" /> Add Rate
            </button>
          </div>
          {manpower.length === 0 ? (
            <p className="text-xs text-slate">No rates defined yet.</p>
          ) : (
            <table className="w-full text-xs">
              <thead className="border-b border-hairline text-slate">
                <tr>
                  <th className="py-1.5 text-left">Category</th>
                  <th className="py-1.5 text-left">Grade</th>
                  <th className="py-1.5 text-left">Unit</th>
                  <th className="py-1.5 text-right">Rate</th>
                  <th className="py-1.5 text-center">Active</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {manpower.map((r) => (
                  <tr key={r._rowKey} className="border-b border-hairline/40">
                    <td className="py-1.5 pr-2">
                      <select
                        value={r.categoryId}
                        onChange={(e) => updateMP(r._rowKey, { categoryId: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      >
                        <option value="">— pick —</option>
                        {categories.map((c) => (
                          <option key={c.id} value={c.id}>
                            {c.name}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="py-1.5 pr-2">
                      <select
                        value={r.gradeId}
                        onChange={(e) => updateMP(r._rowKey, { gradeId: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      >
                        <option value="">— pick —</option>
                        {grades.map((g) => (
                          <option key={g.id} value={g.id}>
                            {g.name}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="py-1.5 pr-2">
                      <select
                        value={r.unit}
                        onChange={(e) => updateMP(r._rowKey, { unit: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      >
                        {/* Use the shared fallback helper so legacy values like
                            "Hr" (used by seeded role rates) surface in the dropdown
                            instead of silently falling back to the first option. */}
                        {rateUnitOptionsWithFallback(r.unit).map((u) => (
                          <option key={u} value={u}>
                            {u}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="py-1.5 pr-2">
                      <input
                        type="number"
                        step="0.01"
                        value={r.rate}
                        onChange={(e) =>
                          updateMP(r._rowKey, { rate: parseFloat(e.target.value) || 0 })
                        }
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1 text-right"
                      />
                    </td>
                    <td className="py-1.5 text-center">
                      <input
                        type="checkbox"
                        checked={r.active ?? true}
                        onChange={(e) => updateMP(r._rowKey, { active: e.target.checked })}
                      />
                    </td>
                    <td className="py-1.5 text-right">
                      <button
                        type="button"
                        onClick={() => removeMP(r._rowKey)}
                        className="text-burgundy hover:opacity-80"
                      >
                        <Trash2 className="inline h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      {isEquipment && (
        <section className="rounded-md border border-hairline p-3">
          <div className="mb-2 flex items-center justify-between">
            <h4 className="text-sm font-semibold">Equipment Variants</h4>
            <button
              type="button"
              onClick={addEquipment}
              className="inline-flex items-center gap-1 rounded-md bg-gold-deep px-2.5 py-1 text-xs font-medium text-black hover:bg-gold"
            >
              <Plus className="h-3.5 w-3.5" /> Add Variant
            </button>
          </div>
          {equipment.length === 0 ? (
            <p className="text-xs text-slate">No variants defined yet.</p>
          ) : (
            <table className="w-full text-xs">
              <thead className="border-b border-hairline text-slate">
                <tr>
                  <th className="py-1.5 text-left">Make</th>
                  <th className="py-1.5 text-left">Model</th>
                  <th className="py-1.5 text-left">Unit</th>
                  <th className="py-1.5 text-right">Rate</th>
                  <th className="py-1.5 text-center">Active</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {equipment.map((r) => (
                  <tr key={r._rowKey} className="border-b border-hairline/40">
                    <td className="py-1.5 pr-2">
                      <input
                        value={r.make}
                        onChange={(e) => updateEQ(r._rowKey, { make: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      />
                    </td>
                    <td className="py-1.5 pr-2">
                      <input
                        value={r.model}
                        onChange={(e) => updateEQ(r._rowKey, { model: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      />
                    </td>
                    <td className="py-1.5 pr-2">
                      <select
                        value={r.unit}
                        onChange={(e) => updateEQ(r._rowKey, { unit: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      >
                        {/* See note in the manpower section above — fallback helper
                            ensures seeded "Hr" values display correctly. */}
                        {rateUnitOptionsWithFallback(r.unit).map((u) => (
                          <option key={u} value={u}>
                            {u}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="py-1.5 pr-2">
                      <input
                        type="number"
                        step="0.01"
                        value={r.rate}
                        onChange={(e) =>
                          updateEQ(r._rowKey, { rate: parseFloat(e.target.value) || 0 })
                        }
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1 text-right"
                      />
                    </td>
                    <td className="py-1.5 text-center">
                      <input
                        type="checkbox"
                        checked={r.active ?? true}
                        onChange={(e) => updateEQ(r._rowKey, { active: e.target.checked })}
                      />
                    </td>
                    <td className="py-1.5 text-right">
                      <button
                        type="button"
                        onClick={() => removeEQ(r._rowKey)}
                        className="text-burgundy hover:opacity-80"
                      >
                        <Trash2 className="inline h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      {isMaterial && (
        <section className="rounded-md border border-hairline p-3">
          <div className="mb-2 flex items-center justify-between">
            <h4 className="text-sm font-semibold">Material Variants</h4>
            <button
              type="button"
              onClick={addMaterial}
              className="inline-flex items-center gap-1 rounded-md bg-gold-deep px-2.5 py-1 text-xs font-medium text-black hover:bg-gold"
            >
              <Plus className="h-3.5 w-3.5" /> Add Variant
            </button>
          </div>
          {material.length === 0 ? (
            <p className="text-xs text-slate">No variants defined yet.</p>
          ) : (
            <table className="w-full text-xs">
              <thead className="border-b border-hairline text-slate">
                <tr>
                  <th className="py-1.5 text-left">Spec / Grade</th>
                  <th className="py-1.5 text-left">Unit</th>
                  <th className="py-1.5 text-right">Rate</th>
                  <th className="py-1.5 text-center">Active</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {material.map((r) => (
                  <tr key={r._rowKey} className="border-b border-hairline/40">
                    <td className="py-1.5 pr-2">
                      <input
                        value={r.specGrade}
                        onChange={(e) => updateMT(r._rowKey, { specGrade: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      />
                    </td>
                    <td className="py-1.5 pr-2">
                      <select
                        value={r.unit}
                        onChange={(e) => updateMT(r._rowKey, { unit: e.target.value })}
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1"
                      >
                        {UNITS_MAT.map((u) => (
                          <option key={u} value={u}>
                            {u}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="py-1.5 pr-2">
                      <input
                        type="number"
                        step="0.01"
                        value={r.rate}
                        onChange={(e) =>
                          updateMT(r._rowKey, { rate: parseFloat(e.target.value) || 0 })
                        }
                        className="w-full rounded-md border border-hairline bg-ivory/40 px-2 py-1 text-right"
                      />
                    </td>
                    <td className="py-1.5 text-center">
                      <input
                        type="checkbox"
                        checked={r.active ?? true}
                        onChange={(e) => updateMT(r._rowKey, { active: e.target.checked })}
                      />
                    </td>
                    <td className="py-1.5 text-right">
                      <button
                        type="button"
                        onClick={() => removeMT(r._rowKey)}
                        className="text-burgundy hover:opacity-80"
                      >
                        <Trash2 className="inline h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={saving}
          className="rounded-md bg-emerald px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          {saving ? "Saving…" : editingRoleId ? "Save changes" : "Create"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-md border border-hairline px-3 py-1.5 text-sm"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
