"use client";

import { useQuery } from "@tanstack/react-query";
import { Info } from "lucide-react";
import { workActivityApi } from "@/lib/api/workActivityApi";

interface Props {
  workActivityId: string | null | undefined;
}

/**
 * Small info chip rendered under the Work Activity picker on the activity edit screen.
 * Tells the planner what productivity tracking is configured for the picked Work Activity
 * BEFORE they save the activity — so a misconfigured "no norms" or "wrong norm type"
 * surfaces here, not on the supervisor's DPR form.
 */
export function WorkActivityCoverageChip({ workActivityId }: Props) {
  const { data } = useQuery({
    queryKey: ["work-activity-coverage", workActivityId],
    queryFn: () => workActivityApi.productivityCoverage(workActivityId!),
    enabled: !!workActivityId,
  });
  if (!workActivityId) return null;
  const coverage = data?.data;
  if (!coverage) return null;

  // For BOTH-coverage activities, the summary names the configured combination rule explicitly
  // so the planner sees up-front how the two sides will resolve into a single expected output —
  // no more generic "min(MP, EQ)" assumption when the activity is actually configured as Parallel
  // or Substitute.
  const summaryLine = (() => {
    switch (coverage.summary) {
      case "BOTH":
        switch (coverage.normCombination) {
          case "PARALLEL":
            return "Productivity tracked from both Manpower and Equipment — Parallel (independent teams). Expected = Manpower + Equipment. Both sides contribute to the day's output; outputs add.";
          case "SUBSTITUTE":
            return "Productivity tracked from both Manpower and Equipment — Substitute (either alone). Expected = max(Manpower, Equipment). The faster side drives the day; the slower side is redundant.";
          default: // SERIES (default)
            return "Productivity tracked from both Manpower and Equipment — Series (bottleneck). Expected = min(Manpower, Equipment). The slower side caps the day; the faster side is constrained by it.";
        }
      case "MANPOWER_ONLY":
        return "Productivity tracked from Manpower only. Equipment is informational on DPRs.";
      case "EQUIPMENT_ONLY":
        return "Productivity tracked from Equipment only. Manpower is informational on DPRs.";
      case "NONE":
        return "No productivity norms configured for this Work Activity yet. Add one in Admin → Productivity Norms.";
      default:
        return null;
    }
  })();
  if (!summaryLine) return null;

  const muted = coverage.summary === "NONE";
  // Master's default unit — appended to every norm output figure so "50 per man/day" reads
  // as "50 kg per man/day". Without it the planner can't tell whether 50 means 50 kg, 50 m³
  // or 50 Sqm, which is the difference between an accurate productivity % and a wrong one.
  const unit = coverage.defaultUnit ?? "";
  const u = unit ? ` ${unit}` : "";

  return (
    <div
      className={`mt-2 rounded-md border px-3 py-2 text-xs ${
        muted
          ? "border-text-muted/20 bg-surface-hover/40 text-text-muted"
          : "border-info/30 bg-info/5 text-text-secondary"
      }`}
    >
      <div className="flex items-start gap-2">
        <Info className={`mt-0.5 h-4 w-4 flex-shrink-0 ${muted ? "text-text-muted" : "text-info"}`} />
        <div className="flex-1">
          <div className="font-semibold text-text-primary">{summaryLine}</div>
          {(coverage.manpower.configured || coverage.equipment.configured) && (
            <ul className="mt-1 list-disc pl-4">
              {coverage.manpower.norms.map((n, i) => (
                <li key={`mp-${i}`}>
                  <span className="font-medium">Manpower</span> · {n.label}
                  {n.outputPerManPerDay != null && ` — ${n.outputPerManPerDay}${u} per man/day`}
                  {n.outputPerDay != null && ` (gang ${n.outputPerDay}${u}/day)`}
                </li>
              ))}
              {coverage.equipment.norms.map((n, i) => (
                <li key={`eq-${i}`}>
                  <span className="font-medium">Equipment</span> · {n.label}
                  {n.outputPerDay != null && ` — ${n.outputPerDay}${u}/day`}
                  {n.workingHoursPerDay != null && ` over ${n.workingHoursPerDay}h`}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
