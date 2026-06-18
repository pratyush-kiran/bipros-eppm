"use client";

import { memo, useMemo, useState } from "react";
import type {
  ActivityDrillDown,
  EquipmentRollup,
  HiddenSideNote,
  PlannedActuals,
  ProductivityNorms,
  ResourceLine,
  SupervisorPerformanceReport,
  TradeRollup,
} from "@/lib/api/capacityUtilizationApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

function fmt(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined) return "—";
  return n.toLocaleString("en-IN", { maximumFractionDigits: digits });
}

function CostLabel({ value }: { value: number | null | undefined }) {
  const { money } = useProjectCurrency();
  if (value === null || value === undefined || value === 0) {
    return <span className="text-text-muted">—</span>;
  }
  if (value < 0) {
    return <span className="text-success">Cost saved: {money(Math.abs(value), { decimals: 0 })}</span>;
  }
  return <span className="text-danger">Cost overrun: {money(value, { decimals: 0 })}</span>;
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

/**
 * "(115 tracked · 154 suppressed · 8 untracked)" — only when at least one of suppressed/untracked
 * is non-zero. Returns null otherwise so the table stays tight on the common case where every
 * day was tracked.
 */
function actualBreakdown(
  total: number | null | undefined,
  suppressed: number | null | undefined,
  untracked: number | null | undefined,
): string | null {
  if (total == null || total <= 0) return null;
  const s = suppressed ?? 0;
  const u = untracked ?? 0;
  if (s <= 0 && u <= 0) return null;
  const tracked = total - s - u;
  const parts: string[] = [];
  if (tracked > 0) parts.push(`${fmt(tracked, 1)} tracked`);
  if (s > 0) parts.push(`${fmt(s, 1)} suppressed`);
  if (u > 0) parts.push(`${fmt(u, 1)} untracked`);
  return parts.join(" · ");
}

const BREAKDOWN_TOOLTIP =
  "Tracked = counted toward Efficiency on this side. Suppressed = on activities where the other side governs (SERIES / SUBSTITUTE) — see banner. Untracked = on activities with no productivity norm for this role.";

function HiddenSideBanner({
  notes,
  sideLabel,
}: {
  notes: HiddenSideNote[] | undefined;
  sideLabel: "Manpower" | "Equipment";
}) {
  if (!notes || notes.length === 0) return null;
  return (
    <div className="mt-3 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning space-y-1">
      {notes.map((n) => {
        const governing = n.governingSide === "MANPOWER" ? "Manpower" : "Equipment";
        return (
          <div key={n.activityId}>
            <span className="font-medium">
              {n.workActivityName ?? "Activity"}
            </span>
            {` (${n.mode}): ${governing} side governs this activity. ${sideLabel} deployments here count toward Actual but are excluded from this section’s Efficiency — see ${governing} Utilization for the activity’s productivity.`}
          </div>
        );
      })}
    </div>
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

function ManpowerUtilizationTableInner({
  rows,
  hiddenNotes,
}: {
  rows: TradeRollup[];
  hiddenNotes?: HiddenSideNote[];
}) {
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
    <>
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
            {visible.map((r, i) => {
              const breakdown = actualBreakdown(
                r.actualManDays,
                r.actualDaysOnHiddenSides,
                r.actualDaysUntracked,
              );
              return (
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
                    <div>{fmt(r.actualManDays)}</div>
                    {breakdown && (
                      <div
                        className="text-[11px] text-text-muted italic"
                        title={BREAKDOWN_TOOLTIP}
                      >
                        ({breakdown})
                      </div>
                    )}
                  </td>
                  <td className="px-3 py-2 text-center">
                    <UtilCell util={r.utilizationPct} />
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    <CostLabel value={r.costImplication} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <HiddenSideBanner notes={hiddenNotes} sideLabel="Manpower" />
    </>
  );
}

export const ManpowerUtilizationTable = memo(ManpowerUtilizationTableInner);

function EquipmentUtilizationTableInner({
  rows,
  hiddenNotes,
}: {
  rows: EquipmentRollup[];
  hiddenNotes?: HiddenSideNote[];
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
    <>
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
            {visible.map((r, i) => {
              const breakdown = actualBreakdown(
                r.actualDays,
                r.actualDaysOnHiddenSides,
                r.actualDaysUntracked,
              );
              return (
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
                    <div>{fmt(r.actualDays)}</div>
                    {breakdown && (
                      <div
                        className="text-[11px] text-text-muted italic"
                        title={BREAKDOWN_TOOLTIP}
                      >
                        ({breakdown})
                      </div>
                    )}
                  </td>
                  <td className="px-3 py-2 text-center">
                    <UtilCell util={r.utilizationPct} />
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    <CostLabel value={r.costImplication} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <HiddenSideBanner notes={hiddenNotes} sideLabel="Equipment" />
    </>
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
          {activity.subContractorQty != null &&
            activity.subContractorQty > 0 &&
            activity.qtyForMonth != null && (
              <span className="ml-1">
                ({fmt(activity.qtyForMonth - activity.subContractorQty)} own +{" "}
                {fmt(activity.subContractorQty)} sub-contractor)
              </span>
            )}
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
        <ManpowerUtilizationTable
          rows={report.summary.manpower}
          hiddenNotes={report.summary.manpowerHiddenNotes}
        />
      </section>

      <section>
        <h2 className="text-lg font-bold text-text-primary mb-3">
          Equipment Utilization
        </h2>
        <EquipmentUtilizationTable
          rows={report.summary.equipment}
          hiddenNotes={report.summary.equipmentHiddenNotes}
        />
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
