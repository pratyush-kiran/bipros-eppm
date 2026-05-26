/**
 * Pure helpers shared by the project-hub data layer (useHubBadges) and the
 * bento card components. No React, no DOM — safe to import from either side.
 */

import { toYearMonth } from "@/lib/api/generalExpensesApi";
import { budgetUnit } from "@/lib/utils/format";

/** ISO yyyy-mm-dd for "today" in the local timezone. */
export function todayIso(): string {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** ISO yyyy-mm-dd for "today minus N days" in the local timezone. */
export function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** YYYYMM integer for the current month. */
export function currentYearMonth(): number {
  const d = new Date();
  return toYearMonth(d.getFullYear(), d.getMonth() + 1);
}

/**
 * Defensive unwrap for endpoints whose TypeScript signature says
 * `AxiosResponse<T[]>` but whose backend wraps the body in an
 * `ApiResponse<T[]>` envelope at runtime. Returns the inner array regardless
 * of which shape we got. Mirrors the dance in the existing OverviewTab.
 */
export function unwrapArray<T>(queryData: unknown): T[] | undefined {
  if (!queryData || typeof queryData !== "object") return undefined;
  const body = (queryData as { data?: unknown }).data;
  if (Array.isArray(body)) return body as T[];
  if (body && typeof body === "object") {
    const inner = (body as { data?: unknown }).data;
    if (Array.isArray(inner)) return inner as T[];
  }
  return undefined;
}

/**
 * Converts an absolute base-unit currency value (e.g. INR rupees, OMR rials)
 * into the project's major scale unit (crores / millions) so it can be passed
 * to formatBudget. Bridges a real inconsistency between cost-summary endpoints
 * (return base units) and budget endpoints (already return major scale).
 */
export function rawToMajorScale(value: number, currency: string | null | undefined): number {
  const code = (currency ?? "INR").toUpperCase();
  const divisor = code === "INR" ? 1e7 : 1e6;
  return value / divisor;
}

/** Format a base-unit currency amount using the project's budget scale (cr/M). */
export function formatBudgetAmount(value: number, currency: string | null | undefined): string {
  const { suffix } = budgetUnit(currency);
  const code = (currency ?? "INR").toUpperCase();
  const symbol = code === "INR" ? "₹" : "";
  const locale = code === "INR" ? "en-IN" : "en-US";
  const scaled = rawToMajorScale(value, currency);
  const formatted = new Intl.NumberFormat(locale, {
    maximumFractionDigits: 2,
    minimumFractionDigits: 0,
  }).format(scaled);
  return `${symbol}${formatted} ${suffix}`;
}
