"use client";

import { useMemo, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import {
  subContractorMasterApi,
  type SubContractorMasterWithMappingsRequest,
} from "@/lib/api/subContractorMasterApi";
import WorkTypeCombobox from "@/components/sub-contractor/WorkTypeCombobox";
import { getErrorMessage } from "@/lib/utils/error";
import { unitOptionsWithFallback, STANDARD_UNITS } from "@/lib/constants/units";
import { scWorkTypeApi } from "@/lib/api/subContractorWorkTypeApi";

interface Props {
  editingId?: string | null;
  onSaved: () => void;
  onCancel: () => void;
}

interface MappingFormRow {
  _key: string;
  scWorkTypeId: string;
  workTypeName: string;
  unit: string;
  ratePerUnit: string;
  outputPerDay: string;
}

const inputCls =
  "w-full rounded-[10px] border border-hairline bg-paper px-3 py-2 text-sm text-charcoal placeholder:text-ash focus:border-gold focus:outline-none focus:shadow-[0_0_0_3px_rgba(212,175,55,0.18)]";

export default function SubContractorWithMappingsEditor({
  editingId,
  onSaved,
  onCancel,
}: Props) {
  const queryClient = useQueryClient();

  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [location, setLocation] = useState("");
  const [primaryContactName, setPrimaryContactName] = useState("");
  const [primaryContactNumber, setPrimaryContactNumber] = useState("");
  const [remarks, setRemarks] = useState("");
  const [active, setActive] = useState(true);
  const [mappings, setMappings] = useState<MappingFormRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Load existing data when editing
  useState(() => {
    if (editingId) {
      subContractorMasterApi
        .get(editingId)
        .then((resp) => {
          const m = resp.data;
          if (m) {
            setCode(m.code);
            setName(m.name);
            setLocation(m.location ?? "");
            setPrimaryContactName(m.primaryContactName ?? "");
            setPrimaryContactNumber(m.primaryContactNumber ?? "");
            setRemarks(m.remarks ?? "");
            setActive(m.active);
            setMappings(
              (m.workActivityMappings ?? []).map((row) => ({
                _key: row.id ?? crypto.randomUUID(),
                scWorkTypeId: row.scWorkTypeId ?? "",
                workTypeName: row.workTypeName ?? "",
                unit: row.unit ?? "",
                ratePerUnit: row.ratePerUnit?.toString() ?? "",
                outputPerDay: row.outputPerDay?.toString() ?? "",
              }))
            );
          }
        })
        .catch((err) => {
          setError(getErrorMessage(err, "Failed to load sub-contractor"));
        });
    }
  });

  const addMapping = () => {
    setMappings((prev) => [
      ...prev,
      {
        _key: crypto.randomUUID(),
        scWorkTypeId: "",
        workTypeName: "",
        unit: "",
        ratePerUnit: "",
        outputPerDay: "",
      },
    ]);
  };

  const removeMapping = (key: string) => {
    setMappings((prev) => prev.filter((r) => r._key !== key));
  };

  const updateMapping = (
    key: string,
    patch: Partial<MappingFormRow>
  ) => {
    setMappings((prev) =>
      prev.map((r) => (r._key === key ? { ...r, ...patch } : r))
    );
  };

  const duplicateWorkTypes = useMemo(() => {
    const seen = new Set<string>();
    return mappings
      .filter((r) => r.scWorkTypeId)
      .some((r) => {
        const dup = seen.has(r.scWorkTypeId);
        seen.add(r.scWorkTypeId);
        return dup;
      });
  }, [mappings]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!code.trim() || !name.trim()) {
      setError("Code and Name are required");
      return;
    }

    setSaving(true);
    try {
      // Resolve any mapping rows that have a typed name but no ID yet.
      // We do this at save time so the unit dropdown value is guaranteed to be set.
      const resolvedMappings = await Promise.all(
        mappings
          .filter((r) => r.scWorkTypeId || r.workTypeName.trim())
          .map(async (r) => {
            if (r.scWorkTypeId) return r; // already resolved
            const resp = await scWorkTypeApi.findOrCreate(r.workTypeName.trim(), r.unit || null);
            return { ...r, scWorkTypeId: resp.data?.id ?? "", workTypeName: resp.data?.name ?? r.workTypeName };
          })
      );

      const validMappings = resolvedMappings.filter((r) => r.scWorkTypeId);

      const payload: SubContractorMasterWithMappingsRequest = {
        code: code.trim(),
        name: name.trim(),
        location: location.trim() || null,
        primaryContactName: primaryContactName.trim() || null,
        primaryContactNumber: primaryContactNumber.trim() || null,
        remarks: remarks.trim() || null,
        active,
        workActivityMappings: validMappings.map((r) => ({
          scWorkTypeId: r.scWorkTypeId,
          workTypeName: r.workTypeName || undefined,
          unit: r.unit || undefined,
          ratePerUnit: r.ratePerUnit === "" ? null : Number(r.ratePerUnit),
          outputPerDay: r.outputPerDay === "" ? null : Number(r.outputPerDay),
        })),
      };

      if (editingId) {
        await subContractorMasterApi.update(editingId, payload);
      } else {
        await subContractorMasterApi.create(payload);
      }
      queryClient.invalidateQueries({ queryKey: ["sub-contractors"] });
      onSaved();
    } catch (err: unknown) {
      setError(
        getErrorMessage(
          err,
          editingId
            ? "Failed to update sub-contractor"
            : "Failed to create sub-contractor"
        )
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      {error && (
        <div className="rounded-xl border border-burgundy/30 bg-burgundy/10 p-4 text-sm text-burgundy">
          {error}
        </div>
      )}

      {/* Basic Details */}
      <section>
        <h3 className="text-sm font-semibold uppercase tracking-wide text-slate mb-3">
          Basic Details
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <FormField label="Code *">
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className={inputCls}
              required
            />
          </FormField>
          <FormField label="Name *">
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className={inputCls}
              required
            />
          </FormField>
          <FormField label="Location">
            <input
              type="text"
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              className={inputCls}
            />
          </FormField>
          <FormField label="Primary Contact Name">
            <input
              type="text"
              value={primaryContactName}
              onChange={(e) => setPrimaryContactName(e.target.value)}
              className={inputCls}
            />
          </FormField>
          <FormField label="Primary Contact Number">
            <input
              type="text"
              value={primaryContactNumber}
              onChange={(e) => setPrimaryContactNumber(e.target.value)}
              className={inputCls}
            />
          </FormField>
          <div className="flex items-end">
            <label className="flex items-center gap-2 text-sm text-text-secondary">
              <input
                type="checkbox"
                checked={active}
                onChange={(e) => setActive(e.target.checked)}
              />
              Active
            </label>
          </div>
          <FormField label="Remarks" className="md:col-span-2">
            <textarea
              value={remarks}
              onChange={(e) => setRemarks(e.target.value)}
              className={inputCls}
              rows={2}
            />
          </FormField>
        </div>
      </section>

      {/* Work Activity Mappings */}
      <section>
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold uppercase tracking-wide text-slate">
            Work Type Mappings
          </h3>
          <button
            type="button"
            onClick={addMapping}
            className="inline-flex h-8 items-center gap-1.5 rounded-[8px] border border-hairline bg-paper px-3 text-xs font-semibold text-slate hover:border-gold hover:text-gold-deep transition-colors"
          >
            <Plus size={12} strokeWidth={2.5} />
            Add Mapping
          </button>
        </div>

        {duplicateWorkTypes && (
          <div className="mb-3 rounded-lg border border-warning/30 bg-warning/10 p-3 text-xs text-warning">
            Duplicate work types detected. Each work type should only appear once per
            sub-contractor.
          </div>
        )}

        {mappings.length === 0 ? (
          <p className="text-sm text-slate italic">
            No mappings yet. Click &ldquo;Add Mapping&rdquo; to link work types.
          </p>
        ) : (
          <div className="space-y-3">
            {mappings.map((row) => (
              <MappingRowEditor
                key={row._key}
                row={row}
                onChange={(patch) => updateMapping(row._key, patch)}
                onRemove={() => removeMapping(row._key)}
              />
            ))}
          </div>
        )}
      </section>

      <div className="flex gap-2 pt-2">
        <button
          type="submit"
          disabled={saving}
          className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-gold px-4 text-sm font-semibold text-paper transition-all duration-200 hover:bg-gold-deep disabled:opacity-50"
        >
          {saving
            ? "Saving…"
            : editingId
              ? "Save Changes"
              : "Create Sub-Contractor"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="inline-flex h-9 items-center gap-1.5 rounded-[10px] border border-hairline bg-paper px-4 text-sm font-semibold text-slate hover:border-gold hover:text-gold-deep"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

/* ------------------------------------------------------------------ */
/* Sub-components                                                      */
/* ------------------------------------------------------------------ */

function MappingRowEditor({
  row,
  onChange,
  onRemove,
}: {
  row: MappingFormRow;
  onChange: (patch: Partial<MappingFormRow>) => void;
  onRemove: () => void;
}) {
  const handleSelect = (id: string, name: string, defaultUnit?: string | null) => {
    // Only auto-fill unit when the user hasn't chosen one yet — never overwrite an existing selection.
    onChange({ scWorkTypeId: id, workTypeName: name, ...(row.unit ? {} : { unit: defaultUnit ?? "" }) });
  };

  return (
    <div className="grid grid-cols-1 md:grid-cols-12 gap-3 items-end rounded-lg border border-hairline bg-paper p-3">
      <div className="md:col-span-4">
        <label className="block text-xs font-medium mb-1 text-text-secondary">
          Work Type
        </label>
        <WorkTypeCombobox
          value={row.scWorkTypeId}
          displayValue={row.workTypeName}
          currentUnit={row.unit}
          onChange={handleSelect}
          placeholder="Type work type…"
        />
      </div>

      <div className="md:col-span-2">
        <label className="block text-xs font-medium mb-1 text-text-secondary">
          Unit
        </label>
        <select
          value={row.unit}
          onChange={(e) => onChange({ unit: e.target.value })}
          className={inputCls}
        >
          <option value="">— select —</option>
          {unitOptionsWithFallback(row.unit).map((u) => (
            <option key={u} value={u}>
              {u}
              {!(STANDARD_UNITS as readonly string[]).includes(u) ? " (legacy)" : ""}
            </option>
          ))}
        </select>
      </div>

      <div className="md:col-span-2">
        <label className="block text-xs font-medium mb-1 text-text-secondary">
          Rate / Unit
        </label>
        <input
          type="number"
          step="0.01"
          value={row.ratePerUnit}
          onChange={(e) => onChange({ ratePerUnit: e.target.value })}
          className={inputCls}
          placeholder="0.00"
        />
      </div>

      <div className="md:col-span-3">
        <label className="block text-xs font-medium mb-1 text-text-secondary">
          Output / Day
        </label>
        <input
          type="number"
          step="0.01"
          value={row.outputPerDay}
          onChange={(e) => onChange({ outputPerDay: e.target.value })}
          className={inputCls}
          placeholder="e.g. 50"
        />
      </div>

      <div className="md:col-span-1 flex justify-end">
        <button
          type="button"
          onClick={onRemove}
          className="rounded-md p-1.5 text-slate transition-colors hover:bg-parchment hover:text-burgundy"
          aria-label="Remove mapping"
        >
          <Trash2 size={14} strokeWidth={1.5} />
        </button>
      </div>
    </div>
  );
}

function FormField({
  label,
  children,
  className = "",
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={className}>
      <label className="block text-sm font-medium mb-1 text-text-secondary">
        {label}
      </label>
      {children}
    </div>
  );
}
