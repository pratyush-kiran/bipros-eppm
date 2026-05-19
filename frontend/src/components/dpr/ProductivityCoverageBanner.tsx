"use client";

import { Info } from "lucide-react";
import type { ProductivityCoverage } from "@/lib/api/dprApi";

interface Props {
  coverage: ProductivityCoverage | null;
  /** Combination rule from the Work Activity master. Drives the BOTH-coverage message wording. */
  normCombination?: "SERIES" | "PARALLEL" | "SUBSTITUTE";
}

/**
 * Always-visible info banner above the DPR resource tabs. Tells the supervisor what the
 * linked Work Activity tracks — so they know up-front whether their manpower / equipment
 * rows drive expected output or are merely informational.
 *
 * <p>Never blocks save. Always softly worded — the underlying state (no WA linked, no norms
 * configured, single-side tracking) is legitimate, not an error.
 */
export function ProductivityCoverageBanner({ coverage, normCombination }: Props) {
  if (!coverage) return null;

  const message = (() => {
    switch (coverage) {
      case "NO_WORK_ACTIVITY":
        return "No productivity tracking for this activity — no Work Activity is linked. The DPR will save; capacity utilization won't measure expected vs actual.";
      case "NONE":
        return "The linked Work Activity has no productivity norms configured. The DPR will save; productivity won't be measured.";
      case "MANPOWER_ONLY":
        return "Productivity is measured from Manpower output. Equipment rows are recorded but don't drive expected output for this kind of work.";
      case "EQUIPMENT_ONLY":
        return "Productivity is measured from Equipment output. Manpower rows are recorded but don't drive expected output for this kind of work.";
      case "BOTH":
        switch (normCombination) {
          case "PARALLEL":
            return "This activity is configured as Parallel — expected output = Manpower + Equipment. Both sides work independent stretches and their outputs add. Log rows on either side; if you log only one, that side drives the expected output.";
          case "SUBSTITUTE":
            return "This activity is configured as Substitute — expected output = max(Manpower, Equipment). Either side alone can finish the unit, so the faster side drives the day and the slower side is redundant. If you log only one side, that side drives the expected output.";
          default: // SERIES or undefined — default behaviour
            return "This activity is configured as Series — expected output = min(Manpower, Equipment). The two sides work on the same unit in sequence, so the slower side caps the day's output. If you log only one side, that side drives the expected output.";
        }
      default:
        return null;
    }
  })();

  if (!message) return null;

  const muted = coverage === "NO_WORK_ACTIVITY" || coverage === "NONE";

  return (
    <div
      className={`mt-3 flex items-start gap-2 rounded-md border px-3 py-2 text-xs ${
        muted
          ? "border-text-muted/20 bg-surface-hover/40 text-text-muted"
          : "border-info/30 bg-info/5 text-text-secondary"
      }`}
    >
      <Info className={`mt-0.5 h-4 w-4 flex-shrink-0 ${muted ? "text-text-muted" : "text-info"}`} />
      <span>{message}</span>
    </div>
  );
}
