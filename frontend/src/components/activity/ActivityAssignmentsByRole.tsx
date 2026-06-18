"use client";

import React, { useMemo, useState } from "react";
import { ChevronRight, ChevronDown } from "lucide-react";
import type { ResourceAssignmentResponse } from "@/lib/api/resourceApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

interface Props {
  assignments: ResourceAssignmentResponse[];
  onStaff: (a: ResourceAssignmentResponse) => void;
  onSwap: (a: ResourceAssignmentResponse) => void;
}

interface RoleGroup {
  key: string;
  roleId: string | null;
  roleName: string;
  rows: ResourceAssignmentResponse[];
  plannedUnits: number;
  actualUnits: number;
  remainingUnits: number;
  plannedCost: number;
  actualCost: number;
  remainingCost: number;
  staffedCount: number;
  /**
   * Common unit shared by every row in the group, or null when rows mix units (rare — would
   * happen if one role had two staffed resources with different rate-master units). When null
   * the group total stays unitless and only the child rows carry their per-row unit.
   */
  groupUnit: string | null;
}

const UNASSIGNED_KEY = "__unassigned__";

function num(n: number | null | undefined): string {
  if (n == null) return "—";
  return Number(n).toLocaleString(undefined, { maximumFractionDigits: 2 });
}

/**
 * Format a unit count with its unit suffix (e.g. "10 Day", "8 Hour"). Returns just the number
 * (no suffix) when the value is null/dash, or when the unit is null — keeps role-only rows
 * and rows with missing unit metadata clean.
 */
function withUnit(n: number | null | undefined, unit: string | null | undefined): string {
  const formatted = num(n);
  if (formatted === "—" || !unit) return formatted;
  return `${formatted} ${unit}`;
}

/**
 * Effective remaining units for a row. Falls back to {@code planned − actual} when the stored
 * column is null — the backend rollup only writes {@code remaining_units} after the first daily
 * output is recorded, so freshly-assigned resources sit at null until that happens.
 */
function effRemainingUnits(a: ResourceAssignmentResponse): number | null {
  if (a.remainingUnits != null) return a.remainingUnits;
  if (a.plannedUnits == null) return null;
  return Math.max(a.plannedUnits - (a.actualUnits ?? 0), 0);
}

function effRemainingCost(a: ResourceAssignmentResponse): number | null {
  if (a.remainingCost != null) return a.remainingCost;
  if (a.plannedCost == null) return null;
  return Math.max(a.plannedCost - (a.actualCost ?? 0), 0);
}

function buildGroups(assignments: ResourceAssignmentResponse[]): RoleGroup[] {
  const map = new Map<string, RoleGroup>();
  for (const a of assignments) {
    const key = a.effectiveRoleId ?? UNASSIGNED_KEY;
    let group = map.get(key);
    if (!group) {
      group = {
        key,
        roleId: a.effectiveRoleId,
        roleName: a.effectiveRoleName ?? "Unassigned role",
        rows: [],
        plannedUnits: 0,
        actualUnits: 0,
        remainingUnits: 0,
        plannedCost: 0,
        actualCost: 0,
        remainingCost: 0,
        staffedCount: 0,
        groupUnit: null,
      };
      map.set(key, group);
    }
    group.rows.push(a);
    group.plannedUnits += a.plannedUnits ?? 0;
    group.actualUnits += a.actualUnits ?? 0;
    group.remainingUnits += effRemainingUnits(a) ?? 0;
    group.plannedCost += a.plannedCost ?? 0;
    group.actualCost += a.actualCost ?? 0;
    group.remainingCost += effRemainingCost(a) ?? 0;
    if (a.staffed) group.staffedCount++;
  }

  const groups = Array.from(map.values());
  groups.sort((a, b) => {
    if (a.key === UNASSIGNED_KEY) return 1;
    if (b.key === UNASSIGNED_KEY) return -1;
    return a.roleName.localeCompare(b.roleName);
  });
  for (const g of groups) {
    g.rows.sort((a, b) => {
      // Staffed rows first (alphabetically), then role-only rows.
      if (a.staffed !== b.staffed) return a.staffed ? -1 : 1;
      const aName = a.resourceName ?? "";
      const bName = b.resourceName ?? "";
      return aName.localeCompare(bName);
    });
    // Compute the shared group unit. If every row has the same non-null unit we surface it on
    // the group total line; otherwise the group totals stay unitless.
    const units = new Set(g.rows.map((r) => r.unit ?? null).filter((u): u is string => !!u));
    g.groupUnit = units.size === 1 ? Array.from(units)[0] : null;
  }
  return groups;
}

