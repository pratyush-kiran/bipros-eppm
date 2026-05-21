"use client";

import { memo, useMemo, useState } from "react";
import type {
  ActivityDrillDown,
  EquipmentRollup,
  PlannedActuals,
  ProductivityNorms,
  ResourceLine,
  SupervisorPerformanceReport,
  TradeRollup,
} from "@/lib/api/capacityUtilizationApi";

function fmt(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: digits });
}

function fmtMoney(n: number): string {
  return n.toLocaleString("en-IN", { maximumFractionDigits: 0 });
}

function CostLabel({ value }: { value: number | null | undefined }) {
  if (value === null || value === undefined || value === 0) {
    return <span className="text-text-muted">—</span>;
  }
  if (value < 0) {
    return <span className="text-success">Cost saved: ₹{fmtMoney(Math.abs(value))}</span>;
  }
  return <span className="text-danger">Cost overrun: ₹{fmtMoney(value)}</span>;
}

function utilBand(util: number | null | undefined): string {
  if (util === null || util === undefined)
    return "bg-surface/30 text-text-muted";
  if (util >= 100) return "bg-success/15 text-success ring-1 ring-success/30";
  if (util >= 80) return "bg-warning/15 text-warning ring-1 ring-warning/30";
  return "bg-danger/15 text-danger ring-1 ring-danger/30";
}

