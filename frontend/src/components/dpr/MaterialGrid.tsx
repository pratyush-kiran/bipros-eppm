"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { roleAssignmentApi } from "@/lib/api/roleAssignmentApi";
import { roleRateApi } from "@/lib/api/roleRateApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { CellInput, RowGrid, type RowGridColumn } from "./RowGrid";
import type { DprMaterialRow } from "@/lib/types/dpr";

const blank = (): DprMaterialRow => ({
  materialName: "",
  quantity: null,
  materialRoleVariantId: null,
  roleId: null,
});

interface Props {
  projectId: string;
  activityId: string | null;
  reportDate: string;
  rows: DprMaterialRow[];
  onChange: (rows: DprMaterialRow[]) => void;
}

const optKey = (roleId: string | null | undefined, variantId: string | null | undefined) =>
  `${roleId ?? ""}::${variantId ?? ""}`;

/**
 * Role-only Material DPR grid. Dropdown shows the activity's planned material at the top
 * (suffixed " (planned)") and the rest of the rate book below — supervisor can report any
 * material consumed. Backend creates a phantom assignment for unplanned picks.
 */
export function MaterialGrid({ projectId, activityId, rows, onChange }: Props) {
  const { money } = useProjectCurrency();
  const { data: plannedResp, isLoading: plannedLoading } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId!),
    enabled: !!projectId && !!activityId,
  });
  const { data: bookResp, isLoading: bookLoading } = useQuery({
    queryKey: ["role-rates", "material"],
    queryFn: () => roleRateApi.listAllMaterial(),
  });
  const isLoading = plannedLoading || bookLoading;

  const options = useMemo(() => {
    // Exclude phantom rows (created by ensureAssignmentsExist for unplanned DPR variants).
    const planned = (Array.isArray(plannedResp?.data) ? plannedResp.data : []).filter(
      (a) => a.roleType === "MATERIAL" && !a.unplanned,
    );
    const seen = new Set<string>();
    const out: { value: string; label: string; roleId: string; variantId: string; materialName: string; unit: string | null }[] = [];
    for (const p of planned) {
      if (!p.roleId || !p.variantId) continue;
      const k = optKey(p.roleId, p.variantId);
      if (seen.has(k)) continue;
      seen.add(k);
      out.push({
        value: k,
        label: `${p.roleName ?? "—"}${p.variantLabel ? ` — ${p.variantLabel}` : ""}  (planned)`,
        roleId: p.roleId,
        variantId: p.variantId,
        materialName: p.roleName ?? "",
        unit: p.unit ?? null,
      });
    }
    const book = Array.isArray(bookResp?.data) ? bookResp.data : [];
    for (const v of book) {
      const k = optKey(v.roleId, v.id);
      if (seen.has(k)) continue;
      seen.add(k);
      const variantLabel = `${v.specGrade} — ${v.unit} @ ${money(v.rate)}`;
      out.push({
        value: k,
        label: `${v.roleName ?? "—"} — ${variantLabel}`,
        roleId: v.roleId,
        variantId: v.id,
        materialName: v.roleName ?? "",
        unit: v.unit ?? null,
      });
    }
    return out;
  }, [plannedResp, bookResp]);

  const update = (idx: number, patch: Partial<DprMaterialRow>) => {
    const next = rows.slice();
    next[idx] = { ...next[idx], ...patch };
    onChange(next);
  };
  const remove = (idx: number) => onChange(rows.filter((_, i) => i !== idx));
  const add = () => onChange([...rows, blank()]);

  const handlePick = (idx: number, key: string) => {
    const opt = options.find((o) => o.value === key);
    if (!opt) return;
    update(idx, {
      materialRoleVariantId: opt.variantId,
      roleId: opt.roleId,
      materialName: opt.materialName,
      unit: opt.unit,
    });
  };

  const selectedKey = (r: DprMaterialRow): string =>
    r.roleId && r.materialRoleVariantId ? optKey(r.roleId, r.materialRoleVariantId) : "";

  const columns: RowGridColumn<DprMaterialRow>[] = [
    {
      key: "material",
      label: "Material · Spec / Grade",
      minWidth: 320,
      grow: 1,
      render: (r, i) => (
        <SearchableSelect
          options={options.map((o) => ({ value: o.value, label: o.label }))}
          value={selectedKey(r)}
          onChange={(v) => handlePick(i, v)}
          placeholder={isLoading ? "Loading…" : "Pick material…"}
          loading={isLoading}
          disabled={!activityId}
        />
      ),
    },
    {
      key: "quantity",
      label: "Qty",
      minWidth: 100,
      align: "right",
      render: (r, _i, u) => (
        <CellInput
          type="number"
          step="0.01"
          min="0"
          value={r.quantity}
          onChange={(v) => u({ quantity: v === "" ? null : Number(v) })}
        />
      ),
    },
  ];

  return (
    <>
      {!activityId && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          Pick an activity above to choose material.
        </div>
      )}
      <RowGrid
        title="Material"
        rows={rows}
        columns={columns}
        onAdd={add}
        onChange={update}
        onRemove={remove}
        emptyHint={
          activityId
            ? "Click Add material to record consumption."
            : "Pick an activity first."
        }
        addLabel="Add material"
      />
    </>
  );
}
