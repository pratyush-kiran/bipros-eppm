"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { evmKpiApi, type WbsEvmNode } from "@/lib/api/evmKpiApi";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

interface Props {
  projectId: string;
  density?: "compact" | "full";
}

type SortKey = "name" | "bac" | "pv" | "ev" | "ac" | "spi" | "cpi" | "vac";

function flattenLeaves(nodes: WbsEvmNode[]): WbsEvmNode[] {
  const out: WbsEvmNode[] = [];
  const walk = (n: WbsEvmNode) => {
    if (!n.children || n.children.length === 0) {
      out.push(n);
    } else {
      n.children.forEach(walk);
    }
  };
  nodes.forEach(walk);
  return out;
}

function formatIndex(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value) || value === 0) return "—";
  return value.toFixed(2);
}

function indexClass(value: number | null | undefined): string {
  if (value == null || value === 0) return "text-text-primary";
  if (value < 0.9) return "text-danger";
  if (value > 1.1) return "text-success";
  return "text-text-primary";
}

export function EvmKpiSection({ projectId, density = "compact" }: Props) {
  const { moneyCompact, code: projectCurrency } = useProjectCurrency();

  const { data, isLoading, error } = useQuery({
    queryKey: ["evm-kpis", projectId],
    queryFn: () => evmKpiApi.getKpis(projectId),
    enabled: !!projectId,
    retry: 1,
  });

  if (!projectId) return null;
  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-text-muted">
        Loading EVM KPIs…
      </div>
    );
  }
  if (error || !data?.data) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-text-muted">
        EVM KPIs not yet calculated for this project. Run an EVM calculation to populate this
        section.
      </div>
    );
  }

  const evm = data.data;
  const hasData =
    (evm.budgetAtCompletion ?? 0) > 0 || (evm.earnedValue ?? 0) > 0 || (evm.actualCost ?? 0) > 0;

  if (!hasData) {
    return (
      <div className="rounded-lg border border-border bg-surface/40 p-4 text-sm text-text-muted">
        EVM not yet calculated. Use the EVM module to run a calculation, then this section will
        show BAC / PV / EV / AC / SPI / CPI / EAC / VAC.
      </div>
    );
  }

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-lg font-semibold text-text-primary">EVM KPIs</h2>
        <div className="text-[11px] text-text-muted">
          {evm.evmTechnique ?? "—"} · ETC: {evm.etcMethod ?? "—"}
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Kpi
          label="BAC"
          value={moneyCompact(evm.budgetAtCompletion)}
          caption="Budget at Completion"
          tooltip={`Total approved project budget — the contractual cost ceiling.
Source: Σ activity at-completion cost rolled to project.
Read it as: "We have permission to spend up to this amount."`}
        />
        <Kpi
          label="PV"
          value={moneyCompact(evm.plannedValue)}
          caption="KPI 10.1 — BAC × Planned %"
          tooltip={`Planned Value (BCWS) — value of work the schedule says SHOULD be done by today.
Formula: BAC × planned-percent-complete-as-of-today.
Read it as: "By this date, we should have produced X ${projectCurrency} worth of deliverables."`}
        />
        <Kpi
          label="EV"
          value={moneyCompact(evm.earnedValue)}
          caption="KPI 10.2 — BAC × Actual %"
          tooltip={`Earned Value (BCWP) — value of work actually completed.
Formula: BAC × actual-percent-complete.
Read it as: "What we've delivered so far is worth X ${projectCurrency} of contract value."`}
        />
        <Kpi
          label="AC"
          value={moneyCompact(evm.actualCost)}
          caption="KPI 10.3 — Actual costs to date"
          tooltip={`Actual Cost (ACWP) — money actually spent so far.
Source: Σ DPR-derived costs + RA bill payments + invoices to date.
Compared to EV: tells you whether the spend bought you matching value.`}
        />
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Kpi
          label="SV"
          value={moneyCompact(evm.scheduleVariance)}
          caption="KPI 10.4 — EV − PV"
          tooltip={`Schedule Variance — schedule slip expressed in ${projectCurrency}.
Formula: EV − PV.
Positive = ahead of schedule.   Negative = behind by this amount.
Pair with SPI for the "ratio" view of the same gap.`}
          accent={evm.scheduleVariance != null && evm.scheduleVariance < 0 ? "danger" : "default"}
        />
        <Kpi
          label="CV"
          value={moneyCompact(evm.costVariance)}
          caption="KPI 10.5 — EV − AC"
          tooltip={`Cost Variance — budget gap expressed in ${projectCurrency}.
Formula: EV − AC.
Positive = under budget (saving money).   Negative = over budget (overrun).
Pair with CPI for the "ratio" view of the same gap.`}
          accent={evm.costVariance != null && evm.costVariance < 0 ? "danger" : "default"}
        />
        <Kpi
          label="SPI"
          value={formatIndex(evm.schedulePerformanceIndex)}
          caption="KPI 10.6 — EV ÷ PV"
          tooltip={`Schedule Performance Index — "how fast are we moving vs plan?"
Formula: EV ÷ PV.
1.0 = on plan.   < 1.0 = behind (e.g., 0.85 = delivering 85% of scheduled output).
< 0.85 = critical — escalate.   Target ≥ 0.90.`}
          accentClass={indexClass(evm.schedulePerformanceIndex)}
        />
        <Kpi
          label="CPI"
          value={formatIndex(evm.costPerformanceIndex)}
          caption="KPI 10.7 — EV ÷ AC"
          tooltip={`Cost Performance Index — "how much value are we getting per unit of ${projectCurrency} spent?"
Formula: EV ÷ AC.
1.0 = on budget.   < 1.0 = over budget (e.g., 0.80 = getting 0.80 ${projectCurrency} of work per 1 ${projectCurrency} spent).
> 1.0 looks favourable but >> 1.5 usually means AC is under-reported (check invoices/DPR cost capture).`}
          accentClass={indexClass(evm.costPerformanceIndex)}
        />
      </div>

      {density === "full" && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <Kpi
            label="EAC"
            value={moneyCompact(evm.estimateAtCompletion)}
            caption="KPI 10.8 — BAC ÷ CPI"
            tooltip={`Estimate at Completion — forecast of total final project cost given current performance.
Formula: BAC ÷ CPI (assumes current cost efficiency continues).
Read it as: "If we keep spending at today's efficiency, we'll finish at this number."
Compared to BAC: tells you the projected final overrun (or saving).`}
          />
          <Kpi
            label="ETC"
            value={moneyCompact(evm.estimateToComplete)}
            caption="EAC − AC"
            tooltip={`Estimate to Complete — additional money needed from today to finish.
Formula: EAC − AC.
Read it as: "From this point onward, we still need to spend approximately X ${projectCurrency} to deliver the remaining work."`}
          />
          <Kpi
            label="VAC"
            value={moneyCompact(evm.varianceAtCompletion)}
            caption="KPI 10.9 — BAC − EAC"
            tooltip={`Variance at Completion — final budget surplus or overrun forecast.
Formula: BAC − EAC.
Positive = projected under budget at finish.   Negative = projected overrun.
This is the "headline" number for the cost-engineer's monthly review.`}
            accent={evm.varianceAtCompletion != null && evm.varianceAtCompletion < 0 ? "danger" : "default"}
          />
          <Kpi
            label="TCPI"
            value={formatIndex(evm.toCompletePerformanceIndex)}
            caption="KPI 10.10 — (BAC − EV) ÷ (BAC − AC)"
            tooltip={`To-Complete Performance Index — efficiency required over remaining work to still hit BAC.
Formula: (BAC − EV) ÷ (BAC − AC).
1.0 = continue at current pace and you'll finish exactly on budget.
> 1.10 = recovery getting harder.   > 1.20 = unrealistic — re-baseline or accept overrun.
A common guardrail: if TCPI is much greater than CPI, the original budget can no longer be met without intervention.`}
            accentClass={indexClass(evm.toCompletePerformanceIndex)}
          />
        </div>
      )}

      {density === "full" && <EvmActivityTable projectId={projectId} />}
    </section>
  );
}