function UtilCell({ util }: { util: number | null | undefined }) {
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded text-xs font-semibold ${utilBand(util)}`}
    >
      {util === null || util === undefined ? "—" : `${fmt(util, 1)} %`}
    </span>
  );
}

function NormSourceBadge({ source }: { source: string }) {
  if (!source || source === "NONE")
    return (
      <span className="text-[10px] text-text-muted italic">no norm</span>
    );
  return (
    <span className="text-[10px] text-text-muted">
      {source.replace(/_/g, " ").toLowerCase()}
    </span>
  );
}

interface SectionsProps {
  report: SupervisorPerformanceReport;
}

const MAX_ROLLUP_ROWS = 50;

function ManpowerUtilizationTableInner({ rows }: { rows: TradeRollup[] }) {
  if (rows.length === 0) {
    return (
      <div className="text-text-muted text-sm py-6 text-center">
        No manpower DPR rows in this window.
      </div>
    );
  }
  const visible = rows.slice(0, MAX_ROLLUP_ROWS);
  const overflow = rows.length - visible.length;
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      {overflow > 0 && (
        <div className="px-3 py-2 bg-warning/10 border-b border-warning/30 text-xs text-warning">
          Showing first {MAX_ROLLUP_ROWS} of {rows.length} trades. Narrow the date range or pick a supervisor to focus.
        </div>
      )}
      <table className="min-w-full text-sm">
        <thead className="bg-surface/50 text-text-secondary text-xs uppercase tracking-wide">
          <tr>
            <th className="px-3 py-2 text-left">#</th>
            <th className="px-3 py-2 text-left">Trade</th>
            <th className="px-3 py-2 text-right">MM Rate</th>
            <th className="px-3 py-2 text-right">Qty Done</th>
            <th className="px-3 py-2 text-right">Bud. Man-days</th>
            <th className="px-3 py-2 text-right">Act. Man-days</th>
            <th className="px-3 py-2 text-center">Efficiency %</th>
            <th className="px-3 py-2 text-right">Cost Implication</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((r, i) => (
            <tr
              key={r.tradeKey}
              className="border-t border-border hover:bg-surface/30"
            >
              <td className="px-3 py-2 text-text-muted">{i + 1}</td>
              <td className="px-3 py-2">
                <div className="font-medium text-text-primary">
                  {r.tradeLabel}
                </div>
                <NormSourceBadge source={r.normSource} />
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.mmRate)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.qtyDone)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.budgetedManDays)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.actualManDays)}
              </td>
              <td className="px-3 py-2 text-center">
                <UtilCell util={r.utilizationPct} />
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                <CostLabel value={r.costImplication} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export const ManpowerUtilizationTable = memo(ManpowerUtilizationTableInner);

function EquipmentUtilizationTableInner({
  rows,
}: {
  rows: EquipmentRollup[];
}) {
  if (rows.length === 0) {
    return (
      <div className="text-text-muted text-sm py-6 text-center">
        No equipment DPR rows in this window.
      </div>
    );
  }
  const visible = rows.slice(0, MAX_ROLLUP_ROWS);
  const overflow = rows.length - visible.length;
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      {overflow > 0 && (
        <div className="px-3 py-2 bg-warning/10 border-b border-warning/30 text-xs text-warning">
          Showing first {MAX_ROLLUP_ROWS} of {rows.length} equipment rows. Narrow the date range or pick a supervisor to focus.
        </div>
      )}
      <table className="min-w-full text-sm">
        <thead className="bg-surface/50 text-text-secondary text-xs uppercase tracking-wide">
          <tr>
            <th className="px-3 py-2 text-left">#</th>
            <th className="px-3 py-2 text-left">Equipment</th>
            <th className="px-3 py-2 text-right">Eq Rate / Day</th>
            <th className="px-3 py-2 text-right">Qty Done</th>
            <th className="px-3 py-2 text-right">Bud. Eqpt-days</th>
            <th className="px-3 py-2 text-right">Act. Eqpt-days</th>
            <th className="px-3 py-2 text-center">Efficiency %</th>
            <th className="px-3 py-2 text-right">Cost Implication</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((r, i) => (
            <tr
              key={r.equipmentKey}
              className="border-t border-border hover:bg-surface/30"
            >
              <td className="px-3 py-2 text-text-muted">{i + 1}</td>
              <td className="px-3 py-2">
                <div className="font-medium text-text-primary">
                  {r.equipmentLabel}
                </div>
                <NormSourceBadge source={r.normSource} />
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.hourRate)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.qtyDone)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.budgetedDays)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                {fmt(r.actualDays)}
              </td>
              <td className="px-3 py-2 text-center">
                <UtilCell util={r.utilizationPct} />
              </td>
              <td className="px-3 py-2 text-right tabular-nums">
                <CostLabel value={r.costImplication} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export const EquipmentUtilizationTable = memo(EquipmentUtilizationTableInner);

function NormsCell({ norms }: { norms: ProductivityNorms }) {
  return (
    <div className="space-y-0.5 text-xs">
      <div>
        <span className="text-text-muted">Budget:</span> {fmt(norms.budget)}
      </div>
      <div>
        <span className="text-text-muted">Actual FTM:</span>{" "}
        {fmt(norms.actualsFtm)}
      </div>
    </div>
  );
}

function PlanActualCell({
  pa,
  variant,
}: {
  pa: PlannedActuals;
  variant: "plan" | "actual";
}) {
  // PLAN column carries raw planned headcount (nos) from RoleAssignment.plannedUnits.
  // ACTUAL column carries deployed person-days from DPRs. Different units → only the
  // Actual column shows a %Util pill (budget-days ÷ actual-days); the Plan column's
  // headcount-vs-days comparison would be meaningless.
  const isPlan = variant === "plan";
  return (
    <div className="space-y-0.5 text-xs">
      <div>
        <span className="text-text-muted">Qty:</span> {fmt(pa.qty)}
      </div>
      <div>
        <span className="text-text-muted">Bud days:</span> {fmt(pa.budgetDays)}
      </div>
      <div>
        <span className="text-text-muted">
          {isPlan ? "Planned:" : "Actual Days:"}
        </span>{" "}
        {fmt(pa.days)}
        {isPlan && pa.days != null && (
          <span className="text-text-muted"> nos</span>
        )}
      </div>
      {!isPlan && (
        <div>
          <UtilCell util={pa.utilizationPct} />
        </div>
      )}
    </div>
  );
}

/**
 * Manual button + state for expand/collapse. React.memo prevents sibling re-renders when one
 * panel toggles. The inner table only mounts while `open` is true so collapsed panels are
 * cheap.
 */
function ActivityDrillDownPanelInner({
  activity,
}: {
  activity: ActivityDrillDown;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="border border-border rounded-lg mb-2 bg-surface/30">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="w-full text-left cursor-pointer select-none px-4 py-3 flex flex-wrap items-center justify-between gap-2 hover:bg-surface/50"
      >
        <div>
          <span className="font-mono text-xs text-text-muted mr-2">
            {open ? "▾" : "▸"} {activity.activityCode ?? "—"}
          </span>
          <span className="font-semibold text-text-primary">
            {activity.activityName}
          </span>
        </div>
        <div className="text-xs text-text-muted">
          Qty for month: {fmt(activity.qtyForMonth)} {activity.unit ?? ""}
          <span className="ml-3">
            {activity.resources.length} resource line
            {activity.resources.length === 1 ? "" : "s"}
          </span>
        </div>
      </button>
      {open && (
        <div className="px-4 py-3 border-t border-border">
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="min-w-full text-sm">
              <thead className="bg-surface/50 text-text-secondary text-xs uppercase tracking-wide">
                <tr>
                  <th className="px-3 py-2 text-left">Kind</th>
                  <th className="px-3 py-2 text-left">Resource</th>
                  <th className="px-3 py-2 text-left">Productivity Norms</th>
                  <th className="px-3 py-2 text-left">Plan (month)</th>
                  <th className="px-3 py-2 text-left">Actuals (month)</th>
                </tr>
              </thead>
              <tbody>
                {activity.resources.map((r: ResourceLine, idx: number) => (
                  <tr
                    key={`${r.kind}-${r.resourceKey}-${idx}`}
                    className="border-t border-border align-top"
                  >
                    <td className="px-3 py-2">
                      <span
                        className={`inline-block px-2 py-0.5 rounded text-[10px] font-semibold uppercase ${
                          r.kind === "MANPOWER"
                            ? "bg-info/15 text-info ring-1 ring-info/30"
                            : "bg-accent/15 text-accent ring-1 ring-accent/30"
                        }`}
                      >
                        {r.kind === "MANPOWER" ? "MP" : "Eqpt"}
                      </span>
                    </td>
                    <td className="px-3 py-2">
                      <div className="font-medium text-text-primary">
                        {r.resourceLabel}
                      </div>
                      <NormSourceBadge source={r.norms.normSource} />
                    </td>
                    <td className="px-3 py-2">
                      <NormsCell norms={r.norms} />
                    </td>
                    <td className="px-3 py-2">
                      <PlanActualCell pa={r.planMonth} variant="plan" />
                    </td>
                    <td className="px-3 py-2">
                      <PlanActualCell pa={r.actualMonth} variant="actual" />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {activity.remarks && (
            <div className="mt-3 text-xs text-text-muted italic">
              <span className="font-semibold not-italic mr-1">Remarks:</span>
              {activity.remarks}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export const ActivityDrillDownPanel = memo(ActivityDrillDownPanelInner);

function SupervisorPerformanceSectionsInner({ report }: SectionsProps) {
  const visibleActivities = useMemo(
    () => report.activities.slice(0, 20),
    [report.activities],
  );
  const overflow = report.activities.length - visibleActivities.length;
  return (
    <div className="mt-8 space-y-8">
      <section>
        <h2 className="text-lg font-bold text-text-primary mb-3">
          Manpower Utilization
        </h2>
        <ManpowerUtilizationTable rows={report.summary.manpower} />
      </section>

      <section>
        <h2 className="text-lg font-bold text-text-primary mb-3">
          Equipment Utilization
        </h2>
        <EquipmentUtilizationTable rows={report.summary.equipment} />
      </section>

      <section>
        <h2 className="text-lg font-bold text-text-primary mb-3">
          Activity Drill-down
        </h2>
        {visibleActivities.length === 0 ? (
          <div className="text-text-muted text-sm py-6 text-center border border-border rounded-lg">
            No activities in this window.
          </div>
        ) : (
          <>
            {overflow > 0 && (
              <div className="mb-3 px-3 py-2 rounded-lg bg-warning/10 border border-warning/30 text-xs text-warning">
                Showing first 20 of {report.activities.length} activities. Narrow the date range
                or pick a supervisor to focus the drill-down.
              </div>
            )}
            {visibleActivities.map((a) => (
              <ActivityDrillDownPanel key={a.activityId} activity={a} />
            ))}
          </>
        )}
      </section>
    </div>
  );
}

export const SupervisorPerformanceSections = memo(SupervisorPerformanceSectionsInner);
