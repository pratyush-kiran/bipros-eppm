import type { BoqItemResponse } from "@/lib/api/boqApi";
import type { RaBill, RaBillStatus } from "@/lib/api/raBillApi";

export interface CategoryRow {
  category: string;
  budget: number;
  actual: number;
  variance: number;
  variancePct: number | null;
  itemCount: number;
}

const BUCKETS = [
  "Earthwork",
  "Structures",
  "Pavement",
  "Drainage",
  "Road Furniture",
  "Misc/Admin",
] as const;

// BoqItem.chapter is free text on the backend (e.g. "1 - Earthwork", "3.2 Bituminous").
// Keyword bucketing keeps MoRTH-numbered seed data and ad-hoc user values in the same
// six categories the dashboard renders. Unknown chapters fall into Misc/Admin.
export function bucketChapter(raw: string | null | undefined): (typeof BUCKETS)[number] {
  const s = (raw ?? "").toLowerCase();
  if (!s) return "Misc/Admin";
  if (/earth|excav|fill|embank|subgrade/.test(s)) return "Earthwork";
  if (/struct|culvert|bridge|rcc|concrete|reinforce|pier|abutment/.test(s)) return "Structures";
  if (/pavement|bitum|asphalt|gsb|wmm|wbm|dbm|bc\b|surface|seal/.test(s)) return "Pavement";
  if (/drain|catch.*pit|chamber/.test(s)) return "Drainage";
  if (/furniture|sign|marking|guard|crash|delineator|stud|barrier/.test(s)) return "Road Furniture";
  return "Misc/Admin";
}

export function aggregateBudgetByCategory(items: BoqItemResponse[]): CategoryRow[] {
  const acc = new Map<string, CategoryRow>();
  for (const cat of BUCKETS) {
    acc.set(cat, { category: cat, budget: 0, actual: 0, variance: 0, variancePct: null, itemCount: 0 });
  }
  for (const it of items) {
    const cat = bucketChapter(it.chapter);
    const row = acc.get(cat)!;
    row.budget += it.boqAmount ?? 0;
    row.actual += it.actualAmount ?? 0;
    row.itemCount += 1;
  }
  return Array.from(acc.values())
    .map((r) => {
      const variance = r.budget - r.actual;
      const variancePct = r.budget > 0 ? (variance / r.budget) * 100 : null;
      return { ...r, variance, variancePct };
    })
    .filter((r) => r.itemCount > 0 || r.budget > 0);
}

// Status sets that bucket the nine RaBillStatus values into the three pills the
// dashboard shows (RAISED / PAID / PENDING).
const RAISED_STATUSES = new Set<RaBillStatus>(["APPROVED", "CERTIFIED"]);
const PAID_STATUSES = new Set<RaBillStatus>(["PAID", "PAID_PMC_OVERRIDE"]);

export function billingRaisedTotal(bills: RaBill[]): number {
  return bills
    .filter((b) => RAISED_STATUSES.has(b.status) || PAID_STATUSES.has(b.status))
    .reduce((s, b) => s + (b.grossAmount ?? 0), 0);
}

// Pending Recovery = retention that is currently held back from the contractor
// on bills that have been PAID. Retention is deducted at payment time, not at
// submission, so SUBMITTED/APPROVED bills haven't actually had money held yet.
// The held amount is "pending" because it will be refunded (or recovered against
// defects) at the end of the Defect Liability Period.
export function pendingRecoveryTotal(bills: RaBill[]): number {
  return bills
    .filter((b) => PAID_STATUSES.has(b.status))
    .reduce((s, b) => s + (b.retention5Pct ?? 0), 0);
}

export interface VarianceBuckets {
  onBudget: number;
  over: number;
  under: number;
  total: number;
}

// BIPROS convention: BoqItem.costVariance = budget − actual (positive = under budget).
// "On budget" = within ±5% of budget; "Over" = actual exceeds budget by >5%;
// "Under" = actual is more than 5% below budget. Items without a percent (no budget)
// are skipped so they don't skew the donut.
export function bucketVariance(items: BoqItemResponse[]): VarianceBuckets {
  let onBudget = 0;
  let over = 0;
  let under = 0;
  for (const it of items) {
    const pct = it.costVariancePercent;
    if (pct == null) continue;
    if (Math.abs(pct) <= 5) onBudget += 1;
    else if (pct < -5) over += 1;
    else under += 1;
  }
  return { onBudget, over, under, total: onBudget + over + under };
}
