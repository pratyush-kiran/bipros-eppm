"use client";

import { AlertTriangle, AlertCircle } from "lucide-react";
import { useActivityMasterStatus } from "@/lib/hooks/useActivityMasterStatus";

interface Props {
  workActivityId: string | null | undefined;
  /** Optional pixel size override; defaults to 14 to fit table-cell density. */
  size?: number;
}

/**
 * Tiny inline icon shown beside an activity row when its productivity setup is incomplete:
 *  - yellow ⚠ when no master Work Activity is linked
 *  - red ⓘ when a master is linked but no productivity norms are configured
 * Renders nothing once the setup is complete (state OK) or while the queries are in flight,
 * so the row UI stays clean.
 */
export function ActivityMasterStatusIndicator({ workActivityId, size = 14 }: Props) {
  const status = useActivityMasterStatus(workActivityId);

  if (status.state === "OK" || status.state === "LOADING") return null;

  if (status.state === "UNLINKED") {
    return (
      <span
        title="Not mapped to a Master Work Activity. Productivity Norms are not configured for this activity, therefore Capacity Utilization calculations may be inaccurate."
        className="inline-flex items-center justify-center rounded-full bg-warning/10 px-1 py-0.5 text-warning ring-1 ring-warning/30"
        aria-label="No master work activity"
      >
        <AlertTriangle size={size} />
      </span>
    );
  }

  return (
    <span
      title={`Mapped to ${status.master?.name ?? "a master"}, but no Productivity Norms are configured. Capacity Utilization will not compute for this activity.`}
      className="inline-flex items-center justify-center rounded-full bg-danger/10 px-1 py-0.5 text-danger ring-1 ring-danger/30"
      aria-label="No productivity norms"
    >
      <AlertCircle size={size} />
    </span>
  );
}
