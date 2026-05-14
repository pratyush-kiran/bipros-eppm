"use client";

import Link from "next/link";
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { roleAssignmentApi } from "@/lib/api/roleAssignmentApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { CellInput, RowGrid, type RowGridColumn } from "./RowGrid";
import type { DprEquipmentRow } from "@/lib/types/dpr";

const blank = (): DprEquipmentRow => ({
  equipmentType: "",
  fleetNo: null,
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

/**
 * Role-only Equipment DPR grid. Three columns — Equipment (dropdown of activity's
 * planned equipment), Nos, Working Hours. Rate auto-resolves server-side.
 */
export function EquipmentGrid({ projectId, activityId, rows, onChange }: Props) {
  const { data, isLoading } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId!),
    enabled: !!projectId && !!activityId,
  });
  const options = useMemo(() => {
    const list = Array.isArray(data?.data) ? data.data : [];
    return list.filter((a) => a.roleType === "EQUIPMENT");
  }, [data]);

  const update = (idx: number, patch: Partial<DprEquipmentRow>) => {
    const next = rows.slice();
    next[idx] = { ...next[idx], ...patch };
    onChange(next);
  };
  const remove = (idx: number) => onChange(rows.filter((_, i) => i !== idx));
  const add = () => onChange([...rows, blank()]);

  const handlePick = (idx: number, assignmentId: string) => {
    const opt = options.find((o) => o.id === assignmentId);
    if (!opt) return;
    update(idx, {
      equipmentRoleVariantId: opt.variantId ?? null,
      roleId: opt.roleId ?? null,
      equipmentType: opt.roleName ?? "",
    });
  };

  const selectedAssignmentId = (r: DprEquipmentRow): string =>
    options.find(
      (o) =>
        (r.equipmentRoleVariantId && r.equipmentRoleVariantId === o.variantId) ||
        (r.roleId && r.roleId === o.roleId),
    )?.id ?? "";

  const remainingFor = (r: DprEquipmentRow): number | null => {
    const opt = options.find(
      (o) =>
        (r.equipmentRoleVariantId && r.equipmentRoleVariantId === o.variantId) ||
        (r.roleId && r.roleId === o.roleId),
    );
    return opt?.remainingUnits ?? null;
  };

  // SHOW_REMAINING flag — temporarily hidden per user request.
  const SHOW_REMAINING = false;

  const columns: RowGridColumn<DprEquipmentRow>[] = [
    {
      key: "equipment",
      label: "Equipment · Make / Model",
      minWidth: 280,
      grow: 1,
      render: (r, i) => (
        <SearchableSelect
          options={options.map((o) => ({
            value: o.id,
            label: o.variantLabel ? `${o.roleName} — ${o.variantLabel}` : (o.roleName ?? "—"),
          }))}
          value={selectedAssignmentId(r)}
          onChange={(v) => handlePick(i, v)}
          placeholder={isLoading ? "Loading…" : "Pick assigned equipment…"}
          loading={isLoading}
          disabled={!activityId || options.length === 0}
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
    ...(SHOW_REMAINING
      ? ([
          {
            key: "remaining",
            label: "Remaining",
            minWidth: 100,
            align: "right" as const,
            render: (r: DprEquipmentRow) => {
              const rem = remainingFor(r);
              return (
                <span
                  className={`tabular-nums text-xs ${rem === null ? "text-slate" : rem <= 0 ? "text-burgundy" : "text-slate"}`}
                >
                  {rem == null ? "—" : rem}
                </span>
              );
            },
          },
        ] as RowGridColumn<DprEquipmentRow>[])
      : []),
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
  ];

  return (
    <>
      {!activityId && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          Pick an activity above to choose its planned equipment.
        </div>
      )}
      {activityId && !isLoading && options.length === 0 && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          No equipment planned for this activity yet.{" "}
          <Link
            href={`/projects/${projectId}/activities/${activityId}`}
            className="font-semibold text-gold-deep underline"
          >
            Open activity
          </Link>{" "}
          and add equipment demand first.
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
            ? options.length === 0
              ? "No equipment planned for this activity."
              : "Click Add equipment to record a deployed unit."
            : "Pick an activity first."
        }
        addLabel="Add equipment"
      />
    </>
  );
}
