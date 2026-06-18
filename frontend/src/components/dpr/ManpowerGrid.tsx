"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { roleAssignmentApi } from "@/lib/api/roleAssignmentApi";
import { roleRateApi } from "@/lib/api/roleRateApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { CellInput, CellSelect, RowGrid, type RowGridColumn } from "./RowGrid";
import type { DprManpowerRow } from "@/lib/types/dpr";

const blank = (): DprManpowerRow => ({
  trade: "",
  shift: "DAY",
  nos: null,
  workingHours: null,
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

// Synthetic dropdown key: "{roleId}::{variantId}". Lets unplanned rate-book entries
// (which have no ResourceAssignment id) carry a meaningful value through the picker.
const optKey = (roleId: string | null | undefined, variantId: string | null | undefined) =>
  `${roleId ?? ""}::${variantId ?? ""}`;

/**
 * Role-only Manpower DPR grid. The dropdown shows the activity's planned manpower at the
 * top (suffixed " (planned)") and the rest of the rate book below — the supervisor can
 * report any role+variant they actually used, planned or not. Backend creates a phantom
 * ResourceAssignment for unplanned picks so they roll up into the activity Resource Plan.
 */
export function ManpowerGrid({ projectId, activityId, rows, onChange }: Props) {
  const { money } = useProjectCurrency();
  const { data: plannedResp, isLoading: plannedLoading } = useQuery({
    queryKey: ["role-assignments", projectId, activityId],
    queryFn: () => roleAssignmentApi.listForActivity(projectId, activityId!),
    enabled: !!projectId && !!activityId,
  });
  const { data: bookResp, isLoading: bookLoading } = useQuery({
    queryKey: ["role-rates", "manpower"],
    queryFn: () => roleRateApi.listAllManpower(),
  });
  const isLoading = plannedLoading || bookLoading;

  // Planned items first (in their existing order), then the rest of the rate book —
  // dedup by (roleId, variantId) so a planned item doesn't appear twice.
  const options = useMemo(() => {
    // Exclude phantom rows (created by ensureAssignmentsExist for unplanned DPR variants) —
    // they're not "planned" and shouldn't carry the (planned) suffix when re-opening a DPR.
    // They still surface via the rate-book bucket below.
    const planned = (Array.isArray(plannedResp?.data) ? plannedResp.data : []).filter(
      (a) => (a.roleType === "MANPOWER" || a.roleType === "LABOR") && !a.unplanned,
    );
    const seen = new Set<string>();
    const out: { value: string; label: string; roleId: string; variantId: string; trade: string }[] = [];
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
        trade: p.roleName ?? "",
      });
    }
    const book = Array.isArray(bookResp?.data) ? bookResp.data : [];
    for (const v of book) {
      const k = optKey(v.roleId, v.id);
      if (seen.has(k)) continue;
      seen.add(k);
      const variantLabel = `${v.categoryName ?? "?"} / ${v.gradeName ?? "?"} — ${v.unit} @ ${money(v.rate)}`;
      out.push({
        value: k,
        label: `${v.roleName ?? "—"} — ${variantLabel}`,
        roleId: v.roleId,
        variantId: v.id,
        trade: v.roleName ?? "",
      });
    }
    return out;
  }, [plannedResp, bookResp]);

  const update = (idx: number, patch: Partial<DprManpowerRow>) => {
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
      manpowerRoleRateId: opt.variantId,
      roleId: opt.roleId,
      trade: opt.trade,
    });
  };

  const selectedKey = (r: DprManpowerRow): string =>
    r.roleId && r.manpowerRoleRateId ? optKey(r.roleId, r.manpowerRoleRateId) : "";

  const remainingFor = (r: DprManpowerRow): number | null => {
    const planned = Array.isArray(plannedResp?.data) ? plannedResp.data : [];
    const match = planned.find(
      (o) => o.roleId === r.roleId && o.variantId === r.manpowerRoleRateId,
    );
    return match?.remainingUnits ?? null;
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
          options={options.map((o) => ({ value: o.value, label: o.label }))}
          value={selectedKey(r)}
          onChange={(v) => handlePick(i, v)}
          placeholder={isLoading ? "Loading…" : "Pick role…"}
          loading={isLoading}
          disabled={!activityId}
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
          Pick an activity above to choose manpower.
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
            ? "Click Add manpower to record a deployed role."
            : "Pick an activity first."
        }
        addLabel="Add manpower"
      />
    </>
  );
}
