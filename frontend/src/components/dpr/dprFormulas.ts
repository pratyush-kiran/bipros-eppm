import type {
  DprEquipmentRow,
  DprManpowerRow,
  DprMaterialRow,
  RateBasis,
} from "@/lib/types/dpr";

/**
 * Mirror of {@code com.bipros.project.application.util.DprCostFormulas}. Used for the live
 * cost preview in each grid's Cost column so the supervisor sees what the row will save as.
 * Line cost = unitRate × nos (unitRate × quantity for material). working_hours / ot_hours are
 * captured as logging fields only — never multiplied into cost, matching the server.
 */
export function manpowerLineCost(row: DprManpowerRow): number | null {
  if (row.unitRate == null || row.nos == null || row.nos <= 0) return null;
  return round2(row.unitRate * row.nos);
}

export function equipmentLineCost(row: DprEquipmentRow): number | null {
  if (row.unitRate == null || row.nos == null || row.nos <= 0) return null;
  return round2(row.unitRate * row.nos);
}

export function materialLineCost(row: DprMaterialRow): number | null {
  if (row.unitRate == null || row.quantity == null) return null;
  return round2(row.unitRate * row.quantity);
}

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

export function fmtMoney(n: number | null | undefined): string {
  if (n == null || !isFinite(n)) return "—";
  return n.toLocaleString(undefined, { maximumFractionDigits: 2, minimumFractionDigits: 2 });
}

export function fmtRate(n: number | null | undefined): string {
  if (n == null || !isFinite(n)) return "—";
  return n.toLocaleString(undefined, { maximumFractionDigits: 2, minimumFractionDigits: 2 });
}

/** Suffix for the rate cell — only HOUR and DAY get a visible badge; EACH stays bare. */
export function rateBasisSuffix(basis: RateBasis | null | undefined): string {
  if (basis === "HOUR") return "/hr";
  if (basis === "DAY") return "/day";
  return "";
}

/** Minimal norm-preview shape needed to pick the winning ("bottleneck") side. Compatible with both
 *  the form's {@code ProductivityPreviewData} and the API's {@code ProductivityPreviewResponse}. */
type PreviewSideInput =
  | {
      source: "BOTH" | "MANPOWER_ONLY" | "EQUIPMENT_ONLY" | "NONE";
      expectedFromManpower: number | null;
      expectedFromEquipment: number | null;
      expectedBottleneck: number | null;
    }
  | null
  | undefined;

/**
 * Winning ("bottleneck") side for the totals-bar Productivity cell: the side whose norm-based
 * expected output equals the "Expected today" value. For BOTH-tracked activities it's the side
 * whose expected is closest to the bottleneck — resolving to min (SERIES), max (SUBSTITUTE) or the
 * larger contributor (PARALLEL). Null when no norm / side can be determined.
 */
export function productivitySideFromPreview(p: PreviewSideInput): "MANPOWER" | "EQUIPMENT" | null {
  if (!p || p.source === "NONE") return null;
  if (p.source === "MANPOWER_ONLY") return "MANPOWER";
  if (p.source === "EQUIPMENT_ONLY") return "EQUIPMENT";
  const { expectedFromManpower: mp, expectedFromEquipment: eq, expectedBottleneck: b } = p;
  if (mp == null || eq == null || b == null) return "MANPOWER";
  return Math.abs(mp - b) <= Math.abs(eq - b) ? "MANPOWER" : "EQUIPMENT";
}
