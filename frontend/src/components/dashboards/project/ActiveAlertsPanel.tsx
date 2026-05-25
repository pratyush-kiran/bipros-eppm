"use client";

import { useMemo } from "react";
import { AlertTriangle } from "lucide-react";
import {
  EmptyBlock,
  SectionCard,
} from "@/components/common/dashboard/primitives";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { DprIssueRow, IssueSeverity } from "@/lib/api/dprIssueApi";

const SEVERITY_ORDER: Record<IssueSeverity, number> = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
};

const SEVERITY_DOT: Record<IssueSeverity, string> = {
  CRITICAL: "bg-burgundy",
  HIGH: "bg-amber-flame",
  MEDIUM: "bg-amber-flame/70",
  LOW: "bg-steel",
};

const SEVERITY_BADGE_STATUS: Record<IssueSeverity, string> = {
  CRITICAL: "CRITICAL",
  HIGH: "HIGH",
  MEDIUM: "MEDIUM",
  LOW: "LOW",
};

interface ActiveAlertsPanelProps {
  issues: DprIssueRow[];
  maxRows?: number;
}

export function ActiveAlertsPanel({
  issues,
  maxRows = 5,
}: ActiveAlertsPanelProps) {
  const rows = useMemo(() => {
    return [...issues]
      .filter((i) => i.status === "OPEN" || i.status === "IN_PROGRESS" || i.status === "BLOCKED")
      .sort(
        (a, b) =>
          (SEVERITY_ORDER[a.severity] ?? 9) - (SEVERITY_ORDER[b.severity] ?? 9),
      )
      .slice(0, maxRows);
  }, [issues, maxRows]);

  return (
    <SectionCard
      title="Active Alerts"
      subtitle={
        rows.length === 0
          ? "No open alerts"
          : `${rows.length} alert${rows.length === 1 ? "" : "s"} need attention`
      }
      icon={<AlertTriangle size={16} />}
      accent
    >
      {rows.length === 0 ? (
        <EmptyBlock label="All clear" />
      ) : (
        <ul className="space-y-2">
          {rows.map((issue) => (
            <li
              key={issue.id ?? `${issue.title}-${issue.reportDate ?? ""}`}
              className="flex items-start gap-3 rounded-lg border border-hairline bg-paper px-3 py-2.5 transition-colors hover:border-gold/30"
            >
              <span
                className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${SEVERITY_DOT[issue.severity]}`}
                aria-hidden
              />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <StatusBadge
                    status={SEVERITY_BADGE_STATUS[issue.severity]}
                    variant="compact"
                  />
                  {issue.activityName && (
                    <span className="text-[10px] font-medium uppercase tracking-wider text-slate">
                      {issue.activityName}
                    </span>
                  )}
                </div>
                <div className="mt-1 truncate text-sm text-charcoal">
                  {issue.title}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </SectionCard>
  );
}
