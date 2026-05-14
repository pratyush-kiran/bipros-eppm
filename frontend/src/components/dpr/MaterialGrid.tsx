"use client";

import Link from "next/link";
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { roleAssignmentApi } from "@/lib/api/roleAssignmentApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
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

/**
 * Role-only Material DPR grid. Two columns — Material (dropdown of activity's
 * planned material) and Quantity.
 */
export function MaterialGrid({ projectId, activityId, rows, onChange }: Props) {
  const { data, isLoading } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId!),
    enabled: !!projectId && !!activityId,
  });
  const options = useMemo(() => {
    const list = Array.isArray(data?.data) ? data.data : [];
    return list.filter((a) => a.roleType === "MATERIAL");
  }, [data]);

  const update = (idx: number, patch: Partial<DprMaterialRow>) => {
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
      materialRoleVariantId: opt.variantId ?? null,
      roleId: opt.roleId ?? null,
      materialName: opt.roleName ?? "",
      unit: opt.unit ?? null,
    });
  };

  const selectedAssignmentId = (r: DprMaterialRow): string =>
    options.find(
      (o) =>
        (r.materialRoleVariantId && r.materialRoleVariantId === o.variantId) ||
        (r.roleId && r.roleId === o.roleId),
    )?.id ?? "";

  const remainingFor = (r: DprMaterialRow): number | null => {
    const opt = options.find(
      (o) =>
        (r.materialRoleVariantId && r.materialRoleVariantId === o.variantId) ||
        (r.roleId && r.roleId === o.roleId),
    );
    return opt?.remainingUnits ?? null;
  };

  // SHOW_REMAINING flag — temporarily hidden per user request.
  const SHOW_REMAINING = false;

  const columns: RowGridColumn<DprMaterialRow>[] = [
    {
      key: "material",
      label: "Material · Spec / Grade",
      minWidth: 320,
      grow: 1,
      render: (r, i) => (
        <SearchableSelect
          options={options.map((o) => ({
            value: o.id,
            label: o.variantLabel ? `${o.roleName} — ${o.variantLabel}` : (o.roleName ?? "—"),
          }))}
          value={selectedAssignmentId(r)}
          onChange={(v) => handlePick(i, v)}
          placeholder={isLoading ? "Loading…" : "Pick assigned material…"}
          loading={isLoading}
          disabled={!activityId || options.length === 0}
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
            render: (r: DprMaterialRow) => {
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
        ] as RowGridColumn<DprMaterialRow>[])
      : []),
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
          Pick an activity above to choose its planned material.
        </div>
      )}
      {activityId && !isLoading && options.length === 0 && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          No material planned for this activity yet.{" "}
          <Link
            href={`/projects/${projectId}/activities/${activityId}`}
            className="font-semibold text-gold-deep underline"
          >
            Open activity
          </Link>{" "}
          and add material demand first.
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
            ? options.length === 0
              ? "No material planned for this activity."
              : "Click Add material to record consumption."
            : "Pick an activity first."
        }
        addLabel="Add material"
      />
    </>
  );
}
