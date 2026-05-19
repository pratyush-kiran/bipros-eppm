"use client";

import { AlertTriangle, Gauge } from "lucide-react";
import type { ProductivityCoverage } from "@/lib/api/dprApi";

export interface ProductivityPreviewData {
  expectedFromManpower: number | null;
  expectedFromEquipment: number | null;
  expectedBottleneck: number | null;
  source: "BOTH" | "MANPOWER_ONLY" | "EQUIPMENT_ONLY" | "NONE";
  coverage: ProductivityCoverage;
  /** Combination rule echoed from the Work Activity. Drives the side-tag label below. */
  normCombination?: "SERIES" | "PARALLEL" | "SUBSTITUTE";
  warnings: string[];
}

interface Props {
  preview: ProductivityPreviewData | null;
  workdone: number | null | undefined;
  unit?: string | null;
}

const fmt = (n: number | null | undefined) =>
  n == null ? "—" : n.toLocaleString("en-IN", { maximumFractionDigits: 2 });

/**
 * Inline panel on the DPR form. Coverage-aware: only renders the sides the Work Activity
 * actually tracks (no misleading "Manpower: —" on equipment-only activities). Soft yellow
 * warning when workdone deviates >25% from the bottleneck. Never blocks save.
 */
export function ProductivityPreviewBanner({ preview, workdone, unit }: Props) {
  if (!preview || preview.source === "NONE") return null;

  const showManpower =
    preview.coverage === "MANPOWER_ONLY" || preview.coverage === "BOTH";
  const showEquipment =
    preview.coverage === "EQUIPMENT_ONLY" || preview.coverage === "BOTH";

  const bottleneck = preview.expectedBottleneck;
  const workdoneNum =
    typeof workdone === "number" && Number.isFinite(workdone) ? workdone : null;
  let deviationPct: number | null = null;
  if (bottleneck != null && bottleneck > 0 && workdoneNum != null) {
    deviationPct = Math.abs(workdoneNum - bottleneck) / bottleneck;
  }
  const warn = deviationPct != null && deviationPct > 0.25;

  const sideTagLabel = (() => {
    switch (preview.source) {
      case "MANPOWER_ONLY":
        return "from Manpower";
      case "EQUIPMENT_ONLY":
        return "from Equipment";
      case "BOTH":
        switch (preview.normCombination) {
          case "PARALLEL":
            return "Manpower + Equipment";
          case "SUBSTITUTE":
            return "max(Manpower, Equipment)";
          default: // SERIES or undefined
            return "min(Manpower, Equipment)";
        }
      default:
        return "";
    }
  })();

  return (
    <div
      className={`mt-2 rounded-md border px-3 py-2 text-xs ${
        warn ? "border-warning/40 bg-warning/10" : "border-info/30 bg-info/5"
      }`}
    >
      <div className="flex items-start gap-2">
        <Gauge className="mt-0.5 h-4 w-4 flex-shrink-0 text-info" />
        <div className="flex-1">
          <div className="font-semibold text-text-primary">
            Expected today: {fmt(bottleneck)}
            {unit ? ` ${unit}` : ""}
            {sideTagLabel && (
              <span className="ml-2 font-normal text-text-muted">({sideTagLabel})</span>
            )}
            {(showManpower || showEquipment) && (
              <span className="ml-2 font-normal text-text-muted">
                {showManpower && (
                  <>Manpower: {fmt(preview.expectedFromManpower)}</>
                )}
                {showManpower && showEquipment && " · "}
                {showEquipment && (
                  <>Equipment: {fmt(preview.expectedFromEquipment)}</>
                )}
              </span>
            )}
          </div>
          {warn && (
            <div className="mt-1 flex items-start gap-1.5 text-warning">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" />
              <span>
                Workdone ({fmt(workdoneNum)}) deviates by ~{Math.round(deviationPct! * 100)}%
                from the expected output. Confirm before saving.
              </span>
            </div>
          )}
          {preview.warnings.length > 0 && (
            <ul className="mt-1 list-disc pl-4 text-text-muted">
              {preview.warnings.slice(0, 3).map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
