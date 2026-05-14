"use client";

import Link from "next/link";
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { roleAssignmentApi } from "@/lib/api/roleAssignmentApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { CellInput, RowGrid, type RowGridColumn } from "./RowGrid";
import type { DprManpowerRow } from "@/lib/types/dpr";

const blank = (): DprManpowerRow => ({
  trade: "",
  nos: null,
  manpowerRoleRateId: null,
  roleId: null,
});

interface Props {
  projectId: string;
  activityId: string | null;
  reportDate: string;
  rows: DprManpowerRow[];
  onChange: (rows: DprManpowerRow[]) => void;
}

/**
 * Role-only Manpower DPR grid. Two columns only — Role (dropdown of the
 * activity's planned manpower assignments) and Nos. Rate and cost are resolved
 * server-side from the variant on save.
 */
export function ManpowerGrid({ projectId, activityId, rows, onChange }: Props) {
  const { data, isLoading } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId!),
    enabled: !!projectId && !!activityId,
  });

  const options = useMemo(() => {
    const list = Array.isArray(data?.data) ? data.data : [];
    return list.filter((a) => a.roleType === "MANPOWER" || a.roleType === "LABOR");
  }, [data]);

  const update = (idx: number, patch: Partial<DprManpowerRow>) => {
    const next = rows.slice();
    next[idx] = { ...next[idx], ...patch };
    onChange(next);
  };
  const remove = (idx: number) => onChange(rows.filter((_, i) => i !== idx));
  const add = () => onChange([...rows, blank()]);

  // We use roleId as the dropdown value (one assignment per role is the common case;
  // if a role has multiple variant rows we use the first match — the variant FK is
  // captured automatically). For a finer-grained pick we'd switch to assignmentId.
  const handlePick = (idx: number, assignmentId: string) => {
    const opt = options.find((o) => o.id === assignmentId);
    if (!opt) return;
    update(idx, {
      manpowerRoleRateId: opt.variantId ?? null,
      roleId: opt.roleId ?? null,
      trade: opt.roleName ?? "",
    });
  };

  const selectedAssignmentId = (r: DprManpowerRow): string => {
    return options.find(
      (o) =>
        (r.manpowerRoleRateId && r.manpowerRoleRateId === o.variantId) ||
        (r.roleId && r.roleId === o.roleId),
    )?.id ?? "";
  };

  const remainingFor = (r: DprManpowerRow): number | null => {
    const opt = options.find(
      (o) =>
        (r.manpowerRoleRateId && r.manpowerRoleRateId === o.variantId) ||
        (r.roleId && r.roleId === o.roleId),
    );
    return opt?.remainingUnits ?? null;
  };

  // SHOW_REMAINING flag — temporarily hidden per user request. Keep the column
  // definition intact below so it can be flipped back on by setting this to true.
  const SHOW_REMAINING = false;

  const columns: RowGridColumn<DprManpowerRow>[] = [
    {
      key: "role",
      label: "Role · Category / Grade",
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
          placeholder={isLoading ? "Loading…" : "Pick assigned role…"}
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
            render: (r: DprManpowerRow) => {
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
        ] as RowGridColumn<DprManpowerRow>[])
      : []),
    {
      key: "nos",
      label: "Nos",
      minWidth: 90,
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
  ];

  return (
    <>
      {!activityId && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          Pick an activity above to choose its planned manpower.
        </div>
      )}
      {activityId && !isLoading && options.length === 0 && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          No manpower planned for this activity yet.{" "}
          <Link
            href={`/projects/${projectId}/activities/${activityId}`}
            className="font-semibold text-gold-deep underline"
          >
            Open activity
          </Link>{" "}
          and add manpower demand first.
        </div>
      )}
      <RowGrid
        title="Manpower"
        rows={rows}
        columns={columns}
        onAdd={add}
        onChange={update}
        onRemove={remove}
        emptyHint={
          activityId
            ? options.length === 0
              ? "No manpower planned for this activity."
              : "Click Add manpower to record a deployed role."
            : "Pick an activity first."
        }
        addLabel="Add manpower"
      />
    </>
  );
}
