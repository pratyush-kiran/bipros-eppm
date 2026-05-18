/**
 * Format a numeric amount as a localised currency string.
 *
 * Default currency is INR — historically every caller assumed INR, which broke
 * the moment Bipros gained its first non-INR project (Oman demo / OMR). Callers
 * that have a project-specific currency code (see `Project.budgetCurrency` or
 * the `BudgetSummaryResponse.budgetCurrency` field) should pass it through so
 * the symbol matches the data.
 *
 * Falsy `currencyCode` (null/undefined/"") falls back to INR so existing call
 * sites keep working.
 */
export function formatCurrency(
  amount: number | null | undefined,
  currencyCode?: string | null,
): string {
  const val = amount ?? 0;
  const code = currencyCode && currencyCode.trim() ? currencyCode : "INR";
  try {
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: code,
      maximumFractionDigits: 2,
      minimumFractionDigits: 2,
    }).format(val);
  } catch {
    // Unknown ISO code → degrade to "<CODE> 0.00" rather than throw.
    return `${code} ${val.toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`;
  }
}

/**
 * Format a percent value (input is the percent itself, not a 0–1 ratio).
 * Returns "—" for null/undefined/NaN. One decimal place by default.
 */
export function formatPercent(
  value: number | null | undefined,
  fractionDigits = 1,
): string {
  if (value == null || Number.isNaN(value)) return "—";
  return `${value.toLocaleString(undefined, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })}%`;
}

/**
 * Format an ISO date string (e.g. "2026-01-15") to a readable format (e.g. "Jan 15, 2026").
 * Returns "—" for null/undefined/empty values.
 */
export function formatDate(date: string | null | undefined): string {
  if (!date) return "—";
  const d = new Date(date + "T00:00:00");
  if (isNaN(d.getTime())) return date;
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

/**
 * Priority is a 1-100 integer (1 = highest). Display labels are derived from bucketing the value
 * so both data seeded on a 1-10 scale and projects created on the 1-100 scale produce the same
 * six labels everywhere in the UI.
 */
export function getPriorityInfo(priority: number | null | undefined): { label: string; color: string } {
  if (priority == null || Number.isNaN(priority)) {
    return { label: "—", color: "text-slate-500" };
  }
  // Clamp into 1-100 for bucketing, but keep the raw value visible for out-of-range data.
  const p = Math.max(1, Math.min(100, Math.round(priority)));
  if (p <= 10) return { label: "Critical", color: "text-red-400" };
  if (p <= 25) return { label: "Very High", color: "text-red-400" };
  if (p <= 40) return { label: "High", color: "text-orange-400" };
  if (p <= 60) return { label: "Medium", color: "text-yellow-400" };
  if (p <= 80) return { label: "Low", color: "text-slate-400" };
  return { label: "Very Low", color: "text-slate-500" };
}

/**
 * Project BAC is stored in the currency's "major scale" unit:
 *   INR → crores (10^7),  OMR / others → millions.
 * Returns the user-facing unit suffix and singular label for the input field.
 */
export function budgetUnit(currency: string | null | undefined): { suffix: string; inputLabel: string } {
  const code = (currency ?? "INR").toUpperCase();
  if (code === "INR") return { suffix: "cr", inputLabel: "crores" };
  return { suffix: `M ${code}`, inputLabel: `millions ${code}` };
}

export function formatBudget(value: number | null | undefined, currency: string | null | undefined): string {
  if (value == null) return "—";
  const { suffix } = budgetUnit(currency);
  const locale = (currency ?? "INR").toUpperCase() === "INR" ? "en-IN" : "en-US";
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value)} ${suffix}`;
}