/**
 * Per-activity (WBS leaf) EVM table — KPI Framework SCR-001 requires "Activity-wise
 * PV/EV/AC/SPI/CPI". Sortable client-side; reads /v1/projects/{id}/evm/wbs-tree and
 * flattens the tree to leaves so each row is one trackable activity / WBS bottom node.
 */
function EvmActivityTable({ projectId }: { projectId: string }) {
  const { money: formatRupees } = useProjectCurrency();
  const [sortKey, setSortKey] = useState<SortKey>("spi");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  const { data, isLoading, error } = useQuery({
    queryKey: ["evm-wbs-tree", projectId],
    queryFn: () => evmKpiApi.getWbsTree(projectId),
    enabled: !!projectId,
    retry: 1,
  });

  const leaves = useMemo(() => flattenLeaves(data?.data ?? []), [data]);

  const sorted = useMemo(() => {
    const get = (n: WbsEvmNode): number | string | null => {
      switch (sortKey) {
        case "name": return n.name ?? "";
        case "bac":  return n.budgetAtCompletion;
        case "pv":   return n.plannedValue;
        case "ev":   return n.earnedValue;
        case "ac":   return n.actualCost;
        case "spi":  return n.schedulePerformanceIndex;
        case "cpi":  return n.costPerformanceIndex;
        case "vac":  return n.varianceAtCompletion;
      }
    };
    const dir = sortDir === "asc" ? 1 : -1;
    return [...leaves].sort((a, b) => {
      const va = get(a);
      const vb = get(b);
      if (va == null && vb == null) return 0;
      if (va == null) return 1;
      if (vb == null) return -1;
      if (typeof va === "string" && typeof vb === "string") return va.localeCompare(vb) * dir;
      return ((va as number) - (vb as number)) * dir;
    });
  }, [leaves, sortKey, sortDir]);

  const onSort = (key: SortKey) => {
    if (key === sortKey) setSortDir(sortDir === "asc" ? "desc" : "asc");
    else { setSortKey(key); setSortDir(key === "name" ? "asc" : "asc"); }
  };

  if (isLoading) return null;
  if (error || leaves.length === 0) return null;

  const Header = ({ k, label, right = false }: { k: SortKey; label: string; right?: boolean }) => (
    <th className={`pb-1 cursor-pointer select-none ${right ? "text-right" : "text-left"}`} onClick={() => onSort(k)}>
      {label}{sortKey === k ? (sortDir === "asc" ? " ▲" : " ▼") : ""}
    </th>
  );

  return (
    <div className="rounded-lg border border-border bg-surface/40 p-4">
      <h3 className="text-sm font-semibold text-text-primary mb-2">
        EVM by activity / WBS leaf
        <span className="ml-2 text-xs font-normal text-text-muted">{leaves.length} rows · click headers to sort</span>
      </h3>
      <table className="w-full text-xs">
        <thead className="text-text-muted">
          <tr>
            <Header k="name" label="Activity / WBS" />
            <Header k="bac"  label="BAC"  right />
            <Header k="pv"   label="PV"   right />
            <Header k="ev"   label="EV"   right />
            <Header k="ac"   label="AC"   right />
            <Header k="spi"  label="SPI"  right />
            <Header k="cpi"  label="CPI"  right />
            <Header k="vac"  label="VAC"  right />
          </tr>
        </thead>
        <tbody>
          {sorted.map((n, i) => (
            <tr key={n.id ?? `${n.code ?? "wbs"}-${i}`} className="text-text-primary">
              <td className="py-1 truncate max-w-[260px]" title={n.name}>
                <span className="text-text-muted mr-2">{n.code}</span>{n.name}
              </td>
              <td className="py-1 text-right">{formatRupees(n.budgetAtCompletion, { decimals: 0 })}</td>
              <td className="py-1 text-right">{formatRupees(n.plannedValue, { decimals: 0 })}</td>
              <td className="py-1 text-right">{formatRupees(n.earnedValue, { decimals: 0 })}</td>
              <td className="py-1 text-right">{formatRupees(n.actualCost, { decimals: 0 })}</td>
              <td className={`py-1 text-right ${indexClass(n.schedulePerformanceIndex)}`}>
                {formatIndex(n.schedulePerformanceIndex)}
              </td>
              <td className={`py-1 text-right ${indexClass(n.costPerformanceIndex)}`}>
                {formatIndex(n.costPerformanceIndex)}
              </td>
              <td className={`py-1 text-right ${n.varianceAtCompletion != null && n.varianceAtCompletion < 0 ? "text-danger" : ""}`}>
                {formatRupees(n.varianceAtCompletion, { decimals: 0 })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * EVM tile. {@code caption} is the short formula line shown beneath the value (always visible).
 * {@code tooltip} is the rich plain-English explanation shown on hover over the whole tile —
 * preserve newlines so the browser tooltip lays out as multiple lines.
 */
function Kpi({
  label,
  value,
  caption,
  tooltip,
  accent,
  accentClass,
}: {
  label: string;
  value: string;
  caption: string;
  tooltip: string;
  accent?: "default" | "danger" | "success";
  accentClass?: string;
}) {
  const cls = accentClass
    ? accentClass
    : accent === "danger"
      ? "text-danger"
      : accent === "success"
        ? "text-success"
        : "text-text-primary";
  return (
    <div
      className="group relative rounded-lg border border-border bg-surface/50 p-4 cursor-help transition-colors hover:border-gold/40"
      title={tooltip}
    >
      <div className="flex items-center justify-between">
        <div className="text-xs uppercase tracking-wide text-text-muted">{label}</div>
        <span
          aria-hidden
          className="text-[10px] text-text-muted/50 group-hover:text-gold-deep transition-colors"
        >
          ⓘ
        </span>
      </div>
      <div className={`mt-1 text-2xl font-semibold ${cls}`}>{value}</div>
      <div className="mt-1 text-[11px] text-text-secondary">{caption}</div>
    </div>
  );
}