function GroupStatusPill({ staffed, total }: { staffed: number; total: number }) {
  if (total === 0) return <span className="text-text-muted">—</span>;
  const allStaffed = staffed === total;
  const cls = allStaffed
    ? "bg-green-50 text-green-700 ring-green-600/20"
    : "bg-amber-50 text-amber-700 ring-amber-600/20";
  return (
    <span className={`inline-flex items-center rounded-md px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset ${cls}`}>
      {staffed} of {total} staffed
    </span>
  );
}

function ResourceStatusPill({ staffed }: { staffed: boolean }) {
  return staffed ? (
    <span className="inline-flex items-center rounded-md bg-green-50 px-1.5 py-0.5 text-xs font-medium text-green-700 ring-1 ring-inset ring-green-600/20">
      Staffed
    </span>
  ) : (
    <span className="inline-flex items-center rounded-md bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-700 ring-1 ring-inset ring-amber-600/20">
      Role-only
    </span>
  );
}

export function ActivityAssignmentsByRole({ assignments, onStaff, onSwap }: Props) {
  const { money } = useProjectCurrency();
  const fmt = (n: number | null | undefined): string => money(n ?? 0, { decimals: 0 });
  const groups = useMemo(() => buildGroups(assignments), [assignments]);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  const toggle = (key: string) =>
    setExpanded((prev) => ({ ...prev, [key]: !prev[key] }));

  const totals = useMemo(
    () =>
      groups.reduce(
        (acc, g) => ({
          plannedUnits: acc.plannedUnits + g.plannedUnits,
          actualUnits: acc.actualUnits + g.actualUnits,
          remainingUnits: acc.remainingUnits + g.remainingUnits,
          plannedCost: acc.plannedCost + g.plannedCost,
          actualCost: acc.actualCost + g.actualCost,
          remainingCost: acc.remainingCost + g.remainingCost,
        }),
        { plannedUnits: 0, actualUnits: 0, remainingUnits: 0, plannedCost: 0, actualCost: 0, remainingCost: 0 }
      ),
    [groups]
  );

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border">
            <th className="text-left py-1.5 pr-3 text-xs font-medium text-text-secondary">Role / Resource</th>
            <th className="text-left py-1.5 pr-3 text-xs font-medium text-text-secondary">Status</th>
            <th className="text-right py-1.5 pr-3 text-xs font-medium text-text-secondary">Planned Units</th>
            <th className="text-right py-1.5 pr-3 text-xs font-medium text-text-secondary">Actual Units</th>
            <th className="text-right py-1.5 pr-3 text-xs font-medium text-text-secondary">Remaining Units</th>
            <th className="text-right py-1.5 pr-3 text-xs font-medium text-text-secondary">Planned Cost</th>
            <th className="text-right py-1.5 pr-3 text-xs font-medium text-text-secondary">Actual Cost</th>
            <th className="text-right py-1.5 pr-3 text-xs font-medium text-text-secondary">Remaining Cost</th>
            <th className="text-left py-1.5 text-xs font-medium text-text-secondary">Actions</th>
          </tr>
        </thead>
        <tbody>
          {groups.map((g) => {
            const isOpen = !!expanded[g.key];
            return (
              <React.Fragment key={g.key}>
                <tr
                  className="border-b border-border/60 bg-surface-hover/20 hover:bg-surface-hover/40 cursor-pointer"
                  onClick={() => toggle(g.key)}
                >
                  <td className="py-1.5 pr-3 text-text-primary">
                    <div className="flex items-center gap-1.5">
                      <button
                        type="button"
                        className="p-0.5 hover:bg-surface-hover rounded shrink-0"
                        onClick={(e) => {
                          e.stopPropagation();
                          toggle(g.key);
                        }}
                        aria-label={isOpen ? "Collapse" : "Expand"}
                      >
                        {isOpen ? (
                          <ChevronDown size={16} className="text-text-muted" />
                        ) : (
                          <ChevronRight size={16} className="text-text-muted" />
                        )}
                      </button>
                      <span className="font-semibold">{g.roleName}</span>
                      <span className="ml-1 text-xs text-text-muted bg-surface-hover px-1.5 py-0.5 rounded-full">
                        {g.rows.length}
                      </span>
                    </div>
                  </td>
                  <td className="py-1.5 pr-3">
                    <GroupStatusPill staffed={g.staffedCount} total={g.rows.length} />
                  </td>
                  <td className="py-1.5 pr-3 text-right text-text-primary font-medium">{withUnit(g.plannedUnits, g.groupUnit)}</td>
                  <td className="py-1.5 pr-3 text-right text-text-primary font-medium">{withUnit(g.actualUnits, g.groupUnit)}</td>
                  <td className="py-1.5 pr-3 text-right text-text-primary font-medium">{withUnit(g.remainingUnits, g.groupUnit)}</td>
                  <td className="py-1.5 pr-3 text-right text-text-primary font-medium">{fmt(g.plannedCost)}</td>
                  <td className="py-1.5 pr-3 text-right text-text-primary font-medium">{fmt(g.actualCost)}</td>
                  <td className="py-1.5 pr-3 text-right text-text-primary font-medium">{fmt(g.remainingCost)}</td>
                  <td className="py-1.5" />
                </tr>

                {isOpen &&
                  g.rows.map((a) => (
                    <tr key={a.id} className="border-b border-border/40">
                      <td className="py-1.5 pr-3 text-text-secondary">
                        <div className="pl-7">
                          {a.resourceName ?? a.resourceId ?? "—"}
                        </div>
                      </td>
                      <td className="py-1.5 pr-3">
                        <ResourceStatusPill staffed={a.staffed} />
                      </td>
                      <td className="py-1.5 pr-3 text-right text-text-primary">{withUnit(a.plannedUnits, a.unit)}</td>
                      <td className="py-1.5 pr-3 text-right text-text-primary">{withUnit(a.actualUnits, a.unit)}</td>
                      <td className="py-1.5 pr-3 text-right text-text-primary">{withUnit(effRemainingUnits(a), a.unit)}</td>
                      <td className="py-1.5 pr-3 text-right text-text-primary">
                        {a.plannedCost != null ? fmt(a.plannedCost) : "—"}
                      </td>
                      <td className="py-1.5 pr-3 text-right text-text-primary">
                        {a.actualCost != null ? fmt(a.actualCost) : "—"}
                      </td>
                      <td className="py-1.5 pr-3 text-right text-text-primary">
                        {(() => {
                          const rc = effRemainingCost(a);
                          return rc != null ? fmt(rc) : "—";
                        })()}
                      </td>
                      <td className="py-1.5 text-text-primary">
                        {!a.staffed && a.roleId && (
                          <button
                            onClick={() => onStaff(a)}
                            className="text-xs px-2 py-0.5 rounded bg-accent text-accent-foreground hover:bg-accent-hover"
                          >
                            Staff role
                          </button>
                        )}
                        {a.staffed && a.roleId && (
                          <button
                            onClick={() => onSwap(a)}
                            className="text-xs px-2 py-0.5 rounded border border-border text-text-secondary hover:bg-surface-hover"
                          >
                            Swap
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
              </React.Fragment>
            );
          })}

          <tr className="font-semibold bg-surface-hover/30">
            <td className="py-1.5 pr-3 text-text-secondary">Totals</td>
            <td />
            <td className="py-1.5 pr-3 text-right text-text-primary">{num(totals.plannedUnits)}</td>
            <td className="py-1.5 pr-3 text-right text-text-primary">{num(totals.actualUnits)}</td>
            <td className="py-1.5 pr-3 text-right text-text-primary">{num(totals.remainingUnits)}</td>
            <td className="py-1.5 pr-3 text-right text-accent">{fmt(totals.plannedCost)}</td>
            <td className="py-1.5 pr-3 text-right text-accent">{fmt(totals.actualCost)}</td>
            <td className="py-1.5 pr-3 text-right text-accent">{fmt(totals.remainingCost)}</td>
            <td />
          </tr>
        </tbody>
      </table>
    </div>
  );
}
