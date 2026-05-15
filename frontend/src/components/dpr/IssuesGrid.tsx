"use client";

import { useId } from "react";
import type { SelectOption } from "@/components/common/SearchableSelect";
import { CellInput, CellSelect, RowGrid, type RowGridColumn } from "./RowGrid";
import {
  CATEGORY_OPTIONS,
  SEVERITY_OPTIONS,
  STATUS_OPTIONS,
} from "./IssueBadges";
import type {
  DprIssueRow,
  IssueCategory,
  IssueSeverity,
  IssueStatus,
} from "@/lib/types/dpr";

const blank = (): DprIssueRow => ({
  id: null,
  title: "",
  description: null,
  category: "OTHER",
  severity: "MEDIUM",
  status: "OPEN",
  supervisorUserId: null,
  supervisorName: null,
  assignedToUserId: null,
  assignedToName: null,
  resolutionNotes: null,
});

interface Props {
  rows: DprIssueRow[];
  onChange: (rows: DprIssueRow[]) => void;
  /**
   * Same option set the DPR header uses for the supervisor picker. We surface these as
   * suggestions via a native &lt;datalist&gt; so the user can either pick a known supervisor
   * (auto-fills the FK) or type any name freely — important because some projects have an
   * empty supervisor pool, in which case a strict picker would block issue logging entirely.
   */
  supervisorOptions: SelectOption[];
  /** Default-fills the assignee name on Add so quick-log defaults to the day's supervisor. */
  defaultSupervisorName?: string;
}

export function IssuesGrid({ rows, onChange, supervisorOptions, defaultSupervisorName }: Props) {
  const datalistId = useId();
  const update = (idx: number, patch: Partial<DprIssueRow>) => {
    const next = rows.slice();
    next[idx] = { ...next[idx], ...patch };
    onChange(next);
  };
  const remove = (idx: number) => onChange(rows.filter((_, i) => i !== idx));
  const add = () =>
    onChange([
      ...rows,
      {
        ...blank(),
        assignedToName: defaultSupervisorName ?? null,
      },
    ]);

  /** Typed name → resource-id lookup via the suggestion list. Falls back to a null id so the
   *  server can stamp the parent DPR's supervisor as the assignee. */
  const handleNameChange = (idx: number, name: string) => {
    const match = supervisorOptions.find(
      (s) => s.label.toLowerCase() === name.toLowerCase()
    );
    update(idx, {
      assignedToName: name || null,
      assignedToUserId: match?.value ?? null,
    });
  };

  const columns: RowGridColumn<DprIssueRow>[] = [
    {
      key: "title",
      label: "Title",
      minWidth: 220,
      grow: 1,
      render: (r, _i, u) => (
        <CellInput
          value={r.title ?? ""}
          onChange={(v) => u({ title: v })}
          placeholder="Short headline…"
        />
      ),
    },
    {
      key: "category",
      label: "Reason",
      minWidth: 180,
      render: (r, _i, u) => (
        <CellSelect
          value={r.category}
          onChange={(v) => u({ category: (v || "OTHER") as IssueCategory })}
          options={CATEGORY_OPTIONS}
        />
      ),
    },
    {
      key: "severity",
      label: "Severity",
      minWidth: 130,
      render: (r, _i, u) => (
        <CellSelect
          value={r.severity}
          onChange={(v) => u({ severity: (v || "MEDIUM") as IssueSeverity })}
          options={SEVERITY_OPTIONS}
        />
      ),
    },
    {
      key: "status",
      label: "Status",
      minWidth: 140,
      render: (r, _i, u) => (
        <CellSelect
          value={r.status}
          onChange={(v) => u({ status: (v || "OPEN") as IssueStatus })}
          options={STATUS_OPTIONS}
        />
      ),
    },
    {
      key: "assignedTo",
      label: "Assigned to",
      minWidth: 200,
      render: (r, i) => (
        <input
          type="text"
          value={r.assignedToName ?? ""}
          onChange={(e) => handleNameChange(i, e.target.value)}
          placeholder="Type or pick assignee…"
          list={datalistId}
          className="w-full rounded border border-hairline bg-paper px-2.5 py-1.5 text-[0.95rem] text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
        />
      ),
    },
    {
      key: "description",
      label: "Description",
      minWidth: 240,
      grow: 1,
      render: (r, _i, u) => (
        <CellInput
          value={r.description ?? ""}
          onChange={(v) => u({ description: v || null })}
          placeholder="What happened, why…"
        />
      ),
    },
  ];

  return (
    <>
      <RowGrid
        title="Issues"
        rows={rows}
        columns={columns}
        onAdd={add}
        onChange={update}
        onRemove={remove}
        emptyHint="No issues logged — click Add issue to record a blocker, breakdown, or RFI."
        addLabel="Add issue"
      />
      <datalist id={datalistId}>
        {supervisorOptions.map((s) => (
          <option key={s.value} value={s.label} />
        ))}
      </datalist>
    </>
  );
}
