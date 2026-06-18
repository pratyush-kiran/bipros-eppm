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
}: Props) {
  const { money } = useProjectCurrency();
  const manpowerCount = sum(manpower.map((m) => m.nos));
  const manpowerHours = sum(manpower.map((m) => m.workingHours)) + sum(manpower.map((m) => m.otHours));
  const equipmentCount = sum(equipment.map((e) => e.nos));
  const equipmentHours = sum(equipment.map((e) => e.workingHours));
  const fuelLitres = sum(equipment.map((e) => e.fuelLitres));
  const productivity = manpowerHours > 0 && qtyExecuted > 0 ? qtyExecuted / manpowerHours : null;

  const manpowerCost = sum(manpower.map((m) => manpowerLineCost(m, m.unitRateBasis)));
  const equipmentCost = sum(equipment.map((e) => equipmentLineCost(e, e.unitRateBasis ?? "HOUR")));
  const materialCost = sum(materials.map((m) => materialLineCost(m)));
  const subContractorCost = sum(
    subContractors.map((s) => (s.quantity ?? 0) * (s.ratePerUnit ?? 0)),
  );
  const totalCost = manpowerCost + equipmentCost + materialCost + subContractorCost;
  const hasAnyCost = totalCost > 0;

  /** Format a monetary value in the project currency, whole numbers (no decimals). */
  const fmtCost = (amount: number) => money(amount, { decimals: 0 });

  return (
    <div className="flex flex-wrap items-stretch gap-3 rounded-lg border border-hairline bg-ivory/60 px-4 py-3 text-sm">
      <Cell label="Manpower" value={`${manpowerCount} ppl`} hint={`${fmt(manpowerHours, 1)} hrs · ${fmtCost(manpowerCost)}`} />
      <Cell label="Equipment" value={`${equipmentCount} units`} hint={`${fmt(equipmentHours, 1)} hrs · ${fmtCost(equipmentCost)}`} />
      <Cell label="Fuel" value={`${fmt(fuelLitres, 1)} L`} />
      <Cell label="Materials" value={`${materials.length} entries`} hint={materialCost > 0 ? fmtCost(materialCost) : undefined} />
      <Cell
        label="Sub-Contractor"
        value={`${subContractors.length} ${subContractors.length === 1 ? "row" : "rows"}`}
        hint={subContractorCost > 0 ? fmtCost(subContractorCost) : undefined}
      />
      <Cell
        label="Productivity"
        value={productivity != null ? `${fmt(productivity, 2)} ${unit}/hr` : "—"}
        hint={productivity != null ? "qty / manpower hours" : "needs hours"}
      />
      <Cell
        label="Day cost"
        value={hasAnyCost ? fmtCost(totalCost) : "—"}
        hint={hasAnyCost ? "manpower + equipment + material + sub-contractor" : "set rates to compute"}
      />
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
