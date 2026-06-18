"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  activitySubContractorApi,
  type ActivitySubContractorAssignment,
} from "@/lib/api/activitySubContractorApi";
import { SearchableSelect } from "@/components/common/SearchableSelect";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import { CellInput, RowGrid, type RowGridColumn } from "./RowGrid";
import type { DprSubContractorRow } from "@/lib/types/dpr";

const blank = (): DprSubContractorRow => ({
  activitySubContractorAssignmentId: "",
  subContractorName: "",
  subContractorCode: "",
  workActivityName: "",
  unit: "",
  ratePerUnit: 0,
  quantity: 0,
  lineCost: 0,
  remarks: null,
});

interface Props {
  projectId: string;
  activityId: string | null;
  rows: DprSubContractorRow[];
  onChange: (rows: DprSubContractorRow[]) => void;
  /** Activity-level workdone qty — used for the soft client-side warning when sum exceeds. */
  workdoneQty?: number | null;
}

/**
 * Sub-contractor DPR grid. Supervisor picks from the activity's PLANNED sub-contractor
 * assignments (created in the activity plan). Each pick snapshots the assignment's unit
 * and rate; the row's lineCost = quantity × ratePerUnit is computed live.
 */
export function SubContractorGrid({ projectId, activityId, rows, onChange, workdoneQty }: Props) {
  const { money } = useProjectCurrency();
  const { data: assignmentsResp, isLoading } = useQuery({
    queryKey: ["sub-contractor-assignments", projectId, activityId],
    queryFn: () => activitySubContractorApi.listForActivity(projectId, activityId!),
    enabled: !!activityId,
  });
  const assignments = useMemo<ActivitySubContractorAssignment[]>(
    () => (Array.isArray(assignmentsResp?.data) ? assignmentsResp.data : []),
    [assignmentsResp],
  );

  const selectedAssignmentIds = useMemo(
    () => new Set(rows.map((r) => r.activitySubContractorAssignmentId).filter(Boolean)),
    [rows],
  );

  const optionsForRow = (currentId: string) => {
    return assignments
      .filter((a) => a.id === currentId || !selectedAssignmentIds.has(a.id))
      .map((a) => ({
        value: a.id,
        label: `${a.subContractorName ?? "—"} — ${a.workTypeName ?? "—"} (planned)`,
      }));
  };

  const update = (idx: number, patch: Partial<DprSubContractorRow>) => {
    const next = rows.slice();
    next[idx] = { ...next[idx], ...patch };
    onChange(next);
  };

  const remove = (idx: number) => onChange(rows.filter((_, i) => i !== idx));
  const add = () => onChange([...rows, blank()]);

  const handlePick = (idx: number, assignmentId: string) => {
    const a = assignments.find((x) => x.id === assignmentId);
    if (!a) return;
    const qty = rows[idx]?.quantity ?? 0;
    const rate = a.ratePerUnit ?? 0;
    update(idx, {
      activitySubContractorAssignmentId: a.id,
      subContractorMasterId: a.subContractorMasterId,
      subContractorName: a.subContractorName,
      subContractorCode: a.subContractorCode,
      // Bridge old wire field name on the DPR row to the renamed assignment field.
      workActivityName: a.workTypeName,
      unit: a.unit,
      ratePerUnit: rate,
      lineCost: qty * rate,
    });
  };

  const handleQty = (idx: number, q: number) => {
    const rate = rows[idx]?.ratePerUnit ?? 0;
    update(idx, { quantity: q, lineCost: q * rate });
  };

  const scSum = rows.reduce((s, r) => s + (r.quantity ?? 0), 0);
  const exceeds = workdoneQty != null && scSum > workdoneQty;

  const columns: RowGridColumn<DprSubContractorRow>[] = [
    {
      key: "assignment",
      label: "Sub-Contractor (planned)",
      minWidth: 320,
      grow: 1,
      render: (r, i) => (
        <SearchableSelect
          options={optionsForRow(r.activitySubContractorAssignmentId)}
          value={r.activitySubContractorAssignmentId}
          onChange={(v) => handlePick(i, v)}
          placeholder={
            isLoading
              ? "Loading…"
              : assignments.length === 0
                ? "No sub-contractors planned for this activity"
                : "Pick planned sub-contractor…"
          }
          loading={isLoading}
          disabled={!activityId || assignments.length === 0}
          selectedLabel={r.subContractorName ?? undefined}
        />
      ),
    },
    {
      key: "quantity",
      label: "Qty",
      minWidth: 100,
      render: (r, i) => (
        <CellInput
          type="number"
          step="0.0001"
          min="0"
          value={r.quantity}
          onChange={(v) => handleQty(i, parseFloat(v) || 0)}
        />
      ),
    },
    {
      key: "unit",
      label: "Unit",
      minWidth: 60,
      render: (r) => <span className="text-xs text-text-secondary">{r.unit ?? "—"}</span>,
    },
    {
      key: "rate",
      label: "Rate",
      minWidth: 90,
      render: (r) => (
        <span className="text-xs tabular-nums text-text-secondary">
          {money(r.ratePerUnit)}
        </span>
      ),
    },
    {
      key: "lineCost",
      label: "Cost",
      minWidth: 110,
      render: (r) => (
        <span className="text-xs tabular-nums">
          {money(r.lineCost)}
        </span>
      ),
    },
    {
      key: "remarks",
      label: "Remarks",
      minWidth: 160,
      grow: 1,
      render: (r, _i, u) => (
        <CellInput
          type="text"
          placeholder="Notes…"
          value={r.remarks}
          onChange={(v) => u({ remarks: v || null })}
        />
      ),
    },
  ];

  return (
    <>
      {!activityId && (
        <div className="mb-3 rounded-md border border-hairline bg-ivory/60 px-3 py-2 text-xs text-slate">
          Pick an activity above to choose sub-contractor.
        </div>
      )}
      {exceeds && (
        <div className="mb-3 rounded-md border border-danger/40 bg-danger/10 px-3 py-2 text-xs text-danger">
          Sub-contractor total ({scSum}) exceeds activity workdone ({workdoneQty}). Save will be rejected.
        </div>
      )}
      <RowGrid
        title="Sub-Contractor"
        rows={rows}
        columns={columns}
        onAdd={add}
        onChange={update}
        onRemove={remove}
        emptyHint={
          activityId
            ? assignments.length === 0
              ? "No sub-contractors planned for this activity — add them in the activity plan first."
              : "Click Add sub-contractor to record work done."
            : "Pick an activity first."
        }
        addLabel="Add sub-contractor"
      />
    </>
  );
}
