/**
 * Single source of truth for the unit-of-measure dropdown shared by:
 * - DPR add/edit form (`DprActivityForm.tsx`)
 * - Work Activity admin form (`admin/work-activities/page.tsx`)
 * - Productivity Norm admin form (`admin/productivity-norms/page.tsx`)
 *
 * Keeping these aligned prevents the case where a user types `cum` (lower-case) or `Lm` on the
 * Work Activity master, and the DPR form's strict dropdown silently rejects it (or falls back to
 * the first option), leading to a unit mismatch and broken Capacity Utilization math.
 *
 * Extend cautiously — adding values is safe, removing values orphans existing data.
 */
export const STANDARD_UNITS = [
  // Volume
  "Cum",
  "Brass",
  "L",
  // Area
  "Sqm",
  "Sqft",
  // Length
  "Rm",
  "Lm",
  "lin.m.",
  "R/mtr",
  "m",
  "mm",
  "ft",
  "in",
  // Mass / weight
  "kg",
  "MT",
  "Tonne",
  "Quintal",
  "Bag",
  // Count
  "Each",
  "Nr",
  "Nos",
  // Time
  "Day",
  "Hour",
] as const;

export type StandardUnit = (typeof STANDARD_UNITS)[number];

/**
 * Options for a `<select>` that includes the standard list plus the current value when it's a
 * legacy / typo'd entry not in the standard list. Lets existing data still display while
 * pushing new entries onto the canonical list.
 */
export function unitOptionsWithFallback(currentValue: string | null | undefined): string[] {
  if (!currentValue) return [...STANDARD_UNITS];
  const trimmed = currentValue.trim();
  if (!trimmed) return [...STANDARD_UNITS];
  if ((STANDARD_UNITS as readonly string[]).includes(trimmed)) return [...STANDARD_UNITS];
  return [...STANDARD_UNITS, trimmed];
}
