/**
 * Independent reimplementation of every EPPM calculation the application
 * surfaces in the UI. The Devil's Advocate suite fetches raw rows from the
 * backend, feeds them through these pure functions, and asserts the result
 * matches the displayed value. If the backend's arithmetic ever drifts from
 * these formulas (rounding, missing-data handling, wrong sign), DA detects
 * it without needing to read the backend service code.
 *
 * Keep this file dependency-free (no axios / no Playwright / no React) so
 * the same module can be unit-tested in isolation if needed.
 */

export type Numeric = number | string | null | undefined;

/** Coerce anything the backend hands us into a finite number. NaN/undefined → 0. */
export const num = (v: Numeric): number => {
  if (v === null || v === undefined || v === "") return 0;
  const n = typeof v === "number" ? v : Number(v);
  return Number.isFinite(n) ? n : 0;
};

/** Tolerance helper — used by tests, kept here so a single constant defines "close enough". */
export const TOLERANCE = {
  CURRENCY_INR: 1.0, // ₹1 rounding tolerance (backend rounds at storage, UI re-rounds)
  RATIO: 0.01, // CPI/SPI/marginPct within ±0.01
  PERCENT: 0.5, // % complete / productivity % within ±0.5
} as const;

export const near = (a: number, b: number, tol: number): boolean =>
  Math.abs(a - b) <= tol;

// ─── Audit 1: BAC = Σ WBS budgets ─────────────────────────────────────────────
export interface WbsNode {
  budget?: Numeric;
  originalBudget?: Numeric;
  currentBudget?: Numeric;
  children?: WbsNode[];
}

/** Walk the WBS tree and sum every leaf node's budget. */
export function computeBacFromWbs(nodes: WbsNode[]): number {
  let total = 0;
  const walk = (n: WbsNode): void => {
    const kids = n.children ?? [];
    if (kids.length === 0) {
      // leaf: use whichever budget field is set
      total += num(n.currentBudget) || num(n.originalBudget) || num(n.budget);
    } else {
      for (const k of kids) walk(k);
    }
  };
  for (const n of nodes) walk(n);
  return total;
}

// ─── Audit 2: Planned cost per activity ───────────────────────────────────────
export interface ResourcePlanLine {
  /** Planned quantity / man-days / machine-days for this line. */
  plannedQty?: Numeric;
  plannedUnits?: Numeric;
  units?: Numeric;
  /** Per-unit rate from resource role / variant. */
  unitRate?: Numeric;
  rate?: Numeric;
  /** Already-multiplied planned cost when backend pre-computes it. */
  plannedCost?: Numeric;
}

export function computePlannedCost(lines: ResourcePlanLine[]): number {
  return lines.reduce((sum, l) => {
    if (l.plannedCost !== undefined && l.plannedCost !== null) return sum + num(l.plannedCost);
    const qty = num(l.plannedQty ?? l.plannedUnits ?? l.units);
    const rate = num(l.unitRate ?? l.rate);
    return sum + qty * rate;
  }, 0);
}

// ─── Audit 3: Actual cost per activity = Σ DPR resource rows ──────────────────
export interface DprResourceRow {
  nos?: Numeric;
  workingHours?: Numeric;
  quantity?: Numeric;
  unitRate?: Numeric;
  lineCost?: Numeric;
  unitRateBasis?: "HOUR" | "DAY" | "EACH" | null;
}

export interface DprRowsBundle {
  manpower?: DprResourceRow[];
  equipment?: DprResourceRow[];
  materials?: DprResourceRow[];
}

/** Sum lineCost when present; else derive qty*rate using basis. */
export function computeActualCost(dprs: DprRowsBundle[]): number {
  const rowCost = (r: DprResourceRow): number => {
    if (r.lineCost !== undefined && r.lineCost !== null) return num(r.lineCost);
    const rate = num(r.unitRate);
    if (r.unitRateBasis === "HOUR") return num(r.workingHours) * rate;
    if (r.unitRateBasis === "EACH" || r.quantity !== undefined)
      return num(r.quantity ?? r.nos) * rate;
    // default: DAY → nos × rate
    return num(r.nos) * rate;
  };
  let total = 0;
  for (const d of dprs) {
    for (const r of d.manpower ?? []) total += rowCost(r);
    for (const r of d.equipment ?? []) total += rowCost(r);
    for (const r of d.materials ?? []) total += rowCost(r);
  }
  return total;
}

// ─── Audit 4: Earned Value = Σ %complete × BAC_activity ───────────────────────
export interface ActivityForEv {
  bac?: Numeric;
  budget?: Numeric;
  plannedCost?: Numeric;
  percentComplete?: Numeric;
}

export function computeEv(activities: ActivityForEv[]): number {
  return activities.reduce((sum, a) => {
    const bac = num(a.bac ?? a.budget ?? a.plannedCost);
    const pct = num(a.percentComplete) / 100;
    return sum + bac * pct;
  }, 0);
}

// ─── Audit 5/6: CPI & SPI ─────────────────────────────────────────────────────
export function computeCpi(ev: number, ac: number): number | null {
  if (ac === 0) return null; // undefined behavior, audit will flag
  return ev / ac;
}

export function computeSpi(ev: number, pv: number): number | null {
  if (pv === 0) return null;
  return ev / pv;
}

// ─── Audit 7: Margin = Revenue − ActualCost ───────────────────────────────────
export interface MarginLine {
  qtyExecuted?: Numeric;
  rate?: Numeric;
  budgetedRate?: Numeric;
  revenue?: Numeric;
  actualCost?: Numeric;
}

export function computeMargin(lines: MarginLine[]): {
  revenue: number;
  actualCost: number;
  margin: number;
  marginPct: number | null;
} {
  let revenue = 0;
  let actualCost = 0;
  for (const l of lines) {
    if (l.revenue !== undefined && l.revenue !== null) {
      revenue += num(l.revenue);
    } else {
      revenue += num(l.qtyExecuted) * num(l.rate ?? l.budgetedRate);
    }
    actualCost += num(l.actualCost);
  }
  const margin = revenue - actualCost;
  const marginPct = revenue === 0 ? null : (margin / revenue) * 100;
  return { revenue, actualCost, margin, marginPct };
}

// ─── Audit 8: Productivity % = qty / (manpower × norm) ────────────────────────
export function computeProductivityPct(
  qtyExecuted: number,
  manpower: number,
  normOutputPerManPerDay: number
): number | null {
  if (manpower === 0 || normOutputPerManPerDay === 0) return null; // /0 → undefined
  const expected = manpower * normOutputPerManPerDay;
  return (qtyExecuted / expected) * 100;
}

// ─── Audit 9/10: Roll-up identity helpers ─────────────────────────────────────
export function sumNumeric<T>(rows: T[], key: keyof T): number {
  return rows.reduce((s, r) => s + num(r[key] as Numeric), 0);
}

/**
 * Identity check: aggregate(parent) ≈ Σ children. Used for both Supervisor→Engineer
 * roll-up and Day→Week→Month period roll-up. Returns the absolute delta so the
 * caller can decide pass/fail vs tolerance.
 */
export function rollupDelta(parent: number, children: number[]): number {
  const sum = children.reduce((a, b) => a + b, 0);
  return Math.abs(parent - sum);
}
