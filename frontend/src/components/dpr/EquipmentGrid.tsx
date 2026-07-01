"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { roleAssignmentApi } from "@/lib/api/roleAssignmentApi";
import { roleRateApi } from "@/lib/api/roleRateApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { CellInput, CellSelect, RowGrid, type RowGridColumn } from "./RowGrid";
import type { DprEquipmentRow } from "@/lib/types/dpr";

const blank = (): DprEquipmentRow => ({
  equipmentType: "",
  fleetNo: null,
  ownership: null,
  shift: "DAY",
  nos: null,
  workingHours: null,
  equipmentRoleVariantId: null,
  roleId: null,
});

interface Props {
  projectId: string;
  activityId: string | null;
  reportDate: string;
  rows: DprEquipmentRow[];
  onChange: (rows: DprEquipmentRow[]) => void;
}

const optKey = (roleId: string | null | undefined, variantId: string | null | undefined) =>
  `${roleId ?? ""}::${variantId ?? ""}`;

/**
 * Role-only Equipment DPR grid. Dropdown shows the activity's planned equipment at the top
 * (suffixed " (planned)") and the rest of the rate book below — supervisor can report any
 * equipment they actually used. Backend creates a phantom assignment for unplanned picks.
 */
export function EquipmentGrid({ projectId, activityId, rows, onChange }: Props) {
  const { money } = useProjectCurrency();
  const { data: plannedResp, isLoading: plannedLoading } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId!),
    enabled: !!projectId && !!activityId,
  });
  const { data: bookResp, isLoading: bookLoading } = useQuery({
    queryKey: ["role-rates", "equipment"],
    queryFn: () => roleRateApi.listAllEquipment(),
  });
  const isLoading = plannedLoading || bookLoading;

  const options = useMemo(() => {
    // Exclude phantom rows (created by ensureAssignmentsExist for unplanned DPR variants).
    const planned = (Array.isArray(plannedResp?.data) ? plannedResp.data : []).filter(
      (a) => a.roleType === "EQUIPMENT" && !a.unplanned,
    );
    const seen = new Set<string>();
    const out: { value: string; label: string; roleId: string; variantId: string; equipmentType: string; rate: number | null }[] = [];
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
        equipmentType: p.roleName ?? "",
        rate: p.effectiveRate ?? null,
      });
    }
    const book = Array.isArray(bookResp?.data) ? bookResp.data : [];
    for (const v of book) {
      const k = optKey(v.roleId, v.id);
      if (seen.has(k)) continue;
      seen.add(k);
      const variantLabel = `${v.make} / ${v.model} — ${v.unit} @ ${money(v.rate)}`;
      out.push({
        value: k,
        label: `${v.roleName ?? "—"} — ${variantLabel}`,
        roleId: v.roleId,
        variantId: v.id,
        equipmentType: v.roleName ?? "",
        rate: v.rate,
      });
    }
    return out;
  }, [plannedResp, bookResp]);

  const update = (idx: number, patch: Partial<DprEquipmentRow>) => {
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
      equipmentRoleVariantId: opt.variantId,
      roleId: opt.roleId,
      equipmentType: opt.equipmentType,
      unitRate: opt.rate ?? null,
    });
  };

  const selectedKey = (r: DprEquipmentRow): string =>
    r.roleId && r.equipmentRoleVariantId ? optKey(r.roleId, r.equipmentRoleVariantId) : "";

  const columns: RowGridColumn<DprEquipmentRow>[] = [
    {
      key: "equipment",
      label: "Equipment · Make / Model",
      minWidth: 280,
      grow: 1,
      render: (r, i) => (
        <SearchableSelect
          options={options.map((o) => ({ value: o.value, label: o.label }))}
          value={selectedKey(r)}
          onChange={(v) => handlePick(i, v)}
          placeholder={isLoading ? "Loading…" : "Pick equipment…"}
          loading={isLoading}
          disabled={!activityId}
        />
      ),
    },
    {
      key: "fleetNo",
      label: "Fleet #",
      minWidth: 120,
      render: (r, _i, u) => (
        <CellInput
          value={r.fleetNo ?? ""}
          onChange={(v) => u({ fleetNo: v.trim() === "" ? null : v })}
        />
      ),
    },
    {
      key: "ownership",
      label: "Ownership",
      minWidth: 130,
      render: (r, _i, u) => (
        <CellSelect
          value={r.ownership ?? ""}
          onChange={(v) =>
            u({
              ownership:
                v === "OWNED" || v === "HIRED" || v === "SUBCONTRACTOR" ? v : null,
            })
          }
          options={[
            { value: "", label: "—" },
            { value: "OWNED", label: "Owned" },
            { value: "HIRED", label: "Hired" },
            { value: "SUBCONTRACTOR", label: "Subcontractor" },
          ]}
        />
      ),
    },
    {
      key: "nos",
      label: "Nos",
      minWidth: 80,
      align: "right",
      render: (r, _i, u) => (
        <CellInput
          type="number"
          min="0"
          value={r.nos}
          onChange={(v) => u({ nos: v === "" ? null : Number(v) })}
        />
      ),
    },
    {
      key: "workingHours",
      label: "Hrs",
      minWidth: 100,
      align: "right",
      render: (r, _i, u) => (
        <CellInput
          type="number"
          step="0.25"
          min="0"
          value={r.workingHours}
          onChange={(v) => u({ workingHours: v === "" ? null : Number(v) })}
        />
      ),
    },
    {
      key: "shift",
      label: "Shift",
      minWidth: 110,
      render: (r, _i, u) => (
        <CellSelect
          value={r.shift ?? "DAY"}
          onChange={(v) => u({ shift: v === "NIGHT" ? "NIGHT" : "DAY" })}
          options={[
            { value: "DAY", label: "Day" },
            { value: "NIGHT", label: "Night" },
          ]}
        />
      ),
    },
  ];

  return (
    <>
      {!activityId && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          Pick an activity above to choose equipment.
        </div>
      )}
      <RowGrid
        title="Equipment / PMV"
        rows={rows}
        columns={columns}
        onAdd={add}
        onChange={update}
        onRemove={remove}
        emptyHint={
          activityId
            ? "Click Add equipment to record a deployed unit."
            : "Pick an activity first."
        }
        addLabel="Add equipment"
      />
    </>
  );
}
