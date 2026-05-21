import React from "react";
import { cn } from "@/lib/utils/cn";
import { StatusBadge } from "@/components/ui/status-badge";

export interface IssueListItem {
  id: string;
  code: string;
  title: string;
  /** CRITICAL / HIGH / MEDIUM / LOW / INFO */
  severity: string;
}

export interface IssuesActivityListProps {
  issues: IssueListItem[];
  className?: string;
}

const railColor: Record<string, string> = {
  CRITICAL: "bg-burgundy",
  HIGH: "bg-burgundy/80",
  MEDIUM: "bg-bronze-warn",
  LOW: "bg-steel",
  INFO: "bg-steel",
};

export function IssuesActivityList({ issues, className }: IssuesActivityListProps) {
  if (issues.length === 0) {
    return (
      <div className={cn("rounded-xl border border-dashed border-hairline p-6 text-center text-sm text-text-secondary", className)}>
        No active issues.
      </div>
    );
  }
  return (
    <ul className={cn("space-y-2.5", className)}>
      {issues.map((issue) => {
        const sev = issue.severity.toUpperCase();
        return (
          <li
            key={issue.id}
            className="relative overflow-hidden rounded-lg border border-hairline bg-parchment/40 py-2.5 pl-4 pr-3"
          >
            <span
              className={cn("absolute left-0 top-0 h-full w-[3px]", railColor[sev] ?? "bg-steel")}
              aria-hidden
            />
            <div className="flex items-center gap-3">
              <span className="text-[11px] font-semibold tabular-nums text-text-secondary">
                {issue.code}
              </span>
              <span className="min-w-0 flex-1 truncate text-sm text-text-primary">
                {issue.title}
              </span>
              <StatusBadge status={sev} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
