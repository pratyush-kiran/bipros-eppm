"use client";

import type {
  DprEquipmentRow,
  DprManpowerRow,
  DprMaterialRow,
  DprSubContractorRow,
} from "@/lib/types/dpr";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";
import {
  equipmentLineCost,
  manpowerLineCost,
  materialLineCost,
} from "./dprFormulas";

interface Props {
  manpower: DprManpowerRow[];
  equipment: DprEquipmentRow[];
  materials: DprMaterialRow[];
  subContractors: DprSubContractorRow[];
  qtyExecuted: number;
  unit: string;
  /**
   * Winning ("bottleneck") side from the norm-based Expected-output model — the side whose
   * expected output equals the "Expected today" value. Drives the Productivity cell:
   * Productivity = workdone ÷ that side's count. Null when no norm / side can be determined.
   */
  productivitySide?: "MANPOWER" | "EQUIPMENT" | null;
  /**
   * Fuel/machinery cost ratio (decimal fraction, e.g. 0.35 = 35%) from backend DBS config.
   * Fuel cost = ratio × equipment cost. Defaults to 0.35 if the config fetch hasn't resolved.
   */
  fuelCostRatio?: number;
  /** Show the trailing Day-cost cell. Off in the read-only detail modal (it has its own KPI). */
  showDayCost?: boolean;
  /**
   * ISO 4217 currency code, kept for call-site compatibility. Cost display now
   * comes from the project currency via useProjectCurrency(); this prop is ignored.
   */
  currency?: string;
}

const sum = (arr: Array<number | null | undefined>): number =>
  arr.reduce<number>((acc, v) => acc + (typeof v === "number" ? v : 0), 0);

const fmt = (n: number, digits = 2) => {
  if (!isFinite(n)) return "—";
  return n.toLocaleString(undefined, { maximumFractionDigits: digits });
};

export function DprTotalsBar({
  manpower,
  equipment,
  materials,
  subContractors,
  qtyExecuted,
  unit,
  productivitySide,
  fuelCostRatio = 0.35,
  showDayCost = true,
}: Props) {
  const { money } = useProjectCurrency();
  const manpowerCount = sum(manpower.map((m) => m.nos));
  const equipmentCount = sum(equipment.map((e) => e.nos));
  // Productivity = effective workdone ÷ the winning side's count (manpower nos when manpower is
  // the bottleneck, equipment nos otherwise). Effective workdone excludes the sub-contractor's
  // share — that output isn't produced by this DPR's own manpower/equipment. "—" when no side.
  const productivityDenom =
    productivitySide === "MANPOWER"
      ? manpowerCount
      : productivitySide === "EQUIPMENT"
        ? equipmentCount
        : 0;
  const subContractorQty = sum(subContractors.map((s) => s.quantity ?? 0));
  const effectiveWorkdone = Math.max(qtyExecuted - subContractorQty, 0);
  const productivity =
    productivitySide && productivityDenom > 0 && effectiveWorkdone > 0
      ? effectiveWorkdone / productivityDenom
      : null;

  const manpowerCost = sum(manpower.map((m) => manpowerLineCost(m)));
  const equipmentCost = sum(equipment.map((e) => equipmentLineCost(e)));
  // Fuel is derived as a fraction of equipment cost (backend rule; ratio from DBS config).
  const fuelCost = equipmentCost * fuelCostRatio;
  const materialCost = sum(materials.map((m) => materialLineCost(m)));
  const subContractorCost = sum(
    subContractors.map((s) => (s.quantity ?? 0) * (s.ratePerUnit ?? 0)),
  );
  const totalCost = manpowerCost + equipmentCost + fuelCost + materialCost + subContractorCost;
  const hasAnyCost = totalCost > 0;

  /** Format a monetary value in the project currency, whole numbers (no decimals). */
  const fmtCost = (amount: number) => money(amount, { decimals: 0 });

  return (
    <div className="flex flex-wrap items-stretch gap-3 rounded-lg border border-hairline bg-ivory/60 px-4 py-3 text-sm">
      <Cell label="Manpower" value={`${manpowerCount} ppl`} hint={fmtCost(manpowerCost)} />
      <Cell label="Equipment" value={`${equipmentCount} units`} hint={fmtCost(equipmentCost)} />
      <Cell
        label="Fuel"
        value={fmtCost(fuelCost)}
        hint={equipmentCost > 0 ? `${Math.round(fuelCostRatio * 100)}% of equipment cost` : undefined}
      />
      <Cell label="Materials" value={`${materials.length} ${materials.length === 1 ? "unit" : "units"}`} hint={materialCost > 0 ? fmtCost(materialCost) : undefined} />
      <Cell
        label="Sub-Contractor"
        value={`${subContractors.length} ${subContractors.length === 1 ? "entry" : "entries"}`}
        hint={subContractorCost > 0 ? fmtCost(subContractorCost) : undefined}
      />
      <Cell
        label="Productivity"
        value={productivity != null ? `${fmt(productivity, 2)} ${unit}` : "—"}
        hint={
          productivity != null
            ? productivitySide === "MANPOWER"
              ? "Manpower"
              : "Equipment"
            : undefined
        }
      />
      {showDayCost && (
        <Cell
          label="Day cost"
          value={hasAnyCost ? fmtCost(totalCost) : "—"}
          hint={hasAnyCost ? "manpower + equipment + fuel + material + sub-contractor" : "set rates to compute"}
        />
      )}
    </div>
  );
}

function Cell({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex-1 min-w-[140px] border-l border-hairline first:border-l-0 first:pl-0 pl-4">
      <div className="text-[10px] font-semibold uppercase tracking-wide text-slate">{label}</div>
      <div className="font-display text-lg font-semibold text-charcoal tabular-nums">{value}</div>
      {hint && <div className="text-[11px] text-ash">{hint}</div>}
    </div>
  );
}
