import type { ActivityStatusRow } from "@/lib/api/projectInsightsApi";

export type HealthBucket = "onTrack" | "atRisk" | "delayed";

export function bucketActivityHealth(r: ActivityStatusRow): HealthBucket {
  if (r.daysDelay > 7) return "delayed";
  if (r.daysDelay >= 1) return "atRisk";
  if (r.isCritical && r.daysDelay >= 0 && r.pctComplete < 100) return "atRisk";
  return "onTrack";
}

export function mapWorkPackageStatus(r: ActivityStatusRow): string {
  if (r.pctComplete >= 100) return "COMPLETED";
  if (r.daysDelay > 7) return "DELAYED";
  if (r.pctComplete > 0) return "IN_PROGRESS";
  return r.status || "NOT_STARTED";
}

export interface DeltaPill {
  value: string;
  direction: "up" | "down" | "flat";
}

export function formatDelta(
  curr: number | null | undefined,
  delta: number | null | undefined,
  opts: { unit?: string; digits?: number; invertColor?: boolean } = {},
): DeltaPill | undefined {
  if (curr == null || delta == null || Number.isNaN(delta)) return undefined;
  const { unit = "", digits = 1, invertColor = false } = opts;
  if (Math.abs(delta) < 10 ** -digits / 2) {
    return { value: `${(0).toFixed(digits)}${unit}`, direction: "flat" };
  }
  const sign = delta > 0 ? "+" : "";
  const value = `${sign}${delta.toFixed(digits)}${unit}`;
  const rising = delta > 0;
  const direction = (invertColor ? !rising : rising) ? "up" : "down";
  return { value, direction };
}

export function bucketActivities(rows: ActivityStatusRow[]) {
  let onTrack = 0;
  let atRisk = 0;
  let delayed = 0;
  for (const r of rows) {
    const b = bucketActivityHealth(r);
    if (b === "onTrack") onTrack++;
    else if (b === "atRisk") atRisk++;
    else delayed++;
  }
  return { onTrack, atRisk, delayed, total: rows.length };
}

export function tasksCompleted(rows: ActivityStatusRow[]) {
  const done = rows.filter(
    (r) => r.pctComplete >= 100 || r.status === "DONE" || r.status === "COMPLETED",
  ).length;
  return { done, total: rows.length };
}

const CRITICAL_ISSUE_STATUSES = new Set(["OPEN", "IN_PROGRESS"]);

export function criticalIssueCount(
  issues: { severity: string; status: string }[],
): number {
  return issues.filter(
    (i) => i.severity === "CRITICAL" && CRITICAL_ISSUE_STATUSES.has(i.status),
  ).length;
}
