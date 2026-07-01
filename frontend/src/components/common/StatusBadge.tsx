import React from "react";

type StatusType =
  | "PLANNED"
  | "ACTIVE"
  | "INACTIVE"
  | "COMPLETED"
  | "LOW"
  | "MEDIUM"
  | "HIGH"
  | "CRITICAL"
  | "NOT_STARTED"
  | "IN_PROGRESS"
  | "SUSPENDED"
  | "DELAYED"
  | "AT_RISK"
  | "ON_TRACK"
  | "ON_HOLD"
  | "CANCELLED"
  | "DONE"
  | "IN_PROGRESS_NOW";

interface StatusBadgeProps {
  status: StatusType | string;
  variant?: "default" | "compact" | "gantt";
}

const statusStyles: Record<string, string> = {
  PLANNED: "bg-surface-hover text-text-secondary ring-1 ring-border",
  ACTIVE: "bg-success/10 text-success ring-1 ring-success/20",
  INACTIVE: "bg-warning/10 text-warning ring-1 ring-warning/20",
  COMPLETED: "bg-accent/10 text-accent ring-1 ring-accent/20",
  LOW: "bg-success/10 text-success ring-1 ring-success/20",
  MEDIUM: "bg-warning/10 text-warning ring-1 ring-warning/20",
  HIGH: "bg-danger/10 text-danger ring-1 ring-danger/20",
  CRITICAL: "bg-danger/15 text-danger ring-1 ring-danger/30",
  NOT_STARTED: "bg-surface-hover text-text-secondary ring-1 ring-border",
  IN_PROGRESS: "bg-blue-500/10 text-blue-400 ring-1 ring-blue-500/20",
  SUSPENDED: "bg-danger/10 text-danger ring-1 ring-danger/20",
  DELAYED: "bg-danger/15 text-danger ring-1 ring-danger/30",
  AT_RISK: "bg-warning/15 text-warning ring-1 ring-warning/30",
  ON_TRACK: "bg-success/10 text-success ring-1 ring-success/20",
  ON_HOLD: "bg-warning/10 text-warning ring-1 ring-warning/20",
  CANCELLED: "bg-danger/10 text-danger ring-1 ring-danger/20",
  DONE: "bg-success/15 text-success ring-1 ring-success/30",
  IN_PROGRESS_NOW: "bg-accent/15 text-accent ring-1 ring-accent/30",
};

const statusLabels: Record<string, string> = {
  PLANNED: "Planned",
  ACTIVE: "Active",
  INACTIVE: "Inactive",
  COMPLETED: "Completed",
  LOW: "Low",
  MEDIUM: "Medium",
  HIGH: "High",
  CRITICAL: "Critical",
  NOT_STARTED: "Not Started",
  IN_PROGRESS: "In Progress",
  SUSPENDED: "Suspended",
  DELAYED: "Delayed",
  AT_RISK: "At Risk",
  ON_TRACK: "On Track",
  ON_HOLD: "On Hold",
  CANCELLED: "Cancelled",
  DONE: "Done",
  IN_PROGRESS_NOW: "In Progress",
};

function toTitleCase(str: string): string {
  return str
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export function StatusBadge({ status, variant = "default" }: StatusBadgeProps) {
  const style =
    statusStyles[status] ??
    "bg-surface-hover/30 text-text-secondary ring-1 ring-border";
  const baseLabel = statusLabels[status] ?? toTitleCase(status);

  if (variant === "gantt") {
    return (
      <span
        className={`inline-flex items-center rounded-full px-3 py-1 text-[10px] font-bold uppercase tracking-wider ${style}`}
      >
        {baseLabel.toUpperCase()}
      </span>
    );
  }

  const size =
    variant === "compact" ? "px-2 py-0.5 text-xs" : "px-2.5 py-1 text-xs";

  return (
    <span className={`inline-flex rounded-md font-medium ${size} ${style}`}>
      {baseLabel}
    </span>
  );
}

const BOQ_STATUS_PILL: Record<string, string> = {
  COMPLETED: "bg-info/15 text-info ring-1 ring-info/20",
  ACTIVE: "bg-success/20 text-success",
  ON_HOLD: "bg-warning/20 text-warning",
  OVERRUN: "bg-danger/15 text-danger ring-1 ring-danger/20",
  PENDING: "bg-info/15 text-info ring-1 ring-info/20",
};

export function boqStatusVariant(status: string | null | undefined): string {
  if (status && BOQ_STATUS_PILL[status]) return BOQ_STATUS_PILL[status];
  return "bg-slate/15 text-slate";
}
